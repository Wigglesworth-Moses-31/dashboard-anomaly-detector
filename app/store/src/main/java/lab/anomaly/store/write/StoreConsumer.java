package lab.anomaly.store.write;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lab.anomaly.contracts.Topics;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.store.StoreSettings;
import lab.anomaly.store.ops.StoreStats;
import lab.anomaly.store.write.BatchPlanner.Batch;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Store's main loop (add-store design S2): poll → plan → one transaction → commit offsets → poll again.
 * Offsets are committed only after the database commit (at least once); the upserts make a repeat harmless
 * (effectively once). A failed save seeks back to the batch's first offsets and retries, so nothing is skipped.
 * The loop polls again only after saving, so a slow database slows reading instead of filling memory (backpressure).
 */
@Component
public class StoreConsumer {

    private static final Logger log = LoggerFactory.getLogger(StoreConsumer.class);

    private final SettingsHolder<StoreSettings> settings;
    private final SeriesRepository series;
    private final PointRepository points;
    private final TransactionTemplate transaction;
    private final StoreStats stats;
    private final String bootstrap;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile KafkaConsumer<String, byte[]> consumer;

    public StoreConsumer(SettingsHolder<StoreSettings> settings, SeriesRepository series, PointRepository points,
                         TransactionTemplate transaction, StoreStats stats, Environment environment) {
        this.settings = settings;
        this.series = series;
        this.points = points;
        this.transaction = transaction;
        this.stats = stats;
        this.bootstrap = environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("store-consumer").daemon().start(this::loop);
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        KafkaConsumer<String, byte[]> c = consumer;
        if (c != null) {
            c.wakeup();                                // the safe way to end a poll from another thread
        }
        if (thread != null) {
            thread.join(10_000);                       // let it close the consumer, so Kafka hears "leaving the group"
            if (thread.isAlive()) {
                thread.interrupt();                    // e.g. sleeping in a retry back-off
                thread.join(5_000);
            }
        }
    }

    private void loop() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "store");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);          // commit only after the database commit
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, settings.get().maxBatchRecords());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "store");
        long backoffMs = 1_000;
        long nextLagCheck = 0;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            this.consumer = consumer;
            consumer.subscribe(List.of(Topics.METRIC_SERIES, Topics.METRIC_POINTS));
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(settings.get().pollTimeout());
                long now = System.currentTimeMillis();
                if (now >= nextLagCheck) {
                    updateLag(consumer, now);
                    nextLagCheck = now + 5_000;
                }
                if (records.isEmpty()) {
                    continue;
                }
                long started = System.nanoTime();
                Batch batch = BatchPlanner.plan(records);
                try {
                    int[] saved = transaction.execute(status -> new int[] {
                            series.upsert(batch.descriptors().values()), points.upsert(batch.points().values())});
                    consumer.commitSync();
                    batch.setAside().forEach(reason -> log.warn("Message set aside: {}", reason));
                    stats.setAside.addAndGet(batch.setAside().size());
                    stats.batches.incrementAndGet();
                    stats.pointsReceived.addAndGet(batch.points().size());
                    stats.descriptionsSaved.addAndGet(saved[0]);
                    stats.pointsSaved.addAndGet(saved[1]);
                    stats.lastSaveMs = System.currentTimeMillis();
                    stats.lastSaveFailed = false;
                    stats.recordDelay((System.nanoTime() - started) / 1_000_000);
                    backoffMs = 1_000;
                } catch (RuntimeException e) {
                    stats.failedBatches.incrementAndGet();
                    stats.lastSaveFailed = true;
                    stats.lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0];
                    log.error("Saving a batch of {} records failed; will retry from its first offsets in {} ms: {}",
                            batch.records(), backoffMs, stats.lastError);
                    batch.firstOffsets().forEach(consumer::seek);           // nothing is skipped
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException e) {
            // stop() asked us to end; the consumer is closed by try-with-resources
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (running) {
                log.error("Store consumer stopped unexpectedly: {}", e.toString());
            }
        }
    }

    private void updateLag(KafkaConsumer<String, byte[]> consumer, long now) {
        try {
            var assigned = consumer.assignment();
            if (assigned.isEmpty()) {
                return;
            }
            Map<TopicPartition, Long> end = consumer.endOffsets(assigned);
            long before = stats.totalLag();
            for (String topic : List.of(Topics.METRIC_SERIES, Topics.METRIC_POINTS)) {
                long lag = 0;
                for (TopicPartition tp : new ArrayList<>(assigned)) {
                    if (tp.topic().equals(topic)) {
                        lag += Math.max(0, end.getOrDefault(tp, 0L) - consumer.position(tp));
                    }
                }
                stats.lagByTopic.put(topic, lag);
            }
            long after = stats.totalLag();
            if (after > before && after > 0) {
                if (stats.lagGrowingSinceMs == 0) {
                    stats.lagGrowingSinceMs = now;
                }
            } else {
                stats.lagGrowingSinceMs = 0;
            }
        } catch (RuntimeException e) {
            log.debug("Lag check failed: {}", e.toString());
        }
    }
}
