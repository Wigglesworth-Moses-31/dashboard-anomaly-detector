package lab.anomaly.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Task 7.1, checked independently of the Store's code: read metric-points from the start up to the end offsets
 * taken now (no consumer group, nothing committed), keep the last value of every finished (series, time), and
 * compare with metrics.points. Run with -Dlive.kafka=true while the Store is idle.
 */
@EnabledIfSystemProperty(named = "live.kafka", matches = "true")
class StoreMatchesKafkaLiveTest {

    record Key(String seriesId, long tsMs) { }

    @Test
    void everyFinishedPointInKafkaIsInTheDatabaseOnceWithItsLatestValue() {
        Map<Key, Double> kafka = new HashMap<>();
        long messages = 0;
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:59092");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> parts = consumer.partitionsFor(Topics.METRIC_POINTS).stream()
                    .map(i -> new TopicPartition(i.topic(), i.partition())).toList();
            consumer.assign(parts);
            consumer.seekToBeginning(parts);
            Map<TopicPartition, Long> end = consumer.endOffsets(parts);
            while (parts.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.offset() >= end.get(new TopicPartition(r.topic(), r.partition()))) {
                        continue;
                    }
                    messages++;
                    try {
                        MetricPoints m = MetricPoints.parseFrom(r.value());
                        for (Point pt : m.getPointsList()) {
                            if (!pt.getPartial()) {
                                kafka.put(new Key(m.getSeriesId(), pt.getTsMs()), pt.getValue());
                            }
                        }
                    } catch (Exception broken) {
                        // set aside by the Store as well
                    }
                }
            }
        }

        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:55432/anomaly", "anomaly",
                System.getenv("PLATFORM_DB_PASSWORD"));
        Map<Key, Double> db = new HashMap<>();
        new JdbcTemplate(ds).query("SELECT series_id, (extract(epoch FROM ts) * 1000)::bigint, value FROM metrics.points WHERE NOT partial",
                rs -> { db.put(new Key(rs.getString(1), rs.getLong(2)), rs.getDouble(3)); });
        long dbRows = new JdbcTemplate(ds).queryForObject("SELECT count(*) FROM metrics.points", Long.class);

        long missing = kafka.keySet().stream().filter(k -> !db.containsKey(k)).count();
        long wrong = kafka.entrySet().stream().filter(e -> db.containsKey(e.getKey()) && !db.get(e.getKey()).equals(e.getValue())).count();
        long extra = db.keySet().stream().filter(k -> !kafka.containsKey(k)).count();
        System.out.printf("KAFKA-VS-DB messages=%d kafkaPoints=%d dbRows=%d missing=%d wrongValue=%d extraInDb=%d%n",
                messages, kafka.size(), dbRows, missing, wrong, extra);
        assertThat(missing).isZero();
        assertThat(wrong).isZero();
        assertThat(extra).isZero();
        assertThat(dbRows).as("primary key: one row per (series, time)").isEqualTo(kafka.size());
    }
}
