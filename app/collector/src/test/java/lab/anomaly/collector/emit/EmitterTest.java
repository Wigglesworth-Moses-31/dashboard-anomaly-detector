package lab.anomaly.collector.emit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.SeriesIds;
import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import org.junit.jupiter.api.Test;

/** Task 4.3. */
class EmitterTest {

    private static final long T0 = 1_763_182_800_000L;
    private final List<Object> sent = new ArrayList<>();
    private final MessageSink sink = new MessageSink() {
        @Override public void send(SeriesDescriptor d) { sent.add(d); }
        @Override public void send(MetricPoints p) { sent.add(p); }
    };

    private static SeriesDescriptor.Builder description(String unit) {
        String queryId = SeriesIds.queryId("sum:x{*}");
        return SeriesDescriptor.newBuilder().setSeriesId(SeriesIds.seriesId("datadog", "datadoghq.com", queryId, Map.of()))
                .setSource(Source.newBuilder().setSystem("datadog").setInstance("datadoghq.com")).setQueryId(queryId)
                .setMetric("x").setSpaceAggregation(Aggregation.AGGREGATION_SUM).setTimeAggregation(Aggregation.AGGREGATION_SUM)
                .setStepSeconds(10).setKind(Kind.KIND_COUNT).setUnit(unit).setClassifiedBy("rule:1");
    }

    private static Point point(long ts, double value, boolean partial) {
        return Point.newBuilder().setTsMs(ts).setValue(value).setPartial(partial).build();
    }

    @Test
    void newLineSendsItsDescriptionFirst() {
        new Emitter(sink, 60).emit(description("requests"), List.of(point(T0, 5, false)), T0 + 15_000);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isInstanceOf(SeriesDescriptor.class);
        assertThat(((SeriesDescriptor) sent.get(0)).getFirstSeenMs()).isEqualTo(T0 + 15_000);
        assertThat(sent.get(1)).isInstanceOf(MetricPoints.class);
    }

    @Test
    void finishedStepIsSentOncePartialAlwaysChangedValueAgain() {
        Emitter emitter = new Emitter(sink, 60);
        emitter.emit(description("requests"), List.of(point(T0, 5, false), point(T0 + 10_000, 2, true)), T0 + 15_000);
        sent.clear();

        // next poll: same finished step, the partial step now finished, a new partial step
        emitter.emit(description("requests"), List.of(point(T0, 5, false), point(T0 + 10_000, 4, false), point(T0 + 20_000, 1, true)), T0 + 30_000);

        assertThat(sent).hasSize(1);                                   // no description: nothing changed
        assertThat(((MetricPoints) sent.get(0)).getPointsList()).extracting(Point::getTsMs).containsExactly(T0 + 10_000, T0 + 20_000);
        sent.clear();

        // late correction from the source: the finished step T0 changes value
        emitter.emit(description("requests"), List.of(point(T0, 6, false)), T0 + 45_000);
        assertThat(((MetricPoints) sent.get(0)).getPointsList()).extracting(Point::getValue).containsExactly(6.0);
    }

    @Test
    void changedDescriptionIsSentAgainKeepingFirstSeen() {
        Emitter emitter = new Emitter(sink, 60);
        emitter.emit(description("requests"), List.of(point(T0, 5, false)), T0 + 15_000);
        sent.clear();
        emitter.emit(description("calls"), List.of(), T0 + 30_000);

        SeriesDescriptor again = (SeriesDescriptor) sent.get(0);
        assertThat(again.getUnit()).isEqualTo("calls");
        assertThat(again.getFirstSeenMs()).isEqualTo(T0 + 15_000);
        assertThat(again.getUpdatedMs()).isEqualTo(T0 + 30_000);
    }

    @Test
    void memoryPerLineStaysBounded() {
        Emitter emitter = new Emitter(sink, 60);
        for (int i = 0; i < 200; i++) {
            emitter.emit(description("requests"), List.of(point(T0 + i * 10_000L, i, false)), T0 + i * 10_000L + 15_000);
        }
        assertThat(emitter.remembered(description("requests").getSeriesId())).isEqualTo(60);
    }

    @Test
    void switchingTheSinkKeepsTheMemoryAndSendsNewMessagesToTheNewSink() {
        Emitter emitter = new Emitter(sink, 60);
        emitter.emit(description("requests"), List.of(point(T0, 5, false)), T0 + 15_000);
        List<Object> recorded = new ArrayList<>();
        emitter.setSink(new MessageSink() {
            @Override public void send(SeriesDescriptor d) { recorded.add(d); }
            @Override public void send(MetricPoints p) { recorded.add(p); }
        });
        sent.clear();

        emitter.emit(description("requests"), List.of(point(T0, 5, false), point(T0 + 10_000, 7, false)), T0 + 30_000);

        assertThat(sent).isEmpty();                                     // the old sink gets nothing more
        assertThat(recorded).hasSize(1);                                // only the new step, no description again
        assertThat(((MetricPoints) recorded.get(0)).getPointsList()).extracting(Point::getTsMs).containsExactly(T0 + 10_000);
    }
}
