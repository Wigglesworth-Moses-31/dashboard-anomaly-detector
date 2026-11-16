package lab.anomaly.store.write;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

/**
 * Turns one poll of Kafka records into what to save (add-store design S2), without touching the database:
 * <ul>
 *   <li>each record is parsed and run through ContractChecks; a broken one is set aside with its reason;</li>
 *   <li>points are merged by (series, time) in a LinkedHashMap, last value wins: a multi-row upsert must not touch
 *       the same row twice, and the newest value is the one to keep (O(n) per batch);</li>
 *   <li>descriptions are merged by series, keeping the most recently updated.</li>
 * </ul>
 */
public final class BatchPlanner {

    public record PointKey(String seriesId, long tsMs) {}

    public record PointRow(String seriesId, long tsMs, double value, boolean partial) {}

    public record Batch(Map<String, SeriesDescriptor> descriptors, Map<PointKey, PointRow> points,
                        List<String> setAside, Map<TopicPartition, Long> firstOffsets, int records) {}

    public static Batch plan(Iterable<ConsumerRecord<String, byte[]>> records) {
        Map<String, SeriesDescriptor> descriptors = new HashMap<>();
        Map<PointKey, PointRow> points = new LinkedHashMap<>();
        List<String> setAside = new ArrayList<>();
        Map<TopicPartition, Long> firstOffsets = new HashMap<>();
        int count = 0;
        for (ConsumerRecord<String, byte[]> record : records) {
            count++;
            firstOffsets.merge(new TopicPartition(record.topic(), record.partition()), record.offset(), Math::min);
            String where = record.topic() + "/" + record.partition() + "@" + record.offset();
            try {
                if (Topics.METRIC_SERIES.equals(record.topic())) {
                    SeriesDescriptor d = SeriesDescriptor.parseFrom(record.value());
                    List<String> problems = ContractChecks.problems(d);
                    if (!problems.isEmpty()) {
                        setAside.add(where + ": " + problems);
                        continue;
                    }
                    descriptors.merge(d.getSeriesId(), d, (a, b) -> b.getUpdatedMs() >= a.getUpdatedMs() ? b : a);
                } else if (Topics.METRIC_POINTS.equals(record.topic())) {
                    MetricPoints m = MetricPoints.parseFrom(record.value());
                    List<String> problems = ContractChecks.problems(m);
                    if (!problems.isEmpty()) {
                        setAside.add(where + ": " + problems);
                        continue;
                    }
                    for (Point p : m.getPointsList()) {
                        PointKey key = new PointKey(m.getSeriesId(), p.getTsMs());
                        points.remove(key);                              // re-insert so order follows the latest
                        points.put(key, new PointRow(m.getSeriesId(), p.getTsMs(), p.getValue(), p.getPartial()));
                    }
                } else {
                    setAside.add(where + ": unexpected topic");
                }
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                setAside.add(where + ": not a valid message (" + e.getMessage() + ")");
            }
        }
        return new Batch(descriptors, points, setAside, firstOffsets, count);
    }

    private BatchPlanner() {
    }
}
