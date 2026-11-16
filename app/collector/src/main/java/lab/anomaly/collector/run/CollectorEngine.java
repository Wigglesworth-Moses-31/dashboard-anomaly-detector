package lab.anomaly.collector.run;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.emit.Emitter;
import lab.anomaly.collector.emit.KafkaPublisher;
import lab.anomaly.collector.emit.MessageSink;
import lab.anomaly.collector.emit.RecordingSink;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import lab.anomaly.collector.source.datadog.DatadogSource;
import lab.anomaly.collector.source.recording.RecordingSource;
import lab.anomaly.collector.source.recording.ReplaySource;
import lab.anomaly.collector.translate.AnswerTranslator;
import lab.anomaly.collector.translate.Classifier;
import lab.anomaly.kit.settings.SettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runs the Collector (add-collector design K1): discovery on a schedule, a scheduler thread that hands due units
 * to virtual threads, and per poll: fetch → translate → classify → emit. In replay mode it plays a recording
 * through the same steps instead.
 */
@Component
public class CollectorEngine {

    private static final Logger log = LoggerFactory.getLogger(CollectorEngine.class);

    private final SettingsHolder<CollectorSettings> settings;
    private final DatadogCredentials credentials;
    private final Environment environment;
    private final Clock clock = Clock.systemUTC();

    private final Map<String, PollUnit> units = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Set<String> polledOnce = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastSuccessMs = new ConcurrentHashMap<>();
    private final AtomicLong polls = new AtomicLong();
    private final AtomicLong failedPolls = new AtomicLong();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private volatile boolean running;
    private volatile Thread schedulerThread;
    private volatile Fetcher fetcher;
    private volatile PollProcessor processor;
    private volatile KafkaPublisher publisher;
    private volatile Discovery discovery;
    private volatile Scheduler scheduler;
    private volatile long settingsVersion;
    private volatile long nextDiscoveryMs;
    private volatile String mode = "starting";

