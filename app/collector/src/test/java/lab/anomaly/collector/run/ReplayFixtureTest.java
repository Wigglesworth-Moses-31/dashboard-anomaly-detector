package lab.anomaly.collector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Task 7.4: a small slice of the real live recording of 15 Nov 2026 (3 panels, about 5 minutes) kept as test data,
 * so replay keeps being checked after the Datadog trial ends. The expected messages in published.jsonl are what
 * the live Collector really published for those panels.
 */
class ReplayFixtureTest {

    private static final Path SAMPLE = Path.of("src/test/resources/recordings/sample");

    @Test
    void replayOfTheRecordedSampleGivesTheLiveMessages() throws Exception {
        var settings = ReplayComparison.settings(SAMPLE.resolve("collector.yml"));
        var result = ReplayComparison.compare(SAMPLE, SAMPLE.resolve("published.jsonl"), settings);

        assertThat(Files.size(SAMPLE.resolve("published.jsonl"))).isGreaterThan(1000);
        assertThat(result.linesCompared()).isGreaterThan(5);
        assertThat(result.differences()).isEmpty();
        assertThat(result.replayMessages()).isEqualTo(result.liveMessages());
    }
}
