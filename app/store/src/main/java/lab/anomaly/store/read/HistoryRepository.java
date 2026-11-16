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
