package lab.anomaly.collector.run;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.collector.CollectorSettings.LaneConfig;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.source.MetricSource;

/**
 * One source's lane (add-multi-site design M1, the Bulkhead pattern at the level of a whole source): its own keys,
 * its own connection to its site, its own Fetcher (token bucket, 429 gate, fair bulkhead), its own metadata cache
 * (inside its PollProcessor's Classifier) and its own discovery. Nothing in here is shared with another lane, so a
 * lane that is throttled, down or slow can only make itself wait.
 *
 * <p>About a dozen small objects per lane; what grows is the units, which grow with panels, not with sources.
 */
public final class Lane {

    private volatile LaneConfig config;
    private final DatadogCredentials credentials;
    private final MetricSource source;
    private final Fetcher fetcher;
    private final PollProcessor processor;
    private final Discovery discovery;

    /** Scheduling ids of this lane's units: replacing a plan touches only these (design M4). */
    final Set<String> unitIds = ConcurrentHashMap.newKeySet();
    final AtomicBoolean discovering = new AtomicBoolean();
    volatile long nextDiscoveryMs;
    volatile long lastDiscoveryMs;
    volatile long lastSuccessMs;
    final AtomicLong polls = new AtomicLong();
    /** Datadog calls made by this lane (a batch is one call). */
    final AtomicLong calls = new AtomicLong();
    /** Calls one round needs, and the interval the budget guard chose (design J13). */
    volatile int plannedCalls;
    volatile long effectiveIntervalMs;
    final AtomicLong failedPolls = new AtomicLong();

    Lane(LaneConfig config, DatadogCredentials credentials, MetricSource source, Fetcher fetcher, PollProcessor processor,
         Discovery discovery) {
        this.config = config;
        this.credentials = credentials;
        this.source = source;
        this.fetcher = fetcher;
        this.processor = processor;
        this.discovery = discovery;
    }

    public String name() {
        return config.name();
    }

    public LaneConfig config() {
        return config;
    }

    /** Only what to watch changed (dashboards, filters): keep everything else, re-read dashboards (design M7). */
    void updateWatched(LaneConfig newConfig) {
        this.config = newConfig;
        this.nextDiscoveryMs = 0;
    }

    /** False when a key is missing: the lane then sends nothing at all (spec: keys missing). */
    public boolean ready() {
        return credentials == null || credentials.complete();
    }

    /** "" when both keys are present, else "API key", "application key" or "both". Never a value. */
    public String keysMissing() {
        return credentials == null ? "" : credentials.missing();
    }

    public MetricSource source() {
        return source;
    }

    public Fetcher fetcher() {
        return fetcher;
    }

    public PollProcessor processor() {
        return processor;
    }

    public Discovery discovery() {
        return discovery;
    }

    public int unitCount() {
        return unitIds.size();
    }

    /** Share of the allowed rate the planned polls use (design M8). O(1). */
    public double budget(double pollIntervalSeconds) {
        return budget(unitIds.size(), pollIntervalSeconds, fetcher.allowedPerSecond());
    }

    /** Share of the allowed rate this lane uses with its batches and its (possibly stretched) interval. */
    public double budgetNow() {
        double seconds = effectiveIntervalMs / 1000.0;
        double allowed = fetcher.allowedPerSecond();
        return plannedCalls == 0 || seconds <= 0 || allowed <= 0 ? 0 : (plannedCalls / seconds) / allowed;
    }

    public int plannedCalls() {
        return plannedCalls;
    }

    public long effectiveIntervalMs() {
        return effectiveIntervalMs;
    }

    /** planned per second ÷ allowed per second; 0 when nothing is allowed yet to compare with. */
    static double budget(int units, double pollIntervalSeconds, double allowedPerSecond) {
        if (units == 0 || pollIntervalSeconds <= 0 || allowedPerSecond <= 0) {
            return 0;
        }
        return (units / pollIntervalSeconds) / allowedPerSecond;
    }
}
