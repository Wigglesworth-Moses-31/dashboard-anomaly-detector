package lab.anomaly.collector.run;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.CollectorSettings.LaneConfig;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runs the Collector over one or more sources (add-collector design K1, add-multi-site design M1-M10).
 *
 * <pre>
 *  one scheduler thread: min-heap of every unit of every lane ──► due unit ──► a virtual thread in its lane
 *  each lane: own keys · own site · own Fetcher (token bucket, 429 gate, bulkhead) · own metadata · own discovery
 *  shared: one Emitter (remembers sent steps by line id) ──► one Kafka producer (batches every lane's messages)
 * </pre>
 *
 * The scheduler thread never waits on a source: tokens, the 429 gate, bulkhead slots, slow answers and dashboard
 * reads all wait inside the lane's own virtual threads (design M2, M4).
 */
@Component
public class CollectorEngine {

    private static final Logger log = LoggerFactory.getLogger(CollectorEngine.class);

    /** Builds a lane's connection to its site; tests give stub sources. */
    public interface SourceFactory {
        MetricSource create(LaneConfig config, DatadogCredentials credentials, Clock clock);
    }

    /** Reads a lane's two keys by their settings names (design M6). */
    public interface KeyLookup {
        DatadogCredentials keys(LaneConfig config);
    }

    /** What the lanes share and whose change rebuilds every lane (design M7). */
    private record Shared(int retries, java.time.Duration retryBase, java.time.Duration retryCap, int metadataCacheSize,
                          java.time.Duration metadataTtl, CollectorSettings.Recording recording, java.time.Duration step,
                          java.time.Duration settle) {
        static Shared of(CollectorSettings s) {
            return new Shared(s.retries(), s.retryBase(), s.retryCap(), s.metadataCacheSize(), s.metadataTtl(), s.recording(), s.step(),
                    s.effectiveSettleDelay());
        }
    }

    /** A lane's new plan, made on the lane's own thread, applied on the scheduler thread (design M4). */
    private record PlanReady(Lane lane, List<PollUnit> units) {}

    private final ViewCatalog catalog;
    private final Supplier<CollectorSettings> settings;
    private final LongSupplier settingsVersion;
    private final KeyLookup keys;
    private final SourceFactory sources;
    private final Environment environment;
    private final Clock clock;

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();            // by source name: O(1) per poll
    private final Map<String, PollUnit> units = new ConcurrentHashMap<>();        // by scheduling id (lane|key)
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Set<String> polledOnce = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastSuccessMs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PlanReady> plansReady = new ConcurrentLinkedQueue<>();
    private final AtomicLong polls = new AtomicLong();
    private final AtomicLong failedPolls = new AtomicLong();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private volatile boolean running;
    private volatile Thread schedulerThread;
    private volatile MessageSink sink;
    private volatile KafkaPublisher publisher;
    private volatile Emitter emitter;
    private volatile Scheduler scheduler;
    private volatile long appliedVersion = Long.MIN_VALUE;
    private volatile Shared shared;
    private volatile String mode = "starting";

    @Autowired
    public CollectorEngine(SettingsHolder<CollectorSettings> settings, Environment environment,
                           lab.anomaly.collector.dashboards.DashboardFiles files) {
        this(settings::get, () -> settings.snapshot().version(),
                config -> DatadogCredentials.from(lab.anomaly.collector.keys.EnvFile.fromEnvironment(environment), environment,
                        config.apiKeyEnv(), config.appKeyEnv(), config.name()),
                (config, credentials, clock) -> new DatadogSource(config.site(), credentials, clock),
                environment, Clock.systemUTC(), (lane, s) -> files.views(s, lane.name()));
        lab.anomaly.collector.backfill.StoreViews store = new lab.anomaly.collector.backfill.StoreViews(
                () -> environment.getProperty("anomaly.collector.store-address", "localhost:9102"));
        this.collected = store::views;                                  // add-backfill B2
    }

    /** For tests: stub sources, keys and clock; dashboards read by id from the stub source. */
    public CollectorEngine(Supplier<CollectorSettings> settings, LongSupplier settingsVersion, KeyLookup keys,
                           SourceFactory sources, Environment environment, Clock clock) {
        this(settings, settingsVersion, keys, sources, environment, clock, CollectorEngine::viewsById);
    }

    /** Dashboards read by id from the lane's source (tests, and settings of the older id form). */
    static List<lab.anomaly.collector.plan.WatchedView> viewsById(Lane lane, CollectorSettings s) {
        LaneConfig c = lane.config();
        return lane.discovery().views(c.dashboards().stream().map(r -> r.idOnly() ? r.id() : r.file()).toList(),
                c.dashboardFilters());
    }

    public CollectorEngine(Supplier<CollectorSettings> settings, LongSupplier settingsVersion, KeyLookup keys,
                           SourceFactory sources, Environment environment, Clock clock, ViewCatalog catalog) {
        this.catalog = catalog;
        this.settings = settings;
        this.settingsVersion = settingsVersion;
        this.keys = keys;
        this.sources = sources;
        this.environment = environment;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        CollectorSettings s = settings.get();
        publisher = new KafkaPublisher(environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092"));
        if ("replay".equals(s.source())) {
            running = true;
            mode = "replay";
            Thread.ofVirtual().name("collector-replay").start(() -> replay(Path.of(s.replayDir())));
            return;
        }
        startLive(publisher);
    }

    /** Starts the scheduler thread, sending every message to {@code target}. */
    public void startLive(MessageSink target) {
        this.sink = target;
        running = true;
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

    // ---- the scheduler thread ----------------------------------------------------------------------------------

    private void loop() {
        CollectorSettings s = settings.get();
        scheduler = new Scheduler(s.pollInterval().toMillis());
        while (running) {
            try {
                long version = settingsVersion.getAsLong() + keysVersion.get();   // a commit, or keys written to .env
                if (version != appliedVersion) {
                    s = settings.get();
                    reconcile(s);
                    appliedVersion = version;
                }
                long now = clock.millis();
                for (Lane lane : lanes.values()) {
                    if (lane.ready() && now >= lane.nextDiscoveryMs && lane.discovering.compareAndSet(false, true)) {
                        CollectorSettings current = s;
                        workers.submit(() -> discover(lane, current));
                    }
                }
                PlanReady ready;
                while ((ready = plansReady.poll()) != null) {
                    apply(ready, now);
                }
                for (Lane lane : lanes.values()) {                         // add-backfill: decisions waiting for the Store
                    Long at = backfillRecheckMs.get(lane.name());
                    if (at != null && now >= at && !backfillRunning.contains(lane.name())) {
                        backfillRecheckMs.remove(lane.name());
                        planBackfill(lane, now);
                    }
                }
                if (now >= nextGuardMs) {
                    lanes.values().forEach(l -> guard(l, settings.get()));     // budget guard (J13), every 5 s
                    nextGuardMs = now + 5_000;
                }
                int batch = s.effectiveBatchFormulas();
                long lookahead = batch > 1 ? Math.min(2_000, s.pollInterval().toMillis() / 4) : 0;
                List<String> due;
                synchronized (scheduler) {
                    due = scheduler.takeDue(now, lookahead);
                }
                dispatch(due, s, batch);
                long sleep;
                synchronized (scheduler) {
                    sleep = scheduler.millisUntilNext(clock.millis());
                }
                Thread.sleep(Math.max(5, Math.min(sleep, 200)));
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.error("Collector loop error (continuing): {}", e.toString());
            }
        }
    }

    /**
     * Settings changed: keep, update or rebuild each lane by name (design M7, a reconciler). O(sources). A lane whose
     * connection is unchanged keeps its token bucket, gate, breakers, metadata cache and units.
     */
    /** Keys were written to .env (add-settings-page S4): the scheduler re-reads them and rebuilds only changed lanes. */
    public void keysChanged() {
        keysVersion.incrementAndGet();
    }

    private final java.util.concurrent.atomic.AtomicLong keysVersion = new java.util.concurrent.atomic.AtomicLong();

    private void reconcile(CollectorSettings s) {
        Shared newShared = Shared.of(s);
        boolean rebuildAll = !newShared.equals(shared);
        if (emitter == null) {
            emitter = new Emitter(sink, (int) (s.backfill().toMillis() / s.step().toMillis()) + 6);
        }
        MessageSink target = s.recording().enabled() ? new RecordingSink(sink, Path.of(s.recording().dir())) : sink;
        emitter.setSink(target);
        shared = newShared;
        synchronized (scheduler) {
            scheduler.setInterval(s.pollInterval().toMillis());
        }

        Map<String, LaneConfig> wanted = new LinkedHashMap<>();
        s.lanes().forEach(c -> wanted.put(c.name(), c));
        for (String name : new ArrayList<>(lanes.keySet())) {
            if (!wanted.containsKey(name)) {
                drop(lanes.remove(name));
                log.info("Source '{}' removed", name);
            }
        }
        for (LaneConfig config : wanted.values()) {
            Lane old = lanes.get(config.name());
            DatadogCredentials current = keys.keys(config);                   // .env read on every reload (add-settings-page S4)
            if (old != null && !old.sameKeys(current)) {
                drop(old);                                                    // new keys: this lane alone starts again
                lanes.remove(config.name());
                log.info("Source '{}': keys changed, starting it again", config.name());
                old = null;
            }
            if (old != null && !rebuildAll && old.config().equals(config)) {
                // Untouched connection: keeps its bucket, gate, breakers and timing. Its dashboard files may still have
                // changed in this commit, so it re-plans (no network: the files were loaded with the settings, J3);
                // units that stay the same keep their due times.
                old.nextDiscoveryMs = 0;
                continue;
            }
            if (old != null && !rebuildAll && old.config().sameConnection(config)) {
                old.updateWatched(config);                                   // dashboards or filters: re-read only
                log.info("Source '{}': dashboards changed, re-reading them", config.name());
                continue;
            }
            if (old != null) {
                drop(old);
            }
            Lane lane = build(config, s, current);
            lanes.put(config.name(), lane);
            log.info("Source '{}' ({}): {}", config.name(), config.site(),
                    lane.ready() ? "ready" : "keys missing: " + lane.keysMissing() + "; nothing will be sent");
        }
    }

    private Lane build(LaneConfig config, CollectorSettings s) {
        return build(config, s, keys.keys(config));
    }

    private Lane build(LaneConfig config, CollectorSettings s, DatadogCredentials credentials) {
        MetricSource source = sources.create(config, credentials, clock);
        if (s.recording().enabled()) {
            source = new RecordingSource(source, recordingDir(Path.of(s.recording().dir()), config.name()), clock);
        }
        Fetcher fetcher = new Fetcher(source, clock, Thread::sleep, config.maxConcurrentRequests(), config.rateLimitShare(),
                s.retries(), new Backoff(s.retryBase(), s.retryCap(), ThreadLocalRandom.current()));
        PollProcessor processor = new PollProcessor(new AnswerTranslator("datadog", config.site(), s.effectiveSettleDelay().toMillis()),
                new Classifier(source, s.metadataCacheSize(), s.metadataTtl().toMillis(), clock), emitter,
                () -> settings.get().classification());
        return new Lane(config, credentials, source, fetcher, processor, new Discovery(source, config.name()));
    }

    /** Removes a lane's units from the plan; their scheduler entries go with the next {@link #syncScheduler}. */
    private void drop(Lane lane) {
        if (lane == null) {
            return;
        }
        for (String id : lane.unitIds) {
            units.remove(id);
            breakers.remove(id);
            polledOnce.remove(id);
            lastSuccessMs.remove(id);
        }
        lane.unitIds.clear();
        syncScheduler(clock.millis());
    }

    /** On the lane's own virtual thread: a slow or unreachable site delays only this lane (design M4). */
    private void discover(Lane lane, CollectorSettings s) {
        try {
            LaneConfig config = lane.config();
            List<PollUnit> plan = lab.anomaly.collector.plan.PollPlan.buildViews(lane.name(), catalog.views(lane, s), s.step());
            plansReady.add(new PlanReady(lane, plan));
        } catch (RuntimeException e) {
            log.error("[{}] Discovery failed: {}", lane.name(), e.toString());
        } finally {
            long done = clock.millis();
            lane.lastDiscoveryMs = done;
            // A dashboard never read yet is retried within a minute, not after the full refresh (add-multi-site 7.3)
            long wait = lane.discovery().unreadable().isEmpty() ? s.dashboardRefresh().toMillis()
                    : Math.min(s.dashboardRefresh().toMillis(), UNREADABLE_RETRY_MS);
            lane.nextDiscoveryMs = done + wait;
            lane.discovering.set(false);
        }
    }

    /** Replaces only this lane's units; other lanes keep their timing. O(u log n) for the lane's u units. */
    private void apply(PlanReady ready, long now) {
        Lane lane = ready.lane();
        if (lanes.get(lane.name()) != lane) {
            return;                                                          // lane was rebuilt or removed meanwhile
        }
        Set<String> fresh = new HashSet<>();
        for (PollUnit unit : ready.units()) {
            fresh.add(unit.id());
            units.put(unit.id(), unit);
        }
        for (String id : lane.unitIds) {
            if (!fresh.contains(id)) {
                units.remove(id);
                breakers.remove(id);
                polledOnce.remove(id);
                lastSuccessMs.remove(id);
            }
        }
        lane.unitIds.retainAll(fresh);
        lane.unitIds.addAll(fresh);
        held.retainAll(units.keySet());
        planBackfill(lane, now);                                           // add-backfill: hold units of new views
        syncScheduler(now);
        CollectorSettings s = settings.get();
        lane.effectiveIntervalMs = 0;                        // recompute for the new units
        guard(lane, s);
        double budget = lane.budgetNow();
        log.info("[{}] Poll plan: {} units for {} panels on {} dashboards; budget {}%", lane.name(), ready.units().size(),
                ready.units().stream().mapToInt(u -> u.views().size()).sum(), lane.config().dashboards().size(),
                Math.round(budget * 100));
        if (budget > BUDGET_WARNING) {
            log.warn("[{}] The plan uses {}% of the rate this source allows: fewer dashboards or a longer poll-interval",
                    lane.name(), Math.round(budget * 100));
        }
    }

    /** How soon a source re-reads dashboards it could never read. */
    static final long UNREADABLE_RETRY_MS = 60_000;

    /** Above this share of a source's allowed rate, health warns (design M8; retries are not planned). */
    public static final double BUDGET_WARNING = 0.9;

    /** Kept units keep their due times (Scheduler.setUnits), so one lane's change never resets another's timing. */
    // ---- add-backfill: a never-collected view gets its last days first (design B2, B3) -----------------------------

    /** Which views the Store already has lines for; null = backfill off (tests, replay). */
    private volatile lab.anomaly.collector.backfill.StoreViews.Collected collected;
    private volatile Duration backfillSpan;                    // tests; else the setting backfill-new-views (default 1d)
    private volatile Duration backfillWindow;                  // tests; else the setting backfill-window (default 4h)
    private volatile long backfillPauseMs = 500;
    /** Units waiting for their view's backfill: not scheduled for live polling until it is done. */
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    /** Views backfilled (or found collected) in this run: "site|view id". */
    private final Set<String> backfilled = ConcurrentHashMap.newKeySet();
    /** Progress per view being backfilled: "site|view id" → {windows done, windows, view title}. */
    private final Map<String, Object[]> backfillProgress = new ConcurrentHashMap<>();
    private final Map<String, Long> backfillRecheckMs = new ConcurrentHashMap<>();   // lane → when to ask the Store again
    private final Map<String, Long> storeWaitSinceMs = new ConcurrentHashMap<>();
    private final Set<String> backfillRunning = ConcurrentHashMap.newKeySet();       // lanes with a job running

    public void setCollectedViews(lab.anomaly.collector.backfill.StoreViews.Collected collected, Duration span, Duration window, long pauseMs) {
        this.collected = collected;
        this.backfillSpan = span;
        this.backfillWindow = window;
        this.backfillPauseMs = pauseMs;
    }

    Duration backfillSpan() {
        if (backfillSpan != null) {
            return backfillSpan;
        }
        Duration d = environment == null ? Duration.ofDays(1)
                : environment.getProperty("anomaly.collector.backfill-new-views", Duration.class, Duration.ofDays(1));
        return d.compareTo(Duration.ofDays(10)) > 0 ? Duration.ofDays(10) : d;      // never more than is kept
    }

    Duration backfillWindow() {
        if (backfillWindow != null) {
            return backfillWindow;
        }
        Duration d = environment == null ? Duration.ofHours(4)
                : environment.getProperty("anomaly.collector.backfill-window", Duration.class, Duration.ofHours(4));
        return d.isNegative() || d.isZero() ? Duration.ofHours(4) : d;
    }

    /** View id → the views of a unit, keyed "site|view id". */
    private static Set<String> viewKeys(Lane lane, PollUnit unit) {
        Set<String> out = new HashSet<>();
        unit.views().forEach(v -> out.add(lab.anomaly.collector.backfill.StoreViews.key(lane.config().site(), v.dashboardId())));
        return out;
    }

    /**
     * Decides which of a lane's units belong only to never-collected views, holds them and starts the lane's backfill.
     * When the Store cannot be asked yet, every unit not yet polled is held and the decision is retried in 10 s (at
     * most 2 minutes, then live polling starts without a backfill). O(units).
     */
    private void planBackfill(Lane lane, long now) {
        if (collected == null || backfillSpan().isZero() || lanes.get(lane.name()) != lane) {
            return;
        }
        java.util.Optional<Set<String>> have = collected.views();
        if (have.isEmpty()) {
            long since = storeWaitSinceMs.computeIfAbsent(lane.name(), k -> now);
            if (now - since < 120_000) {
                for (String id : lane.unitIds) {
                    if (!polledOnce.contains(id)) {
                        held.add(id);
                    }
                }
                backfillRecheckMs.put(lane.name(), now + 10_000);
                syncScheduler(now);
                return;
            }
            log.warn("[{}] The Store cannot be asked which dashboards are new: live polling starts without a backfill", lane.name());
            storeWaitSinceMs.remove(lane.name());
            backfillRecheckMs.remove(lane.name());
            lane.unitIds.forEach(held::remove);
            syncScheduler(now);
            return;
        }
        storeWaitSinceMs.remove(lane.name());
        backfillRecheckMs.remove(lane.name());
        List<PollUnit> toFill = new ArrayList<>();
        Map<String, String> newViews = new LinkedHashMap<>();
        for (String id : lane.unitIds) {
            PollUnit unit = units.get(id);
            if (unit == null || polledOnce.contains(id)) {
                held.remove(id);
                continue;
            }
            Set<String> keys = viewKeys(lane, unit);
            boolean allNew = keys.stream().allMatch(k -> !have.get().contains(k) && !backfilled.contains(k));
            if (allNew) {
                held.add(id);
                toFill.add(unit);
                unit.views().forEach(v -> newViews.putIfAbsent(lab.anomaly.collector.backfill.StoreViews.key(lane.config().site(), v.dashboardId()),
                        v.dashboardTitle()));
            } else {
                held.remove(id);
            }
        }
        syncScheduler(now);
        if (toFill.isEmpty() || !backfillRunning.add(lane.name())) {
            return;                                     // nothing new, or this lane's job is running (it re-plans at its end)
        }
        long windows = Math.max(1, (backfillSpan().toMillis() + backfillWindow().toMillis() - 1) / backfillWindow().toMillis());
        newViews.forEach((k, title) -> backfillProgress.put(k, new Object[] {0L, windows, title}));
        log.info("[{}] Backfill: {} new views ({}), {} questions, last {} in {} windows", lane.name(), newViews.size(),
                String.join(", ", newViews.values()), toFill.size(), backfillSpan(), windows);
        List<PollUnit> fill = List.copyOf(toFill);
        Set<String> views = Set.copyOf(newViews.keySet());
        workers.submit(() -> runBackfill(lane, fill, views));
    }

    /** One lane's backfill: windows oldest first, batched like polls, through the lane's fetcher (rate gate, 429 wait). */
    private void runBackfill(Lane lane, List<PollUnit> fill, Set<String> views) {
        CollectorSettings s = settings.get();
        long stepMs = s.step().toMillis();
        long now = clock.millis();
        long to = (now / stepMs) * stepMs;
        long from = ((to - backfillSpan().toMillis()) / stepMs) * stepMs;
        long window = Math.max(stepMs, (backfillWindow().toMillis() / stepMs) * stepMs);
        int lines = 0;
        try {
            for (long w = from; w < to; w += window) {
                if (lanes.get(lane.name()) != lane || !running) {
                    log.info("[{}] Backfill stopped: the source was changed", lane.name());
                    return;
                }
                long wEnd = Math.min(to, w + window);
                for (Batch b : Batch.pack(fill, s.effectiveBatchFormulas())) {
                    lines += backfillAsk(lane, b, s, w, wEnd);
                    Thread.sleep(backfillPauseMs);                         // leaves room for live polls
                }
                views.forEach(v -> backfillProgress.computeIfPresent(v, (k, p) -> new Object[] {(Long) p[0] + 1, p[1], p[2]}));
            }
            backfilled.addAll(views);
            log.info("[{}] Backfill done: {} views, {} line-windows sent", lane.name(), views.size(), lines);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            views.forEach(backfillProgress::remove);
            fill.forEach(u -> held.remove(u.id()));
            backfillRunning.remove(lane.name());
            backfillRecheckMs.put(lane.name(), clock.millis());          // anything added meanwhile is planned next
            syncScheduler(clock.millis());
        }
    }

    private int backfillAsk(Lane lane, Batch batch, CollectorSettings s, long from, long to) throws InterruptedException {
        try {
            TimeseriesAnswer answer = lane.fetcher().fetch(batch.request(from, to, s.step().toMillis()));
            List<TimeseriesAnswer> answers = batch.split(answer);
            int lines = 0;
            for (int k = 0; k < batch.size(); k++) {
                try {
                    lines += lane.processor().backfill(batch.units().get(k), answers.get(k), s.step());
                } catch (RuntimeException e) {
                    log.debug("[{}] Backfill of {} skipped a window: {}", lane.name(), batch.units().get(k).label(), e.toString());
                }
            }
            lane.calls.incrementAndGet();
            return lines;
        } catch (SourceException e) {
            lane.calls.incrementAndGet();
            if (e.kind() == SourceException.Kind.BAD_REQUEST && batch.size() > 1) {
                int lines = 0;
                for (Batch half : batch.halves()) {
                    lines += backfillAsk(lane, half, s, from, to);
                }
                return lines;
            }
            log.warn("[{}] Backfill window not answered ({}): it stays empty", lane.name(), e.getMessage());
            return 0;
        }
    }

    /** Views being backfilled: title → "n%" (health, Settings tab). */
    public Map<String, String> backfills() {
        Map<String, String> out = new LinkedHashMap<>();
        backfillProgress.forEach((k, p) -> out.put(p[2] + " (" + k.substring(0, k.indexOf('|')) + ")",
                Math.round(100.0 * (Long) p[0] / Math.max(1L, (Long) p[1])) + "%"));
        return out;
    }

    Set<String> held() {
        return held;
    }

    private void syncScheduler(long now) {
        Scheduler s = scheduler;
        if (s == null) {
            return;
        }
        synchronized (s) {
            s.setUnits(units.keySet().stream().filter(id -> !held.contains(id)).toList(), now);   // held: backfilling first
        }
    }

    private volatile long nextGuardMs;

    /**
     * Groups due units by lane, keeps first polls (which fetch the backfill) apart from regular ones because a batch
     * shares one time window, packs each group into batches of at most {@code batch} formulas (design J14), and polls
     * every batch on a virtual thread of its lane. O(due).
     */
    private void dispatch(List<String> due, CollectorSettings s, int batch) {
        Map<Lane, List<PollUnit>> regular = new LinkedHashMap<>();
        Map<Lane, List<PollUnit>> first = new LinkedHashMap<>();
        for (String id : due) {
            PollUnit unit = units.get(id);
            Lane lane = unit == null ? null : lanes.get(unit.lane());
            if (unit != null && lane != null && lane.ready()) {
                (polledOnce.contains(id) ? regular : first).computeIfAbsent(lane, l -> new ArrayList<>()).add(unit);
            } else {
                synchronized (scheduler) {
                    scheduler.finished(id);
                }
            }
        }
        submitAll(first, s, batch, true);
        submitAll(regular, s, batch, false);
    }

    private void submitAll(Map<Lane, List<PollUnit>> byLane, CollectorSettings s, int batch, boolean first) {
        byLane.forEach((lane, list) -> {
            for (Batch b : Batch.pack(list, batch)) {
                workers.submit(() -> pollBatch(lane, b, s, first));
            }
        });
    }

    // ---- one batch of polls, on a virtual thread of its lane ------------------------------------------------------

    /** Polls a batch; every unit is marked finished exactly once at the end, whatever happened. */
    private void pollBatch(Lane lane, Batch batch, CollectorSettings s, boolean first) {
        try {
            List<PollUnit> allowed = new ArrayList<>();
            long now = clock.millis();
            for (PollUnit unit : batch.units()) {
                if (breaker(unit, s).allow(now)) {
                    allowed.add(unit);
                }
            }
            if (!allowed.isEmpty()) {
                long stepMs = s.step().toMillis();
                long from = ((now - (first ? s.backfill() : s.recentWindow()).toMillis()) / stepMs) * stepMs;
                ask(lane, allowed.size() == batch.size() ? batch : Batch.of(allowed), s, from, now);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (scheduler) {
                batch.units().forEach(u -> scheduler.finished(u.id()));
            }
        }
    }

    /**
     * Asks Datadog once for the whole batch. When Datadog refuses the request as a bad question, one panel is wrong
     * and the whole batch was refused: the batch is split in two and each half asked again (bisection), so the bad
     * panel is found in O(log n) extra calls and every other panel still gets its numbers.
     */
    private void ask(Lane lane, Batch batch, CollectorSettings s, long from, long to) throws InterruptedException {
        try {
            TimeseriesAnswer answer = lane.fetcher().fetch(batch.request(from, to, s.step().toMillis()));
            List<TimeseriesAnswer> answers = batch.split(answer);
            long done = clock.millis();
            for (int k = 0; k < batch.size(); k++) {
                PollUnit unit = batch.units().get(k);
                try {
                    int lines = lane.processor().process(unit, answers.get(k), s.step());
                    lastLines.put(unit.id(), lines);
                    polledOnce.add(unit.id());
                    breaker(unit, s).onSuccess();
                    lastSuccessMs.put(unit.id(), done);
                    lane.polls.incrementAndGet();
                    polls.incrementAndGet();
                } catch (RuntimeException e) {
                    failed(lane, breaker(unit, s), unit, e.toString());
                }
            }
            lane.lastSuccessMs = done;
            lane.calls.incrementAndGet();
        } catch (SourceException e) {
            lane.calls.incrementAndGet();
            if (e.kind() == SourceException.Kind.BAD_REQUEST && batch.size() > 1) {
                for (Batch half : batch.halves()) {
                    ask(lane, half, s, from, to);
                }
                return;
            }
            batch.units().forEach(u -> failed(lane, breaker(u, s), u, e.getMessage()));
        }
    }

    /**
     * Calls one round of a lane needs: one per unit without batching; with batching, enough batches for all formulas,
     * and at least one per lookahead slot the units are spread over (they are not all due at once). O(1).
     */
    static int callsPerRound(int units, int formulas, int batch, long intervalMs) {
        if (batch <= 1 || units == 0) {
            return units;
        }
        long lookahead = Math.min(2_000, intervalMs / 4);
        long slots = lookahead <= 0 ? units : (intervalMs + lookahead - 1) / lookahead;
        return (int) Math.max((formulas + batch - 1) / batch, Math.min(units, slots));
    }

    /** The interval that keeps a lane at 90% of what its account allows, never shorter than the configured one. */
    static long guardedInterval(int calls, long baseMs, double allowedPerSecond) {
        if (allowedPerSecond <= 0 || calls == 0) {
            return baseMs;
        }
        return Math.max(baseMs, (long) Math.ceil(calls / (BUDGET_WARNING * allowedPerSecond) * 1000));
    }

    private CircuitBreaker breaker(PollUnit unit, CollectorSettings s) {
        return breakers.computeIfAbsent(unit.id(), k -> new CircuitBreaker(s.breakerFailures(), s.breakerOpen().toMillis()));
    }

    /** Lines in each unit's latest answer: panels that return nothing are reported on health (design J5). */
    private final Map<String, Integer> lastLines = new ConcurrentHashMap<>();

    public Map<String, Integer> lastLines() {
        return lastLines;
    }

    /**
     * Budget guard (design J13): the calls one round of this lane needs (batches, or units without batching), against
     * what its account allows; when over 90%, the lane's units get a longer interval so the lane slows itself instead
     * of collecting 429s. O(units of the lane) when the interval changes, O(1) otherwise.
     */
    void guard(Lane lane, CollectorSettings s) {
        long base = s.pollInterval().toMillis();
        int batch = s.effectiveBatchFormulas();
        int unitCount = lane.unitIds.size();
        int formulas = 0;
        for (String id : lane.unitIds) {
            PollUnit u = units.get(id);
            formulas += u == null ? 0 : u.formulas().size();
        }
        int calls = callsPerRound(unitCount, formulas, batch, base);
        double allowed = lane.fetcher().allowedPerSecond();
        long every = guardedInterval(calls, base, allowed);
        lane.plannedCalls = calls;
        if (every != lane.effectiveIntervalMs) {
            lane.effectiveIntervalMs = every;
            synchronized (scheduler) {
                scheduler.setInterval(lane.unitIds, every);
            }
            if (every > base) {
                log.warn("[{}] {} calls per round would exceed {}% of the allowed {}/s: polling every {} s instead of {} s",
                        lane.name(), calls, Math.round(BUDGET_WARNING * 100), allowed, every / 1000.0, base / 1000.0);
            }
        }
    }

    private void failed(Lane lane, CircuitBreaker breaker, PollUnit unit, String error) {
        failedPolls.incrementAndGet();
        lane.failedPolls.incrementAndGet();
        breaker.onFailure(clock.millis(), error);
        log.warn("[{}] Poll failed for '{}' ({} in a row, breaker {}): {}", lane.name(), unit.label(),
                breaker.failuresInARow(), breaker.state(), error);
    }

    // ---- replay (design M10) ----------------------------------------------------------------------------------

    /** A source's recording folder: the run folder itself for the single-site source, else a sub-folder per source. */
    static Path recordingDir(Path run, String source) {
        return CollectorSettings.DEFAULT_SOURCE.equals(source) ? run : run.resolve(source);
    }

    /**
     * Replays every source of a recording, in recorded time order: a k-way merge with a min-heap holding the next
     * answer of each source, O(N log k) for N answers and k sources. A folder without per-source sub-folders is the
     * single-site source, as before add-multi-site.
     */
    void replay(Path dir) {
        try {
            CollectorSettings s = settings.get();
            ReplayClock replayClock = new ReplayClock();
            Emitter replayEmitter = new Emitter(publisher, (int) (s.backfill().toMillis() / s.step().toMillis()) + 6);
            emitter = replayEmitter;
            int played = replayInto(dir, s, replayEmitter, replayClock);
            publisher.flush();
            log.info("Replay finished: {} recorded answers played from {}", played, dir.toAbsolutePath());
            mode = "replay (finished)";
        } catch (RuntimeException e) {
            log.error("Replay failed: {}", e.toString());
            mode = "replay (failed)";
        }
    }

    /** One source being replayed: its processing chain, its plan and its next recorded answer. */
    private record ReplayLane(String name, PollProcessor processor, Map<String, PollUnit> plan,
                              Iterator<ReplaySource.Recorded> answers) {}

    private record Next(long atMs, ReplaySource.Recorded recorded, ReplayLane lane) {}

    int replayInto(Path dir, CollectorSettings s, Emitter target, ReplayClock replayClock) {
        List<ReplayLane> replayLanes = new ArrayList<>();
        for (LaneConfig config : s.lanes()) {
            Path laneDir = recordingDir(dir, config.name());
            if (!Files.isDirectory(laneDir)) {
                log.warn("Replay: no recording for source '{}' in {}", config.name(), laneDir);
                continue;
            }
            ReplaySource source = new ReplaySource(laneDir);
            PollProcessor processor = new PollProcessor(new AnswerTranslator("datadog", config.site(), s.effectiveSettleDelay().toMillis()),
                    new Classifier(source, s.metadataCacheSize(), s.metadataTtl().toMillis(), replayClock), target,
                    () -> settings.get().classification());
            Map<String, PollUnit> plan = new HashMap<>();
            lab.anomaly.collector.plan.PollPlan.buildViews(config.name(),
                            new Discovery(source, config.name()).views(source.dashboardIds(), config.dashboardFilters()), s.step())
                    .forEach(u -> plan.put(u.key(), u));
            plan.values().forEach(u -> units.put(u.id(), u));
            replayLanes.add(new ReplayLane(config.name(), processor, plan, source.timeseriesInOrder().iterator()));
        }
        PriorityQueue<Next> heap = new PriorityQueue<>((a, b) -> Long.compare(a.atMs(), b.atMs()));
        replayLanes.forEach(l -> advance(heap, l));
        int played = 0;
        while (!heap.isEmpty()) {
            Next next = heap.poll();
            advance(heap, next.lane());
            ReplaySource.Recorded recorded = next.recorded();
            PollUnit unit = next.lane().plan().get(recorded.request().canonicalQuestion());
            if (unit == null || recorded.answer() == null) {
                continue;
            }
            replayClock.set(recorded.atMs());
            next.lane().processor().process(unit, recorded.answer(), s.step());
            played++;
            polls.incrementAndGet();
        }
        return played;
    }

    private static void advance(PriorityQueue<Next> heap, ReplayLane lane) {
        if (lane.answers().hasNext()) {
            ReplaySource.Recorded r = lane.answers().next();
            heap.add(new Next(r.atMs(), r, lane));
        }
    }

    // ---- for the health page ---------------------------------------------------------------------------------

    public String mode() {
        return mode;
    }

    /** The lanes in settings order (design M11). */
    public List<Lane> lanes() {
        Map<String, Lane> current = new LinkedHashMap<>();
        settings.get().lanes().forEach(c -> {
            Lane lane = lanes.get(c.name());
            if (lane != null) {
                current.put(c.name(), lane);
            }
        });
        return List.copyOf(current.values());
    }

    /** Late changes of a source (fix-false-drops design F2). */
    public lab.anomaly.collector.emit.Emitter.LateChanges lateChanges(String site) {
        Emitter e = emitter;
        return e == null ? null : e.lateChanges(site);
    }

    public long settleDelayMs() {
        return settings.get().effectiveSettleDelay().toMillis();
    }

    public double recentWindowSeconds() {
        return settings.get().recentWindow().toMillis() / 1000.0;
    }

    public double pollIntervalSeconds() {
        return settings.get().pollInterval().toMillis() / 1000.0;
    }

    public int unitCount() {
        return units.size();
    }

    public int lineCount() {
        Emitter e = emitter;
        return e == null ? 0 : e.lineCount();
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

    /**
     * Panels whose latest answer held no lines, per view (design J5): a panel is empty when every unit showing it
     * returned 0 lines. Value: the view's filters and its empty panels; plus the view's panel count. O(units of lane).
     */
    public record EmptyPanels(String view, String filters, int panels, java.util.List<String> empty) {}

    public java.util.List<EmptyPanels> emptyPanels(Lane lane) {
        Map<String, Map<String, Boolean>> byView = new LinkedHashMap<>();       // view → panel → has lines
        Map<String, String> filters = new HashMap<>();
        for (String id : lane.unitIds) {
            PollUnit unit = units.get(id);
            Integer lines = lastLines.get(id);
            if (unit == null || lines == null) {
                continue;                                                       // not answered yet
            }
            for (PollUnit.View v : unit.views()) {
                byView.computeIfAbsent(v.dashboardTitle(), k -> new LinkedHashMap<>()).merge(v.panelLabel(), lines > 0, Boolean::logicalOr);
                filters.putIfAbsent(v.dashboardTitle(), v.filters());
            }
        }
        java.util.List<EmptyPanels> out = new ArrayList<>();
        byView.forEach((view, panels) -> {
            java.util.List<String> empty = panels.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
            if (!empty.isEmpty()) {
                out.add(new EmptyPanels(view, filters.get(view), panels.size(), empty));
            }
        });
        return out;
    }

    /** Views of a lane whose dashboard has no id, so incidents get no link (design J4). */
    public java.util.Set<String> viewsWithoutId(Lane lane) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String id : lane.unitIds) {
            PollUnit unit = units.get(id);
            if (unit != null) {
                unit.views().stream().filter(v -> v.dashboardId().startsWith("file:")).forEach(v -> out.add(v.dashboardTitle()));
            }
        }
        return out;
    }

    /** Open breakers of one lane, by panel label. */
    public Map<String, String> openBreakers(Lane lane) {
        Map<String, String> open = new java.util.TreeMap<>();
        for (String id : lane.unitIds) {
            CircuitBreaker breaker = breakers.get(id);
            if (breaker != null && breaker.state() != CircuitBreaker.State.CLOSED) {
                PollUnit unit = units.get(id);
                open.put(unit == null ? id : unit.label(), breaker.state() + ": " + breaker.lastError());
            }
        }
        return open;
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

    /** For tests: the lane of a source, or null. */
    Lane lane(String name) {
        return lanes.get(name);
    }

    /** For tests: the units currently planned. */
    Map<String, PollUnit> units() {
        return units;
    }

    /** A clock that follows the recording's times during replay. */
    static final class ReplayClock extends Clock {
        private volatile long nowMs;
        private volatile boolean wentBack;

        void set(long ms) {
            if (ms < nowMs) {
                wentBack = true;                                  // would mean the merge broke time order
            }
            nowMs = ms;
        }

        boolean wentBack() {
            return wentBack;
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

    @Override
    public String toString() {
        return "CollectorEngine" + Objects.toString(lanes.keySet());
    }
}
