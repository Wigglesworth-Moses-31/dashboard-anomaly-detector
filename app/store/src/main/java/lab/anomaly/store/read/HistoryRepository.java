package lab.anomaly.store.read;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import lab.anomaly.contracts.v1.Point;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read queries (add-store design S4). History is read with a database cursor (fetch size = chunk size, inside a
 * read-only transaction so Postgres streams instead of loading everything) and handed on in chunks: memory per
 * question is one chunk, however long the range.
 */
@Repository
public class HistoryRepository {

    public static final int CHUNK = 1000;

    /** One line's summary over a window. */
    public record Summary(String seriesId, long count, double min, double max, double mean, double p95) {}

    private final JdbcTemplate streaming;
    private final TransactionTemplate readOnly;

    public HistoryRepository(DataSource dataSource, PlatformTransactionManager transactions) {
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(CHUNK);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Raw points of one line in [from, to), oldest first, in chunks of at most {@link #CHUNK}. */
    public void raw(String seriesId, long fromMs, long toMs, Consumer<List<Point>> chunks) {
        stream("SELECT ts, value, partial FROM metrics.points WHERE series_id = ? AND ts >= ? AND ts < ? ORDER BY ts",
                seriesId, fromMs, toMs, true, chunks);
    }

    /** Per-minute averages of one line in [from, to), oldest first, in chunks of at most {@link #CHUNK}. */
    public void perMinute(String seriesId, long fromMs, long toMs, Consumer<List<Point>> chunks) {
        stream("SELECT minute, avg FROM metrics.points_1m WHERE series_id = ? AND minute >= ? AND minute < ? ORDER BY minute",
                seriesId, fromMs, toMs, false, chunks);
    }

    /**
     * One summary per line over finished points in [from, to), ordered by series id. The p95 is exact
     * ({@code percentile_cont}, linear interpolation) because raw points are kept until reported.
     */
    public void summaries(long fromMs, long toMs, Consumer<Summary> each) {
        readOnly.executeWithoutResult(status -> streaming.query("""
                SELECT series_id, count(*), min(value), max(value), avg(value),
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY value)
                FROM metrics.points
                WHERE ts >= ? AND ts < ? AND NOT partial
                GROUP BY series_id ORDER BY series_id""",
                rs -> {
                    each.accept(new Summary(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)));
                }, Timestamp.from(Instant.ofEpochMilli(fromMs)), Timestamp.from(Instant.ofEpochMilli(toMs))));
    }

    /** Every line's description (add-dashboard-view D1). O(lines), streamed. */
    public void descriptors(Consumer<byte[]> each) {
        readOnly.executeWithoutResult(status -> streaming.query("SELECT descriptor FROM metrics.series ORDER BY series_id",
                rs -> {
                    each.accept(rs.getBytes(1));
                }));
    }

    /** redesign-dashboard R1: each line's newest saved time over the last 25 hours, one pass. */
    public void newest(java.util.function.ObjLongConsumer<String> each) {
        readOnly.executeWithoutResult(status -> streaming.query(
                "SELECT series_id, max(ts) FROM metrics.points WHERE ts > now() - interval '25 hours' GROUP BY series_id",
                rs -> {
                    each.accept(rs.getString(1), rs.getTimestamp(2).getTime());
                }));
    }

    /**
     * Points of many lines in [from, to), per line oldest first, in chunks of at most {@link #CHUNK} that never mix
     * lines (add-dashboard-view D1). Raw: the 10-second values. Per minute: two points per minute, its lowest value at
     * the minute and its highest 30 s later, so thinning keeps both drops and spikes. One query: O(points).
     */
    public void batch(List<String> seriesIds, long fromMs, long toMs, boolean raw, java.util.function.BiConsumer<String, List<Point>> chunks) {
        batch(seriesIds, fromMs, toMs, raw, 0, "avg", chunks);
    }

    /**
     * As above; with {@code intervalMs} > 0 the raw values are rolled up in the database to that interval with
     * {@code method} (avg, sum, min, max), one point per interval and line (add-datadog-look K8): a page gets at most a
     * few hundred points per line, whatever the range. One query, O(points read).
     */
    public void batch(List<String> seriesIds, long fromMs, long toMs, boolean raw, long intervalMs, String method,
                      java.util.function.BiConsumer<String, List<Point>> chunks) {
        if (intervalMs > 0) {
            if (!java.util.Set.of("avg", "sum", "min", "max").contains(method)) {
                throw new IllegalArgumentException("method must be avg, sum, min or max");
            }
            String bucketed = "SELECT series_id, time_bucket(make_interval(secs => ?), ts) AS b, " + method + "(value) FROM metrics.points "
                    + "WHERE series_id = ANY (?) AND ts >= ? AND ts < ? GROUP BY series_id, b ORDER BY series_id, b";
            readOnly.executeWithoutResult(status -> streaming.execute((java.sql.Connection c) -> {
                try (var ps = c.prepareStatement(bucketed)) {
                    ps.setFetchSize(CHUNK);
                    ps.setDouble(1, intervalMs / 1000.0);
                    ps.setArray(2, c.createArrayOf("text", seriesIds.toArray()));
                    ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(fromMs)));
                    ps.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(toMs)));
                    try (var rs = ps.executeQuery()) {
                        String current = null;
                        List<Point> chunk = new ArrayList<>(CHUNK);
                        while (rs.next()) {
                            String id = rs.getString(1);
                            if (current != null && (!current.equals(id) || chunk.size() >= CHUNK)) {
                                chunks.accept(current, List.copyOf(chunk));
                                chunk.clear();
                            }
                            current = id;
                            chunk.add(Point.newBuilder().setTsMs(rs.getTimestamp(2).getTime()).setValue(rs.getDouble(3)).build());
                        }
                        if (current != null && !chunk.isEmpty()) {
                            chunks.accept(current, List.copyOf(chunk));
                        }
                    }
                }
                return null;
            }));
            return;
        }
        String sql = raw
                ? "SELECT series_id, ts, value, value FROM metrics.points WHERE series_id = ANY (?) AND ts >= ? AND ts < ? ORDER BY series_id, ts"
                : "SELECT series_id, minute, min, max FROM metrics.points_1m WHERE series_id = ANY (?) AND minute >= ? AND minute < ? ORDER BY series_id, minute";
        readOnly.executeWithoutResult(status -> streaming.execute((java.sql.Connection c) -> {
            try (var ps = c.prepareStatement(sql)) {
                ps.setFetchSize(CHUNK);
                ps.setArray(1, c.createArrayOf("text", seriesIds.toArray()));
                ps.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(fromMs)));
                ps.setTimestamp(3, Timestamp.from(Instant.ofEpochMilli(toMs)));
                try (var rs = ps.executeQuery()) {
                    String current = null;
                    List<Point> chunk = new ArrayList<>(CHUNK);
                    while (rs.next()) {
                        String id = rs.getString(1);
                        if (current != null && (!current.equals(id) || chunk.size() >= CHUNK - 1)) {
                            chunks.accept(current, List.copyOf(chunk));
                            chunk.clear();
                        }
                        current = id;
                        long ts = rs.getTimestamp(2).getTime();
                        if (raw) {
                            chunk.add(Point.newBuilder().setTsMs(ts).setValue(rs.getDouble(3)).build());
                        } else {
                            chunk.add(Point.newBuilder().setTsMs(ts).setValue(rs.getDouble(3)).build());
                            chunk.add(Point.newBuilder().setTsMs(ts + 30_000).setValue(rs.getDouble(4)).build());
                        }
                    }
                    if (current != null && !chunk.isEmpty()) {
                        chunks.accept(current, List.copyOf(chunk));
                    }
                }
            }
            return null;
        }));
    }

    private void stream(String sql, String seriesId, long fromMs, long toMs, boolean hasPartial, Consumer<List<Point>> chunks) {
        readOnly.executeWithoutResult(status -> {
            List<Point> chunk = new ArrayList<>(CHUNK);
            streaming.query(sql, rs -> {
                Point.Builder p = Point.newBuilder().setTsMs(rs.getTimestamp(1).getTime()).setValue(rs.getDouble(2));
                if (hasPartial) {
                    p.setPartial(rs.getBoolean(3));
                }
                chunk.add(p.build());
                if (chunk.size() == CHUNK) {
                    chunks.accept(List.copyOf(chunk));
                    chunk.clear();
                }
            }, seriesId, Timestamp.from(Instant.ofEpochMilli(fromMs)), Timestamp.from(Instant.ofEpochMilli(toMs)));
            if (!chunk.isEmpty()) {
                chunks.accept(List.copyOf(chunk));
            }
        });
    }
}
