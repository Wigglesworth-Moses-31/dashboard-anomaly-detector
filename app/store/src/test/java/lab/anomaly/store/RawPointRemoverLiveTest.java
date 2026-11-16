package lab.anomaly.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lab.anomaly.store.write.BatchPlanner.PointRow;
import lab.anomaly.store.write.PointRepository;
import lab.anomaly.store.write.RawPointRemover;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Task 1.2 against the platform database (-Dlive.db=true). Works on 10 November, older than any real data, and passes
 * "now" explicitly, so nothing real is touched. Line id "ffff000000000099", removed afterwards.
 */
@EnabledIfSystemProperty(named = "live.db", matches = "true")
class RawPointRemoverLiveTest {

    static final String ID = "ffff000000000099";
    static final long DAY = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli();
    static final long HOUR = 3_600_000;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:55432/anomaly?reWriteBatchedInserts=true",
                "anomaly", System.getenv("PLATFORM_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("metrics").createSchemas(false).load().migrate();
        jdbc = new JdbcTemplate(ds);
        cleanUp();
        List<PointRow> rows = new ArrayList<>();
        for (long t = DAY; t < DAY + 10 * HOUR; t += 60_000) {
            rows.add(new PointRow(ID, t, 100, false));
        }
        new PointRepository(jdbc).upsert(rows);
        jdbc.execute("CALL refresh_continuous_aggregate('metrics.points_1m', '2026-11-10', '2026-11-11')");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metrics.points WHERE series_id = ?", ID);
        jdbc.execute("CALL refresh_continuous_aggregate('metrics.points_1m', '2026-11-09', '2026-11-12')");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void removesOlderPointsKeepsTheLastThreeHoursAndThePerMinuteSummaries() {
        long now = DAY + 10 * HOUR;
        RawPointRemover.Result r = new RawPointRemover(jdbc).removeBefore(DAY + 9 * HOUR, now);

        assertThat(r.effectiveBeforeMs()).as("floor: now minus 3 hours").isEqualTo(DAY + 7 * HOUR);
        assertThat(r.removedPoints()).isEqualTo(7 * 60);
        assertThat(count("SELECT count(*) FROM metrics.points WHERE series_id = ? AND ts < to_timestamp(?)", ID, (DAY + 7 * HOUR) / 1000.0)).isZero();
        assertThat(count("SELECT count(*) FROM metrics.points WHERE series_id = ?", ID)).isEqualTo(3 * 60);
        assertThat(count("SELECT count(*) FROM metrics.points_1m WHERE series_id = ? AND minute < to_timestamp(?)", ID, (DAY + 7 * HOUR) / 1000.0))
                .as("per-minute summaries of the removed hours stay").isEqualTo(7 * 60);
    }

    @Test
    void aTimeInTheFutureIsRefusedAndNothingIsRemoved() {
        long now = DAY + 10 * HOUR;
        assertThatThrownBy(() -> new RawPointRemover(jdbc).removeBefore(now + HOUR, now)).hasMessageContaining("future");
        assertThat(count("SELECT count(*) FROM metrics.points WHERE series_id = ?", ID)).isEqualTo(10 * 60);
    }
}
