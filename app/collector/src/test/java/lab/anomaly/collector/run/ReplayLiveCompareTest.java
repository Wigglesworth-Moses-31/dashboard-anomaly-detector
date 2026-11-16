package lab.anomaly.collector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Task 6.2/7.4 on a real live recording: -Dreplay.compare=<recordings dir> (with published.jsonl in it)
 * -Dreplay.settings=<config-repo/collector.yml>.
 */
@EnabledIfSystemProperty(named = "replay.compare", matches = ".+")
class ReplayLiveCompareTest {

    @Test
    void replayOfALiveRecordingPublishesWhatTheLiveRunPublished() throws Exception {
        Path dir = Path.of(System.getProperty("replay.compare"));
        var settings = ReplayComparison.settings(Path.of(System.getProperty("replay.settings")));
        var result = ReplayComparison.compare(dir, dir.resolve("published.jsonl"), settings);
        System.out.println("REPLAY: live messages=" + result.liveMessages() + ", replayed=" + result.replayMessages()
                + ", lines compared=" + result.linesCompared() + ", lines differing=" + result.differences().size());
        result.differences().stream().limit(5).forEach(d -> System.out.println("REPLAY differs: " + d));
        assertThat(result.linesCompared()).isGreaterThan(50);
        assertThat(result.differences()).isEmpty();
    }
}
