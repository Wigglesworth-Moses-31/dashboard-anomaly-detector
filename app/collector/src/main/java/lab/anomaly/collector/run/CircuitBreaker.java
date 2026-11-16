package lab.anomaly.collector.run;

/**
 * Per-unit circuit breaker (design K10), a small state machine:
 * CLOSED (normal) → after {@code threshold} failures in a row → OPEN (no calls) for {@code openMs} →
 * HALF_OPEN (one trial call) → success: CLOSED, failure: OPEN again. O(1) per call.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final long openMs;
    private State state = State.CLOSED;
    private int failuresInARow;
    private long openUntilMs;
    private String lastError = "";

    public CircuitBreaker(int threshold, long openMs) {
        this.threshold = threshold;
        this.openMs = openMs;
    }

    /** May a call go through now? Moves OPEN to HALF_OPEN once the open time has passed. */
    public synchronized boolean allow(long nowMs) {
        if (state == State.OPEN && nowMs >= openUntilMs) {
            state = State.HALF_OPEN;
        }
        return state != State.OPEN;
    }

    public synchronized void onSuccess() {
        state = State.CLOSED;
        failuresInARow = 0;
        lastError = "";
    }

    public synchronized void onFailure(long nowMs, String error) {
        failuresInARow++;
        lastError = error;
        if (state == State.HALF_OPEN || failuresInARow >= threshold) {
            state = State.OPEN;
            openUntilMs = nowMs + openMs;
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized int failuresInARow() {
        return failuresInARow;
    }
}
