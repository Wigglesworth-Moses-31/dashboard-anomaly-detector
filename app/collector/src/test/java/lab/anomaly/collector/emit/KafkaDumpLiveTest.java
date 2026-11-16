package lab.anomaly.collector.emit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import lab.anomaly.contracts.ContractJson;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Reads everything on metric-series and metric-points back from Kafka and writes it as contract JSON lines, in
 * the same format as published.jsonl, so a live run can be analysed from what Kafka really holds (task 7.2/7.3).
 * Run with -Dkafka.dump=<file>.
 */
@EnabledIfSystemProperty(named = "kafka.dump", matches = ".+")
class KafkaDumpLiveTest {

    @Test
    void dumpTopics() throws Exception {
        Properties config = new Properties();
        config.put("bootstrap.servers", "localhost:59092");
        config.put("group.id", "dump-" + UUID.randomUUID());
        config.put("auto.offset.reset", "earliest");
        config.put("max.poll.records", 5000);
        List<String> lines = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            // Read up to where each partition ends *now*: the Collector keeps publishing, so "until quiet" never ends
            List<org.apache.kafka.common.TopicPartition> partitions = new ArrayList<>();
            for (String topic : List.of(Topics.METRIC_SERIES, Topics.METRIC_POINTS)) {
                consumer.partitionsFor(topic).forEach(p -> partitions.add(new org.apache.kafka.common.TopicPartition(topic, p.partition())));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var end = consumer.endOffsets(partitions);
            long deadline = System.currentTimeMillis() + 300_000;
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p)) && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(1000))) {
                    var tp = new org.apache.kafka.common.TopicPartition(r.topic(), r.partition());
                    if (r.offset() >= end.get(tp)) {
                        continue;
                    }
                    lines.add(r.topic().equals(Topics.METRIC_SERIES)
                            ? "SeriesDescriptor\t" + ContractJson.toJson(SeriesDescriptor.parseFrom(r.value()))
                            : "MetricPoints\t" + ContractJson.toJson(MetricPoints.parseFrom(r.value())));
                }
            }
        }
        Path out = Path.of(System.getProperty("kafka.dump"));
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
        System.out.println("DUMP: " + lines.size() + " messages -> " + out);
    }
}
