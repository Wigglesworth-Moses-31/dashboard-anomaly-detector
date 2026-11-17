package lab.anomaly.collector.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.anomaly.kit.settings.SettingsHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health check "collector" (add-collector task 5.4, add-multi-site design M11): totals, then one block per source
 * with its keys (present or missing, never values), budget, calls, throttling, open breakers and freshness.
 * DEGRADED, with a warning naming the source, when a source misses a key, plans more than 90% of its allowed rate,
 * or has open breakers. One source's trouble never makes the Collector DOWN.
 */
@Component("collector")
public class CollectorHealthIndicator implements HealthIndicator {

    private final CollectorEngine engine;

    public CollectorHealthIndicator(CollectorEngine engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", engine.mode());
        details.put("pollUnits", engine.unitCount());
        details.put("metricLines", engine.lineCount());
        details.put("polls", engine.polls());
        details.put("failedPolls", engine.failedPolls());
        details.put("skippedTurns", engine.skippedTurns());
        Long ago = engine.lastSuccessAgoMs();
        details.put("lastSuccessfulPollSecondsAgo", ago == null ? null : ago / 1000.0);
        if (engine.publisher() != null) {
            details.put("messagesSent", engine.publisher().sentCount());
            details.put("messagesDropped", engine.publisher().droppedCount());
        }
        List<String> warnings = new ArrayList<>();
        Map<String, Object> sources = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Lane lane : engine.lanes()) {
            sources.put(lane.name(), source(lane, now, warnings));
        }
        details.put("sources", sources);
        details.put("warnings", warnings);
        return (warnings.isEmpty() ? Health.up() : Health.status(SettingsHealthIndicator.DEGRADED)).withDetails(details).build();
    }

    private Map<String, Object> source(Lane lane, long now, List<String> warnings) {
        Map<String, Object> d = new LinkedHashMap<>();
        String missing = lane.keysMissing();
        d.put("site", lane.config().site());
        d.put("keys", missing.isEmpty() ? "present" : "missing: " + missing);
        d.put("dashboards", lane.config().dashboards().size());
        d.put("units", lane.unitCount());
        double budget = lane.budgetNow();
        d.put("budgetPercent", Math.round(budget * 100));
        d.put("callsPerRound", lane.plannedCalls());
        d.put("pollEverySeconds", lane.effectiveIntervalMs() / 1000.0);
        Fetcher f = lane.fetcher();
        d.put("calls", lane.calls.get());
        d.put("polls", lane.polls.get());
        d.put("failedPolls", lane.failedPolls.get());
        d.put("throttled", f.throttledCount());
        d.put("inFlight", f.inFlight(lane.config().maxConcurrentRequests()));
        d.put("sourceLimit", f.lastLimit());
        long gate = f.gate().waitMs(now);
        d.put("pausedForMs", gate);
        lab.anomaly.collector.emit.Emitter.LateChanges late = engine.lateChanges(lane.config().site());
        long settleMs = engine.settleDelayMs();
        d.put("lateChanges", late == null ? 0 : late.count());
        d.put("latestChangeAfterSeconds", late == null ? null : late.latestAfterMs() / 1000.0);
        d.put("maxChangeAfterSeconds", late == null ? null : late.maxAfterMs() / 1000.0);
        if (late != null && late.latestAfterMs() > settleMs) {
            warnings.add(lane.name() + ": values still changed " + Math.round(late.latestAfterMs() / 1000.0)
                    + " s after their step, longer than settle-delay (" + settleMs / 1000 + " s): raise settle-delay");
        }
        Map<String, String> unreadable = lane.discovery().unreadable();
        d.put("unreadableDashboards", unreadable);
        Map<String, String> open = engine.openBreakers(lane);
        d.put("openBreakers", open);
        d.put("lastDiscoverySecondsAgo", lane.lastDiscoveryMs == 0 ? null : (now - lane.lastDiscoveryMs) / 1000.0);
        d.put("lastSuccessfulPollSecondsAgo", lane.lastSuccessMs == 0 ? null : (now - lane.lastSuccessMs) / 1000.0);
        if (!missing.isEmpty()) {
            warnings.add(lane.name() + ": keys missing: " + missing + " (" + lane.config().apiKeyEnv() + ", "
                    + lane.config().appKeyEnv() + "); nothing is collected from this source");
        }
        double base = engine.pollIntervalSeconds();
        double every = lane.effectiveIntervalMs() / 1000.0;
        if (every > base + 0.001) {
            warnings.add(lane.name() + ": polling every " + Math.round(every) + " s instead of " + Math.round(base)
                    + " s to stay within the rate limit (" + lane.plannedCalls() + " calls per round)"
                    + (every > engine.recentWindowSeconds() ? "; that is longer than recent-window, so steps may be missed: fewer dashboards or presets" : ""));
        } else if (budget > CollectorEngine.BUDGET_WARNING) {
            warnings.add(lane.name() + ": the plan uses " + Math.round(budget * 100)
                    + "% of the rate this source allows: fewer dashboards or a longer poll-interval");
        }
        java.util.List<CollectorEngine.EmptyPanels> empty = engine.emptyPanels(lane);
        Map<String, Object> emptyDetails = new LinkedHashMap<>();
        for (CollectorEngine.EmptyPanels e : empty) {
            emptyDetails.put(e.view(), Map.of("filters", e.filters(), "emptyPanels", e.empty(), "panels", e.panels()));
            if (e.empty().size() == e.panels()) {
                warnings.add(lane.name() + ": dashboard " + e.view() + ": " + e.panels() + " of " + e.panels()
                        + " panels return no lines (filters " + e.filters() + ")");
            }
        }
        d.put("panelsWithNoLines", emptyDetails);
        java.util.Set<String> noId = engine.viewsWithoutId(lane);
        if (!noId.isEmpty()) {
            d.put("noIdForLinks", noId);
        }
        if (!unreadable.isEmpty()) {
            warnings.add(lane.name() + ": " + unreadable.size() + " of " + lane.config().dashboards().size()
                    + " dashboard(s) cannot be read, so they are not collected (" + unreadable.values().iterator().next()
                    + "); retried every minute");
        }
        if (!open.isEmpty()) {
            warnings.add(lane.name() + ": " + open.size() + " panel(s) paused by their breaker");
        }
        return d;
    }
}
