package lab.anomaly.notifier.dash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.View;

/**
 * Every watched line, arranged as the pages show it (add-dashboard-view D2): site → dashboard view → group → panel →
 * lines, in Datadog's order (group, then panel position; unknown positions after, by title). Immutable: a new one is
 * built and swapped in, so readers never lock. Build O(lines log lines).
 */
public final class Catalogue {

    public record Line(String id, String metric, Map<String, String> tags, String unit, String kind, String query) {}

    public record Panel(String id, String title, String group, int groupIndex, int panelIndex, long widgetId, List<Line> lines) {}

    public record Group(String title, int index, List<Panel> panels) {}

    public record Dashboard(String id, String title, String site, List<Group> groups, int panelCount, int lineCount) {}

    public record Site(String site, List<Dashboard> dashboards) {}

    /** Where a line is shown: for the Metric page. */
    public record Place(String site, String dashboardId, String dashboardTitle, String group, String panelId, String panel) {}

    private final List<Site> sites;
    private final Map<String, Dashboard> dashboards = new LinkedHashMap<>();
    private final Map<String, Panel> panels = new LinkedHashMap<>();
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<String, List<Place>> places = new LinkedHashMap<>();

    public static final Catalogue EMPTY = of(List.of());

    private Catalogue(List<Site> sites) {
        this.sites = sites;
    }

    public static Catalogue of(List<SeriesDescriptor> descriptors) {
        record Raw(String site, View view, SeriesDescriptor d) {}
        Map<String, Map<String, Map<String, List<Raw>>>> tree = new LinkedHashMap<>();   // site → view → panel id → lines
        for (SeriesDescriptor d : descriptors) {
            String site = d.getSource().getInstance();
            for (View v : d.getViewsList()) {
                tree.computeIfAbsent(site, k -> new LinkedHashMap<>()).computeIfAbsent(v.getViewId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(panelId(site, v), k -> new ArrayList<>()).add(new Raw(site, v, d));
            }
        }
        List<Site> sites = new ArrayList<>();
        Catalogue c = new Catalogue(sites);
        for (var siteEntry : tree.entrySet()) {
            List<Dashboard> dashboards = new ArrayList<>();
            for (var viewEntry : siteEntry.getValue().entrySet()) {
                List<Panel> panelList = new ArrayList<>();
                String title = "";
                for (var panelEntry : viewEntry.getValue().entrySet()) {
                    View v = panelEntry.getValue().get(0).view();
                    title = v.getViewTitle();
                    List<Line> ls = panelEntry.getValue().stream().map(r -> line(r.d())).distinct()
                            .sorted(Comparator.comparing(l -> l.tags().toString())).toList();
                    long widgetId = panelEntry.getValue().stream().mapToLong(r -> r.view().getWidgetId()).filter(x -> x != 0).findFirst().orElse(0);
                    panelList.add(new Panel(panelEntry.getKey(), v.getPanelTitle(), v.getPanelPath(), v.getGroupIndex(), v.getPanelIndex(),
                            widgetId, ls));
                }
                panelList.sort(Comparator.comparingInt((Panel p) -> p.groupIndex() == 0 ? Integer.MAX_VALUE : p.groupIndex())
                        .thenComparingInt(p -> p.panelIndex() == 0 ? Integer.MAX_VALUE : p.panelIndex())
                        .thenComparing(Panel::group).thenComparing(Panel::title));
                Map<String, List<Panel>> byGroup = new LinkedHashMap<>();
                for (Panel p : panelList) {
                    byGroup.computeIfAbsent(p.group(), k -> new ArrayList<>()).add(p);
                }
                List<Group> groups = new ArrayList<>();
                int gi = 0;
                for (var g : byGroup.entrySet()) {
                    groups.add(new Group(g.getKey(), ++gi, List.copyOf(g.getValue())));
                }
                int lineCount = (int) panelList.stream().flatMap(p -> p.lines().stream()).map(Line::id).distinct().count();
                // the same Datadog dashboard may be watched in two regions: the id names the site too
                Dashboard dash = new Dashboard(siteEntry.getKey() + "~" + viewEntry.getKey(), title, siteEntry.getKey(), List.copyOf(groups),
                        panelList.size(), lineCount);
                dashboards.add(dash);
                c.dashboards.put(dash.id(), dash);
                for (Panel p : panelList) {
                    c.panels.put(p.id(), p);
                    for (Line l : p.lines()) {
                        c.lines.putIfAbsent(l.id(), l);
                        c.places.computeIfAbsent(l.id(), k -> new ArrayList<>())
                                .add(new Place(siteEntry.getKey(), dash.id(), dash.title(), p.group(), p.id(), p.title()));
                    }
                }
            }
            dashboards.sort(Comparator.comparing(Dashboard::title));
            sites.add(new Site(siteEntry.getKey(), List.copyOf(dashboards)));
        }
        return c;
    }

    private static Line line(SeriesDescriptor d) {
        return new Line(d.getSeriesId(), d.getMetric(), new java.util.TreeMap<>(d.getGroupTagsMap()), d.getUnit(),
                d.getKind().name().replace("KIND_", "").toLowerCase(java.util.Locale.ROOT), d.getQueryText());
    }

    /** A short stable id for a panel of a view in a site: 12 hex characters of SHA-256 over site, view, group and title. */
    static String panelId(String site, View v) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((site + "\u0001" + v.getViewId() + "\u0001" + v.getPanelPath() + "\u0001" + v.getPanelTitle())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Site> sites() {
        return sites;
    }

    public Dashboard dashboard(String id) {
        return dashboards.get(id);
    }

    public Panel panel(String id) {
        return panels.get(id);
    }

    public Line line(String id) {
        return lines.get(id);
    }

    public List<Place> places(String lineId) {
        return places.getOrDefault(lineId, List.of());
    }

    public int lineCount() {
        return lines.size();
    }
}