    public CollectorEngine(SettingsHolder<CollectorSettings> settings, DatadogCredentials credentials, Environment environment) {
        this.settings = settings;
        this.credentials = credentials;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        CollectorSettings s = settings.get();
        publisher = new KafkaPublisher(environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092"));
        running = true;
        if ("replay".equals(s.source())) {
            mode = "replay";
            Thread.ofVirtual().name("collector-replay").start(() -> replay(Path.of(s.replayDir())));
            return;
        }
        mode = "live";
        schedulerThread = Thread.ofPlatform().name("collector-scheduler").daemon().start(this::loop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (schedulerThread != null) {
            schedulerThread.interrupt();
        }
        workers.shutdownNow();
        if (publisher != null) {
            publisher.close();
        }
    }

    /** (Re)builds source, fetcher and processor from the current settings; keeps the emitter's memory. */
    private void configure(CollectorSettings s, Emitter keepEmitter) {
        MetricSource source = new DatadogSource(s.datadogSite(), credentials, clock);
        MessageSink sink = publisher;
        if (s.recording().enabled()) {
            source = new RecordingSource(source, Path.of(s.recording().dir()), clock);
            sink = new RecordingSink(publisher, Path.of(s.recording().dir()));
            log.info("Recording every answer and message to {}", Path.of(s.recording().dir()).toAbsolutePath());
        }
        Emitter emitter = keepEmitter != null ? keepEmitter
                : new Emitter(sink, (int) (s.backfill().toMillis() / s.step().toMillis()) + 6);
        // A kept emitter must also send to the new sink: found in the live run, where switching recording on
        // kept the old sink and published.jsonl stayed empty
        emitter.setSink(sink);
        fetcher = new Fetcher(source, clock, Thread::sleep, s.maxConcurrentRequests(), s.rateLimitShare(), s.retries(),
                new Backoff(s.retryBase(), s.retryCap(), ThreadLocalRandom.current()));
        processor = new PollProcessor(new AnswerTranslator("datadog", s.datadogSite()),
                new Classifier(source, s.metadataCacheSize(), s.metadataTtl().toMillis(), clock), emitter,
                () -> settings.get().classification());
        discovery = new Discovery(source);
        settingsVersion = settings.snapshot().version();
    }

    private void loop() {
        CollectorSettings s = settings.get();
        scheduler = new Scheduler(s.pollInterval().toMillis());
        configure(s, null);
        while (running) {
            try {
                long now = clock.millis();
                if (settings.snapshot().version() != settingsVersion) {
                    s = settings.get();
                    log.info("Settings changed (version {}): applying, and re-reading dashboards", settings.snapshot().version());
                    configure(s, processor.emitter());
                    synchronized (scheduler) {
                        scheduler.setInterval(s.pollInterval().toMillis());
                    }
                    nextDiscoveryMs = 0;
                }
                if (now >= nextDiscoveryMs) {
                    rediscover(s, now);
                    nextDiscoveryMs = now + s.dashboardRefresh().toMillis();
                }
                List<String> due;
                synchronized (scheduler) {
                    due = scheduler.takeDue(now);
                }
                for (String key : due) {
                    PollUnit unit = units.get(key);
                    if (unit != null) {
                        CollectorSettings current = s;
                        workers.submit(() -> poll(unit, current));
                    } else {
                        synchronized (scheduler) {
                            scheduler.finished(key);
                        }
                    }
                }
                long sleep;
                synchronized (scheduler) {
                    sleep = scheduler.millisUntilNext(clock.millis());
                }
                Thread.sleep(Math.max(10, Math.min(sleep, 1000)));
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.error("Collector loop error (continuing): {}", e.toString());
            }
        }
    }

    private void rediscover(CollectorSettings s, long now) {
        List<PollUnit> plan = discovery.plan(s.dashboards(), s.dashboardFilters(), s.step());
        Map<String, PollUnit> fresh = new java.util.HashMap<>();
        plan.forEach(u -> fresh.put(u.key(), u));
        units.keySet().retainAll(fresh.keySet());
        units.putAll(fresh);
        breakers.keySet().retainAll(fresh.keySet());
        polledOnce.retainAll(fresh.keySet());
        synchronized (scheduler) {
            scheduler.setUnits(List.copyOf(fresh.keySet()), now);
        }
        log.info("Poll plan: {} units for {} panels on {} dashboards", plan.size(),
                plan.stream().mapToInt(u -> u.views().size()).sum(), s.dashboards().size());
    }

    private void poll(PollUnit unit, CollectorSettings s) {
        CircuitBreaker breaker = breakers.computeIfAbsent(unit.key(), k -> new CircuitBreaker(s.breakerFailures(), s.breakerOpen().toMillis()));
        try {
            long now = clock.millis();
            if (!breaker.allow(now)) {
                return;
            }
            boolean first = !polledOnce.contains(unit.key());
            long stepMs = s.step().toMillis();
            long from = ((now - (first ? s.backfill() : s.recentWindow()).toMillis()) / stepMs) * stepMs;
            TimeseriesAnswer answer = fetcher.fetch(new TimeseriesRequest(unit.queries(), unit.formulas(), from, now, stepMs));
            processor.process(unit, answer, s.step());
            polledOnce.add(unit.key());
            breaker.onSuccess();
            lastSuccessMs.put(unit.key(), clock.millis());
            polls.incrementAndGet();
        } catch (SourceException e) {
            failedPolls.incrementAndGet();
            breaker.onFailure(clock.millis(), e.getMessage());
            log.warn("Poll failed for '{}' ({} in a row, breaker {}): {}", unit.label(), breaker.failuresInARow(), breaker.state(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            failedPolls.incrementAndGet();
            breaker.onFailure(clock.millis(), e.toString());
            log.error("Poll error for '{}': {}", unit.label(), e.toString());
        } finally {
            synchronized (scheduler) {
                scheduler.finished(unit.key());
            }
        }
    }

    /** Replay mode: dashboards and answers from a recording, through the same processing steps. */
    void replay(Path dir) {
        try {
            CollectorSettings s = settings.get();
            ReplaySource source = new ReplaySource(dir);
            ReplayClock replayClock = new ReplayClock();
            Emitter emitter = new Emitter(publisher, (int) (s.backfill().toMillis() / s.step().toMillis()) + 6);
            processor = new PollProcessor(new AnswerTranslator("datadog", s.datadogSite()),
                    new Classifier(source, s.metadataCacheSize(), s.metadataTtl().toMillis(), replayClock), emitter,
                    () -> settings.get().classification());
            Map<String, PollUnit> plan = new java.util.HashMap<>();
            new Discovery(source).plan(s.dashboards(), s.dashboardFilters(), s.step()).forEach(u -> plan.put(u.key(), u));
            units.putAll(plan);
            int played = 0;
            for (ReplaySource.Recorded recorded : source.timeseriesInOrder()) {
                PollUnit unit = plan.get(recorded.request().canonicalQuestion());
                if (unit == null || recorded.answer() == null) {
                    continue;
                }
                replayClock.set(recorded.atMs());
                processor.process(unit, recorded.answer(), s.step());
                played++;
                polls.incrementAndGet();
            }
            publisher.flush();
            log.info("Replay finished: {} recorded answers played from {}", played, dir.toAbsolutePath());
            mode = "replay (finished)";
        } catch (RuntimeException e) {
            log.error("Replay failed: {}", e.toString());
            mode = "replay (failed)";
        }
    }

    // ---- for the health page ---------------------------------------------------------------------------

    public String mode() {
        return mode;
    }

    public int unitCount() {
        return units.size();
    }

    public int lineCount() {
        return processor == null ? 0 : processor.emitter().lineCount();
    }

    public long polls() {
        return polls.get();
    }

    public long failedPolls() {
        return failedPolls.get();
    }

    public Long lastSuccessAgoMs() {
        return lastSuccessMs.values().stream().max(Long::compare).map(t -> clock.millis() - t).orElse(null);
    }

    public Map<String, String> openBreakers() {
        Map<String, String> open = new java.util.TreeMap<>();
        breakers.forEach((key, breaker) -> {
            if (breaker.state() != CircuitBreaker.State.CLOSED) {
                PollUnit unit = units.get(key);
                open.put(unit == null ? key : unit.label(), breaker.state() + ": " + breaker.lastError());
            }
        });
        return open;
    }

    public Fetcher fetcher() {
        return fetcher;
    }

    public KafkaPublisher publisher() {
        return publisher;
    }

    public long skippedTurns() {
        Scheduler s = scheduler;
        if (s == null) {
            return 0;
        }
        synchronized (s) {
            return s.skippedTurns();
        }
    }

    /** A clock that follows the recording's times during replay. */
    static final class ReplayClock extends Clock {
        private volatile long nowMs;

        void set(long ms) {
            nowMs = ms;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(nowMs);
        }
    }
}
