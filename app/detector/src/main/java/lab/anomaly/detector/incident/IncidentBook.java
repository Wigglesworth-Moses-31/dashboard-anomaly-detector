package lab.anomaly.detector.incident;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.contracts.v1.Link;
import lab.anomaly.contracts.v1.View;
import lab.anomaly.detector.line.LineWatch;
import lab.anomaly.detector.rules.LineFacts;

/**
 * The open incidents (design D6). A hash map from group key to open incident: a line that starts spiking or goes
 * silent joins its group's open incident or opens one. An incident closes when every line in it has calmed.
 * Pure logic: no clock, no database; each call returns the changes to save and announce.
 */
public final class IncidentBook {

    /** One change to save and announce. {@code occurredMs} is data time; {@code arrivedMs} is when the deciding value reached Kafka. */
    public record Change(EventType type, Incident incident, long sequence, long occurredMs, long arrivedMs) {
    }

    static final long UPDATE_EVERY_MS = 30_000;       // at most one UPDATED for a growing peak per 30 s of data time
    static final double PEAK_GROWTH = 0.10;            // ...and only when the distance from normal grew by 10%
    static final long LINK_MARGIN_MS = 5 * 60_000;

    private final Supplier<String> ids;
    private final Map<String, Open> byGroup = new LinkedHashMap<>();
    private final Map<String, Open> bySeries = new HashMap<>();
    private final Map<String, Open> recentlyClosed = new HashMap<>();   // group key -> its last closed incident
    private long calmMs = 120_000;
    private List<String> groupBy = List.of();
    private Set<String> upstreamTags = Set.of();
    private final Set<String> sitesSeen = new java.util.HashSet<>();     // add-multi-site design M9

    public IncidentBook(Supplier<String> ids) {
        this.ids = ids;
    }

    public void configure(List<String> groupByTags, Collection<String> upstream, long calmPeriodMs) {
        this.calmMs = calmPeriodMs;
        this.groupBy = List.copyOf(groupByTags);
        this.upstreamTags = Set.copyOf(upstream);
    }

    // ---- grouping ---------------------------------------------------------------------------------------------

    /**
     * The group a line belongs to: the first tag of the grouping list it has, else its dashboard. The key starts with
     * the line's site (add-multi-site design M9), so the same API in two regions is two incidents, never one.
     */
    public record Group(String key, String label, boolean upstream, boolean byDashboard, String site) {
        public Group(String key, String label, boolean upstream, boolean byDashboard) {
            this(key, label, upstream, byDashboard, "");
        }
    }

    public Group groupOf(LineFacts line) {
        String site = line.site() == null ? "" : line.site();
        sitesSeen.add(site);                                                 // O(1); a handful of sites at most
        for (String tag : groupBy) {
            String value = line.tags().get(tag);
            if (value != null && !value.isBlank() && !value.equalsIgnoreCase("N/A")) {
                return new Group(site + "|" + tag + "=" + value, value, upstreamTags.contains(tag), false, site);
            }
        }
        View v = line.primaryView();
        String title = v.getViewTitle().isBlank() ? v.getViewId() : v.getViewTitle();
        return new Group(site + "|dashboard=" + v.getViewId(), title, false, true, site);
    }

    /** Remembers a watched line's site. O(1). */
    public void noteSite(String site) {
        sitesSeen.add(site == null ? "" : site);
    }

    /** True once lines from two or more sites were seen: titles then start with the site's label ("EU · ..."). */
    public boolean multiSite() {
        return sitesSeen.size() > 1;
    }

    // ---- events from lines ------------------------------------------------------------------------------------

    public List<Change> on(LineWatch line, LineWatch.Event event, long ts, long arrivedMs) {
        String id = line.facts().seriesId();
        Open member = bySeries.get(id);
        return switch (event) {
            case SPIKE_STARTED, SILENT -> join(line, ts, arrivedMs);
            case RESPIKED -> member == null ? join(line, ts, arrivedMs) : respike(member, line, ts, arrivedMs);
            case PEAK -> member == null ? List.of() : peak(member, line, ts, arrivedMs);
            case CALMED -> member == null ? List.of() : calmed(member, line, arrivedMs);
            default -> List.of();
        };
    }

