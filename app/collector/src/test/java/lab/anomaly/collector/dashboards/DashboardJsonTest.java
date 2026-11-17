package lab.anomaly.collector.dashboards;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.CollectorSettings.DashboardRef;
import lab.anomaly.collector.plan.FilterFiller;
import lab.anomaly.collector.plan.PollPlan;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.plan.WatchedView;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.TemplateVariable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * add-dashboard-json tasks 2.1-2.6: files, the (path, SHA-256) cache, the all-or-nothing check, presets, several values
 * and section headings. The fixture is the lab skeleton (fictional names) with three made-up presets.
 */
public class DashboardJsonTest {

    private static final Path SKELETON = Path.of("src/test/resources/dashboards/lab-skeleton-dashboard.json");
    private static final Duration STEP = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The skeleton with presets: one with several teams, one that sets only some variables, one plain. */
    public static String withPresets() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(SKELETON));
        ArrayNode presets = root.putArray("template_variable_presets");
        ObjectNode payments = presets.addObject().put("name", "Payments");
        ArrayNode p = payments.putArray("template_variables");
        p.addObject().put("name", "team").putArray("values").add("web").add("mobile");
        p.addObject().put("name", "service").put("value", "payments");
        p.addObject().put("name", "env").put("value", "prod");
        ObjectNode profiles = presets.addObject().put("name", "Profiles");
        ArrayNode q = profiles.putArray("template_variables");
        q.addObject().put("name", "team").put("value", "platform");
        q.addObject().put("name", "service").put("value", "profiles");
        ObjectNode billing = presets.addObject().put("name", "Billing");
        billing.putArray("template_variables").addObject().put("name", "service").put("value", "billing");
        // Same values as Payments (like two saved views of one service): every question is shared
        ObjectNode worker = presets.addObject().put("name", "Payments Worker");
        ArrayNode w = worker.putArray("template_variables");
        w.addObject().put("name", "team").putArray("values").add("web").add("mobile");
        w.addObject().put("name", "service").put("value", "payments");
        w.addObject().put("name", "env").put("value", "prod");
        return JSON.writeValueAsString(root);
    }

    private static CollectorSettings settings(DashboardRef... refs) {
        return new CollectorSettings("datadog", null, List.of(), Duration.ofSeconds(15), STEP, Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofMinutes(60), Map.of(), List.of(), 8, 0.8, 3, Duration.ofMillis(500),
                Duration.ofSeconds(8), 5, Duration.ofMinutes(5), Duration.ofHours(24), 1000,
                new CollectorSettings.Recording(false, "recordings"), null,
                List.of(new CollectorSettings.Source("us", "datadoghq.com", "DD_US_API_KEY", "DD_US_APP_KEY", List.of(refs), Map.of(), null, null)));
    }

    private static DashboardRef ref(String file, List<String> presets, Map<String, String> filters) {
        return new DashboardRef(file, null, filters, presets);
    }

    private final Map<String, String> repo = new HashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final DashboardFiles files = new DashboardFiles(path -> {
        reads.incrementAndGet();
        return repo.get(path);
    });

    // ---- 2.1 / 2.2: loading, cache, all-or-nothing --------------------------------------------------------------

    @Test
    void anUnchangedFileIsParsedOnceAChangedOneAgain() throws Exception {
        repo.put("dashboards/us/lab.json", withPresets());
        CollectorSettings s = settings(ref("dashboards/us/lab.json", List.of(), Map.of()));
        assertThat(files.check(s)).isEmpty();
        assertThat(files.check(settings(ref("dashboards/us/lab.json", List.of(), Map.of())))).isEmpty();
        assertThat(files.parses()).isEqualTo(1);

        repo.put("dashboards/us/lab.json", withPresets().replace("\"prod\"", "\"stage\""));
        assertThat(files.check(s)).isEmpty();
        assertThat(files.parses()).isEqualTo(2);
    }

    @Test
    void problemsRefuseTheWholeVersionWithAClearMessage() throws Exception {
        repo.put("dashboards/us/lab.json", withPresets());
        repo.put("dashboards/us/broken.json", "{\"title\": \"x\", \"widgets\": [");
        repo.put("dashboards/us/empty.json", "{\"title\": \"x\", \"widgets\": []}");

        assertThat(files.check(settings(ref("dashboards/us/lab.json", List.of(), Map.of()), ref("dashboards/us/broken.json", List.of(), Map.of()))))
                .get().asString().startsWith("dashboard files: dashboards/us/broken.json: not valid JSON");
        assertThat(files.check(settings(ref("dashboards/us/empty.json", List.of(), Map.of()))))
                .get().asString().contains("no widgets; is this an exported dashboard JSON?");
        assertThat(files.check(settings(ref("dashboards/us/missing.json", List.of(), Map.of()))))
                .get().asString().contains("dashboards/us/missing.json: file not found");
        assertThat(files.check(settings(ref("dashboards/us/lab.json", List.of(), Map.of("envv", "prod")))))
                .get().asString().contains("filters name 'envv', but the dashboard's variables are [team, service, env");
        assertThat(files.check(settings(ref("dashboards/us/lab.json", List.of("Payments", "Nope"), Map.of()))))
                .get().asString().contains("no preset named 'Nope'; the dashboard has [Payments, Profiles, Billing, Payments Worker]");
    }

    @Test
    void theEngineGetsTheViewsOfExactlyTheCheckedSettings() throws Exception {
        repo.put("dashboards/us/lab.json", withPresets());
        CollectorSettings s = settings(ref("dashboards/us/lab.json", List.of("all"), Map.of()));
        assertThat(files.check(s)).isEmpty();
        int before = reads.get();
        assertThat(files.views(s, "us")).hasSize(4);
        assertThat(reads.get()).as("no second read of the files").isEqualTo(before);
    }

    // ---- 2.3 / 2.4: filters and presets ----------------------------------------------------------------------

    @Test
    void filtersFromTheFileThenPresetThenSettings() throws Exception {
        repo.put("dashboards/us/lab.json", withPresets());
        Dashboard d = files.views(settings(ref("dashboards/us/lab.json", List.of(), Map.of())), "us").get(0).dashboard();

        WatchedView plain = WatchedView.of(d, ref("dashboards/us/lab.json", List.of(), Map.of())).get(0);
        assertThat(plain.values().get("env")).containsExactly("prod");                            // the file's default
        WatchedView overridden = WatchedView.of(d, ref("dashboards/us/lab.json", List.of(), Map.of("service", "payments"))).get(0);
        assertThat(overridden.values().get("service")).containsExactly("payments");

        List<WatchedView> all = WatchedView.of(d, ref("dashboards/us/lab.json", List.of("all"), Map.of()));
        assertThat(all).extracting(WatchedView::viewTitle)
                .containsExactly(d.title() + " · Payments", d.title() + " · Profiles", d.title() + " · Billing", d.title() + " · Payments Worker");
        assertThat(all).extracting(WatchedView::viewId).allMatch(id -> id.startsWith("file:dashboards/us/lab.json#"));
        WatchedView profiles = all.get(1);
        assertThat(profiles.values().get("team")).containsExactly("platform");
        assertThat(profiles.values().get("env")).as("not set by the preset: the file's default").containsExactly("prod");
        assertThat(profiles.filtersText()).isEqualTo("team:platform, service:profiles, env:prod");
    }

    @Test
    void presetsGiveOneViewEachAndSharedQuestionsAreAskedOnce() throws Exception {
        repo.put("dashboards/us/lab.json", withPresets());
        List<WatchedView> views = files.views(settings(ref("dashboards/us/lab.json", List.of("all"), Map.of())), "us");
        List<PollUnit> units = PollPlan.buildViews("us", views, STEP);
        int panels = views.get(0).dashboard().panels().size();

        assertThat(views).hasSize(4);
        assertThat(units).hasSize(3 * panels);                                 // Payments Worker adds no question
        assertThat(units).filteredOn(u -> u.views().size() == 2).hasSize(panels)
                .allMatch(u -> u.views().stream().map(PollUnit.View::dashboardTitle).anyMatch(t -> t.endsWith("· Payments Worker")));
        assertThat(units).allMatch(u -> u.lane().equals("us"));
    }

    // ---- 2.5: several values ---------------------------------------------------------------------------------

    @Test
    void severalValuesUseDatadogsBooleanForm() {
        List<TemplateVariable> vars = List.of(new TemplateVariable("team", "team", "*"), new TemplateVariable("service", "service", "*"),
                new TemplateVariable("env", "env", "*"));
        String q = "count:svc.api.duration{$team,$service,$env,!api:/health} by {api}";
        assertThat(FilterFiller.fillMulti(q, vars, Map.of("team", List.of("web", "mobile"), "service", List.of("payments"), "env", List.of("prod"))))
                .isEqualTo("count:svc.api.duration{team IN (web,mobile) AND service:payments AND env:prod AND NOT api:/health} by {api}");
        // Single values: exactly the old comma form, so existing questions and line ids stay the same
        assertThat(FilterFiller.fillMulti(q, vars, Map.of("team", List.of("web"), "service", List.of("*"), "env", List.of("prod"))))
                .isEqualTo(FilterFiller.fill(q, vars, Map.of("team", "web", "service", "*", "env", "prod")))
                .isEqualTo("count:svc.api.duration{team:web,env:prod,!api:/health} by {api}");
    }

    // ---- 2.6: section headings -------------------------------------------------------------------------------

    @Test
    void panelsBelowAnEmptyGroupAreNamedByIt() throws Exception {
        repo.put("dashboards/us/lab.json", withPresets());
        Dashboard d = files.views(settings(ref("dashboards/us/lab.json", List.of(), Map.of())), "us").get(0).dashboard();
        assertThat(d.panels()).extracting(Dashboard.Panel::label)
                .contains("API Metrics / Latencies", "Upstream Service Metrics / Latencies", "API Gateway Metrics / Latency",
                        "Service Analytics / Total Service Traffic by API");
    }
}
