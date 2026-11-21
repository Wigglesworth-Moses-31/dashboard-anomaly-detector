package lab.anomaly.collector.source;

import java.util.List;
import java.util.Map;

/**
 * A dashboard as the Collector needs it: its filters, its saved views (presets) and every panel with metric
 * questions. {@code skippedWidgets} counts widgets with no metric question (service map, notes...).
 */
public record Dashboard(String id, String title, List<TemplateVariable> variables, List<Panel> panels, int skippedWidgets,
                        List<Preset> presets) {

    public Dashboard {
        variables = List.copyOf(variables);
        panels = List.copyOf(panels);
        presets = presets == null ? List.of() : List.copyOf(presets);
    }

    public Dashboard(String id, String title, List<TemplateVariable> variables, List<Panel> panels, int skippedWidgets) {
        this(id, title, variables, panels, skippedWidgets, List.of());
    }

    /** A dashboard placeholder such as {@code $env}: its tag prefix and default value. */
    public record TemplateVariable(String name, String prefix, String defaultValue) {}

    /**
     * A saved view (Datadog "template variable preset", add-dashboard-json design J10): the values it gives some
     * variables. A variable may get several values (shown as {@code tag IN (a,b)}).
     */
    public record Preset(String name, Map<String, List<String>> values) {
        public Preset {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /** One panel request: its questions and the formulas built from them. {@code path} = group or section titles. */
    public record Panel(String title, String path, List<Query> queries, List<String> formulas, Long widgetId) {
        public Panel {
            queries = List.copyOf(queries);
            formulas = List.copyOf(formulas);
            widgetId = widgetId == null ? 0L : widgetId;                   // recordings made before add-datadog-look
        }

        /** Without a widget id (0 = not known): tests and sources that have none. */
        public Panel(String title, String path, List<Query> queries, List<String> formulas) {
            this(title, path, queries, formulas, 0L);
        }

        /** "Upstream Service Metrics / Latencies", or just the title outside any group or section. */
        public String label() {
            return path == null || path.isEmpty() ? title : path + " / " + title;
        }
    }

    /** One named metric question, e.g. {@code query1 = sum:requests{$env} by {api}}. */
    public record Query(String name, String text) {}
}