    private List<Change> join(LineWatch line, long ts, long arrivedMs) {
        Group group = groupOf(line.facts());
        Open open = byGroup.get(group.key());
        boolean opened = false;
        boolean reopened = false;
        if (open == null) {
            // Lines of one group are read in data-time order per line only, so a line can report a spike from a
            // moment before its group's incident was closed by another line's later values. A spike that starts
            // within one calm period of the close belongs to that incident: reopen it instead of opening another.
            Open closed = recentlyClosed.get(group.key());
            if (closed != null && line.spikeStart() <= closed.endedMs + calmMs) {
                open = closed;
                recentlyClosed.remove(group.key());
                open.endedMs = 0;
                open.members.values().forEach(x -> bySeries.put(x.seriesId, closed));
                reopened = true;
            } else {
                open = new Open(ids.get(), group, line.spikeStart(), this::multiSite);
                opened = true;
            }
            byGroup.put(group.key(), open);
        }
        Member m = open.members.get(line.facts().seriesId());
        boolean newMember = m == null;
        boolean news = !newMember && (m.calmed || !m.reason.equals(line.reason()));
        if (m == null) {
            m = new Member(line.facts());
            open.members.put(line.facts().seriesId(), m);
            open.addLink(line.facts());
        }
        m.calmed = false;
        m.signal = line.rule().signal();
        m.reason = line.reason();
        m.normal = line.normalAtSpike();
        m.peak = line.peak();
        m.peakTs = line.peakTs();
        m.announcedPeak = m.peak;
        bySeries.put(line.facts().seriesId(), open);
        if (opened || newMember || reopened) {
            open.lastUpdateTs = ts;
            open.dirty = false;
            return List.of(open.change(opened ? EventType.EVENT_TYPE_OPENED : EventType.EVENT_TYPE_UPDATED, ts, arrivedMs));
        }
        if (news) {
            open.dirty = true;                       // a line changed reason: announced at most every 30 s
        }
        return flush(open, ts, arrivedMs);
    }

    private List<Change> respike(Open open, LineWatch line, long ts, long arrivedMs) {
        Member m = open.members.get(line.facts().seriesId());
        boolean wasCalm = m.calmed;
        m.calmed = false;
        m.reason = line.reason();
        m.peak = line.peak();
        m.peakTs = line.peakTs();
        if (wasCalm) {
            m.announcedPeak = m.peak;
            open.dirty = true;
            return flush(open, ts, arrivedMs);
        }
        return peak(open, line, ts, arrivedMs);
    }

    private List<Change> peak(Open open, LineWatch line, long ts, long arrivedMs) {
        Member m = open.members.get(line.facts().seriesId());
        m.peak = line.peak();
        m.peakTs = line.peakTs();
        double announced = Math.abs(m.announcedPeak - m.normal);
        double now = Math.abs(m.peak - m.normal);
        if (Double.isNaN(announced) || now >= announced * (1 + PEAK_GROWTH)) {
            open.dirty = true;
            if (ts - open.lastUpdateTs >= UPDATE_EVERY_MS) {
                m.announcedPeak = m.peak;
            }
        }
        return flush(open, ts, arrivedMs);
    }

    /** Announces pending news (reason changes, grown peaks) at most once per 30 s of data time. */
    private List<Change> flush(Open open, long ts, long arrivedMs) {
        if (!open.dirty || ts - open.lastUpdateTs < UPDATE_EVERY_MS) {
            return List.of();
        }
        open.dirty = false;
        open.lastUpdateTs = ts;
        return List.of(open.change(EventType.EVENT_TYPE_UPDATED, ts, arrivedMs));
    }

    private List<Change> calmed(Open open, LineWatch line, long arrivedMs) {
        Member m = open.members.get(line.facts().seriesId());
        m.calmed = true;
        m.calmSince = line.calmSince();
        for (Member other : open.members.values()) {
            if (!other.calmed) {
                return List.of();
            }
        }
        long ended = open.members.values().stream().mapToLong(x -> x.calmSince).max().orElse(m.calmSince);
        open.endedMs = ended;
        byGroup.remove(open.group.key());
        open.members.keySet().forEach(bySeries::remove);
        recentlyClosed.put(open.group.key(), open);
        return List.of(open.change(EventType.EVENT_TYPE_CLOSED, line.lastTs(), arrivedMs));
    }

    // ---- restart ----------------------------------------------------------------------------------------------

    /** Loads open incidents saved before a restart, with their last sequence numbers (design D7). */
    public void restore(Incident incident, long sequence) {
        String key = incident.getGroupKey();
        int bar = key.indexOf('|');
        String site = bar >= 0 ? key.substring(0, bar) : "";                 // keys saved before add-multi-site have none
        Group group = new Group(key, labelOf(incident), incident.getUpstream(),
                key.substring(bar + 1).startsWith("dashboard="), site);
        if (!site.isEmpty()) {
            sitesSeen.add(site);
        }
        Open open = new Open(incident.getIncidentId(), group, incident.getStartedMs(), this::multiSite);
        open.sequence = sequence;
        open.lastUpdateTs = incident.getStartedMs();
        for (Link link : incident.getLinksList()) {
            open.links.put(link.getLabel(), link);
        }
        for (AffectedSeries s : incident.getSeriesList()) {
            Member m = new Member(s);
            open.members.put(s.getSeriesId(), m);
            bySeries.put(s.getSeriesId(), open);
        }
        byGroup.put(group.key(), open);
    }

    private static String labelOf(Incident incident) {
        String key = incident.getGroupKey();
        key = key.substring(key.indexOf('|') + 1);
        int eq = key.indexOf('=');
        if (key.startsWith("dashboard=") && !incident.getLinksList().isEmpty()) {
            return incident.getLinks(0).getLabel();
        }
        return eq >= 0 ? key.substring(eq + 1) : key;
    }

    /** What a restarted line needs to continue an open incident, or null. */
    public AffectedSeries memberOf(String seriesId) {
        Open open = bySeries.get(seriesId);
        return open == null ? null : open.members.get(seriesId).toProto();
    }

