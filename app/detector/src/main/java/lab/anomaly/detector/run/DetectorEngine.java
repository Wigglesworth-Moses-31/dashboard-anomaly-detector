package lab.anomaly.detector.run;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.detector.Detector;
import lab.anomaly.detector.DetectorSettings;
import lab.anomaly.detector.incident.IncidentBook.Change;
import lab.anomaly.detector.incident.IncidentRepository;
import lab.anomaly.detector.incident.OutboxPublisher;
import lab.anomaly.detector.ops.DetectorStats;
import lab.anomaly.kit.settings.SettingsHolder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The Detector's loop (design D1): read descriptions and values from Kafka, judge them, save the incident changes
 * with their events in one transaction, then commit the Kafka offsets. One thread owns all detection state.
 *
 * <ul>
 *   <li>Descriptions ({@code metric-series}, compacted) are read from the beginning whenever partitions are
 *       assigned, so the Detector knows every line even if it starts after the Collector.</li>
 *   <li>Values ({@code metric-points}) start from the latest offset the first time: history comes from the Store.</li>
 *   <li>A failed save is retried until it works, before the offsets are committed: changes are never dropped.</li>
 * </ul>
 */
@Component
public class DetectorEngine {

    private static final Logger log = LoggerFactory.getLogger(DetectorEngine.class);

    private final SettingsHolder<DetectorSettings> settings;
    private final IncidentRepository repository;
    private final OutboxPublisher publisher;
    private final DetectorStats stats;
    private final String bootstrap;
    private final Detector detector = new Detector(() -> "inc-" + UUID.randomUUID());
    private volatile Detector.Snapshot snapshot;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile KafkaConsumer<String, byte[]> consumer;
    private long appliedVersion = -1;
    private StoreHistory store;
    private String storeAddress = "";

    public DetectorEngine(SettingsHolder<DetectorSettings> settings, IncidentRepository repository, OutboxPublisher publisher,
                          DetectorStats stats, Environment environment) {
        this.settings = settings;
        this.repository = repository;
        this.publisher = publisher;
        this.stats = stats;
        this.bootstrap = environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092");
    }

    public Detector.Snapshot snapshot() {
        return snapshot;
    }

    public long settingsVersion() {
        return appliedVersion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        applySettings();
        for (IncidentRepository.Restored r : repository.openIncidents()) {
            detector.restore(r.incident(), r.sequence());                  // design D7: continue open incidents
        }
        snapshot = detector.view();
        running = true;
        thread = Thread.ofPlatform().name("detector").daemon().start(this::loop);
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        KafkaConsumer<String, byte[]> c = consumer;
        if (c != null) {
            c.wakeup();
        }
        if (thread != null) {
            thread.join(10_000);
        }
        if (store != null) {
            store.close();
        }
    }

    private void applySettings() {
        var s = settings.snapshot();
        if (s.version() != appliedVersion) {
            detector.configure(s.value(), s.version());
            appliedVersion = s.version();
            if (!s.value().storeAddress().equals(storeAddress)) {
                if (store != null) {
                    store.close();
                }
                storeAddress = s.value().storeAddress();
                store = new StoreHistory(storeAddress);
            }
        }
    }

