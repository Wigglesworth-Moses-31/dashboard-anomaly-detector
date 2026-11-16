package lab.anomaly.store.write;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lab.anomaly.store.write.BatchPlanner.PointRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL for points (Repository pattern). Idempotent upsert on the primary key (series_id, ts): a repeat with the
 * same value writes nothing, a new value replaces the old (spec: every number is kept once).
 */
@Repository
public class PointRepository {

    private static final String UPSERT = """
            INSERT INTO metrics.points (series_id, ts, value, partial) VALUES (?, ?, ?, ?)
            ON CONFLICT (series_id, ts) DO UPDATE SET value = EXCLUDED.value, partial = EXCLUDED.partial
            WHERE metrics.points.value IS DISTINCT FROM EXCLUDED.value
               OR metrics.points.partial IS DISTINCT FROM EXCLUDED.partial
            """;

    private final JdbcTemplate jdbc;

    public PointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the number of rows written (new, corrected or unchanged repeat). The driver's batched insert
     * reports "done, count unknown" (-2) per row, so changed-only counts are not available here.
     */
    public int upsert(Collection<PointRow> points) {
        if (points.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(points.size());
        for (PointRow p : points) {
            rows.add(new Object[] {p.seriesId(), Timestamp.from(Instant.ofEpochMilli(p.tsMs())), p.value(), p.partial()});
        }
        jdbc.batchUpdate(UPSERT, rows);
        return rows.size();
    }
}
