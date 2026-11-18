package lab.anomaly.notifier.dash;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live values only for the panels a page shows (add-dashboard-view D4). The Notifier reads {@code metric-points} from
 * the newest offset; each page's stream names its lines; per stream a map line → newest points (latest wins, so a slow
 * page never builds a backlog), flushed by one thread every second. Fan-out by an index line → streams, O(1) per point.
 */
@Component
public class LivePoints implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LivePoints.class);

    /** One page's stream. */
    public static final class Stream {
        final Set<String> lines;
        final SseEmitter emitter;
        final Map<String, List<Point>> pending = new LinkedHashMap<>();

        Stream(Set<String> lines, SseEmitter emitter) {
            this.lines = lines;
            this.emitter = emitter;
        }

        synchronized void offer(String line, List<Point> points) {
            pending.put(line, points);                                  // latest wins
        }

        synchronized Map<String, List<Point>> drain() {
            if (pending.isEmpty()) {
                return Map.of();
            }
            Map<String, List<Point>> out = new LinkedHashMap<>(pending);
            pending.clear();
            return out;
        }
    }

    private final Map<String, Set<Stream>> byLine = new ConcurrentHashMap<>();
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    private final AtomicLong pushed = new AtomicLong();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("dash-live").factory());
    private final String bootstrap;
    private volatile boolean running = true;
    private Thread reader;

    @Autowired
    LivePoints(Environment environment) {
        this(environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092"), true);
    }

    public LivePoints(String bootstrap, boolean readKafka) {
        this.bootstrap = bootstrap;
        flusher.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.SECONDS);
        if (readKafka) {
            reader = Thread.ofVirtual().name("dash-points").start(this::read);
        }
    }

    /** Opens a stream for these lines (at most 5,000). */
    public SseEmitter open(Set<String> lines) {
        SseEmitter emitter = new SseEmitter(0L);
        Stream s = new Stream(Set.copyOf(lines), emitter);
        streams.add(s);
        for (String l : s.lines) {
            byLine.computeIfAbsent(l, k -> ConcurrentHashMap.newKeySet()).add(s);
        }
        Runnable close = () -> remove(s);
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(e -> close.run());
        try {
            emitter.send(SseEmitter.event().comment("open"));
        } catch (IOException e) {
            remove(s);
        }
        return emitter;
    }

    private void remove(Stream s) {
        if (streams.remove(s)) {
            for (String l : s.lines) {
                byLine.computeIfPresent(l, (k, set) -> {
                    set.remove(s);
                    return set.isEmpty() ? null : set;
                });
            }
        }
    }

    /** A Collector message: offered to every stream that shows the line. */
    public void accept(MetricPoints m) {
        Set<Stream> to = byLine.get(m.getSeriesId());
        if (to != null && m.getPointsCount() > 0) {
            for (Stream s : to) {
                s.offer(m.getSeriesId(), m.getPointsList());
            }
        }
    }

    /** Sends each stream its newest points as one event: {"line": [[t, v], ...], ...}. */
    void flush() {
        for (Stream s : streams) {
            Map<String, List<Point>> batch = s.drain();
            if (batch.isEmpty()) {
                continue;
            }
            StringBuilder json = new StringBuilder("{");
            batch.forEach((line, points) -> {
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append('"').append(line).append("\":[");
                for (int i = 0; i < points.size(); i++) {
                    Point p = points.get(i);
                    json.append(i == 0 ? "" : ",").append('[').append(p.getTsMs()).append(',').append(num(p.getValue())).append(']');
                }
                json.append(']');
                pushed.addAndGet(points.size());
            });
            json.append('}');
            try {
                s.emitter.send(SseEmitter.event().name("points").data(json.toString()));
            } catch (IOException | IllegalStateException e) {
                remove(s);
            }
        }
    }

    private static String num(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "null";
    }

    private void read() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "notifier-dash");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        while (running) {
            try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
                List<TopicPartition> parts = new ArrayList<>();
                c.partitionsFor(Topics.METRIC_POINTS).forEach(i -> parts.add(new TopicPartition(i.topic(), i.partition())));
                c.assign(parts);
                c.seekToEnd(parts);                                        // live only: history comes from the Store
                while (running) {
                    for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(500))) {
                        try {
                            accept(MetricPoints.parseFrom(r.value()));
                        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                            // set aside: a broken message never reaches a page
                        }
                    }
                }
            } catch (RuntimeException e) {
                if (running) {
                    log.warn("Live points: Kafka not readable ({}); trying again in 5 s", e.toString());
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }

    public int streams() {
        return streams.size();
    }

    public int linesShown() {
        return byLine.size();
    }

    public long pushed() {
        return pushed.get();
    }

    @Override
    public void destroy() {
        running = false;
        flusher.shutdownNow();
        streams.forEach(s -> s.emitter.complete());
    }
}
