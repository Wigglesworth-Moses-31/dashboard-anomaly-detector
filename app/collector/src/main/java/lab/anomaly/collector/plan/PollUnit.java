package lab.anomaly.collector.plan;

import java.util.List;
import lab.anomaly.collector.source.Dashboard.Query;

/**
 * One request to the source, polled on a schedule. Several panels (on one or more dashboards) asking the same
 * question share one unit: {@code views} lists them all.
 */
public record PollUnit(String key, List<Query> queries, List<String> formulas, List<View> views) {

    public PollUnit {
        queries = List.copyOf(queries);
        formulas = List.copyOf(formulas);
        views = List.copyOf(views);
    }

    /** A panel showing this unit's numbers. */
    public record View(String dashboardId, String dashboardTitle, String panelTitle, String path) {}

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
