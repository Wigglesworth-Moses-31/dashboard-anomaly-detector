package lab.anomaly.store.ops;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counters and timings for the health page (add-store design S6). Write delays of the last 1000 batches live in a
 * ring buffer: O(1) to add, percentiles computed only when read.
 */
@Component
public class StoreStats {

    private static final int RING = 1000;

    public final AtomicLong batches = new AtomicLong();
    public final AtomicLong pointsReceived = new AtomicLong();
    public final AtomicLong pointsSaved = new AtomicLong();
    public final AtomicLong descriptionsSaved = new AtomicLong();
    public final AtomicLong setAside = new AtomicLong();
    public final AtomicLong failedBatches = new AtomicLong();
    public volatile long lastSaveMs;
    public volatile String lastError = "";
    public volatile boolean lastSaveFailed;
    public final Map<String, Long> lagByTopic = new ConcurrentHashMap<>();
    public volatile long lagGrowingSinceMs;

    private final long[] delays = new long[RING];
    private int next;
    private int filled;

    public synchronized void recordDelay(long millis) {
        delays[next] = millis;
        next = (next + 1) % RING;
        filled = Math.min(filled + 1, RING);
    }

    /** Percentile of recorded write delays in milliseconds, or null if none yet. */
    public synchronized Long delayPercentile(double p) {
        if (filled == 0) {
            return null;
        }
        long[] copy = Arrays.copyOf(delays, filled);
        Arrays.sort(copy);
        return copy[Math.min(filled - 1, (int) Math.round(p / 100.0 * (filled - 1)))];
    }

    public long totalLag() {
        return lagByTopic.values().stream().mapToLong(Long::longValue).sum();
    }
}
