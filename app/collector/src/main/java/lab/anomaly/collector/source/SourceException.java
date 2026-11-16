package lab.anomaly.collector.source;

/** A failed call to a source, sorted into the kinds the Fetcher treats differently (design K10). */
public class SourceException extends Exception {

    public enum Kind {
        /** Too many requests: wait {@link #retryAfterSeconds()} then continue. */
        THROTTLED,
        /** The question itself is wrong: retrying will not help. */
        BAD_REQUEST,
        /** Keys missing or not allowed. */
        AUTH,
        /** The source failed (5xx): worth a retry. */
        SERVER,
        /** Network problem or timeout: worth a retry. */
        NETWORK
    }

    private final Kind kind;
    private final int retryAfterSeconds;

    public SourceException(Kind kind, String message, int retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public SourceException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    public Kind kind() {
        return kind;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean worthRetrying() {
        return kind == Kind.SERVER || kind == Kind.NETWORK || kind == Kind.THROTTLED;
    }
}
