package lab.anomaly.collector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.emit.Emitter;
import lab.anomaly.collector.emit.MessageSink;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * add-multi-site task 3.1 (design M10): an old recording (no source folders) replays as the single source with the
 * same line ids; a two-source recording replays both sources, in recorded time order, with different ids.
 */
class MultiSiteReplayTest {

    private static final Path SAMPLE = Path.of("src/test/resources/recordings/sample");

    private record Played(int answers, Set<String> usIds, Set<String> euIds, Set<String> allIds, boolean wentBack) {}

    private static Played replay(Path dir, CollectorSettings s) {
        List<SeriesDescriptor> descriptors = new ArrayList<>();
        MessageSink sink = new MessageSink() {
            @Override public synchronized void send(SeriesDescriptor d) { descriptors.add(d); }
            @Override public void send(MetricPoints p) { }
        };
        CollectorEngine engine = new CollectorEngine(() -> s, () -> 1, c -> null, (c, k, clock) -> null, null, Clock.systemUTC());
        CollectorEngine.ReplayClock clock = new CollectorEngine.ReplayClock();
        int answers = engine.replayInto(dir, s, new Emitter(sink, 66), clock);
        Set<String> us = new HashSet<>();
        Set<String> eu = new HashSet<>();
        Set<String> all = new HashSet<>();
        for (SeriesDescriptor d : descriptors) {
            all.add(d.getSeriesId());
            (d.getSource().getInstance().equals("datadoghq.eu") ? eu : us).add(d.getSeriesId());
        }
        return new Played(answers, us, eu, all, clock.wentBack());
    }

    /** Series ids the live Collector really published for the sample (published.jsonl). */
    private static Set<String> publishedIds() throws Exception {
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(SAMPLE.resolve("published.jsonl"))) {
            if (line.startsWith("SeriesDescriptor")) {
                int at = line.indexOf("\"series_id\":\"");
                ids.add(line.substring(at + 13, line.indexOf('"', at + 13)));
            }
        }
        return ids;
    }

    @Test
    void oldRecordingReplaysAsTheSingleSourceWithTheSameIds() throws Exception {
        CollectorSettings s = ReplayComparison.settings(SAMPLE.resolve("collector.yml"));
        Played played = replay(SAMPLE, s);

        assertThat(played.answers()).isGreaterThan(10);
        assertThat(played.allIds()).isNotEmpty().isEqualTo(publishedIds());
        assertThat(played.wentBack()).isFalse();
    }

    @Test
    void twoSourceRecordingReplaysBothInTimeOrderWithDifferentIds(@TempDir Path run) throws Exception {
        copy(SAMPLE.resolve("2026-11-15"), run.resolve("us/2026-11-15"));
        copy(SAMPLE.resolve("2026-11-15"), run.resolve("eu/2026-11-15"));
        CollectorSettings sample = ReplayComparison.settings(SAMPLE.resolve("collector.yml"));
        CollectorSettings s = new CollectorSettings(sample.source(), null, List.of(), sample.pollInterval(), sample.step(),
                sample.backfill(), sample.recentWindow(), sample.dashboardRefresh(), java.util.Map.of(), sample.classification(),
                8, 0.8, 3, sample.retryBase(), sample.retryCap(), 5, sample.breakerOpen(), sample.metadataTtl(), 10_000,
                sample.recording(), null, List.of(
                new CollectorSettings.Source("us", "datadoghq.com", "DD_US_API_KEY", "DD_US_APP_KEY", sample.dashboards(),
                        sample.dashboardFilters(), null, null),
                new CollectorSettings.Source("eu", "datadoghq.eu", "DD_EU_API_KEY", "DD_EU_APP_KEY", sample.dashboards(),
                        sample.dashboardFilters(), null, null)));
        Played single = replay(SAMPLE, sample);
        Played both = replay(run, s);

        assertThat(both.answers()).isEqualTo(2 * single.answers());
        assertThat(both.usIds()).as("US lines keep their ids").isEqualTo(single.allIds());
        assertThat(both.euIds()).hasSameSizeAs(single.allIds()).doesNotContainAnyElementsOf(both.usIds());
        assertThat(both.wentBack()).as("answers of both sources played in recorded time order").isFalse();
    }

    private static void copy(Path from, Path to) throws Exception {
        Files.createDirectories(to);
        try (Stream<Path> files = Files.list(from)) {
            for (Path f : files.toList()) {
                Files.copy(f, to.resolve(f.getFileName()));
            }
        }
    }
}
