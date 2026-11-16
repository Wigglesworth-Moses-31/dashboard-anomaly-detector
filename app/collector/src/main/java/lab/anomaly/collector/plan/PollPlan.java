package lab.anomaly.collector.plan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Panel;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.TimeseriesRequest;

/**
 * Turns dashboards into poll units (add-collector design K3): filters filled in, rollups added, and identical
 * requests merged. A hash map keyed by the request's canonical form finds a duplicate in O(1), so a question
 * shown on several panels is asked once.
 */
public final class PollPlan {

    public static List<PollUnit> build(List<Dashboard> dashboards, Map<String, Map<String, String>> filtersByDashboard, Duration step) {
        Map<String, PollUnit> byKey = new LinkedHashMap<>();
        for (Dashboard dashboard : dashboards) {
            Map<String, String> values = FilterFiller.values(dashboard.variables(),
                    filtersByDashboard.getOrDefault(dashboard.id(), Map.of()));
            for (Panel panel : dashboard.panels()) {
                List<Query> queries = new ArrayList<>();
                for (Query query : panel.queries()) {
                    String filled = FilterFiller.fill(query.text(), dashboard.variables(), values);
                    queries.add(new Query(query.name(), QueryPlanner.withRollup(filled, step)));
                }
                String key = new TimeseriesRequest(queries, panel.formulas(), 0, 0, 0).canonicalQuestion();
                PollUnit.View view = new PollUnit.View(dashboard.id(), dashboard.title(), panel.title(), panel.path());
                PollUnit existing = byKey.get(key);
                if (existing == null) {
                    byKey.put(key, new PollUnit(key, queries, panel.formulas(), List.of(view)));
                } else if (!existing.views().contains(view)) {
                    List<PollUnit.View> views = new ArrayList<>(existing.views());
                    views.add(view);
                    byKey.put(key, new PollUnit(key, existing.queries(), existing.formulas(), views));
                }
            }
        }
        return List.copyOf(byKey.values());
    }

    private PollPlan() {
    }
}
