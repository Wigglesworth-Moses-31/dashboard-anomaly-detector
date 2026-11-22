package lab.anomaly.config.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** add-settings-page tasks 3.1 and 3.2, on a temporary settings repository. */
class SettingsControllerTest {

    static final String EXPORT = "{\"title\":\"Shop\",\"widgets\":[{\"definition\":{\"type\":\"timeseries\",\"title\":\"Latency\"}}]}";
    static final Path TEMPLATE = Path.of("../../config-template");

    @TempDir Path repo;
    final AtomicInteger refreshes = new AtomicInteger();
    SettingsController c;

    /** A repository like setup.sh writes: the template, US with one dashboard in regions.yml. */
    @BeforeEach
    void setUp() throws Exception {
        Files.copy(TEMPLATE.resolve("collector.yml"), repo.resolve("collector.yml"));
        Files.writeString(repo.resolve("regions.yml"), Files.readString(TEMPLATE.resolve("regions.yml")).replace("__SOURCES__", """
                      - name: us
                        site: datadoghq.com
                        api-key-env: DD_US_API_KEY
                        app-key-env: DD_US_APP_KEY
                        dashboards:
                          - file: dashboards/us/shop.json
                            presets: all
                """.stripTrailing()));
        Files.createDirectories(repo.resolve("dashboards/us"));
        Files.writeString(repo.resolve("dashboards/us/shop.json"), EXPORT);
        commitAll("setup");
        c = new SettingsController(new SettingsRepo(repo, "main"), refreshes::incrementAndGet);
    }

    void commitAll(String message) throws Exception {
        try (Git git = Files.exists(repo.resolve(".git")) ? Git.open(repo.toFile()) : Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            git.commit().setMessage(message).setAuthor("me", "me@localhost").setCommitter("me", "me@localhost").call();
        }
    }

