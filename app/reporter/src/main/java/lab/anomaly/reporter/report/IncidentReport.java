package lab.anomaly.reporter.report;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.Link;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.contracts.v1.View;

/** The incident report (design R5): headline, table, one section per incident, patterns, unclassified lines. */
public final class IncidentReport extends ReportTemplate {

    static final int TOP = 10;
    static final int MIN_POINTS = 30;
    static final double COMPLETE = 0.90;

    // ---- headline -------------------------------------------------------------------------------------------

    @Override
    protected void headline(StringBuilder md, ReportData d) {
        var w = d.window();
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).withZone(w.zone());
        md.append("# Incident report · ").append(day.format(Instant.ofEpochMilli(w.startMs()))).append(" · ").append(w.zone().getId())
                .append(" (").append(hm(w.startMs(), d)).append("–").append(w.wholeDay() ? "24:00" : hm(w.endMs(), d)).append(")\n\n");
        List<Incident> inc = d.incidents();
        if (inc.isEmpty()) {
            md.append("**No incidents** in this window");
        } else {
            md.append("**").append(inc.size()).append(inc.size() == 1 ? " incident" : " incidents").append("**");
        }
        md.append(d.previousCount() == null ? "" : " (previous window " + d.previousCount() + ")");
        Incident longest = null;
        long longestMs = -1;
        int open = 0;
        for (Incident i : inc) {
            if (stillOpen(i, d)) {
                open++;
            } else if (i.getEndedMs() - i.getStartedMs() > longestMs) {
                longestMs = i.getEndedMs() - i.getStartedMs();
                longest = i;
            }
        }
        if (longest != null) {
            md.append(" · longest ").append(duration(longestMs)).append(" (").append(esc(label(longest))).append(")");
        }
        if (open > 0) {
            md.append(" · ").append(open).append(" still open at the window's end");
        }
        md.append("\n\n");
        long received = 0;
        for (SeriesSummary s : d.summaries()) {
            received += s.getCount();
        }
        long expected = (long) d.summaries().size() * ((w.endMs() - w.startMs()) / d.stepMs());
        double share = expected == 0 ? 0 : received / (double) expected;
        md.append("Data: ").append(d.summaries().size()).append(" lines, ")
                .append(String.format(Locale.ROOT, "%.1f", share * 100)).append("% of expected points");
        if (share < COMPLETE) {
            md.append(" — **data incomplete** (for example the Collector was stopped or the data is no longer kept)");
        }
        md.append("\n\n");
    }

    // ---- table ----------------------------------------------------------------------------------------------

    @Override
    protected void incidentTable(StringBuilder md, ReportData d) {
        if (d.incidents().isEmpty()) {
            return;
        }
        md.append("| Start | End | Duration | Incident |\n|---|---|---|---|\n");
        for (Incident i : d.incidents()) {
            md.append("| ").append(hms(i.getStartedMs(), d)).append(" | ")
                    .append(stillOpen(i, d) ? "open" : hms(i.getEndedMs(), d)).append(" | ")
                    .append(stillOpen(i, d) ? "–" : duration(i.getEndedMs() - i.getStartedMs())).append(" | ")
                    .append(esc(i.getTitle())).append(" |\n");
        }
        md.append('\n');
    }

    // ---- one section per incident ----------------------------------------------------------------------------

    @Override
    protected void incidentSections(StringBuilder md, ReportData d) {
        for (Incident i : d.incidents()) {
            md.append("## ").append(esc(i.getTitle())).append(" (").append(hms(i.getStartedMs(), d)).append("–")
                    .append(stillOpen(i, d) ? "open" : hms(i.getEndedMs(), d)).append(")\n\n");
            md.append("| Line | Peak | Normal | How far | Panel |\n|---|---|---|---|---|\n");
            for (AffectedSeries s : i.getSeriesList()) {
                md.append("| ").append(esc(s.getSignal().replace('-', ' ') + " " + s.getReason())).append(" | ")
                        .append("silent".equals(s.getReason()) ? "—" : value(s.getPeakValue(), s.getUnit())).append(" | ")
                        .append(value(s.getNormalValue(), s.getUnit())).append(" | ")
                        .append(howFar(s)).append(" | ")
                        .append(esc(s.getPanelTitle())).append(tags(s.getGroupTagsMap())).append(" |\n");
            }
            md.append('\n');
            for (Link l : i.getLinksList()) {
                md.append("[").append(esc(l.getLabel())).append(" at ").append(hm(i.getStartedMs(), d)).append("](").append(l.getUrl()).append(")  \n");
            }
            md.append('\n');
        }
    }

    // ---- patterns -------------------------------------------------------------------------------------------

    @Override
    protected void brokeMostOften(StringBuilder md, ReportData d) {
        if (d.incidents().isEmpty()) {
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Incident i : d.incidents()) {
            counts.merge(label(i), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        md.append("## Broke most often\n\n");
        List<String> parts = new ArrayList<>();
        for (var e : sorted) {
            parts.add(esc(e.getKey()) + " " + e.getValue());
        }
        md.append(String.join(" · ", parts)).append("\n\n");
    }

    /** Top 10 lines by highest value over average, with a min-heap of size 10: O(n log 10). */
    @Override
    protected void movedMost(StringBuilder md, ReportData d) {
        record Moved(SeriesSummary s, double ratio) {
        }
        PriorityQueue<Moved> heap = new PriorityQueue<>(Comparator.comparingDouble(Moved::ratio));
        for (SeriesSummary s : d.summaries()) {
            if (s.getCount() < MIN_POINTS || s.getMean() <= 0) {
                continue;
            }
            heap.add(new Moved(s, s.getMax() / s.getMean()));
            if (heap.size() > TOP) {
                heap.poll();                                          // drop the smallest of the 11
            }
        }
        if (heap.isEmpty()) {
            return;
        }
        List<Moved> top = new ArrayList<>(heap);
        top.sort(Comparator.comparingDouble(Moved::ratio).reversed());
        Map<String, SeriesDescriptor> byId = new HashMap<>();
        for (SeriesDescriptor l : d.lines()) {
            byId.put(l.getSeriesId(), l);
        }
        boolean multiSite = multiSite(d);
        md.append("## Lines that moved most\n\nHighest value against the window's average.\n\n| Line | Highest | Average | Times | Panel |\n|---|---|---|---|---|\n");
        for (Moved m : top) {
            SeriesDescriptor l = byId.get(m.s().getSeriesId());
            String unit = l == null ? "" : l.getUnit();
            md.append("| ").append(esc(l == null ? m.s().getSeriesId() : l.getMetric() + tags(l.getGroupTagsMap()))).append(" | ")
                    .append(value(m.s().getMax(), unit)).append(" | ").append(value(m.s().getMean(), unit)).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f×", m.ratio())).append(" | ")
                    .append(l == null || l.getViewsCount() == 0 ? "" : esc(site(l, multiSite) + l.getViews(0).getPanelTitle())).append(" |\n");
        }
        md.append('\n');
    }

    @Override
    protected void unclassified(StringBuilder md, ReportData d) {
        java.util.Set<String> inWindow = new java.util.HashSet<>();
        for (SeriesSummary s : d.summaries()) {
            inWindow.add(s.getSeriesId());                            // only lines that had values in this window
        }
        Map<String, Integer> byPanel = new TreeMap<>();
        boolean multiSite = multiSite(d);
        for (SeriesDescriptor l : d.lines()) {
            if (l.getKind() == Kind.KIND_UNKNOWN && inWindow.contains(l.getSeriesId())) {
                View v = l.getViewsCount() == 0 ? View.getDefaultInstance() : l.getViews(0);
                byPanel.merge(site(l, multiSite) + v.getViewTitle() + " / " + v.getPanelTitle(), 1, Integer::sum);
            }
        }
        if (byPanel.isEmpty()) {
            return;
        }
        md.append("## Lines not classified\n\nTheir kind is unknown to the Collector; a rule in `collector.yml` can set it.\n\n| Dashboard / panel | Lines |\n|---|---|\n");
        byPanel.forEach((panel, n) -> md.append("| ").append(esc(panel)).append(" | ").append(n).append(" |\n"));
        md.append('\n');
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** True when the window's lines come from two or more Datadog sites (add-multi-site). One pass, O(lines). */
    static boolean multiSite(ReportData d) {
        java.util.Set<String> sites = new java.util.HashSet<>();
        for (SeriesDescriptor l : d.lines()) {
            sites.add(l.getSource().getInstance());
            if (sites.size() > 1) {
                return true;
            }
        }
        return false;
    }

    /** "EU · " when several sites are watched, else "". */
    private static String site(SeriesDescriptor l, boolean multiSite) {
        return multiSite && !l.getSource().getInstance().isBlank() ? lab.anomaly.contracts.Sites.label(l.getSource().getInstance()) + " · " : "";
    }

    static boolean stillOpen(Incident i, ReportData d) {
        return i.getState() != IncidentState.INCIDENT_STATE_CLOSED || i.getEndedMs() > d.window().endMs();
    }

    /** The group part of a title: everything before the last ": " (labels may contain ": "). */
    static String label(Incident i) {
        String t = i.getTitle();
        int at = t.lastIndexOf(": ");
        return at > 0 ? t.substring(0, at) : t;
    }

    static String howFar(AffectedSeries s) {
        if ("silent".equals(s.getReason())) {
            return "silent";
        }
        double peak = s.getPeakValue();
        double normal = s.getNormalValue();
        if ("percent".equals(s.getUnit()) || s.getSignal().contains("rate")) {
            double pts = peak - normal;
            return (pts >= 0 ? "+" : "−") + String.format(Locale.ROOT, "%.1f", Math.abs(pts)) + " pts";
        }
        if (normal <= 0) {
            return "new";
        }
        return String.format(Locale.ROOT, "%.1f×", peak >= normal ? peak / normal : normal / Math.max(peak, 1e-9));
    }

    static String value(double v, String unit) {
        String n = number(v);
        return switch (unit == null ? "" : unit) {
            case "ms", "millisecond" -> n + " ms";
            case "percent" -> n + "%";
            case "second" -> v < 1 ? number(v * 1000) + " ms" : n + " s";
            default -> n;
        };
    }

    static String number(double v) {
        double a = Math.abs(v);
        if (a >= 100) {
            return String.format(Locale.ENGLISH, "%,.0f", v);
        }
        if (a >= 1) {
            return String.format(Locale.ENGLISH, "%,.1f", v);
        }
        return a == 0 ? "0" : String.format(Locale.ENGLISH, "%.3g", v);
    }

    static String duration(long ms) {
        long s = Math.max(0, Math.round(ms / 1000.0));
        if (s < 60) {
            return s + " s";
        }
        long m = s / 60;
        return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }

    static String tags(Map<String, String> tags) {
        if (tags.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        new TreeMap<>(tags).forEach((k, v) -> parts.add(k + "=" + v));
        return " (" + String.join(", ", parts) + ")";
    }

    /** Escapes what Markdown would otherwise read as table or emphasis syntax. */
    static String esc(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("|", "\\|").replace("*", "\\*").replace("_", "\\_")
                .replace("<", "&lt;").replace("\n", " ");
    }

    private static String hms(long ms, ReportData d) {
        return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(d.window().zone()).format(Instant.ofEpochMilli(ms));
    }

    private static String hm(long ms, ReportData d) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(d.window().zone()).format(Instant.ofEpochMilli(ms));
    }
}
