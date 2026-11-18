package lab.anomaly.detector.incident;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lab.anomaly.contracts.Topics;
import lab.anomaly.detector.ops.DetectorStats;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Sends saved incident events to Kafka in the order they were saved, then marks them sent (design D7, the
 * transactional outbox). Woken right after each save, and checks every second anyway. A crash between sending and
 * marking sends the event again after restart; readers drop the repeat by its event id.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final IncidentRepository repository;
    private final DetectorStats stats;
    private final String bootstrap;
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean running;
    private volatile Thread thread;

    public OutboxPublisher(IncidentRepository repository, DetectorStats stats, Environment environment) {
        this.repository = repository;
        this.stats = stats;
        this.bootstrap = environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("outbox-publisher").daemon().start(this::loop);
    }

    /** Called after a save: send now instead of within the next second. */
    public void wake() {
        wake.release();
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        wake.release();
        if (thread != null) {
            thread.join(10_000);
        }
    }

    private void loop() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");             // keep-ten-days T3: smaller on disk
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "detector-outbox");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer())) {
            long backoff = 1_000;
            while (running) {
                try {
                    wake.tryAcquire(1, TimeUnit.SECONDS);
                    wake.drainPermits();
                    List<IncidentRepository.Pending> pending = repository.unpublished(500);
                    // Send the whole batch (the idempotent producer keeps each incident's order), wait once, then mark.
                    List<java.util.concurrent.Future<?>> sent = new java.util.ArrayList<>();
                    for (IncidentRepository.Pending e : pending) {
                        sent.add(producer.send(new ProducerRecord<>(Topics.INCIDENT_EVENTS, e.incidentId(), e.payload())));
                    }
                    for (java.util.concurrent.Future<?> f : sent) {
                        f.get();
                    }
                    if (!pending.isEmpty()) {
                        repository.markPublished(pending.stream().map(IncidentRepository.Pending::id).toList());
                        long now = System.currentTimeMillis();
                        for (IncidentRepository.Pending e : pending) {
                            stats.eventsSent.incrementAndGet();
                            if (e.arrivedMs() > 0) {
                                stats.recordAnnounce(now - e.arrivedMs());
                            }
                        }
                    }
                    backoff = 1_000;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    stats.lastError = "outbox: " + e;
                    log.warn("Sending incident events failed; retrying in {} ms: {}", backoff, e.toString());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }
        }
    }
}
