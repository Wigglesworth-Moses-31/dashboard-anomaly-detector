package lab.anomaly.notifier.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.Incident;

/**
 * A line's past (add-incident-history design H4): for every metric line, its incidents of the last 2 days, built from
 * the incident events the Notifier already reads. A read model (a projection of the event stream): no database, and
 * rebuilt the same way after a restart, because the events are read again from the beginning (Kafka keeps 2 days).
 *
 * <ul>
 *   <li>{@code line id → (incident id → occurrence)}: each event replaces its incident's entry (latest wins), O(lines
 *       of the incident) per event.</li>
 *   <li>A read sorts the line's few entries, O(k log k); a sweep drops entries that ended before the window, O(n).</li>
 * </ul>
 * Thread-safe: events arrive on the reader thread, pages read from web threads.
 */
public final class LineHistory {

    public static final long WINDOW_MS = 2 * 24 * 3_600_000L;

    /** One incident of one line, as the page shows it. {@code reason} is the human reason, "" until one is given. */
    public record Occurrence(String incidentId, String title, long startedMs, long endedMs, String signal, String direction,
                             double peak, double normal, String unit, String reason) {}

    /** What the page asks for: the last incident before the current one, and all of the window, newest first. */
    public record Past(String line, Occurrence last, List<Occurrence> all, long windowMs) {}

    private final Map<String, LinkedHashMap<String, Occurrence>> byLine = new HashMap<>();

    public synchronized void accept(Incident incident) {
        for (AffectedSeries s : incident.getSeriesList()) {
            byLine.computeIfAbsent(s.getSeriesId(), k -> new LinkedHashMap<>())
                    .put(incident.getIncidentId(), new Occurrence(incident.getIncidentId(), incident.getTitle(),
                            incident.getStartedMs(), incident.getEndedMs(), s.getSignal(), s.getReason(), s.getPeakValue(),
                            s.getNormalValue(), s.getUnit(), reasonOf(incident, s)));
        }
    }

    /**
     * The line's own reason (add-line-reasons): its explanation, "BAU" for business-as-usual lines, else the incident's
     * reason (incidents closed before reasons were per line); "" until one is given.
     */
    static String reasonOf(Incident incident, AffectedSeries s) {
        if (s.getBau()) {
            return "BAU";
        }
        return s.getExplanation().isEmpty() ? incident.getReason() : s.getExplanation();
    }

    /** The line's past within the window before {@code nowMs}; {@code current} (may be null) is left out of "last". */
    public synchronized Past past(String line, String current, long nowMs) {
        List<Occurrence> all = new ArrayList<>(byLine.getOrDefault(line, new LinkedHashMap<>()).values());
        all.removeIf(o -> !inWindow(o, nowMs));
        all.sort(Comparator.comparingLong(Occurrence::startedMs).reversed());
        // "Last time before this": the newest incident that started before the current one (not just any other one:
        // the current incident may be an older one opened from the closed list)
        long currentStart = all.stream().filter(o -> o.incidentId().equals(current)).mapToLong(Occurrence::startedMs)
                .findFirst().orElse(Long.MAX_VALUE);
        Occurrence last = all.stream().filter(o -> !o.incidentId().equals(current) && o.startedMs() < currentStart)
                .findFirst().orElse(null);
        return new Past(line, last, List.copyOf(all), WINDOW_MS);
    }

    /** Drops incidents that ended before the window; open ones stay. Returns how many were dropped. */
    public synchronized int sweep(long nowMs) {
        int dropped = 0;
        for (Iterator<Map.Entry<String, LinkedHashMap<String, Occurrence>>> lines = byLine.entrySet().iterator(); lines.hasNext(); ) {
            LinkedHashMap<String, Occurrence> entries = lines.next().getValue();
            int before = entries.size();
            entries.values().removeIf(o -> !inWindow(o, nowMs));
            dropped += before - entries.size();
            if (entries.isEmpty()) {
                lines.remove();
            }
        }
        return dropped;
    }

    public synchronized int lines() {
        return byLine.size();
    }

    private static boolean inWindow(Occurrence o, long nowMs) {
        return o.endedMs() == 0 || o.endedMs() >= nowMs - WINDOW_MS;
    }
}
