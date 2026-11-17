package lab.anomaly.collector.run;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lab.anomaly.collector.plan.PollPlan;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the watched dashboards and builds the poll plan (spec: find every panel). If one dashboard cannot be
 * read, its last good definition is kept, so a passing problem never stops the panels already known.
 */
public final class Discovery {

    private static final Logger log = LoggerFactory.getLogger(Discovery.class);

    private final MetricSource source;
    private final String lane;
    private final Map<String, Dashboard> lastGood = new ConcurrentHashMap<>();
    /** Dashboards that could not be read and have no earlier good definition: id → why. Shown on health. */
    private final Map<String, String> unreadable = new ConcurrentHashMap<>();

    public Discovery(MetricSource source) {
        this(source, "default");
    }

    /** The discovery of one source (add-multi-site design M4): its plan's units belong to {@code lane}. */
    public Discovery(MetricSource source, String lane) {
        this.source = source;
        this.lane = lane;
    }

    /** Views of dashboards read by id from the source (replay and tests); unreadable ones are tracked. */
    public List<lab.anomaly.collector.plan.WatchedView> views(List<String> dashboardIds, Map<String, Map<String, String>> filters) {
        List<lab.anomaly.collector.plan.WatchedView> views = new ArrayList<>();
        for (Dashboard d : read(dashboardIds)) {
            views.add(lab.anomaly.collector.plan.WatchedView.of(d, filters.getOrDefault(d.id(), Map.of())));
        }
        return views;
    }

    public List<PollUnit> plan(List<String> dashboardIds, Map<String, Map<String, String>> filters, Duration step) {
        return PollPlan.build(lane, read(dashboardIds), filters, step);
    }

    private List<Dashboard> read(List<String> dashboardIds) {
        List<Dashboard> dashboards = new ArrayList<>();
        for (String id : dashboardIds) {
            try {
                Dashboard dashboard = source.dashboard(id);
                lastGood.put(id, dashboard);
                unreadable.remove(id);
                dashboards.add(dashboard);
                log.info("[{}] Dashboard '{}' ({}): {} panel requests, {} widgets without metric questions skipped",
                        lane, dashboard.title(), id, dashboard.panels().size(), dashboard.skippedWidgets());
            } catch (SourceException e) {
                Dashboard previous = lastGood.get(id);
                log.warn("[{}] Cannot read dashboard {} ({}); {}", lane, id, e.getMessage(),
                        previous == null ? "it is skipped for now" : "keeping its last known panels");
                if (previous != null) {
                    dashboards.add(previous);
                } else {
                    unreadable.put(id, e.getMessage());
                }
            }
        }
        lastGood.keySet().retainAll(dashboardIds);
        unreadable.keySet().retainAll(dashboardIds);
        return dashboards;
    }

    /** Dashboards not collected at all because they were never read (found live in add-multi-site 7.3). */
    public Map<String, String> unreadable() {
        return Map.copyOf(unreadable);
    }
}
