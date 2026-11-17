package lab.anomaly.collector.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import lab.anomaly.collector.CollectorSettings.DashboardRef;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Preset;
import lab.anomaly.collector.source.Dashboard.TemplateVariable;

/**
 * One way of looking at a dashboard: the dashboard with every variable's values decided (add-dashboard-json design
 * J10). Without presets there is one view with the file's defaults; with presets, one view per saved view, named
 * "Service Overview · Payments". Values are layered: the file's defaults, then the preset, then {@code filters} from settings.
 */
public record WatchedView(Dashboard dashboard, String viewId, String viewTitle, Map<String, List<String>> values) {

    public WatchedView {
        values = Map.copyOf(values);
    }

    /** The decided filters, e.g. "team IN (web,mobile), service:payments, env:prod" ("*" values left out). */
    public String filtersText() {
        StringJoiner out = new StringJoiner(", ");
        for (TemplateVariable v : dashboard.variables()) {
            List<String> x = values.getOrDefault(v.name(), List.of("*"));
            if (!x.isEmpty() && !x.contains("*")) {
                out.add(x.size() == 1 ? v.name() + ":" + x.get(0) : v.name() + " IN (" + String.join(",", x) + ")");
            }
        }
        return out.length() == 0 ? "no filters" : out.toString();
    }

    /** A view of a recorded or id-read dashboard with single values (replay and the older id path). */
    public static WatchedView of(Dashboard d, Map<String, String> filters) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        FilterFiller.values(d.variables(), filters).forEach((k, v) -> values.put(k, List.of(v)));
        return new WatchedView(d, d.id(), d.title(), values);
    }

    /**
     * The views a reference asks for. O(variables × presets). Throws IllegalArgumentException for a preset name the
     * dashboard does not have, a filter for a variable it does not have, or ids that disagree; the message lists what
     * exists (used when the settings are checked, design J3).
     */
    public static List<WatchedView> of(Dashboard d, DashboardRef ref) {
        Map<String, TemplateVariable> byName = new LinkedHashMap<>();
        d.variables().forEach(v -> byName.put(v.name(), v));
        for (String name : ref.filters().keySet()) {
            if (!byName.containsKey(name)) {
                throw new IllegalArgumentException(ref.name() + ": filters name '" + name + "', but the dashboard's variables are "
                        + byName.keySet());
            }
        }
        if (!d.id().isBlank() && ref.id() != null && !ref.id().isBlank() && !d.id().equals(ref.id())) {
            throw new IllegalArgumentException(ref.name() + ": id '" + ref.id() + "' differs from the id in the file '" + d.id() + "'");
        }
        String baseId = !d.id().isBlank() ? d.id() : ref.id() != null && !ref.id().isBlank() ? ref.id() : "file:" + ref.file();
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        for (TemplateVariable v : d.variables()) {
            defaults.put(v.name(), List.of(v.defaultValue() == null || v.defaultValue().isBlank() ? "*" : v.defaultValue()));
        }
        List<Preset> chosen = new ArrayList<>();
        if (ref.allPresets()) {
            chosen.addAll(d.presets());
        } else {
            Map<String, Preset> presets = new LinkedHashMap<>();
            d.presets().forEach(p -> presets.put(p.name(), p));
            for (String name : ref.presets()) {
                Preset p = presets.get(name);
                if (p == null) {
                    throw new IllegalArgumentException(ref.name() + ": no preset named '" + name + "'; the dashboard has "
                            + presets.keySet());
                }
                chosen.add(p);
            }
        }
        List<WatchedView> views = new ArrayList<>();
        if (chosen.isEmpty()) {
            views.add(new WatchedView(d, baseId, d.title(), layered(defaults, Map.of(), ref.filters())));
        }
        for (Preset p : chosen) {
            views.add(new WatchedView(d, baseId + "#" + p.name(), d.title() + " · " + p.name(), layered(defaults, p.values(), ref.filters())));
        }
        return views;
    }

    private static Map<String, List<String>> layered(Map<String, List<String>> defaults, Map<String, List<String>> preset,
                                                     Map<String, String> filters) {
        Map<String, List<String>> values = new LinkedHashMap<>(defaults);
        preset.forEach((k, v) -> {
            if (values.containsKey(k)) {
                values.put(k, v);
            }
        });
        filters.forEach((k, v) -> values.put(k, List.of(v)));
        return values;
    }
}
