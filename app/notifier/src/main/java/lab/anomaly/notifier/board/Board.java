package lab.anomaly.notifier.board;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.IncidentState;

/**
 * The day's incidents, one current state each (design N2). Not thread-safe: the hub calls it under its lock.
 *
 * <ul>
 *   <li>Hash map incident id -> entry; an event is accepted only with a higher sequence (repeats and late older
 *       events change nothing). O(1).</li>
 *   <li>Day buckets: a sorted map day -> incident ids. At midnight every bucket before today loses its closed
 *       incidents in one go. O(removed).</li>
 *   <li>The JSON of each accepted event is made once by the caller and kept: every page gets the same string.</li>
 * </ul>
 */
public final class Board {

    /** One incident's current state. {@code firstReceivedMs} is when the Notifier first heard of it. */
    public record Entry(String id, IncidentEvent event, String json, long firstReceivedMs, LocalDate day) {
        public boolean open() {
            return event.getIncident().getState() != IncidentState.INCIDENT_STATE_CLOSED;
        }
    }

    public enum Outcome { ACCEPTED, REPEAT_OR_LATE, OTHER_DAY }

    private final Map<String, Entry> entries = new HashMap<>();
    private final NavigableMap<LocalDate, Set<String>> days = new TreeMap<>();
    private ZoneId zone;
    private boolean byReceived;
    private LocalDate today;

    public Board(ZoneId zone, boolean byReceived, long nowMs) {
        this.zone = zone;
        this.byReceived = byReceived;
        this.today = dayOf(nowMs);
    }

    public LocalDate today() {
        return today;
    }

    public ZoneId zone() {
        return zone;
    }

    public boolean byReceived() {
        return byReceived;
    }

    public Outcome accept(IncidentEvent event, String json, long receivedMs) {
        String id = event.getIncidentId();
        Entry old = entries.get(id);
        if (old != null && event.getSequence() <= old.event().getSequence()) {
            return Outcome.REPEAT_OR_LATE;
        }
        long firstReceived = old == null ? receivedMs : old.firstReceivedMs();
        LocalDate day = dayOf(byReceived ? firstReceived : event.getIncident().getStartedMs());
        boolean open = event.getIncident().getState() != IncidentState.INCIDENT_STATE_CLOSED;
        if (day.isBefore(today) && !open) {
            remove(id);                                   // an older day's incident that is (now) closed: not today's
            return old == null ? Outcome.OTHER_DAY : Outcome.ACCEPTED;
        }
        if (old != null && !old.day().equals(day)) {
            bucket(old.day()).remove(id);
        }
        entries.put(id, new Entry(id, event, json, firstReceived, day));
        bucket(day).add(id);
        return Outcome.ACCEPTED;
    }

    /** Moves to the day of {@code nowMs}; returns true when the day changed (pages then need a fresh snapshot). */
    public boolean roll(long nowMs) {
        LocalDate now = dayOf(nowMs);
        if (now.equals(today)) {
            return false;
        }
        today = now;
        dropClosedBeforeToday();
        return true;
    }

    /** A new time zone or day rule: every incident's day is computed again. */
    public void reconfigure(ZoneId newZone, boolean newByReceived, long nowMs) {
        zone = newZone;
        byReceived = newByReceived;
        today = dayOf(nowMs);
        days.clear();
        for (Entry e : List.copyOf(entries.values())) {
            LocalDate day = dayOf(byReceived ? e.firstReceivedMs() : e.event().getIncident().getStartedMs());
            entries.put(e.id(), new Entry(e.id(), e.event(), e.json(), e.firstReceivedMs(), day));
            bucket(day).add(e.id());
        }
        dropClosedBeforeToday();
    }

    private void dropClosedBeforeToday() {
        Iterator<Map.Entry<LocalDate, Set<String>>> it = days.headMap(today, false).entrySet().iterator();
        while (it.hasNext()) {
            Set<String> ids = it.next().getValue();
            ids.removeIf(id -> {
                Entry e = entries.get(id);
                if (e != null && !e.open()) {
                    entries.remove(id);
                    return true;
                }
                return false;
            });
            if (ids.isEmpty()) {
                it.remove();
            }
        }
    }

    /** Open first (newest start first), then closed (newest end first). O(n log n), once per page connect. */
    public List<Entry> snapshot() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparing((Entry e) -> !e.open())
                .thenComparing(e -> e.open() ? -e.event().getIncident().getStartedMs() : -e.event().getIncident().getEndedMs()));
        return list;
    }

    public Entry get(String id) {
        return entries.get(id);
    }

    public int size() {
        return entries.size();
    }

    public int openCount() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.open()) {
                n++;
            }
        }
        return n;
    }

    private void remove(String id) {
        Entry e = entries.remove(id);
        if (e != null) {
            Set<String> ids = days.get(e.day());
            if (ids != null) {
                ids.remove(id);
            }
        }
    }

    private Set<String> bucket(LocalDate day) {
        return days.computeIfAbsent(day, d -> new HashSet<>());
    }

    private LocalDate dayOf(long ms) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }
}
