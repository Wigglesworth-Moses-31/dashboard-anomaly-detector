package lab.anomaly.collector.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Decides which poll unit is due (add-collector design K9). A min-heap (PriorityQueue) of due times returns the
 * soonest in O(log n). First due times are spread across the interval by a mixed hash of the unit (jitter); a unit
 * whose previous poll is still running skips its turn instead of piling up. Not thread-safe by itself: the engine
 * calls it from one scheduler thread, and {@link #finished} from workers under the engine's lock.
 */
public final class Scheduler {

    private record Due(long atMs, String key) {}

    private final PriorityQueue<Due> queue = new PriorityQueue<>((a, b) -> Long.compare(a.atMs(), b.atMs()));
    private final Map<String, Long> nextDue = new HashMap<>();
    private final Set<String> running = new HashSet<>();
    private long intervalMs;
    private long skipped;

    public Scheduler(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** Replaces the set of units: kept units keep their timing, new ones get a jittered start, gone ones leave. */
    public void setUnits(List<String> keys, long nowMs) {
        Set<String> wanted = new HashSet<>(keys);
        nextDue.keySet().retainAll(wanted);
        for (String key : keys) {
            nextDue.computeIfAbsent(key, k -> nowMs + Math.floorMod(spread(k), intervalMs));
        }
        rebuildQueue();
    }

    public void setInterval(long newIntervalMs) {
        this.intervalMs = newIntervalMs;
    }

    /** Units due at {@code nowMs}, marked running and rescheduled one interval later. */
    public List<String> takeDue(long nowMs) {
        List<String> due = new ArrayList<>();
        while (!queue.isEmpty() && queue.peek().atMs() <= nowMs) {
            Due next = queue.poll();
            Long current = nextDue.get(next.key());
            if (current == null || current != next.atMs()) {
                continue;                                         // stale entry from a previous plan or timing
            }
            long following = next.atMs() + intervalMs;
            if (following <= nowMs) {                             // fell behind (e.g. machine asleep): skip ahead
                following = nowMs + intervalMs;
            }
            nextDue.put(next.key(), following);
            queue.add(new Due(following, next.key()));
            if (running.contains(next.key())) {
                skipped++;                                        // previous poll still running: skip this turn
                continue;
            }
            running.add(next.key());
            due.add(next.key());
        }
        return due;
    }

    public void finished(String key) {
        running.remove(key);
    }

    /** Milliseconds until the next unit is due (for the scheduler thread's sleep). */
    public long millisUntilNext(long nowMs) {
        return queue.isEmpty() ? intervalMs : Math.max(0, queue.peek().atMs() - nowMs);
    }

    public long skippedTurns() {
        return skipped;
    }

    public int size() {
        return nextDue.size();
    }

    /**
     * Jitter offset source. String.hashCode() of keys that differ only at the end differs only slightly
     * (e.g. by 1), which would start such units almost together; MurmurHash3's 32-bit finaliser mixes the bits
     * so that nearly equal keys land far apart (avalanche). O(1).
     */
    static int spread(String key) {
        int h = key.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    private void rebuildQueue() {
        queue.clear();
        nextDue.forEach((key, at) -> queue.add(new Due(at, key)));
    }
}
