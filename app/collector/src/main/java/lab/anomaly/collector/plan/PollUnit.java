package lab.anomaly.collector.plan;

import java.util.List;
import lab.anomaly.collector.source.Dashboard.Query;

/**
 * One request to the source, polled on a schedule. Several panels (on one or more dashboards) of one source asking
 * the same question share one unit: {@code views} lists them all.
 *
 * <p>{@code key} is the canonical question; line ids are built from it, so it must not change (add-multi-site:
 * old line ids stay). {@code lane} is the source the unit belongs to, and {@link #id()} = {@code lane|key} is what
 * the scheduler, breakers and maps use, so the same question in two sources is two units (design M3).
 */
public record PollUnit(String key, List<Query> queries, List<String> formulas, List<View> views, String lane) {

    public PollUnit {
        queries = List.copyOf(queries);
        formulas = List.copyOf(formulas);
        views = List.copyOf(views);
        lane = lane == null || lane.isBlank() ? "default" : lane;
    }

    public PollUnit(String key, List<Query> queries, List<String> formulas, List<View> views) {
        this(key, queries, formulas, views, "default");
    }

    /** Scheduling id: unique across sources. */
    public String id() {
        return lane + "|" + key;
    }

    /** A panel showing this unit's numbers; {@code filters} is the view's decided filters (for health and the check). */
    public record View(String dashboardId, String dashboardTitle, String panelTitle, String path, String filters,
                       int groupIndex, int panelIndex, long widgetId) {
        public View(String dashboardId, String dashboardTitle, String panelTitle, String path, String filters,
                    int groupIndex, int panelIndex) {
            this(dashboardId, dashboardTitle, panelTitle, path, filters, groupIndex, panelIndex, 0);
        }

        public View(String dashboardId, String dashboardTitle, String panelTitle, String path) {
            this(dashboardId, dashboardTitle, panelTitle, path, "");
        }

        public View(String dashboardId, String dashboardTitle, String panelTitle, String path, String filters) {
            this(dashboardId, dashboardTitle, panelTitle, path, filters, 0, 0);
        }

        /** "Upstream Service Metrics / Latencies". */
        public String panelLabel() {
            return path == null || path.isEmpty() ? panelTitle : path + " / " + panelTitle;
        }
    }

    /** True when formula number {@code index} computes something, rather than showing one query as it is. */
    public boolean hasFormula(int index) {
        String formula = formulas.get(index).strip();
        return queries.stream().noneMatch(q -> q.name().equals(formula));
    }

    /** Short label for logs and the health page. */
    public String label() {
        View first = views.get(0);
        return first.dashboardTitle() + " / " + (first.path().isEmpty() ? "" : first.path() + " / ") + first.panelTitle();
    }
}
