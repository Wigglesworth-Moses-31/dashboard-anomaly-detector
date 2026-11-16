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
    private final Map<String, Dashboard> lastGood = new ConcurrentHashMap<>();

    public Discovery(MetricSource source) {
        this.source = source;
    }

    public List<PollUnit> plan(List<String> dashboardIds, Map<String, Map<String, String>> filters, Duration step) {
        List<Dashboard> dashboards = new ArrayList<>();
        for (String id : dashboardIds) {
            try {
                Dashboard dashboard = source.dashboard(id);
                lastGood.put(id, dashboard);
                dashboards.add(dashboard);
                log.info("Dashboard '{}' ({}): {} panel requests, {} widgets without metric questions skipped",
                        dashboard.title(), id, dashboard.panels().size(), dashboard.skippedWidgets());
            } catch (SourceException e) {
                Dashboard previous = lastGood.get(id);
                log.warn("Cannot read dashboard {} ({}); {}", id, e.getMessage(),
                        previous == null ? "it is skipped for now" : "keeping its last known panels");
                if (previous != null) {
                    dashboards.add(previous);
                }
            }
        }
        lastGood.keySet().retainAll(dashboardIds);
        return PollPlan.build(dashboards, filters, step);
    }
}
