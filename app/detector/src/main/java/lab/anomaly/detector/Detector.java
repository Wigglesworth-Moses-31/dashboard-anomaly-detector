package lab.anomaly.detector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.View;
import lab.anomaly.detector.incident.IncidentBook;
import lab.anomaly.detector.incident.IncidentBook.Change;
import lab.anomaly.detector.line.LineWatch;
import lab.anomaly.detector.rules.Direction;
import lab.anomaly.detector.rules.LineFacts;
import lab.anomaly.detector.rules.RuleBook;

/**
 * The detection core (design D1-D6): line descriptions, one {@link LineWatch} per line, the rules, dashboard
 * watermarks for silence, and the incident book. No Kafka, no database, no clock: the engine feeds it values and
 * saves the changes it returns. Owned by one thread.
 */
public final class Detector {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Detector.class);

    private final IncidentBook book;
    private final Map<String, LineFacts> facts = new HashMap<>();
    private final Map<String, LineWatch> lines = new HashMap<>();
    private final Map<String, Long> watermarks = new HashMap<>();          // dashboard id -> newest value time
    private final Map<String, Long> queryWatermarks = new HashMap<>();     // panel query id -> newest value time
    private final Map<String, Integer> linesPerQuery = new HashMap<>();
    private final Map<String, String> dashboardTitles = new HashMap<>();
    private final TreeSet<String> notArriving = new TreeSet<>();
    private DetectorSettings settings;
    private RuleBook rules;
    private LineWatch.Limits limits;
    private long valuesWithoutDescription;

    public Detector(Supplier<String> ids) {
        this.book = new IncidentBook(ids);
    }

    /** Applies a settings version: rules are matched again lazily, per line, on its next value. */
    public void configure(DetectorSettings s, long version) {
        this.settings = s;
        this.rules = new RuleBook(s, version);
        this.limits = new LineWatch.Limits(s.history().toMillis(), s.minHistory().toMillis(), s.calmPeriod().toMillis());
        book.configure(s.groupBy(), s.upstreamTags(), s.calmPeriod().toMillis());
    }

    public void describe(SeriesDescriptor d) {
        LineFacts f = LineFacts.of(d);
        book.noteSite(f.site());                     // sites are known from descriptions, before any spike (M9)
        facts.put(f.seriesId(), f);
        for (View v : f.views()) {
            dashboardTitles.put(v.getViewId(), v.getViewTitle());
        }
        LineWatch line = lines.get(f.seriesId());
        if (line != null) {
            line.describe(f);
        }
    }

    public boolean knows(String seriesId) {
        return facts.containsKey(seriesId);
    }

    /** True when the line is described and its history has not been filled yet (warm start, design D3). */
    public boolean needsWarm(String seriesId) {
        LineWatch line = lines.get(seriesId);
        return facts.containsKey(seriesId) && (line == null || !line.warmed());
    }

    public void warm(String seriesId, long[] times, double[] values) {
        LineWatch line = line(seriesId);
        if (line != null) {
            line.warm(times, values, limits.historyMs());
        }
    }

    /** Judges one value; returns the incident changes it caused. */
    public List<Change> accept(String seriesId, long ts, double value, boolean partial, long arrivedMs) {
        if (partial) {
            return List.of();                                  // never judged, never in the history (spec)
        }
        LineWatch line = line(seriesId);
        if (line == null) {
            valuesWithoutDescription++;
            return List.of();
        }
        if (line.needsRule(rules.version())) {
            line.useRule(rules.ruleFor(line.facts()), rules.version());
        }
        if (!line.warmed()) {
            line.markWarmed();
        }
        LineWatch.Event event = line.accept(ts, value, limits);
        if (event == LineWatch.Event.SKIPPED) {
            return List.of();
        }
        if (log.isDebugEnabled() && event != LineWatch.Event.NONE && event != LineWatch.Event.WARMING) {
            log.debug("Line {} {} at {} value {} normal {}", seriesId, event, ts, value, line.normalNow());
        }
        for (View v : line.facts().views()) {
            watermarks.merge(v.getViewId(), ts, Math::max);
        }
        queryWatermarks.merge(line.facts().queryId(), ts, Math::max);
        return book.on(line, event, ts, arrivedMs);
    }

    /**
     * Silence (design D2): a dashboard's lines are checked against its watermark. A dashboard whose watermark is
     * {@code silence-after} behind the newest dashboard is "not arriving": a collection problem, not incidents.
     */
    public List<Change> checkSilence(long arrivedMs) {
        long newest = watermarks.values().stream().mapToLong(Long::longValue).max().orElse(Long.MIN_VALUE);
        long silenceMs = settings.silenceAfter().toMillis();
        List<Change> changes = new ArrayList<>();
        notArriving.clear();
        for (Map.Entry<String, Long> e : watermarks.entrySet()) {
            if (e.getValue() < newest - silenceMs) {
                notArriving.add(e.getKey());
            }
        }
        for (LineWatch line : lines.values()) {
            String dashboard = line.facts().primaryView().getViewId();
            // Silence is judged against the other lines of the same panel query: they come back in the same answer
            // from the source, so their arrival times are comparable. A query with one line cannot show silence.
            Long wm = queryWatermarks.get(line.facts().queryId());
            if (wm == null || linesPerQuery.getOrDefault(line.facts().queryId(), 0) < 2 || notArriving.contains(dashboard)
                    || line.rule() == null || settings.silenceSkipSignals().contains(line.rule().signal())) {
                continue;
            }
            LineWatch.Event event = line.checkSilence(wm, silenceMs, settings.silenceMinPresence(), limits.minHistoryMs());
            if (event == LineWatch.Event.SILENT) {
                log.debug("Line {} SILENT at watermark {}", line.facts().seriesId(), wm);
                changes.addAll(book.on(line, event, wm, arrivedMs));
            }
        }
        return changes;
    }

    /** Loads an incident that was open before a restart. Its lines continue in CALMING when their values arrive. */
    public void restore(Incident incident, long sequence) {
        book.restore(incident, sequence);
    }

    private LineWatch line(String seriesId) {
        LineWatch line = lines.get(seriesId);
        if (line != null) {
            return line;
        }
        LineFacts f = facts.get(seriesId);
        if (f == null) {
            return null;
        }
        line = new LineWatch(f, limits.historyMs());
        linesPerQuery.merge(f.queryId(), 1, Integer::sum);
        AffectedSeries member = book.memberOf(seriesId);
        if (member != null) {
            boolean silent = "silent".equals(member.getReason());
            Direction dir = "down".equals(member.getReason()) ? Direction.DOWN : silent ? null : Direction.UP;
            line.resumeInIncident(member.getNormalValue(), member.getPeakValue(), member.getPeakTsMs(), dir, silent);
        }
        lines.put(seriesId, line);
        return line;
    }

    // ---- health view (design D10) -----------------------------------------------------------------------------

    public record OpenIncident(String id, String title, long startedMs) {
    }

    /** An immutable snapshot for the health page, built on the detection thread. */
    public record Snapshot(int lines, int warming, Map<String, Integer> linesPerRule, Map<String, Integer> generalByPanel,
                        int groupedByDashboard, List<String> dashboardsNotArriving, Map<String, Integer> states,
                        List<OpenIncident> openIncidents, long valuesWithoutDescription) {
    }

    public Snapshot view() {
        Map<String, Integer> perRule = new TreeMap<>();
        Map<String, Integer> general = new TreeMap<>();
        Map<String, Integer> states = new TreeMap<>();
        int warming = 0;
        int byDashboard = 0;
        for (LineWatch line : lines.values()) {
            if (line.rule() == null) {
                continue;
            }
            perRule.merge(line.rule().name(), 1, Integer::sum);
            states.merge(line.state().name(), 1, Integer::sum);
            if (line.rule().isGeneral()) {
                View v = line.facts().primaryView();
                general.merge(v.getViewTitle() + " / " + v.getPanelTitle(), 1, Integer::sum);
            }
            if (!line.judgeable(limits.minHistoryMs())) {
                warming++;
            }
            if (book.groupOf(line.facts()).byDashboard()) {
                byDashboard++;
            }
        }
        List<String> missing = notArriving.stream().map(id -> dashboardTitles.getOrDefault(id, id)).toList();
        List<OpenIncident> open = book.openIncidents().stream()
                .map(i -> new OpenIncident(i.getIncidentId(), i.getTitle(), i.getStartedMs())).toList();
        return new Snapshot(lines.size(), warming, perRule, general, byDashboard, missing, states, open, valuesWithoutDescription);
    }

    /** For tests: the line's state machine. */
    LineWatch lineForTest(String seriesId) {
        return lines.get(seriesId);
    }
}
