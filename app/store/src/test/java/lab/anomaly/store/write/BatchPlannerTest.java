package lab.anomaly.store.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.SeriesIds;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** Tasks 3.1/3.2 logic without a database: merge, set aside, first offsets. */
class BatchPlannerTest {

    private static final long T0 = 1_763_182_800_000L;
    static final String ID = SeriesIds.seriesId("datadog", "datadoghq.com", SeriesIds.queryId("sum:x{*}"), Map.of());

    static SeriesDescriptor descriptor(long updated, String unit) {
        return SeriesDescriptor.newBuilder().setSeriesId(ID).setSource(Source.newBuilder().setSystem("datadog").setInstance("datadoghq.com"))
                .setQueryId(SeriesIds.queryId("sum:x{*}")).setMetric("x").setSpaceAggregation(Aggregation.AGGREGATION_SUM)
                .setTimeAggregation(Aggregation.AGGREGATION_SUM).setStepSeconds(10).setKind(Kind.KIND_COUNT).setUnit(unit)
                .setFirstSeenMs(T0).setUpdatedMs(updated).build();
    }

    static MetricPoints points(String id, double... values) {
        MetricPoints.Builder m = MetricPoints.newBuilder().setSeriesId(id).setCollectedMs(T0 + 60_000);
        for (int i = 0; i < values.length; i++) {
            m.addPoints(Point.newBuilder().setTsMs(T0 + i * 10_000L).setValue(values[i]));
        }
        return m.build();
    }

    private final List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
    private long offset = 100;

    private void add(String topic, byte[] value) {
        records.add(new ConsumerRecord<>(topic, 2, offset++, ID, value));
    }

    @Test
    void lastValueWinsAndABrokenMessageBetweenTwoGoodOnesIsSetAside() {
        add(Topics.METRIC_POINTS, points(ID, 1, 2).toByteArray());
        add(Topics.METRIC_POINTS, points("", 9).toByteArray());              // no series id: set aside
        add(Topics.METRIC_POINTS, points(ID, 1, 3).toByteArray());           // step 2 corrected to 3
        add(Topics.METRIC_POINTS, new byte[] {1, 2, 3, 4, 5});               // not a message at all

        BatchPlanner.Batch batch = BatchPlanner.plan(records);

        assertThat(batch.points()).hasSize(2);
        assertThat(batch.points().get(new BatchPlanner.PointKey(ID, T0 + 10_000)).value()).isEqualTo(3.0);
        assertThat(batch.setAside()).hasSize(2);
        assertThat(batch.setAside().get(0)).contains("metric-points/2@101").contains("series_id");
        assertThat(batch.firstOffsets()).containsEntry(new TopicPartition(Topics.METRIC_POINTS, 2), 100L);
        assertThat(batch.records()).isEqualTo(4);
    }

    @Test
    void newestDescriptionWins() {
        add(Topics.METRIC_SERIES, descriptor(T0 + 2, "new").toByteArray());
        add(Topics.METRIC_SERIES, descriptor(T0 + 1, "old").toByteArray());

        assertThat(BatchPlanner.plan(records).descriptors().get(ID).getUnit()).isEqualTo("new");
    }
}
