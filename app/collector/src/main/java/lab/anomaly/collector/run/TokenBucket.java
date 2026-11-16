package lab.anomaly.collector.run;

import java.util.function.LongSupplier;

/**
 * Token bucket (add-collector design K10): {@code capacity} tokens, refilled continuously at
 * {@code refillPerSecond}. Each request takes one token; {@link #reserve()} returns how long the caller must wait
 * for it. O(1) per request; synchronized because many virtual threads share one bucket.
 */
public final class TokenBucket {

    private final LongSupplier nanoTime;
    private double capacity;
    private double refillPerNano;
    private double tokens;
    private long lastNanos;

    public TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1e9;
        this.tokens = capacity;
        this.lastNanos = nanoTime.getAsLong();
    }

    /** Takes a token, going into debt if none is left; returns the nanoseconds to wait before using it. */
    public synchronized long reserve() {
        refill();
        tokens -= 1;
        return tokens >= 0 ? 0 : (long) Math.ceil(-tokens / refillPerNano);
    }

    /** Adopts the limit the source reports (e.g. 300 per 10 s), times the share we allow ourselves. */
    public synchronized void adjust(double newCapacity, double refillPerSecond) {
        refill();
        this.capacity = Math.max(1, newCapacity);
        this.refillPerNano = refillPerSecond / 1e9;
        this.tokens = Math.min(tokens, capacity);
    }

    public synchronized double available() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        tokens = Math.min(capacity, tokens + (now - lastNanos) * refillPerNano);
        lastNanos = now;
    }
}
