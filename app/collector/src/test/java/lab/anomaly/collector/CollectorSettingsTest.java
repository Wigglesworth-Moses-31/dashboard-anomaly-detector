package lab.anomaly.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lab.anomaly.collector.CollectorSettings.ClassificationRule;
import org.junit.jupiter.api.Test;

class CollectorSettingsTest {

    /** Valid settings, matching config-repo/collector.yml. */
    static CollectorSettings valid() {
        return new CollectorSettings("datadog", "datadoghq.com", List.of(ref("dashboards/lab.json")),
                Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(60),
                Map.of("w2f-pqs-ghd", Map.of("env", "prod")),
                List.of(new ClassificationRule(new ClassificationRule.Match("lab.*.latency", null, null),
                        new ClassificationRule.Assignment("latency", "ms"))),
                8, 0.8, 3, Duration.ofMillis(500), Duration.ofSeconds(8), 5, Duration.ofMinutes(5),
                Duration.ofHours(24), 10_000, new CollectorSettings.Recording(false, "recordings"), null);
    }

    private static CollectorSettings with(java.util.function.UnaryOperator<Object[]> edit) {
        CollectorSettings v = valid();
        Object[] a = {v.source(), v.datadogSite(), v.dashboards(), v.pollInterval(), v.step(), v.backfill(), v.recentWindow(),
                v.dashboardRefresh(), v.dashboardFilters(), v.classification(), v.maxConcurrentRequests(), v.rateLimitShare(),
                v.retries(), v.retryBase(), v.retryCap(), v.breakerFailures(), v.breakerOpen(), v.metadataTtl(),
                v.metadataCacheSize(), v.recording(), v.replayDir()};
        a = edit.apply(a);
        return new CollectorSettings((String) a[0], (String) a[1], (List<CollectorSettings.DashboardRef>) a[2], (Duration) a[3], (Duration) a[4],
                (Duration) a[5], (Duration) a[6], (Duration) a[7], (Map<String, Map<String, String>>) a[8],
                (List<ClassificationRule>) a[9], (int) a[10], (double) a[11], (int) a[12], (Duration) a[13], (Duration) a[14],
                (int) a[15], (Duration) a[16], (Duration) a[17], (int) a[18], (CollectorSettings.Recording) a[19], (String) a[20]);
    }

    private static List<String> problems(CollectorSettings settings) {
        return CollectorSettings.validators().stream().map(v -> v.check(settings)).flatMap(Optional::stream).toList();
    }

    @Test
    void goodSettingsPass() {
        assertThat(problems(valid())).isEmpty();
    }

    @Test
    void negativePollIntervalIsRejected() {
        assertThat(problems(with(a -> { a[3] = Duration.ofSeconds(-5); return a; })))
                .singleElement().asString().startsWith("poll-interval");
    }

    @Test
    void duplicateDashboardIsRejected() {
        assertThat(problems(with(a -> { a[2] = List.of(ref("dashboards/a.json"), ref("dashboards/b.json"), ref("dashboards/a.json")); return a; })))
                .containsExactly("dashboards[2] file 'dashboards/a.json' is listed twice");
    }

    @Test
    void negativeConcurrencyIsRejected() {
        assertThat(problems(with(a -> { a[10] = -1; return a; }))).containsExactly("max-concurrent-requests: must be at least 1");
    }

    @Test
    void rateLimitShareAboveOneIsRejected() {
        assertThat(problems(with(a -> { a[11] = 1.5; return a; }))).containsExactly("rate-limit-share: must be more than 0 and at most 1");
    }

    @Test
    void unknownKindInARuleIsRejected() {
        var rule = new ClassificationRule(new ClassificationRule.Match("x.*", null, null), new ClassificationRule.Assignment("speed", null));
        assertThat(problems(with(a -> { a[9] = List.of(rule); return a; })))
                .singleElement().asString().startsWith("classification[0]: unknown kind 'speed'");
    }

    @Test
    void replayNeedsADirectory() {
        assertThat(problems(with(a -> { a[0] = "replay"; return a; }))).containsExactly("replay-dir: must be set when source is 'replay'");
    }

