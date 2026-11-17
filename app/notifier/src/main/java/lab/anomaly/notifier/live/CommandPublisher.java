package lab.anomaly.notifier.live;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.ResolveIncident;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Puts "close this incident with this reason" on the incident-commands topic (add-incident-resolution S3). The Detector,
 * the only service that changes incidents, applies it and announces the result as an incident event.
 */
public class CommandPublisher implements AutoCloseable {

    /** How a command leaves (tests replace it). */
    @FunctionalInterface
    public interface Sender {
        void send(ResolveIncident command) throws Exception;
    }

    private final Sender sender;
    private final KafkaProducer<String, byte[]> producer;

    public CommandPublisher(String bootstrap) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "notifier-commands");
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        this.producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
        this.sender = c -> producer.send(new ProducerRecord<>(Topics.INCIDENT_COMMANDS, Topics.key(c), c.toByteArray())).get(5, TimeUnit.SECONDS);
    }

    public CommandPublisher(Sender sender) {
        this.sender = sender;
        this.producer = null;
    }

    /** Sends the command and returns its id; throws when Kafka did not take it within 5 seconds. */
    public String resolve(String incidentId, String reason, long nowMs) throws Exception {
        ResolveIncident command = ResolveIncident.newBuilder().setCommandId(UUID.randomUUID().toString())
                .setIncidentId(incidentId).setReason(reason.strip()).setRequestedMs(nowMs).build();
        sender.send(command);
        return command.getCommandId();
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.close();
        }
    }
}
