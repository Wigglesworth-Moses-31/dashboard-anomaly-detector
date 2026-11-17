package lab.anomaly.collector.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.CollectorSettings.DashboardRef;
import lab.anomaly.collector.dashboards.DashboardFiles;
import lab.anomaly.collector.dashboards.DashboardJsonTest;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import org.junit.jupiter.api.Test;

/** add-dashboard-json task 5.1: the check prints per view and panel, batches its calls, and fails on an empty view. */
class CheckDashboardsTest {

    /** Every question has one line, except those filtered to service:billing, which match nothing. */
    static final class Stub implements MetricSource {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Dashboard dashboard(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TimeseriesAnswer timeseries(TimeseriesRequest request) {
            calls.incrementAndGet();
            List<TimeseriesAnswer.Series> series = new ArrayList<>();
            for (int i = 0; i < request.formulas().size(); i++) {
                String f = request.formulas().get(i);
                boolean billing = request.queries().stream().filter(q -> f.contains(q.name())).anyMatch(q -> q.text().contains("service:billing"));
                if (!billing) {
                    series.add(new TimeseriesAnswer.Series(i, Map.of(), List.of(1.0)));
                }
            }
            return new TimeseriesAnswer(List.of(0L), series, 0L, null);
        }

        @Override
        public Optional<MetricMetadata> metadata(String metric) {
            return Optional.empty();
        }
    }

    @Test
    void printsEveryViewAndFailsWhenOneReturnsNothing() throws Exception {
        String json = DashboardJsonTest.withPresets();
        DashboardFiles files = new DashboardFiles(path -> path.equals("dashboards/us/lab.json") ? json : null);
        CollectorSettings s = new CollectorSettings("datadog", null, List.of(), Duration.ofSeconds(15), Duration.ofSeconds(10),
                Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(60), Map.of(), List.of(), 8, 0.8, 3,
                Duration.ofMillis(500), Duration.ofSeconds(8), 5, Duration.ofMinutes(5), Duration.ofHours(24), 1000,
                new CollectorSettings.Recording(false, "recordings"), null,
                List.of(new CollectorSettings.Source("us", "datadoghq.com", "DD_US_API_KEY", "DD_US_APP_KEY",
                        List.of(new DashboardRef("dashboards/us/lab.json", null, Map.of(), List.of("all"))), Map.of(), null, null)));
        assertThat(files.check(s)).isEmpty();
        Stub stub = new Stub();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int code = CheckDashboards.run(s, files, lane -> stub, null, new PrintStream(bytes, true, StandardCharsets.UTF_8), 0);
        String out = bytes.toString(StandardCharsets.UTF_8);

        assertThat(code).isEqualTo(1);
        assertThat(out).contains("== us (datadoghq.com): 4 views")
                .contains("· Payments   filters: team IN (web,mobile), service:payments, env:prod")
                .contains("  ✓ API Metrics / Latencies")
                .contains("· Billing   filters: service:billing")
                .contains("  ✗ ")
                .contains("0 of 12 panels returned lines")
                .contains("Some views return no lines at all");
        assertThat(out).doesNotContain("service:select");
        assertThat(stub.calls.get()).as("batched: far fewer calls than questions").isLessThan(6);
    }
}
