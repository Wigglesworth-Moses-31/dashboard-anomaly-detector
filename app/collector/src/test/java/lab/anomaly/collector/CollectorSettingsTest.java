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
        return new CollectorSettings("datadog", "datadoghq.com", List.of("w2f-pqs-ghd"),
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
        return new CollectorSettings((String) a[0], (String) a[1], (List<String>) a[2], (Duration) a[3], (Duration) a[4],
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
        assertThat(problems(with(a -> { a[2] = List.of("a", "b", "a"); return a; })))
                .containsExactly("dashboards: 'a' is listed twice");
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
        CollectorSettings settings = with(a -> { a[2] = new ArrayList<>(List.of("a")); return a; });
        assertThatThrownBy(() -> settings.dashboards().add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.dashboardFilters().put("x", Map.of())).isInstanceOf(UnsupportedOperationException.class);
    }
}
