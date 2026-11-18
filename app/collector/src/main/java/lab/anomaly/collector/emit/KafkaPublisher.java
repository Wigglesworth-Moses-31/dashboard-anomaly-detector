package lab.anomaly.collector.emit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes to Kafka (add-collector design K11): idempotent producer with acks=all, key from Topics (keeps each
 * line in order), header {@code contract} = message type. Every message passes ContractChecks first; one that
 * fails is logged and dropped, because it would mean a bug in the Collector.
 */
public final class KafkaPublisher implements MessageSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);

    private final KafkaProducer<String, byte[]> producer;
    private final String seriesTopic;
    private final String pointsTopic;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public KafkaPublisher(String bootstrapServers) {
        this(bootstrapServers, Topics.METRIC_SERIES, Topics.METRIC_POINTS);
    }

    /** Topic names can be replaced in tests, so tests never write to the real topics. */
    public KafkaPublisher(String bootstrapServers, String seriesTopic, String pointsTopic) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");             // keep-ten-days T3: smaller on disk
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);     // producer retries cannot create duplicates
        config.put(ProducerConfig.ACKS_CONFIG, "all");                  // sent = safely stored
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);                 // small batches
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "collector");
        this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
        this.seriesTopic = seriesTopic;
        this.pointsTopic = pointsTopic;
    }

    @Override
    public void send(SeriesDescriptor description) {
        publish(seriesTopic, Topics.key(description), description.toByteArray(), "SeriesDescriptor",
                ContractChecks.problems(description));
    }

    @Override
    public void send(MetricPoints points) {
        publish(pointsTopic, Topics.key(points), points.toByteArray(), "MetricPoints", ContractChecks.problems(points));
    }

    private void publish(String topic, String key, byte[] value, String contract, List<String> problems) {
        if (!problems.isEmpty()) {
            dropped.incrementAndGet();
            log.error("Not sending a {} that fails the contract checks (a Collector bug): {}", contract, problems);
            return;
        }
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
        record.headers().add("contract", contract.getBytes(StandardCharsets.UTF_8));
        producer.send(record, (metadata, error) -> {
            if (error != null) {
                log.warn("Kafka send to {} failed: {}", topic, error.getMessage());
            }
        });
        sent.incrementAndGet();
    }

    public long sentCount() {
        return sent.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public void flush() {
        producer.flush();
    }

    @Override
    public void close() {
        producer.close();
    }
}
