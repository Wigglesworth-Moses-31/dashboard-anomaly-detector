package lab.anomaly.collector.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.dashboards.DashboardFiles;
import lab.anomaly.collector.dashboards.DashboardJsonTest;
import lab.anomaly.collector.source.Dashboard;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** add-settings-page task 2.2: an upload is read with the Collector's code, compared, and its panels asked once. */
class DashboardCheckTest {

    static final Path SKELETON = Path.of("src/test/resources/dashboards/lab-skeleton-dashboard.json");

    @Test
    void theSkeletonExportIsSummarised() throws Exception {
        Dashboard d = DashboardCheck.parse("lab.json", Files.readString(SKELETON));
        DashboardCheck.Summary s = DashboardCheck.summary(d);
        assertThat(s.title()).isNotBlank();
        assertThat(s.panelCount()).isPositive().isEqualTo(s.groups().stream().mapToInt(g -> g.panels().size()).sum());
    }

    @Test
    void aNewerExportWithOnePanelMoreShowsPlusOne() throws Exception {
        String before = Files.readString(SKELETON);
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode root = (ObjectNode) json.readTree(before);
        ArrayNode widgets = (ArrayNode) root.get("widgets");
        Dashboard old = DashboardCheck.parse("lab.json", before);
        String firstPanel = old.panels().get(0).title();
        // copy a top-level widget that holds panels, renamed: every panel inside it appears once more
        ObjectNode copy = null;
        for (int i = 0; i < widgets.size(); i++) {
            ObjectNode w = (ObjectNode) widgets.get(i);
            if (!"group".equals(w.path("definition").path("type").asString())) {
                copy = w.deepCopy();
                break;
            }
        }
        if (copy == null) {                                    // all grouped: copy the first group's first widget out
            copy = ((ObjectNode) widgets.get(0).path("definition").path("widgets").get(0)).deepCopy();
        }
        ((ObjectNode) copy.get("definition")).put("title", "Brand new panel");
        copy.remove("id");
        widgets.add(copy);
        DashboardCheck.Diff diff = DashboardCheck.diff(old, DashboardCheck.parse("lab.json", json.writeValueAsString(root)));
        assertThat(diff.panelsAdded()).singleElement().satisfies(p -> assertThat(p).endsWith("Brand new panel"));
        assertThat(diff.panelsRemoved()).isEmpty();
        assertThat(firstPanel).isNotBlank();
    }

    @Test
    void notJsonAndNoWidgetsAreRefusedWithAReason() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DashboardCheck.parse("x.json", "{oops"))
                .hasMessageStartingWith("x.json: not valid JSON");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DashboardCheck.parse("x.json", "{\"title\":\"t\",\"widgets\":[]}"))
                .hasMessage("x.json: no widgets; is this an exported dashboard JSON?");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DashboardCheck.parse("x.json", " "))
                .hasMessage("x.json: the file is empty");
    }

    @Test
    void eachPanelIsAskedOnceAndCountedPerView() throws Exception {
        Dashboard d = DashboardCheck.parse("lab.json", DashboardJsonTest.withPresets());
        CollectorSettings s = new CollectorSettings("datadog", null, List.of(), Duration.ofSeconds(15), Duration.ofSeconds(10),
                Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(60), Map.of(), List.of(), 8, 0.8, 3,
                Duration.ofMillis(500), Duration.ofSeconds(8), 5, Duration.ofMinutes(5), Duration.ofHours(24), 1000,
                new CollectorSettings.Recording(false, "recordings"), null, List.of());
        CheckDashboardsTest.Stub stub = new CheckDashboardsTest.Stub();
        List<DashboardCheck.ViewLines> lines = DashboardCheck.ask(stub, "eu",
                DashboardCheck.views(d, "lab.json", List.of("all"), Map.of()), s, 0);
        assertThat(lines).hasSize(4);
        assertThat(lines).filteredOn(v -> v.view().endsWith("· Billing")).singleElement()
                .satisfies(v -> assertThat(v.panels()).allSatisfy(p -> assertThat(p.lines()).isZero()));
        assertThat(lines).filteredOn(v -> v.view().endsWith("· Payments")).singleElement()
                .satisfies(v -> assertThat(v.panels()).anySatisfy(p -> assertThat(p.lines()).isPositive()));
        assertThat(stub.calls.get()).isLessThan(6);
    }

    @Test
    void theEndpointComparesWithTheCommittedFileAndRefusesAnUnknownView() throws Exception {
        String before = Files.readString(SKELETON);
        DashboardFiles files = new DashboardFiles(path -> path.equals("dashboards/eu/lab.json") ? before : null);
        DashboardCheckController c = new DashboardCheckController(files, null, r -> null, 0);
        var ok = c.check(Map.of("region", "eu", "file_name", "lab.json", "content", before, "current", "dashboards/eu/lab.json"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).containsKeys("summary", "views", "diff");
        assertThat((DashboardCheck.Diff) ok.getBody().get("diff")).satisfies(diff -> {
            assertThat(diff.panelsAdded()).isEmpty();
            assertThat(diff.panelsRemoved()).isEmpty();
        });
        var bad = c.check(Map.of("region", "eu", "file_name", "lab.json", "content", DashboardJsonTest.withPresets(),
                "presets", List.of("No such view")));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat((String) bad.getBody().get("problem")).contains("no preset named 'No such view'");
        assertThat(c.check(Map.of("region", "ap", "content", before)).getStatusCode().value()).isEqualTo(400);
    }
}
