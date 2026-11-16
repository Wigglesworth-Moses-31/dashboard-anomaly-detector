package lab.anomaly.collector.source.datadog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import lab.anomaly.collector.plan.PollPlan;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.recording.RecordingSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;

/**
 * Talks to the real Datadog trial (tasks 2.2, 2.3). Off by default; run with
 * {@code mvn -pl collector test -Dtest=DatadogSourceLiveTest -Dlive.datadog=true} and the keys in the environment.
 * Read-only calls only.
 */
@EnabledIfSystemProperty(named = "live.datadog", matches = "true")
class DatadogSourceLiveTest {

    @TempDir
    Path recordings;

    @Test
    void readsADashboardNumbersAndMetadataAndRecordsThemWithoutKeys() throws Exception {
        DatadogCredentials credentials = DatadogCredentials.fromEnvironment(new StandardEnvironment());
        assertThat(credentials.complete()).as("DD_API_KEY and DD_APP_KEY in the environment").isTrue();
        MetricSource source = new RecordingSource(new DatadogSource("datadoghq.com", credentials, Clock.systemUTC()),
                recordings, Clock.systemUTC());

        Dashboard dashboard = source.dashboard("w2f-pqs-ghd");
        System.out.println("LIVE dashboard: '" + dashboard.title() + "', panels=" + dashboard.panels().size()
                + ", queries=" + dashboard.panels().stream().mapToInt(p -> p.queries().size()).sum());
        assertThat(dashboard.panels()).isNotEmpty();

        long from = ZonedDateTime.parse("2026-11-15T09:06:00+05:30").toInstant().toEpochMilli();
        TimeseriesAnswer answer = source.timeseries(new TimeseriesRequest(
                List.of(new Query("query1", "sum:lab.api.request.count{env:prod} by {api}.as_count()")),
                List.of("query1"), from, from + 120_000, 10_000));
        System.out.println("LIVE timeseries: steps=" + answer.times().size() + ", series=" + answer.series().size()
                + ", first tags=" + answer.series().get(0).groupTags() + ", rate limit=" + answer.rateLimit());
        assertThat(answer.times()).hasSize(12);

        var metadata = source.metadata("trace.http.request");
        System.out.println("LIVE metadata trace.http.request: " + metadata);
        assertThat(metadata).isPresent();
        assertThat(metadata.get().unit()).isEqualTo("second");

        // Task 2.3: the recording holds the request and answer, but no key
        List<Path> files;
        try (Stream<Path> walk = Files.walk(recordings)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertThat(files).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("dashboards.jsonl", "timeseries.jsonl", "metadata.jsonl");
        for (Path file : files) {
            String content = Files.readString(file);
            assertThat(content).doesNotContain(credentials.apiKey()).doesNotContain(credentials.applicationKey());
        }
        System.out.println("LIVE recording files: " + files.size() + ", none contains a key");
    }

    /** Task 3.2: every request the planner builds from both lab dashboards is accepted by Datadog. */
    @Test
    void everyPlannedRequestIsAcceptedByDatadog() throws Exception {
        DatadogCredentials credentials = DatadogCredentials.fromEnvironment(new StandardEnvironment());
        DatadogSource source = new DatadogSource("datadoghq.com", credentials, Clock.systemUTC());
        var dashboards = List.of(source.dashboard("w2f-pqs-ghd"), source.dashboard("jrp-8af-7xd"));
        var filters = java.util.Map.of("w2f-pqs-ghd", java.util.Map.of("env", "prod"), "jrp-8af-7xd", java.util.Map.of("env", "tsreenv"));
        List<PollUnit> units = PollPlan.build(dashboards, filters, java.time.Duration.ofSeconds(10));

        long from = ZonedDateTime.parse("2026-11-15T09:06:00+05:30").toInstant().toEpochMilli();
        int accepted = 0;
        int seriesTotal = 0;
        List<String> rejected = new java.util.ArrayList<>();
        for (PollUnit unit : units) {
            try {
                TimeseriesAnswer answer = source.timeseries(new TimeseriesRequest(unit.queries(), unit.formulas(), from, from + 120_000, 10_000));
                accepted++;
                seriesTotal += answer.series().size();
            } catch (SourceException e) {
                rejected.add(unit.label() + " -> " + e.getMessage());
            }
        }
        System.out.println("LIVE plan: units=" + units.size() + " accepted=" + accepted + " series=" + seriesTotal
                + " views=" + units.stream().mapToInt(u -> u.views().size()).sum());
        rejected.forEach(r -> System.out.println("LIVE rejected: " + r));
        assertThat(rejected).isEmpty();
    }
}
