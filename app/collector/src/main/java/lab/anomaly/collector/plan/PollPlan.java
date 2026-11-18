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
        return build("default", dashboards, filtersByDashboard, step);
    }

    /** The plan of one source (add-multi-site design M3): questions are merged only within that source. */
    public static List<PollUnit> build(String lane, List<Dashboard> dashboards, Map<String, Map<String, String>> filtersByDashboard,
                                       Duration step) {
        List<WatchedView> views = new ArrayList<>();
        for (Dashboard d : dashboards) {
            views.add(WatchedView.of(d, filtersByDashboard.getOrDefault(d.id(), Map.of())));
        }
        return buildViews(lane, views, step);
    }

    /**
     * The plan of a source's views (add-dashboard-json design J10): every panel of every view filled with that view's
     * values. Equal questions are merged by their canonical text in a hash map, O(1) each, so a question two presets
     * share is asked once and its unit lists both views. O(views × panels).
     */
    public static List<PollUnit> buildViews(String lane, List<WatchedView> views, Duration step) {
        Map<String, PollUnit> byKey = new LinkedHashMap<>();
        for (WatchedView v : views) {
            Dashboard dashboard = v.dashboard();
            String filters = v.filtersText();
            Map<String, Integer> groups = new LinkedHashMap<>();            // Datadog's order: first appearance
            int panelIndex = 0;
            for (Panel panel : dashboard.panels()) {
                panelIndex++;
                int groupIndex = groups.computeIfAbsent(panel.path() == null ? "" : panel.path(), k -> groups.size() + 1);
                List<Query> queries = new ArrayList<>();
                for (Query query : panel.queries()) {
                    String filled = FilterFiller.fillMulti(query.text(), dashboard.variables(), v.values());
                    queries.add(new Query(query.name(), QueryPlanner.withRollup(filled, step)));
                }
                String key = new TimeseriesRequest(queries, panel.formulas(), 0, 0, 0).canonicalQuestion();
                PollUnit.View view = new PollUnit.View(v.viewId(), v.viewTitle(), panel.title(), panel.path(), filters,
                        groupIndex, panelIndex);                          // add-dashboard-view D1
                PollUnit existing = byKey.get(key);
                if (existing == null) {
                    byKey.put(key, new PollUnit(key, queries, panel.formulas(), List.of(view), lane));
                } else if (!existing.views().contains(view)) {
                    List<PollUnit.View> all = new ArrayList<>(existing.views());
                    all.add(view);
                    byKey.put(key, new PollUnit(key, existing.queries(), existing.formulas(), all, lane));
                }
            }
        }
        return List.copyOf(byKey.values());
    }

    private PollPlan() {
    }
}
