package lab.anomaly.detector.ops;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Counters for the health page, written by the detection and publisher threads. */
@Component
public class DetectorStats {

    public final AtomicLong valuesJudged = new AtomicLong();
    public final AtomicLong messagesSetAside = new AtomicLong();
    public final AtomicLong eventsSaved = new AtomicLong();
    public final AtomicLong eventsSent = new AtomicLong();
    public final AtomicLong warmCalls = new AtomicLong();
    public final AtomicLong warmFailures = new AtomicLong();
    public final AtomicLong saveFailures = new AtomicLong();
    public final Map<String, Long> lagByTopic = new ConcurrentHashMap<>();
    public volatile String lastError = "";

    // Time from the Detector reading the deciding value to its event being sent: a ring buffer of the last 1000.
    private final long[] announceMs = new long[1000];
    // Time values waited in Kafka before the Detector read them (0 when it keeps up): the last 1000 messages.
    private final long[] readDelayMs = new long[1000];
    private int readNext;
    private int readCount;

    public synchronized void recordReadDelay(long ms) {
        readDelayMs[readNext] = ms;
        readNext = (readNext + 1) % readDelayMs.length;
        readCount = Math.min(readCount + 1, readDelayMs.length);
    }

    public synchronized long readDelayPercentile(double p) {
        if (readCount == 0) {
            return -1;
        }
        long[] copy = Arrays.copyOf(readDelayMs, readCount);
        Arrays.sort(copy);
        return copy[Math.max(0, Math.min(readCount - 1, (int) Math.ceil(p / 100.0 * readCount) - 1))];
    }
    private int next;
    private int count;

    public synchronized void recordAnnounce(long ms) {
        announceMs[next] = ms;
        next = (next + 1) % announceMs.length;
        count = Math.min(count + 1, announceMs.length);
    }

    /** The p-th percentile (0-100) of time-to-announce, or -1 when nothing was sent yet. O(k log k). */
    public synchronized long announcePercentile(double p) {
        if (count == 0) {
            return -1;
        }
        long[] copy = Arrays.copyOf(announceMs, count);
        Arrays.sort(copy);
        int index = (int) Math.ceil(p / 100.0 * count) - 1;
        return copy[Math.max(0, Math.min(count - 1, index))];
    }

    public synchronized double shareAnnouncedWithin(long ms) {
        if (count == 0) {
            return Double.NaN;
        }
        int ok = 0;
        for (int i = 0; i < count; i++) {
            if (announceMs[i] <= ms) {
                ok++;
            }
        }
        return ok / (double) count;
    }

    public long totalLag() {
        return lagByTopic.values().stream().mapToLong(Long::longValue).sum();
    }
}
