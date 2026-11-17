package lab.anomaly.notifier.live;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.notifier.board.Board;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Connects the board to the pages (design N1, N3). One lock covers "accept an event and hand it to every page" and
 * "register a page and give it the snapshot", so a page never misses a change between its snapshot and its first
 * change (Observer pattern: the pages are the observers).
 */
public final class Hub {

    private final Board board;
    private final List<Viewer> viewers = new CopyOnWriteArrayList<>();
    public final AtomicLong accepted = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicLong merged = new AtomicLong();
    private volatile long lastChangeMs;
    private volatile int cap;

    public Hub(Board board, int cap) {
        this.board = board;
        this.cap = cap;
    }

    public void setCap(int cap) {
        this.cap = cap;
    }

    /** An event from Kafka: kept if newer, then offered to every page. */
    public synchronized Board.Outcome apply(IncidentEvent event, String json, long receivedMs) {
        Board.Outcome outcome = board.accept(event, json, receivedMs);
        if (outcome == Board.Outcome.ACCEPTED) {
            accepted.incrementAndGet();
            lastChangeMs = receivedMs;
            Board.Entry e = board.get(event.getIncidentId());
            boolean closed = e == null || !e.open();
            for (Viewer v : viewers) {
                v.offer(event.getIncidentId(), json, closed);
            }
        } else {
            dropped.incrementAndGet();
        }
        return outcome;
    }

    /** A page connects: the day's incidents, the end-of-snapshot line, then changes, with nothing in between lost. */
    public Flux<String> connect() {
        return Flux.create((FluxSink<String> sink) -> {
            Viewer v = new Viewer(cap, merged);
            synchronized (this) {
                for (Board.Entry e : board.snapshot()) {
                    v.offer(e.id(), e.json(), !e.open());
                }
                v.offer(Viewer.SNAPSHOT_COMPLETE, snapshotComplete(), false);
                viewers.add(v);
            }
            sink.onRequest(v::request);
            sink.onDispose(() -> viewers.remove(v));
            v.attach(sink);
        });
    }

    /** At midnight: returns true and ends every page's stream when the day changed. */
    public synchronized boolean roll(long nowMs) {
        if (board.roll(nowMs)) {
            endAll();
            return true;
        }
        return false;
    }

    /** A new time zone or day rule: recompute and send every page a fresh snapshot. */
    /** How the page writes times: each zone, the main one first (add-display-time-zones Z2). */
    private volatile java.util.List<String> displayZones = lab.anomaly.kit.settings.SharedSettings.DEFAULT_DISPLAY_ZONES;

    public synchronized void reconfigure(ZoneId zone, boolean byReceived, long nowMs, java.util.List<String> zones) {
        this.displayZones = java.util.List.copyOf(zones);
        reconfigure(zone, byReceived, nowMs);
    }

    public synchronized void reconfigure(ZoneId zone, boolean byReceived, long nowMs) {
        board.reconfigure(zone, byReceived, nowMs);
        endAll();
    }

    public synchronized void endAll() {
        for (Viewer v : viewers) {
            v.complete();
        }
        viewers.clear();
    }

    /** For tests: the end line a page receives. */
    synchronized String snapshotLineForTest() {
        return snapshotComplete();
    }

    private String snapshotComplete() {
        return "{\"snapshot_complete\":true,\"day\":\"" + board.today() + "\",\"time_zone\":\"" + board.zone().getId()
                + "\",\"day_by\":\"" + (board.byReceived() ? "received" : "started")
                + "\",\"display_zones\":[" + displayZones.stream().map(z -> "\"" + z + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    // ---- health ---------------------------------------------------------------------------------------------

    public int viewers() {
        return viewers.size();
    }

    public synchronized int incidents() {
        return board.size();
    }

    public synchronized int open() {
        return board.openCount();
    }

    public synchronized String day() {
        return board.today().toString();
    }

    public synchronized String zone() {
        return board.zone().getId();
    }

    public long lastChangeMs() {
        return lastChangeMs;
    }

    public int maxWaiting() {
        int max = 0;
        for (Viewer v : viewers) {
            max = Math.max(max, v.waiting());
        }
        return max;
    }
}
