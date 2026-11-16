package lab.anomaly.collector.run;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter (design K10): wait a random time between 0 and
 * {@code min(cap, base * 2^attempt)}. Randomness spreads retries so failed callers do not all retry together.
 */
public final class Backoff {

    private final long baseMs;
    private final long capMs;
    private final RandomGenerator random;

    public Backoff(Duration base, Duration cap, RandomGenerator random) {
        this.baseMs = base.toMillis();
        this.capMs = cap.toMillis();
        this.random = random;
    }

    /** Wait before retry number {@code attempt} (0 = first retry). */
    public long delayMs(int attempt) {
        long ceiling = Math.min(capMs, baseMs * (1L << Math.min(attempt, 20)));
        return ceiling <= 0 ? 0 : random.nextLong(ceiling + 1);
    }
}