    // ---- read-only --------------------------------------------------------------------------------------------

    public List<Incident> openIncidents() {
        List<Incident> out = new ArrayList<>();
        for (Open o : byGroup.values()) {
            out.add(o.toProto(IncidentState.INCIDENT_STATE_OPEN, o.lastUpdateTs));
        }
        return out;
    }

    // ---- inner state ------------------------------------------------------------------------------------------

    private static final class Member {
        final String seriesId;
        final String metric;
        final Map<String, String> tags;
        final String unit;
        final String dashboardTitle;
        final String panelTitle;
        String signal = "";
        String reason = "";
        double normal = Double.NaN;
        double peak = Double.NaN;
        double announcedPeak = Double.NaN;
        long peakTs;
        boolean calmed;
        long calmSince;

        Member(LineFacts f) {
            seriesId = f.seriesId();
            metric = f.metric();
            tags = f.tags();
            unit = f.unit();
            dashboardTitle = f.primaryView().getViewTitle();
            panelTitle = f.primaryView().getPanelTitle();
        }

        Member(AffectedSeries s) {
            seriesId = s.getSeriesId();
            metric = s.getMetric();
            tags = Map.copyOf(s.getGroupTagsMap());
            unit = s.getUnit();
            dashboardTitle = s.getDashboardTitle();
            panelTitle = s.getPanelTitle();
            signal = s.getSignal();
            reason = s.getReason();
            normal = s.getNormalValue();
            peak = s.getPeakValue();
            announcedPeak = peak;
            peakTs = s.getPeakTsMs();
        }

        AffectedSeries toProto() {
            return AffectedSeries.newBuilder().setSeriesId(seriesId).setMetric(metric).putAllGroupTags(tags)
                    .setPeakValue(Double.isNaN(peak) ? 0 : peak).setPeakTsMs(peakTs)
                    .setNormalValue(Double.isNaN(normal) ? 0 : normal).setUnit(unit).setSignal(signal).setReason(reason)
                    .setDashboardTitle(dashboardTitle).setPanelTitle(panelTitle).build();
        }
    }

    private static final class Open {
        final String id;
        final Group group;
        final long startedMs;
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, Link> links = new LinkedHashMap<>();
        final Map<String, LineFacts> linkFacts = new LinkedHashMap<>();
        long sequence;
        long lastUpdateTs;
        long endedMs;
        boolean dirty;

        final java.util.function.BooleanSupplier multiSite;

        Open(String id, Group group, long startedMs, java.util.function.BooleanSupplier multiSite) {
            this.id = id;
            this.group = group;
            this.startedMs = startedMs;
            this.multiSite = multiSite;
        }

        void addLink(LineFacts f) {
            View v = f.primaryView();
            if (!v.getViewId().isBlank()) {
                linkFacts.putIfAbsent(v.getViewId(), f);
            }
        }

        Change change(EventType type, long ts, long arrivedMs) {
            sequence++;
            IncidentState state = type == EventType.EVENT_TYPE_CLOSED ? IncidentState.INCIDENT_STATE_CLOSED : IncidentState.INCIDENT_STATE_OPEN;
            return new Change(type, toProto(state, ts), sequence, ts, arrivedMs);
        }

        Incident toProto(IncidentState state, long ts) {
            Incident.Builder b = Incident.newBuilder().setIncidentId(id).setState(state).setTitle(title())
                    .setStartedMs(startedMs).setEndedMs(state == IncidentState.INCIDENT_STATE_CLOSED ? endedMs : 0)
                    .setGroupKey(group.key()).setUpstream(group.upstream());
            members.values().forEach(m -> b.addSeries(m.toProto()));
            long to = (state == IncidentState.INCIDENT_STATE_CLOSED ? endedMs : ts) + LINK_MARGIN_MS;
            Map<String, Link> all = new LinkedHashMap<>(links);
            linkFacts.forEach((viewId, f) -> {
                String url = f.dashboardUrl(viewId, startedMs - LINK_MARGIN_MS, to);
                if (!url.isEmpty()) {
                    String label = f.primaryView().getViewTitle().isBlank() ? viewId : f.primaryView().getViewTitle();
                    if (multiSite.getAsBoolean() && f.site() != null && !f.site().isBlank()) {
                        label = lab.anomaly.contracts.Sites.label(f.site()) + " · " + label;     // "EU · Shop" (M9)
                    }
                    all.put(label, Link.newBuilder().setLabel(label).setUrl(url).build());
                }
            });
            b.addAllLinks(all.values());
            return b.build();
        }

        /** e.g. "upstream payments: success rate down, latency up". */
        String title() {
            Set<String> parts = new LinkedHashSet<>();
            for (Member m : members.values()) {
                parts.add(m.signal.replace('-', ' ') + " " + m.reason);
            }
            String site = multiSite.getAsBoolean() && !group.site().isEmpty()
                    ? lab.anomaly.contracts.Sites.label(group.site()) + " · " : "";
            return site + (group.upstream() ? "upstream " : "") + group.label() + ": " + String.join(", ", parts);
        }
    }
}
