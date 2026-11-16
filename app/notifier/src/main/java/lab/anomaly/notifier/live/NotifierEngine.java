package lab.anomaly.notifier.live;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.contracts.ContractJson;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.kit.settings.SharedSettings;
import lab.anomaly.notifier.NotifierSettings;
import lab.anomaly.notifier.board.Board;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
 * Reads {@code incident-events} into the hub (design N1): every partition from the beginning, no consumer group,
 * nothing committed, so a restart rebuilds the day from Kafka alone. Also watches the clock (midnight) and the
 * settings (time zone, day rule) and asks pages for a fresh snapshot when either changes.
 */
@Component
public class NotifierEngine {

    private static final Logger log = LoggerFactory.getLogger(NotifierEngine.class);

    private final SettingsHolder<NotifierSettings> settings;
    private final SettingsHolder<SharedSettings> shared;
    private final String bootstrap;
    private final Hub hub;
    public final AtomicLong setAside = new AtomicLong();
    private volatile boolean caughtUp;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile KafkaConsumer<String, byte[]> consumer;
    private long appliedNotifierVersion;
    private long appliedSharedVersion;

    public NotifierEngine(SettingsHolder<NotifierSettings> settings, SettingsHolder<SharedSettings> shared, Environment environment) {
        this.settings = settings;
        this.shared = shared;
        this.bootstrap = environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092");
        this.hub = new Hub(new Board(ZoneId.of(shared.get().timeZone()), settings.get().byReceived(), System.currentTimeMillis()),
                settings.get().maxQueuedUpdatesPerViewer());
        this.appliedNotifierVersion = settings.snapshot().version();
        this.appliedSharedVersion = shared.snapshot().version();
    }

    public Hub hub() {
        return hub;
    }

    /** True once the topic has been read up to where it ended at start: before that the list is incomplete. */
    public boolean caughtUp() {
        return caughtUp;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("notifier-reader").daemon().start(this::loop);
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
        hub.endAll();
    }

    private void loop() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "notifier");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        long nextCheck = 0;
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            this.consumer = c;
            List<TopicPartition> parts = c.partitionsFor(Topics.INCIDENT_EVENTS).stream()
                    .map(i -> new TopicPartition(i.topic(), i.partition())).toList();
            c.assign(parts);
            c.seekToBeginning(parts);
            Map<TopicPartition, Long> endAtStart = c.endOffsets(parts);
            while (running) {
                for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(200))) {
                    accept(r.value(), System.currentTimeMillis());
                }
                if (!caughtUp && parts.stream().allMatch(tp -> c.position(tp) >= endAtStart.get(tp))) {
                    caughtUp = true;
                    log.info("Caught up with {}: {} incidents of {} ({})", Topics.INCIDENT_EVENTS, hub.incidents(), hub.day(), hub.zone());
                }
                long now = System.currentTimeMillis();
                if (now >= nextCheck) {
                    checkDayAndSettings(now);
                    nextCheck = now + 1_000;
                }
            }
        } catch (WakeupException e) {
            // stop() asked us to end
        } catch (RuntimeException e) {
            if (running) {
                log.error("Notifier reader stopped unexpectedly", e);
            }
        }
    }

    void accept(byte[] value, long receivedMs) {
        IncidentEvent event;
        try {
            event = IncidentEvent.parseFrom(value);
        } catch (Exception e) {
            setAside.incrementAndGet();
            log.warn("Incident event set aside: not readable ({})", e.getMessage());
            return;
        }
        List<String> problems = ContractChecks.problems(event);
        if (!problems.isEmpty()) {
            setAside.incrementAndGet();
            log.warn("Incident event {} set aside: {}", event.getEventId(), problems);
            return;
        }
        hub.apply(event, ContractJson.toJson(event), receivedMs);       // JSON made once, shared by every page
    }

    /** Once a second: a new day, a new time zone or a new day rule means a fresh snapshot for every page. */
    private void checkDayAndSettings(long now) {
        var n = settings.snapshot();
        var s = shared.snapshot();
        hub.setCap(n.value().maxQueuedUpdatesPerViewer());
        if (n.version() != appliedNotifierVersion || s.version() != appliedSharedVersion) {
            appliedNotifierVersion = n.version();
            appliedSharedVersion = s.version();
            hub.reconfigure(ZoneId.of(s.value().timeZone()), n.value().byReceived(), now);
            log.info("Day {} in {} (day by {}): pages get a fresh snapshot", hub.day(), hub.zone(), n.value().dayBy());
            return;
        }
        if (hub.roll(now)) {
            log.info("New day {} in {}: pages get a fresh snapshot", hub.day(), hub.zone());
        }
    }
}
