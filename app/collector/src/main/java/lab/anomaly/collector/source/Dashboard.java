package lab.anomaly.collector.source;

import java.util.List;

/**
 * A dashboard as the Collector needs it: its filters and every panel with metric questions.
 * {@code skippedWidgets} counts widgets with no metric question (service map, notes...).
 */
public record Dashboard(String id, String title, List<TemplateVariable> variables, List<Panel> panels, int skippedWidgets) {

    public Dashboard {
        variables = List.copyOf(variables);
        panels = List.copyOf(panels);
    }

    /** A dashboard placeholder such as {@code $env}: its tag prefix and default value. */
    public record TemplateVariable(String name, String prefix, String defaultValue) {}

    /** One panel request: its questions and the formulas built from them. {@code path} = group titles. */
    public record Panel(String title, String path, List<Query> queries, List<String> formulas) {
        public Panel {
            queries = List.copyOf(queries);
            formulas = List.copyOf(formulas);
        }
    }

    /** One named metric question, e.g. {@code query1 = sum:requests{$env} by {api}}. */
    public record Query(String name, String text) {}
}