    List<RevCommit> log() throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            return java.util.stream.StreamSupport.stream(git.log().call().spliterator(), false).toList();
        }
    }

    String head() throws Exception {
        return (String) c.state().getBody().get("commit");
    }

    @Test
    void addingEuIsOneCommitWithNoKeyAndRefusesAThird() throws Exception {
        var state = c.state().getBody();
        assertThat(state.get("form")).isEqualTo("regions.yml");
        assertThat(state.get("can_add")).isEqualTo(List.of("eu"));
        var r = c.addRegion(head(), Map.of("region", "eu"));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(log()).hasSize(2).first().satisfies(x -> assertThat(x.getFullMessage()).isEqualTo("settings page: add region eu"));
        String regions = Files.readString(repo.resolve("regions.yml"));
        assertThat(regions).contains("name: eu", "site: datadoghq.eu", "api-key-env: DD_EU_API_KEY", "dashboards/us/shop.json");
        assertThat(RegionsYaml.parse(regions).sources()).hasSize(2);
        assertThat(refreshes).hasValue(1);
        assertThat(c.state().getBody().get("can_add")).isEqualTo(List.of());
        assertThat(c.addRegion(head(), Map.of("region", "us")).getStatusCode().value()).isEqualTo(409);
        assertThat(c.addRegion(head(), Map.of("region", "ap")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aDashboardIsAddedReplacedWatchedAndDeleted() throws Exception {
        c.addRegion(head(), Map.of("region", "eu"));
        var add = c.putDashboard(head(), "eu", Map.of("file_name", "My Shop (EU).json", "content", EXPORT, "presets", List.of("all")));
        assertThat(add.getStatusCode().value()).isEqualTo(200);
        assertThat(Files.readString(repo.resolve("dashboards/eu/my-shop-eu.json"))).startsWith(EXPORT);
        assertThat(log().get(0).getFullMessage()).isEqualTo("settings page: add eu/my-shop-eu.json");
        assertThat(c.putDashboard(head(), "eu", Map.of("file_name", "My Shop (EU).json", "content", EXPORT)).getStatusCode().value())
                .as("the same file twice").isEqualTo(409);

        String newer = EXPORT.replace("Latency", "Latency p99");
        assertThat(c.putDashboard(head(), "eu", Map.of("file_name", "my-shop-eu.json", "content", newer, "replace", true))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(Files.readString(repo.resolve("dashboards/eu/my-shop-eu.json"))).contains("Latency p99");
        assertThat(RegionsYaml.parse(Files.readString(repo.resolve("regions.yml"))).dashboard("eu", "dashboards/eu/my-shop-eu.json"))
                .get().satisfies(e -> assertThat(e.get("presets")).as("kept").isEqualTo(List.of("all")));

        assertThat(c.watch(head(), "eu", Map.of("file", "dashboards/eu/my-shop-eu.json", "presets", List.of("Payments", "Billing")))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(log().get(0).getFullMessage()).isEqualTo("settings page: watch 2 saved views of my-shop-eu.json");

        assertThat(c.deleteDashboard(head(), "eu", "dashboards/eu/my-shop-eu.json").getStatusCode().value()).isEqualTo(200);
        assertThat(repo.resolve("dashboards/eu/my-shop-eu.json")).doesNotExist();
        assertThat(log().get(0).getFullMessage()).isEqualTo("settings page: delete eu/my-shop-eu.json");
    }

    @Test
    void anEditInThePageKeepsTheEntrysViewsAndFilters() throws Exception {
        Files.writeString(repo.resolve("regions.yml"), Files.readString(repo.resolve("regions.yml"))
                .replace("            presets: all", "            presets: all\n            filters: { env: prod }"));
        commitAll("filters by hand");
        String edited = EXPORT.replace("Latency", "Latency (p95)");
        var r = c.putDashboard(head(), "us", Map.of("file_name", "shop.json", "content", edited, "replace", true, "edited", true));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(log().get(0).getFullMessage()).isEqualTo("settings page: edit us/shop.json");
        assertThat(Files.readString(repo.resolve("dashboards/us/shop.json"))).contains("Latency (p95)");
        assertThat(RegionsYaml.parse(Files.readString(repo.resolve("regions.yml"))).dashboard("us", "dashboards/us/shop.json")).get()
                .satisfies(e -> {
                    assertThat(e.get("presets")).isEqualTo("all");
                    assertThat(e.get("filters")).isEqualTo(Map.of("env", "prod"));
                });
    }

    @Test
    void removingARegionTakesItsEntryAndFilesInOneCommit() throws Exception {
        var r = c.removeRegion(head(), "us");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(repo.resolve("dashboards/us/shop.json")).doesNotExist();
        assertThat(RegionsYaml.parse(Files.readString(repo.resolve("regions.yml"))).sources()).isEmpty();
        assertThat(log()).hasSize(2);
    }

    @Test
    void notADashboardIsRefusedAndNothingWritten() throws Exception {
        c.addRegion(head(), Map.of("region", "eu"));
        int before = log().size();
        assertThat(c.putDashboard(head(), "eu", Map.of("file_name", "x.json", "content", "{oops")).getBody().get("problem")).isEqualTo("not valid JSON");
        assertThat(c.putDashboard(head(), "eu", Map.of("file_name", "x.json", "content", "{\"widgets\":[]}")).getStatusCode().value()).isEqualTo(400);
        assertThat(log()).hasSize(before);
    }

    @Test
    void handEditsAndStalePagesAreRefused() throws Exception {
        String seen = head();
        Files.writeString(repo.resolve("regions.yml"), Files.readString(repo.resolve("regions.yml")) + "# my edit\n");
        var r = c.addRegion(seen, Map.of("region", "eu"));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("problem")).isEqualTo("regions.yml has uncommitted edits in config-repo: commit or discard your edit first");
        commitAll("my edit");
        assertThat(c.addRegion(seen, Map.of("region", "eu")).getBody().get("problem")).isEqualTo("the settings changed meanwhile; reload the page");
        assertThat(c.addRegion(head(), Map.of("region", "eu")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anOlderMachineHasItsRegionsMovedFirstWithCommentsKept() throws Exception {
        String collector = Files.readString(TEMPLATE.resolve("collector.yml")).replace(
                "    # The Datadog regions to watch (at most two: us, eu) and their dashboards are in regions.yml, written by the\n"
                        + "    # Settings tab of the live page (or by hand). Every other Collector setting is here.\n",
                """
                    # my own comment about the sources
                    sources:
                      - name: us
                        site: datadoghq.com
                        api-key-env: DD_US_API_KEY
                        app-key-env: DD_US_APP_KEY
                        dashboards:
                          - file: dashboards/us/shop.json
                            presets: all
                        max-concurrent-requests: 4
                    #  - name: eu                             #   example
                """);
        assertThat(collector).contains("sources:");
        Files.writeString(repo.resolve("collector.yml"), collector);
        Files.delete(repo.resolve("regions.yml"));
        commitAll("older machine");
        assertThat(c.state().getBody().get("form")).isEqualTo("collector.yml");

        assertThat(c.addRegion(head(), Map.of("region", "eu")).getStatusCode().value()).isEqualTo(200);
        assertThat(log()).extracting(RevCommit::getFullMessage).startsWith("settings page: add region eu", "settings page: move regions to regions.yml");
        String after = Files.readString(repo.resolve("collector.yml"));
        assertThat(after).contains("# my own comment about the sources", "#  - name: eu", "poll-interval: 15s").doesNotContain("sources:");
        assertThat(RegionsYaml.parse(Files.readString(repo.resolve("regions.yml"))).region("us")).get()
                .satisfies(us -> assertThat(us.get("max-concurrent-requests")).isEqualTo(4));
    }

    @Test
    void theOldSingleSiteFormIsReadOnly() throws Exception {
        Files.writeString(repo.resolve("collector.yml"), "anomaly:\n  collector:\n    datadog-site: datadoghq.com\n    dashboards:\n      - abc-def-ghi\n");
        Files.delete(repo.resolve("regions.yml"));
        commitAll("old form");
        assertThat(c.state().getBody().get("form")).isEqualTo("old");
        var r = c.addRegion(head(), Map.of("region", "eu"));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat((String) r.getBody().get("problem")).startsWith("collector.yml uses the old single-site form");
    }

    @Test
    void fileNamesBecomeSafeSlugs() {
        assertThat(SettingsController.slug("My Shop (EU).json")).isEqualTo("my-shop-eu.json");
        assertThat(SettingsController.slug("../../etc/passwd")).isEqualTo("etc-passwd.json");
        assertThat(SettingsController.slug("   ")).isEqualTo("dashboard.json");
    }

    // ---- upstream-callers 1.2 ----------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void upstreamsAreSavedAsOneCommitAndReadBack() throws Exception {
        var empty = c.upstreams().getBody();
        assertThat(empty.get("regions")).as("no file yet").isEqualTo(Map.of());
        assertThat((List<String>) empty.get("upstream_tags")).contains("upstream_service");
        String text = "caller-tag: api\nregions:\n  us:\n    billing-core: [/v1/invoices, /v1/payments]\n";
        var r = c.saveUpstreams(head(), Map.of("text", text));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(log().get(0).getFullMessage()).isEqualTo("settings page: upstreams");
        assertThat(refreshes.get()).isEqualTo(1);
        var back = c.upstreams().getBody();
        assertThat(back.get("text")).isEqualTo(text);
        assertThat(back.get("regions")).isEqualTo(Map.of("us", Map.of("billing-core", List.of("/v1/invoices", "/v1/payments"))));
    }

    @Test
    void aWrongShapeIsRefusedAndNothingWritten() throws Exception {
        int before = log().size();
        var asia = c.saveUpstreams(head(), Map.of("text", "regions:\n  asia:\n    x: [/a]\n"));
        assertThat(asia.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(asia.getBody().get("problem"))).contains("regions must be us or eu");
        var notList = c.saveUpstreams(head(), Map.of("text", "regions:\n  us:\n    billing-core: /v1/invoices\n"));
        assertThat(String.valueOf(notList.getBody().get("problem"))).contains("must be a list of APIs");
        var stale = c.saveUpstreams("0000000", Map.of("text", "regions:\n  us: {}\n"));
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(log()).hasSize(before);
    }
}
