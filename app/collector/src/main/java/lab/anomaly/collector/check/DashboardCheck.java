package lab.anomaly.collector.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.CollectorSettings.DashboardRef;
import lab.anomaly.collector.dashboards.DashboardFiles;
import lab.anomaly.collector.plan.PollPlan;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.plan.WatchedView;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricSource;

/**
 * What the Settings tab shows before a dashboard is saved (add-settings-page S5): an uploaded export read with the
 * Collector's own code, compared with the current version, and optionally each panel asked once.
 */
public final class DashboardCheck {

    public static final int MAX_CHARS = 5_000_000;

    private DashboardCheck() {}

    public record Group(String group, List<String> panels) {}

    public record Variable(String name, String defaultValue) {}

    public record Diff(List<String> panelsAdded, List<String> panelsRemoved, List<String> viewsAdded, List<String> viewsRemoved) {}

    public record Summary(String title, String id, int panelCount, int skippedWidgets, List<Group> groups,
                          List<Variable> variables, List<String> savedViews) {}

    public record PanelLines(String panel, int lines, String error) {}

    public record ViewLines(String view, String filters, List<PanelLines> panels) {}

    /** Parses and summarises an export; IllegalArgumentException with a reason when it is not one. O(widgets). */
    public static Dashboard parse(String fileName, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fileName + ": the file is empty");
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException(fileName + ": larger than 5 MB");
        }
        return DashboardFiles.parseText(fileName, text);
    }

    public static Summary summary(Dashboard d) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Dashboard.Panel p : d.panels()) {
            groups.computeIfAbsent(p.path() == null ? "" : p.path(), k -> new ArrayList<>()).add(p.title());
        }
        List<Group> g = new ArrayList<>();
        groups.forEach((k, v) -> g.add(new Group(k, List.copyOf(v))));
        return new Summary(d.title(), d.id(), d.panels().size(), d.skippedWidgets(), g,
                d.variables().stream().map(v -> new Variable(v.name(), v.defaultValue() == null ? "" : v.defaultValue())).toList(),
                d.presets().stream().map(Dashboard.Preset::name).toList());
    }

    /** Panels ("group › panel") and saved views added or removed: two hash sets per kind, O(panels + views). */
    public static Diff diff(Dashboard before, Dashboard after) {
        return new Diff(minus(panels(after), panels(before)), minus(panels(before), panels(after)),
                minus(views(after), views(before)), minus(views(before), views(after)));
    }

    /** The views a choice of saved views would watch; IllegalArgumentException for an unknown name. */
    public static List<WatchedView> views(Dashboard d, String fileName, List<String> presets, Map<String, String> filters) {
        return WatchedView.of(d, new DashboardRef(fileName, null, filters, presets));
    }

    /** Asks each panel of the views once (read-only, batched like a poll); per view and panel, how many lines. */
    public static List<ViewLines> ask(MetricSource source, String lane, List<WatchedView> views, CollectorSettings settings,
                                      long pauseMs) throws InterruptedException {
        List<PollUnit> units = PollPlan.buildViews(lane, views, settings.step());
        Map<String, Integer> lines = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        CheckDashboards.askAll(source, units, settings, System.currentTimeMillis(), pauseMs, lines, errors);
        List<ViewLines> out = new ArrayList<>();
        for (WatchedView view : views) {
            List<PanelLines> panels = new ArrayList<>();
            for (PollUnit unit : units) {
                unit.views().stream().filter(x -> x.dashboardId().equals(view.viewId())).findFirst().ifPresent(v ->
                        panels.add(new PanelLines(v.panelLabel(), lines.getOrDefault(unit.id(), -1), errors.getOrDefault(unit.id(), ""))));
            }
            out.add(new ViewLines(view.viewTitle(), view.filtersText(), panels));
        }
        return out;
    }

    private static Set<String> panels(Dashboard d) {
        Set<String> s = new LinkedHashSet<>();
        d.panels().forEach(p -> s.add((p.path() == null || p.path().isBlank() ? "" : p.path() + " › ") + p.title()));
        return s;
    }

    private static Set<String> views(Dashboard d) {
        Set<String> s = new LinkedHashSet<>();
        d.presets().forEach(p -> s.add(p.name()));
        return s;
    }

    private static List<String> minus(Set<String> a, Set<String> b) {
        Set<String> bs = new HashSet<>(b);
        return a.stream().filter(x -> !bs.contains(x)).toList();
    }
}