    private void loop() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "detector");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "detector");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        long nextLag = 0;
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            this.consumer = c;
            c.subscribe(List.of(Topics.METRIC_SERIES, Topics.METRIC_POINTS), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    List<TopicPartition> series = partitions.stream().filter(tp -> tp.topic().equals(Topics.METRIC_SERIES)).toList();
                    c.seekToBeginning(series);                               // every line's description, every time
                }
            });
            while (running) {
                ConsumerRecords<String, byte[]> records = c.poll(Duration.ofMillis(200));
                long readMs = System.currentTimeMillis();
                applySettings();
                List<Change> changes = new ArrayList<>(process(records, readMs));
                // Silence only when caught up: Kafka orders values per partition only, so while some partitions are
                // read ahead of others a line can look missing. At lag 0 every line of an answer has been read.
                if (!records.isEmpty() && caughtUp(c)) {
                    changes.addAll(detector.checkSilence(readMs));
                }
                save(changes);
                if (!records.isEmpty()) {
                    c.commitSync();
                }
                snapshot = detector.view();
                long now = System.currentTimeMillis();
                if (now >= nextLag) {
                    updateLag(c);
                    nextLag = now + 5_000;
                }
            }
        } catch (WakeupException e) {
            // stop() asked us to end
        } catch (RuntimeException e) {
            if (running) {
                stats.lastError = e.toString();
                log.error("Detector loop stopped unexpectedly", e);
            }
        }
    }

    /** Descriptions first, so values in the same batch find their line. */
    /** True when every assigned metric-points partition has been read to its end (no request: uses fetch metadata). */
    private static boolean caughtUp(KafkaConsumer<String, byte[]> c) {
        boolean any = false;
        for (TopicPartition tp : c.assignment()) {
            if (!tp.topic().equals(Topics.METRIC_POINTS)) {
                continue;
            }
            any = true;
            var lag = c.currentLag(tp);
            if (lag.isEmpty() || lag.getAsLong() > 0) {
                return false;
            }
        }
        return any;
    }

    private List<Change> process(ConsumerRecords<String, byte[]> records, long readMs) {
        for (ConsumerRecord<String, byte[]> r : records.records(Topics.METRIC_SERIES)) {
            try {
                SeriesDescriptor d = SeriesDescriptor.parseFrom(r.value());
                if (ContractChecks.problems(d).isEmpty()) {
                    detector.describe(d);
                } else {
                    stats.messagesSetAside.incrementAndGet();
                }
            } catch (Exception e) {
                stats.messagesSetAside.incrementAndGet();
            }
        }
        List<Change> changes = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> r : records.records(Topics.METRIC_POINTS)) {
            MetricPoints m;
            try {
                m = MetricPoints.parseFrom(r.value());
            } catch (Exception e) {
                stats.messagesSetAside.incrementAndGet();
                continue;
            }
            if (!ContractChecks.problems(m).isEmpty()) {
                stats.messagesSetAside.incrementAndGet();
                continue;
            }
            stats.recordReadDelay(Math.max(0, readMs - r.timestamp()));
            List<Point> pts = m.getPointsList().stream().sorted((a, b) -> Long.compare(a.getTsMs(), b.getTsMs())).toList();
            if (!pts.isEmpty() && detector.needsWarm(m.getSeriesId())) {
                warm(m.getSeriesId(), pts.get(0).getTsMs());
            }
            for (Point p : pts) {
                changes.addAll(detector.accept(m.getSeriesId(), p.getTsMs(), p.getValue(), p.getPartial(), readMs));
                stats.valuesJudged.incrementAndGet();
            }
        }
        return changes;
    }

    /** Warm start (design D3): the line's values from the Store for the history window before its first value. */
    private void warm(String seriesId, long firstTs) {
        stats.warmCalls.incrementAndGet();
        long from = firstTs - settings.get().history().toMillis();
        try {
            StoreHistory.Values v = store.history(seriesId, from, firstTs);
            detector.warm(seriesId, v.times(), v.values());
            log.debug("Warm start {}: first value {}, {} values from the Store", seriesId, java.time.Instant.ofEpochMilli(firstTs), v.times().length);
        } catch (RuntimeException e) {
            stats.warmFailures.incrementAndGet();
            detector.warm(seriesId, new long[0], new double[0]);
            log.debug("Warm start of {} failed: {}", seriesId, e.toString());
        }
    }

    /** Saves until it works: the in-memory state already moved on, so the changes must not be lost. */
    private void save(List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        long backoff = 1_000;
        while (true) {
            try {
                repository.save(changes);
                stats.eventsSaved.addAndGet(changes.size());
                publisher.wake();
                return;
            } catch (RuntimeException e) {
                stats.saveFailures.incrementAndGet();
                stats.lastError = "save: " + e;
                log.error("Saving {} incident changes failed; retrying in {} ms: {}", changes.size(), backoff, e.toString());
                if (!running) {
                    return;
                }
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

    private void updateLag(KafkaConsumer<String, byte[]> c) {
        try {
            var assigned = c.assignment();
            if (assigned.isEmpty()) {
                return;
            }
            Map<TopicPartition, Long> end = c.endOffsets(assigned);
            long points = 0;
            long series = 0;
            for (TopicPartition tp : assigned) {
                long lag = Math.max(0, end.getOrDefault(tp, 0L) - c.position(tp));
                if (tp.topic().equals(Topics.METRIC_POINTS)) {
                    points += lag;
                } else {
                    series += lag;
                }
            }
            stats.lagByTopic.put(Topics.METRIC_POINTS, points);
            stats.lagByTopic.put(Topics.METRIC_SERIES, series);
        } catch (RuntimeException e) {
            log.debug("Lag check failed: {}", e.toString());
        }
    }
}
