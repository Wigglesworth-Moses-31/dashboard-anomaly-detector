package lab.anomaly.collector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lab.anomaly.collector.CollectorSettings.ClassificationRule;
import lab.anomaly.collector.emit.Emitter;
import lab.anomaly.collector.emit.MessageSink;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Panel;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.Dashboard.TemplateVariable;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import lab.anomaly.collector.source.recording.RecordingSource;
import lab.anomaly.collector.source.recording.ReplaySource;
import lab.anomaly.collector.support.MutableClock;
import lab.anomaly.collector.translate.AnswerTranslator;
import lab.anomaly.collector.translate.Classifier;
import lab.anomaly.contracts.ContractJson;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tasks 6.1 (dashboard refresh) and 6.2 (replay gives the same messages as the live run). */
class RefreshAndReplayTest {

    private static final long T0 = 1_763_182_800_000L;
    private static final Duration STEP = Duration.ofSeconds(10);
    private static final List<TemplateVariable> VARS = List.of(new TemplateVariable("env", "env", "prod"));

    /** A source whose dashboard can change and whose numbers follow a fixed pattern. */
    private static final class LabSource implements MetricSource {
        List<Panel> panels = new ArrayList<>(List.of(panel("Errors", "sum:errors{$env} by {api}.as_count()")));
        boolean dashboardFails;
        final MutableClock clock;

        LabSource(MutableClock clock) {
            this.clock = clock;
        }

        @Override public Dashboard dashboard(String id) throws SourceException {
            if (dashboardFails) {
                throw new SourceException(SourceException.Kind.NETWORK, "timeout");
            }
            return new Dashboard(id, "Lab", VARS, panels, 0);
        }

        @Override public Optional<MetricMetadata> metadata(String metric) {
            return Optional.of(new MetricMetadata("count", "error"));
        }

        @Override public TimeseriesAnswer timeseries(TimeseriesRequest request) {
            List<Long> times = new ArrayList<>();
            List<Double> a = new ArrayList<>();
            List<Double> b = new ArrayList<>();
            for (long t = request.fromMs(); t < request.toMs(); t += request.intervalMs()) {
                times.add(t);
                a.add((double) ((t / 10_000) % 7));
                b.add(t % 30_000 == 0 ? null : 2.0);                               // some gaps
            }
            return new TimeseriesAnswer(times, List.of(
                    new TimeseriesAnswer.Series(0, Map.of("api", "/v1/a"), a),
                    new TimeseriesAnswer.Series(0, Map.of("api", "/v1/b"), b)), clock.millis(), null);
        }
    }

    private static Panel panel(String title, String query) {
        return new Panel(title, "", List.of(new Query("query1", query)), List.of("query1"));
    }

    // ---- 6.1 ------------------------------------------------------------------------------------------

    @Test
    void addedPanelsAreCollectedRemovedOnesStopAndAFailedReadKeepsTheLastPlan() {
        LabSource source = new LabSource(new MutableClock(Instant.ofEpochMilli(T0)));
        Discovery discovery = new Discovery(source);
        Scheduler scheduler = new Scheduler(15_000);

        List<PollUnit> first = discovery.plan(List.of("lab"), Map.of(), STEP);
        scheduler.setUnits(first.stream().map(PollUnit::key).toList(), T0);
        assertThat(scheduler.size()).isEqualTo(1);

        source.panels.add(panel("Latency", "p95:latency{$env} by {api}"));
        List<PollUnit> second = discovery.plan(List.of("lab"), Map.of(), STEP);
        scheduler.setUnits(second.stream().map(PollUnit::key).toList(), T0 + 3_600_000);
        assertThat(scheduler.size()).isEqualTo(2);

        source.panels.remove(0);
        List<PollUnit> third = discovery.plan(List.of("lab"), Map.of(), STEP);
        scheduler.setUnits(third.stream().map(PollUnit::key).toList(), T0 + 7_200_000);
        assertThat(third).extracting(u -> u.views().get(0).panelTitle()).containsExactly("Latency");
        assertThat(scheduler.size()).isEqualTo(1);

        source.dashboardFails = true;
        assertThat(discovery.plan(List.of("lab"), Map.of(), STEP)).isEqualTo(third);   // last good plan kept
    }

    // ---- 6.2 ------------------------------------------------------------------------------------------

    private static final class ListSink implements MessageSink {
        final List<String> messages = new ArrayList<>();
        @Override public void send(SeriesDescriptor d) { messages.add("SeriesDescriptor\t" + ContractJson.toJson(d)); }
        @Override public void send(MetricPoints p) { messages.add("MetricPoints\t" + ContractJson.toJson(p)); }
    }

    private static PollProcessor processor(MetricSource source, MessageSink sink, Clock clock) {
        List<ClassificationRule> rules = List.of();
        return new PollProcessor(new AnswerTranslator("datadog", "datadoghq.com"),
                new Classifier(source, 100, Duration.ofHours(24).toMillis(), clock), new Emitter(sink, 66), () -> rules);
    }

    @Test
    void replayPublishesTheSameMessagesAsTheLiveRun(@TempDir Path recordings) throws Exception {
        // Live run: recorded through RecordingSource, 5 polls 15 s apart
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(T0));
        MetricSource live = new RecordingSource(new LabSource(clock), recordings, clock);
        ListSink liveSink = new ListSink();
        PollProcessor liveProcessor = processor(live, liveSink, clock);
        List<PollUnit> plan = new Discovery(live).plan(List.of("lab"), Map.of(), STEP);
        for (int poll = 0; poll < 5; poll++) {
            for (PollUnit unit : plan) {
                long now = clock.millis();
                long from = ((now - (poll == 0 ? 600_000 : 120_000)) / 10_000) * 10_000;
                liveProcessor.process(unit, live.timeseries(new TimeseriesRequest(unit.queries(), unit.formulas(), from, now, 10_000)), STEP);
            }
            clock.advance(Duration.ofSeconds(15));
        }

        // Replay: no source but the files
        ReplaySource replay = new ReplaySource(recordings);
        ListSink replaySink = new ListSink();
        CollectorEngine.ReplayClock replayClock = new CollectorEngine.ReplayClock();
        PollProcessor replayProcessor = processor(replay, replaySink, replayClock);
        Map<String, PollUnit> replayPlan = new HashMap<>();
        new Discovery(replay).plan(List.of("lab"), Map.of(), STEP).forEach(u -> replayPlan.put(u.key(), u));
        for (ReplaySource.Recorded recorded : replay.timeseriesInOrder()) {
            replayClock.set(recorded.atMs());
            replayProcessor.process(replayPlan.get(recorded.request().canonicalQuestion()), recorded.answer(), STEP);
        }

        assertThat(liveSink.messages).hasSizeGreaterThan(5);
        assertThat(replaySink.messages).isEqualTo(liveSink.messages);
    }
}
