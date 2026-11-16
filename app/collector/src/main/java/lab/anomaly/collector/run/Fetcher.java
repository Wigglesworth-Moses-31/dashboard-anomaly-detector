package lab.anomaly.collector.run;

import java.time.Clock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;

/**
 * Calls the source politely (design K10): the 429 gate, the token bucket, a bulkhead (semaphore) of
 * {@code maxConcurrent} calls, and retries with backoff for errors worth retrying. A bad question is not retried.
 * It adopts the limit the source reports in its answers.
 */
public final class Fetcher {

    /** How the Fetcher waits; tests replace it to run instantly. */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final MetricSource source;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RateGate gate = new RateGate();
    private final TokenBucket bucket;
    private final Semaphore bulkhead;
    private final Backoff backoff;
    private final int retries;
    private final double share;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong throttled = new AtomicLong();
    private volatile TimeseriesAnswer.RateLimitStatus lastLimit;

    public Fetcher(MetricSource source, Clock clock, Sleeper sleeper, int maxConcurrent, double share,
                   int retries, Backoff backoff) {
        this.source = source;
        this.clock = clock;
        this.sleeper = sleeper;
        this.bulkhead = new Semaphore(maxConcurrent, true);
        this.share = share;
        this.retries = retries;
        this.backoff = backoff;
        // Start at the measured limit (300 per 10 s) times our share; adjusted from real answers
        this.bucket = new TokenBucket(300 * share, 30 * share, System::nanoTime);
    }

    public TimeseriesAnswer fetch(TimeseriesRequest request) throws SourceException, InterruptedException {
        SourceException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                sleeper.sleep(last.kind() == SourceException.Kind.THROTTLED ? 0 : backoff.delayMs(attempt - 1));
            }
            long gateWait = gate.waitMs(clock.millis());
            if (gateWait > 0) {
                sleeper.sleep(gateWait);
            }
            long tokenWaitNanos = bucket.reserve();
            if (tokenWaitNanos > 0) {
                sleeper.sleep(TimeUnit.NANOSECONDS.toMillis(tokenWaitNanos) + 1);
            }
            bulkhead.acquire();
            try {
                calls.incrementAndGet();
                TimeseriesAnswer answer = source.timeseries(request);
                adopt(answer.rateLimit());
                return answer;
            } catch (SourceException e) {
                last = e;
                if (e.kind() == SourceException.Kind.THROTTLED) {
                    throttled.incrementAndGet();
                    gate.closeFor(e.retryAfterSeconds(), clock.millis());
                }
                if (!e.worthRetrying()) {
                    throw e;
                }
            } finally {
                bulkhead.release();
            }
        }
        throw last;
    }

    private void adopt(TimeseriesAnswer.RateLimitStatus limit) {
        if (limit == null || limit.limit() == null || limit.periodSeconds() == null || limit.periodSeconds() <= 0) {
            return;
        }
        TimeseriesAnswer.RateLimitStatus before = lastLimit;
        lastLimit = limit;
        if (before == null || !limit.limit().equals(before.limit()) || !limit.periodSeconds().equals(before.periodSeconds())) {
            bucket.adjust(limit.limit() * share, limit.limit() * share / limit.periodSeconds());
        }
    }

    public RateGate gate() {
        return gate;
    }

    public long calls() {
        return calls.get();
    }

    public long throttledCount() {
        return throttled.get();
    }

    public TimeseriesAnswer.RateLimitStatus lastLimit() {
        return lastLimit;
    }

    public int inFlight(int maxConcurrent) {
        return maxConcurrent - bulkhead.availablePermits();
    }
}