    @Test
    void collectionsCannotBeChangedAfterLoading() {
        CollectorSettings settings = with(a -> { a[2] = new ArrayList<>(List.of(ref("dashboards/a.json"))); return a; });
        assertThatThrownBy(() -> settings.dashboards().add(ref("dashboards/b.json"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.dashboardFilters().put("x", Map.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- add-multi-site: sources (design M5) --------------------------------------------------------------------

    static CollectorSettings.Source source(String name, String site, String api, String app, String... dashboards) {
        return new CollectorSettings.Source(name, site, api, app,
                java.util.Arrays.stream(dashboards).map(d -> ref("dashboards/" + d + ".json")).toList(), Map.of(), null, null);
    }

    static CollectorSettings withSources(CollectorSettings.Source... sources) {
        CollectorSettings v = valid();
        return new CollectorSettings(v.source(), null, List.of(), v.pollInterval(), v.step(), v.backfill(), v.recentWindow(),
                v.dashboardRefresh(), Map.of(), v.classification(), v.maxConcurrentRequests(), v.rateLimitShare(),
                v.retries(), v.retryBase(), v.retryCap(), v.breakerFailures(), v.breakerOpen(), v.metadataTtl(),
                v.metadataCacheSize(), v.recording(), v.replayDir(), List.of(sources));
    }

    static CollectorSettings usAndEu() {
        return withSources(source("us", "datadoghq.com", "DD_US_API_KEY", "DD_US_APP_KEY", "abc-def-ghi"),
                source("eu", "datadoghq.eu", "DD_EU_API_KEY", "DD_EU_APP_KEY", "stu-vwx-yz1"));
    }

    @Test
    void oldSettingsBecomeOneDefaultSource() {
        var lanes = valid().lanes();
        assertThat(lanes).singleElement().satisfies(l -> {
            assertThat(l.name()).isEqualTo("default");
            assertThat(l.site()).isEqualTo("datadoghq.com");
            assertThat(l.apiKeyEnv()).isEqualTo("DD_API_KEY");
            assertThat(l.appKeyEnv()).isEqualTo("DD_APP_KEY");
            assertThat(l.dashboards()).containsExactly(ref("dashboards/lab.json"));
            assertThat(l.dashboardFilters()).containsKey("w2f-pqs-ghd");
            assertThat(l.maxConcurrentRequests()).isEqualTo(8);
        });
    }

    @Test
    void twoSourcesPassAndTakeSharedDefaults() {
        assertThat(problems(usAndEu())).isEmpty();
        assertThat(usAndEu().lanes()).extracting(CollectorSettings.LaneConfig::name).containsExactly("us", "eu");
        assertThat(usAndEu().lanes().get(1).rateLimitShare()).isEqualTo(0.8);
    }

    @Test
    void duplicateSourceNameIsRefused() {
        assertThat(problems(withSources(source("us", "datadoghq.com", "DD_US_API_KEY", "DD_US_APP_KEY", "a"),
                source("us", "datadoghq.eu", "DD_EU_API_KEY", "DD_EU_APP_KEY", "b"))))
                .containsExactly("sources[1] (us): the name is already used by sources[0]");
    }

    @Test
    void duplicateSiteIsRefused() {
        assertThat(problems(withSources(source("us", "datadoghq.com", "DD_US_API_KEY", "DD_US_APP_KEY", "a"),
                source("us2", "DatadogHQ.com", "DD_US2_API_KEY", "DD_US2_APP_KEY", "b"))))
                .singleElement().asString().startsWith("sources[1] (us2): site datadoghq.com is already used by source 'us'");
    }

    @Test
    void anotherSecretAsKeyNameIsRefused() {
        assertThat(problems(withSources(source("eu", "datadoghq.eu", "PLATFORM_DB_PASSWORD", "DD_EU_APP_KEY", "a"))))
                .singleElement().asString()
                .startsWith("sources[0] (eu): api-key-env 'PLATFORM_DB_PASSWORD' is not allowed");
    }

    @Test
    void applicationKeyNameInTheApiSlotIsRefused() {
        assertThat(problems(withSources(source("eu", "datadoghq.eu", "DD_EU_APP_KEY", "DD_EU_APP_KEY", "a"))))
                .singleElement().asString().contains("api-key-env 'DD_EU_APP_KEY' is not allowed");
    }

    @Test
    void missingApplicationKeyNameIsRefused() {
        assertThat(problems(withSources(source("eu", "datadoghq.eu", "DD_EU_API_KEY", null, "a"))))
                .singleElement().asString().contains("app-key-env must name the environment variable");
    }

    @Test
    void bothFormsAtOnceAreRefused() {
        CollectorSettings v = valid();
        CollectorSettings both = new CollectorSettings(v.source(), "datadoghq.com", v.dashboards(), v.pollInterval(), v.step(),
                v.backfill(), v.recentWindow(), v.dashboardRefresh(), Map.of(), v.classification(), 8, 0.8, 3,
                v.retryBase(), v.retryCap(), 5, v.breakerOpen(), v.metadataTtl(), 10_000, v.recording(), null,
                List.of(source("eu", "datadoghq.eu", "DD_EU_API_KEY", "DD_EU_APP_KEY", "a")));
        assertThat(problems(both)).containsExactly("sources: use either 'sources' or 'datadog-site' with 'dashboards', not both");
    }

    @Test
    void badSourceNameIsRefused() {
        assertThat(problems(withSources(source("EU-west", "datadoghq.eu", "DD_EU_API_KEY", "DD_EU_APP_KEY", "a"))))
                .singleElement().asString().startsWith("sources[0]: name must be 1-12 lower-case letters or digits");
    }

    @Test
    void sameConnectionIgnoresDashboards() {
        var a = usAndEu().lanes().get(0);
        var b = new CollectorSettings.LaneConfig(a.name(), a.site(), a.apiKeyEnv(), a.appKeyEnv(), List.of(ref("dashboards/other.json")),
                Map.of(), a.maxConcurrentRequests(), a.rateLimitShare());
        var c = new CollectorSettings.LaneConfig(a.name(), "datadoghq.eu", a.apiKeyEnv(), a.appKeyEnv(), a.dashboards(),
                Map.of(), a.maxConcurrentRequests(), a.rateLimitShare());
        assertThat(a.sameConnection(b)).isTrue();
        assertThat(a.sameConnection(c)).isFalse();
    }

    // ---- add-dashboard-json (design J1) ------------------------------------------------------------------------

    static CollectorSettings.DashboardRef ref(String file) {
        return new CollectorSettings.DashboardRef(file, null, Map.of(), List.of());
    }

    @Test
    void anIdIsRefusedWithHowToExport() {
        assertThat(problems(with(a -> { a[2] = List.of(CollectorSettings.DashboardRef.valueOf("abc-def-ghi")); return a; })))
                .singleElement().asString()
                .startsWith("dashboards[0] 'abc-def-ghi' is an id; dashboards are now JSON files. Export it in Datadog")
                .contains("'- file: dashboards/<name>.json'");
    }

    @Test
    void filesMustBeJsonUnderDashboards() {
        for (String bad : List.of("../secrets.json", "/etc/passwd.json", "dashboards/x.yml", "other/x.json", "dashboards/../x.json")) {
            assertThat(problems(with(a -> { a[2] = List.of(ref(bad)); return a; }))).as(bad)
                    .singleElement().asString().contains("must be a .json file under dashboards/");
        }
        assertThat(problems(with(a -> { a[2] = List.of(ref("dashboards/us/service-overview.json")); return a; }))).isEmpty();
    }

    @Test
    void replayAcceptsTheRecordedIds() {
        assertThat(problems(with(a -> { a[0] = "replay"; a[20] = "recordings/run-1";
            a[2] = List.of(CollectorSettings.DashboardRef.valueOf("w2f-pqs-ghd")); return a; }))).isEmpty();
    }

    @Test
    void allPresetsMustStandAlone() {
        var r = new CollectorSettings.DashboardRef("dashboards/x.json", null, Map.of(), List.of("all", "Payments"));
        assertThat(problems(with(a -> { a[2] = List.of(r); return a; }))).singleElement().asString().contains("use 'all' alone");
        assertThat(new CollectorSettings.DashboardRef("dashboards/x.json", null, Map.of(), List.of("ALL")).allPresets()).isTrue();
    }
}
