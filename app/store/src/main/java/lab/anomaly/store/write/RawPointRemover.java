package lab.anomaly.store.write;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Removes raw points older than a time, on request (add-reporter design R6). No service asks since remove-reporter.
 *
 * <ul>
 *   <li>Floor: never newer than {@link #FLOOR} (3 hours) ago. The per-minute summaries are refreshed from raw points
 *       over the last 3 hours; removing raw points there would make the next refresh erase those minutes.</li>
 *   <li>Whole daily chunks entirely before the time are dropped at once ({@code drop_chunks}); the rest is deleted
 *       in one-hour slices, each its own short statement, so the Store's writes never wait long.</li>
 *   <li>Only {@code metrics.points}: per-minute summaries and line descriptions are never touched.</li>
 * </ul>
 */
@Repository
public class RawPointRemover {

    public static final Duration FLOOR = Duration.ofHours(3);
    private static final Duration SLICE = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(RawPointRemover.class);

    public record Result(long removedPoints, long effectiveBeforeMs) {
    }

    private final JdbcTemplate jdbc;

    public RawPointRemover(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws IllegalArgumentException when {@code beforeMs} is in the future */
    public Result removeBefore(long beforeMs, long nowMs) {
        if (beforeMs > nowMs) {
            throw new IllegalArgumentException("before_ms is in the future: nothing is removed");
        }
        long effective = Math.min(beforeMs, nowMs - FLOOR.toMillis());
        Timestamp before = Timestamp.from(Instant.ofEpochMilli(effective));
        Long total = jdbc.queryForObject("SELECT count(*) FROM metrics.points WHERE ts < ?", Long.class, before);
        jdbc.queryForList("SELECT drop_chunks('metrics.points', older_than => ?::timestamptz)", before);   // whole chunks
        long sliceMs = SLICE.toMillis();
        while (true) {
            Timestamp oldest = jdbc.queryForObject("SELECT min(ts) FROM metrics.points WHERE ts < ?", Timestamp.class, before);
            if (oldest == null) {
                break;
            }
            Timestamp sliceEnd = Timestamp.from(Instant.ofEpochMilli(Math.min(oldest.getTime() + sliceMs, effective)));
            jdbc.update("DELETE FROM metrics.points WHERE ts >= ? AND ts < ?", oldest, sliceEnd);
        }
        long removed = total == null ? 0 : total;
        log.info("Removed {} raw points older than {} (asked for {})", removed, Instant.ofEpochMilli(effective), Instant.ofEpochMilli(beforeMs));
        return new Result(removed, effective);
    }
}
