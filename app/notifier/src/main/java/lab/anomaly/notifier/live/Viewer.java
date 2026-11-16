package lab.anomaly.notifier.live;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.FluxSink;

/**
 * One page's tray (design N4): the newest JSON per incident not yet sent, in arrival order. A change for an incident
 * already waiting replaces it in place ("merged"), so a stalled page never builds a backlog: memory is at most one
 * entry per incident. Lines are sent only while the page has asked for more (RSocket request-n), so the reader thread
 * only puts into the map and never waits.
 */
public final class Viewer {

    /** Key of the line that ends the snapshot. */
    static final String SNAPSHOT_COMPLETE = "\u0000snapshot_complete";

    private record Pending(String json, boolean closed) {
    }

    private final LinkedHashMap<String, Pending> tray = new LinkedHashMap<>();
    private final int cap;
    private final AtomicLong merged;
    private final long connectedMs = System.currentTimeMillis();
    private FluxSink<String> sink;
    private long requested;
    private boolean done;

    Viewer(int cap, AtomicLong merged) {
        this.cap = cap;
        this.merged = merged;
    }

    synchronized void attach(FluxSink<String> s) {
        this.sink = s;
        drain();
    }

    synchronized void request(long n) {
        requested = requested + n < 0 ? Long.MAX_VALUE : requested + n;   // RSocket may ask for Long.MAX_VALUE
        drain();
    }

    /** Puts the newest state of one incident (or the end-of-snapshot line) into the tray. O(1). */
    synchronized void offer(String key, String json, boolean closed) {
        if (done) {
            return;
        }
        Pending previous = tray.get(key);
        if (previous != null) {
            tray.put(key, new Pending(json, closed));                     // replace, keep its place
            merged.incrementAndGet();
        } else {
            tray.put(key, new Pending(json, closed));
            if (tray.size() > cap) {
                dropOldestClosed();
            }
        }
        drain();
    }

    /** Ends the stream (midnight, zone change, shutdown): the page reconnects and gets a fresh snapshot. */
    synchronized void complete() {
        done = true;
        tray.clear();
        if (sink != null) {
            sink.complete();
        }
    }

    synchronized int waiting() {
        return tray.size();
    }

    long connectedMs() {
        return connectedMs;
    }

    private void dropOldestClosed() {
        Iterator<Map.Entry<String, Pending>> it = tray.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pending> e = it.next();
            if (e.getValue().closed() && !e.getKey().equals(SNAPSHOT_COMPLETE)) {
                it.remove();
                merged.incrementAndGet();
                return;
            }
        }
    }

    private void drain() {
        if (sink == null || done) {
            return;
        }
        Iterator<Map.Entry<String, Pending>> it = tray.entrySet().iterator();
        while (requested > 0 && it.hasNext()) {
            String json = it.next().getValue().json();
            it.remove();
            requested--;
            sink.next(json);
        }
    }
}
