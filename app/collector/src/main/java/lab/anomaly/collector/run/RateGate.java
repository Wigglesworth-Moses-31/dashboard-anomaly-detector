package lab.anomaly.collector.run;

/** Shared "closed" sign (design K10): after a 429 every caller waits until the source's reset time. */
public final class RateGate {

    private volatile long closedUntilMs;

    public void closeFor(int seconds, long nowMs) {
        long until = nowMs + seconds * 1000L;
        synchronized (this) {
            if (until > closedUntilMs) {
                closedUntilMs = until;
            }
        }
    }

    /** Milliseconds to wait before calling the source; 0 when open. */
    public long waitMs(long nowMs) {
        return Math.max(0, closedUntilMs - nowMs);
    }

    public long closedUntilMs() {
        return closedUntilMs;
    }
}
