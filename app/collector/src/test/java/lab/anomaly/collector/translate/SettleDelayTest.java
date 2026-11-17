package lab.anomaly.collector.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.emit.Emitter;
import lab.anomaly.collector.emit.MessageSink;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.junit.jupiter.api.Test;

/** fix-false-drops tasks 1.1-1.3: the settle delay and late changes, with the 13:04:10 example from the live page. */
class SettleDelayTest {

    private static final Duration STEP = Duration.ofSeconds(10);
    private static final long STEP_START = Instant.parse("2026-11-17T13:04:10Z").toEpochMilli();
    private static final PollUnit UNIT = new PollUnit("sum:requests{*}.as_count()", List.of(new Query("query1", "sum:requests{*}.as_count()")),
            List.of("query1"), List.of(new PollUnit.View("d", "Shop", "Request Counts by Status Code", "API Gateway Metrics")), "us");

    private final List<Point> sent = new ArrayList<>();
    private final Emitter emitter = new Emitter(new MessageSink() {
        @Override public void send(SeriesDescriptor d) { }
        @Override public void send(MetricPoints p) { sent.addAll(p.getPointsList()); }
    }, 66);

    private void poll(AnswerTranslator translator, String at, double count) {
        long received = Instant.parse(at).toEpochMilli();
        TimeseriesAnswer answer = new TimeseriesAnswer(List.of(STEP_START), List.of(new TimeseriesAnswer.Series(0, Map.of("status", "200"), List.of(count))), received, null);
        for (TranslatedLine line : translator.translate(UNIT, answer, STEP)) {
            line.description().setStepSeconds(10);
            emitter.emit(line.description(), line.points(), received);
        }
    }

    @Test
    void aStepStaysUnfinishedUntilItSettled() {
        AnswerTranslator translator = new AnswerTranslator("datadog", "datadoghq.com", 60_000);
        poll(translator, "2026-11-17T13:04:25Z", 1);       // the false "traffic down" of 17 Nov
        poll(translator, "2026-11-17T13:04:55Z", 9);
        poll(translator, "2026-11-17T13:05:25Z", 13);
        poll(translator, "2026-11-17T13:05:40Z", 13);

        assertThat(sent).extracting(Point::getValue).containsExactly(1.0, 9.0, 13.0);
        assertThat(sent).extracting(Point::getPartial).containsExactly(true, true, false);
        assertThat(emitter.lateChanges("datadoghq.com")).as("nothing changed after it was finished").isNull();
    }

    @Test
    void withoutASettleDelayTheIncompleteCountIsFinishedAtOnce() {
        AnswerTranslator translator = new AnswerTranslator("datadog", "datadoghq.com", 0);
        poll(translator, "2026-11-17T13:04:25Z", 1);
        assertThat(sent).singleElement().satisfies(p -> {
            assertThat(p.getPartial()).isFalse();                 // judged as 1: the old behaviour
            assertThat(p.getValue()).isEqualTo(1.0);
        });
    }

    @Test
    void lateChangesAreCountedWithHowLateTheyCame() {
        AnswerTranslator translator = new AnswerTranslator("datadog", "datadoghq.com", 30_000);
        poll(translator, "2026-11-17T13:05:05Z", 9);               // 45 s after the step's end: finished with 9
        poll(translator, "2026-11-17T13:05:10Z", 13);              // 50 s after: changed
        Emitter.LateChanges late = emitter.lateChanges("datadoghq.com");
        assertThat(late.count()).isEqualTo(1);
        assertThat(late.latestAfterMs()).isEqualTo(50_000);
        assertThat(sent).extracting(Point::getValue).containsExactly(9.0, 13.0);
    }

    @Test
    void theSettleDelayHasADefaultAndMustFitTheWindow() {
        CollectorSettings base = lab.anomaly.collector.CollectorSettingsTest.valid();
        assertThat(base.effectiveSettleDelay()).isEqualTo(Duration.ofSeconds(60));
        CollectorSettings tooLong = new CollectorSettings(base.source(), base.datadogSite(), base.dashboards(), base.pollInterval(),
                base.step(), base.backfill(), Duration.ofMinutes(2), base.dashboardRefresh(), base.dashboardFilters(),
                base.classification(), 8, 0.8, 3, base.retryBase(), base.retryCap(), 5, base.breakerOpen(), base.metadataTtl(),
                10_000, base.recording(), null, null, null, Duration.ofMinutes(3));
        assertThat(lab.anomaly.collector.CollectorSettingsTest.problemsOf(tooLong))
                .containsExactly("settle-delay: must be shorter than recent-window, or a step would leave the window before it is finished");
    }

    // ---- add-incident-history task 2.1 ---------------------------------------------------------------------

    @Test
    void theLinesViewCarriesItsPanelGroup() {
        AnswerTranslator translator = new AnswerTranslator("datadog", "datadoghq.com", 0);
        TimeseriesAnswer answer = new TimeseriesAnswer(List.of(STEP_START), List.of(new TimeseriesAnswer.Series(0, Map.of(), List.of(1.0))), STEP_START + 99_000, null);
        TranslatedLine line = translator.translate(UNIT, answer, STEP).get(0);
        assertThat(line.description().getViews(0).getPanelPath()).isEqualTo("API Gateway Metrics");
        assertThat(line.description().getViews(0).getPanelTitle()).isEqualTo("Request Counts by Status Code");
    }
}
