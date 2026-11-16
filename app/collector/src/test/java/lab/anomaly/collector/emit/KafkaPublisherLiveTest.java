package lab.anomaly.collector.emit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import lab.anomaly.contracts.SeriesIds;
import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Task 4.4 against the local platform Kafka, on temporary topics (deleted afterwards) so the real topics never
 * get test data. Run with -Dlive.kafka=true while the platform is up.
 */
@EnabledIfSystemProperty(named = "live.kafka", matches = "true")
class KafkaPublisherLiveTest {

    private static final String BOOTSTRAP = "localhost:59092";

    @Test
    void descriptionAndPointsArriveOnTheirTopicsKeyedByTheLine() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String seriesTopic = "test-metric-series-" + suffix;
        String pointsTopic = "test-metric-points-" + suffix;
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(seriesTopic, 3, (short) 1), new NewTopic(pointsTopic, 6, (short) 1))).all().get();
            try {
                String queryId = SeriesIds.queryId("sum:x{*}");
                String id = SeriesIds.seriesId("datadog", "datadoghq.com", queryId, Map.of("api", "/v1/invoices"));
                SeriesDescriptor d = SeriesDescriptor.newBuilder().setSeriesId(id)
                        .setSource(Source.newBuilder().setSystem("datadog").setInstance("datadoghq.com")).setQueryId(queryId)
                        .putGroupTags("api", "/v1/invoices").setMetric("x").setSpaceAggregation(Aggregation.AGGREGATION_SUM)
                        .setTimeAggregation(Aggregation.AGGREGATION_SUM).setStepSeconds(10).setKind(Kind.KIND_COUNT).build();
                MetricPoints p = MetricPoints.newBuilder().setSeriesId(id).setCollectedMs(1_763_182_815_000L)
                        .addPoints(Point.newBuilder().setTsMs(1_763_182_800_000L).setValue(42)).build();
                MetricPoints broken = p.toBuilder().clearSeriesId().build();

                try (KafkaPublisher publisher = new KafkaPublisher(BOOTSTRAP, seriesTopic, pointsTopic)) {
                    publisher.send(d);
                    publisher.send(p);
                    publisher.send(broken);                                 // fails ContractChecks: must not be sent
                    publisher.flush();
                    assertThat(publisher.sentCount()).isEqualTo(2);
                    assertThat(publisher.droppedCount()).isEqualTo(1);
                }

                List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
                Properties config = new Properties();
                config.put("bootstrap.servers", BOOTSTRAP);
                config.put("group.id", "test-" + suffix);
                config.put("auto.offset.reset", "earliest");
                try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
                    consumer.subscribe(List.of(seriesTopic, pointsTopic));
                    long until = System.currentTimeMillis() + 20_000;
                    while (received.size() < 2 && System.currentTimeMillis() < until) {
                        consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                    }
                }
                System.out.println("LIVE kafka: received " + received.size() + " -> "
                        + received.stream().map(r -> r.topic().replace("-" + suffix, "") + " key=" + r.key() + " partition=" + r.partition()
                        + " contract=" + new String(r.headers().lastHeader("contract").value(), StandardCharsets.UTF_8)).toList());

                assertThat(received).hasSize(2);
                assertThat(received).allMatch(r -> r.key().equals(id));
                ConsumerRecord<String, byte[]> series = received.stream().filter(r -> r.topic().equals(seriesTopic)).findFirst().orElseThrow();
                ConsumerRecord<String, byte[]> points = received.stream().filter(r -> r.topic().equals(pointsTopic)).findFirst().orElseThrow();
                assertThat(SeriesDescriptor.parseFrom(series.value())).isEqualTo(d);
                assertThat(MetricPoints.parseFrom(points.value())).isEqualTo(p);
            } finally {
                admin.deleteTopics(List.of(seriesTopic, pointsTopic)).all().get();
            }
        }
    }
}
