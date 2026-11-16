package lab.anomaly.store.write;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** SQL for metric line descriptions (Repository pattern). A description replaces the stored one only if newer. */
@Repository
public class SeriesRepository {

    private static final String UPSERT = """
            INSERT INTO metrics.series (series_id, metric, group_tags, kind, unit, query_text, views, descriptor, first_seen, updated)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (series_id) DO UPDATE SET
              metric = EXCLUDED.metric, group_tags = EXCLUDED.group_tags, kind = EXCLUDED.kind, unit = EXCLUDED.unit,
              query_text = EXCLUDED.query_text, views = EXCLUDED.views, descriptor = EXCLUDED.descriptor,
              first_seen = LEAST(metrics.series.first_seen, EXCLUDED.first_seen), updated = EXCLUDED.updated
            WHERE metrics.series.updated < EXCLUDED.updated
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public SeriesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsert(Collection<SeriesDescriptor> descriptors) {
        if (descriptors.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>();
        for (SeriesDescriptor d : descriptors) {
            List<Object> views = new ArrayList<>();
            d.getViewsList().forEach(v -> views.add(java.util.Map.of("view_id", v.getViewId(), "view_title", v.getViewTitle(),
                    "panel_title", v.getPanelTitle())));
            rows.add(new Object[] {d.getSeriesId(), d.getMetric(), json.writeValueAsString(d.getGroupTagsMap()),
                    d.getKind().name(), d.getUnit(), d.getQueryText(), json.writeValueAsString(views), d.toByteArray(),
                    Timestamp.from(Instant.ofEpochMilli(d.getFirstSeenMs())), Timestamp.from(Instant.ofEpochMilli(d.getUpdatedMs()))});
        }
        jdbc.batchUpdate(UPSERT, rows);
        return rows.size();               // written; older descriptions are skipped by the WHERE, count unknown
    }
}
