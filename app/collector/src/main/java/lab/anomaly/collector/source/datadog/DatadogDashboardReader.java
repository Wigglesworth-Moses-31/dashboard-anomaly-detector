package lab.anomaly.collector.source.datadog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Panel;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.Dashboard.TemplateVariable;
import tools.jackson.databind.JsonNode;

/**
 * Turns Datadog's dashboard JSON into the neutral {@link Dashboard} (design K3).
 *
 * <p>A dashboard is a tree: groups hold widgets, split groups hold one source widget. An iterative depth-first
 * walk with an explicit stack visits every widget once, O(W), and cannot overflow the call stack however deep
 * the nesting. Unknown widget types are not an error: if they have metric queries they are used, otherwise they
 * are counted as skipped.
 */
public final class DatadogDashboardReader {

    private record Visit(JsonNode widget, String path) {}

    public static Dashboard read(JsonNode root) {
        List<TemplateVariable> variables = new ArrayList<>();
        for (JsonNode v : root.path("template_variables")) {
            String defaultValue = v.path("default").isMissingNode() || v.path("default").isNull()
                    ? (v.path("defaults").isArray() && !v.path("defaults").isEmpty() ? text(v.path("defaults").get(0)) : "*")
                    : text(v.path("default"));
            variables.add(new TemplateVariable(text(v.path("name")), text(v.path("prefix")), defaultValue));
        }

        List<Panel> panels = new ArrayList<>();
        int skipped = 0;
        Deque<Visit> stack = new ArrayDeque<>();
        // Top level, in order: an empty group is a section heading for the panels below it, until the next group
        // (add-dashboard-json design J12). Same O(W) walk.
        List<Visit> top = new ArrayList<>();
        String section = "";
        for (JsonNode widget : root.path("widgets")) {
            JsonNode definition = widget.path("definition");
            if ("group".equals(text(definition.path("type")))) {
                boolean empty = !definition.path("widgets").isArray() || definition.path("widgets").isEmpty();
                section = empty ? text(definition.path("title")) : "";
                if (empty) {
                    continue;
                }
            }
            top.add(new Visit(widget, section));
        }
        for (int i = top.size() - 1; i >= 0; i--) {
            stack.push(top.get(i));
        }
        while (!stack.isEmpty()) {
            Visit visit = stack.pop();
            JsonNode definition = visit.widget().path("definition");
            String type = text(definition.path("type"));
            String title = text(definition.path("title"));
            if ("group".equals(type)) {
                pushAll(stack, definition.path("widgets"), join(visit.path(), title));
                continue;
            }
            // A split group draws one source widget once per tag value; we ask for the source widget's question
            JsonNode requestsHolder = "split_group".equals(type) ? definition.path("source_widget_definition") : definition;
            boolean found = false;
            for (JsonNode request : requestsOf(requestsHolder)) {
                Panel panel = panelOf(request, title, visit.path());
                if (panel != null) {
                    long widgetId = visit.widget().path("id").asLong(0);   // add-datadog-look K1: exact placement
                    String splitTag = "split_group".equals(type)            // anomaly-per-graph P2: one graph per value of this tag
                            ? text(definition.path("split_config").path("split_dimensions").path(0).path("one_graph_per")) : "";
                    panels.add(widgetId == 0 && splitTag.isEmpty() ? panel
                            : new Panel(panel.title(), panel.path(), panel.queries(), panel.formulas(), widgetId, splitTag));
                    found = true;
                }
            }
            if (!found) {
                skipped++;
            }
        }
        return new Dashboard(text(root.path("id")), text(root.path("title")), variables, panels, skipped, presets(root));
    }

    /** Saved views: {"name", "template_variables": [{"name", "value" | "values"}]} (design J10). */
    private static List<Dashboard.Preset> presets(JsonNode root) {
        List<Dashboard.Preset> presets = new ArrayList<>();
        for (JsonNode p : root.path("template_variable_presets")) {
            java.util.Map<String, List<String>> values = new java.util.LinkedHashMap<>();
            for (JsonNode v : p.path("template_variables")) {
                List<String> list = new ArrayList<>();
                if (v.path("values").isArray()) {
                    v.path("values").forEach(x -> list.add(text(x)));
                } else if (!v.path("value").isMissingNode() && !v.path("value").isNull()) {
                    list.add(text(v.path("value")));
                }
                if (!list.isEmpty() && !text(v.path("name")).isEmpty()) {
                    values.put(text(v.path("name")), List.copyOf(list));
                }
            }
            presets.add(new Dashboard.Preset(text(p.path("name")), values));
        }
        return presets;
    }

    /** Pushed in reverse so the walk visits widgets in their on-screen order. */
    private static void pushAll(Deque<Visit> stack, JsonNode widgets, String path) {
        List<JsonNode> list = new ArrayList<>();
        widgets.forEach(list::add);
        for (int i = list.size() - 1; i >= 0; i--) {
            stack.push(new Visit(list.get(i), path));
        }
    }

    private static List<JsonNode> requestsOf(JsonNode definition) {
        List<JsonNode> requests = new ArrayList<>();
        JsonNode node = definition.path("requests");
        if (node.isArray()) {
            node.forEach(requests::add);
        } else if (node.isObject()) {             // some widget types use an object of named requests
            node.forEach(requests::add);
        }
        return requests;
    }

    private static Panel panelOf(JsonNode request, String title, String path) {
        List<Query> queries = new ArrayList<>();
        for (JsonNode q : request.path("queries")) {
            if ("metrics".equals(text(q.path("data_source"))) && !text(q.path("query")).isBlank()) {
                queries.add(new Query(text(q.path("name")), text(q.path("query"))));
            }
        }
        if (queries.isEmpty() && !text(request.path("q")).isBlank()) {     // older single-query form
            queries.add(new Query("query1", text(request.path("q"))));
        }
        if (queries.isEmpty()) {
            return null;
        }
        List<String> formulas = new ArrayList<>();
        for (JsonNode f : request.path("formulas")) {
            formulas.add(text(f.path("formula")));
        }
        if (formulas.isEmpty()) {
            queries.forEach(q -> formulas.add(q.name()));                // no formulas: show each query as is
        }
        return new Panel(title, path, queries, formulas);
    }

    private static String join(String path, String title) {
        return path.isEmpty() ? title : path + " / " + title;
    }

    public static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asString();
    }

    private DatadogDashboardReader() {
    }
}
