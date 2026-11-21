package lab.anomaly.store.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.store.StoreSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the database small (add-store design S3): applies retention and refresh policies from settings at start and
 * whenever settings change, and reads the database size on a schedule for the size alarm. Lab system: limits are
 * days, not weeks.
 */
@Component
public class StorageHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(StorageHousekeeping.class);

    private final JdbcTemplate jdbc;
    private final SettingsHolder<StoreSettings> settings;
    private volatile long appliedVersion = -1;
    private volatile long totalBytes;
    private volatile Map<String, Long> tableBytes = Map.of();
    private volatile String sizeError = "";

    private final StoreStats stats;
    private volatile Map<String, long[]> compression = Map.of();

    public StorageHousekeeping(JdbcTemplate jdbc, SettingsHolder<StoreSettings> settings, StoreStats stats) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.stats = stats;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("store-housekeeping").start(this::loop);
    }

    private void loop() {
        long nextSizeCheck = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (settings.snapshot().version() != appliedVersion) {
                    applyPolicies(settings.get());
                    appliedVersion = settings.snapshot().version();
                }
                refreshLate();
                long now = System.currentTimeMillis();
                if (now >= nextSizeCheck) {
                    measure();
                    nextSizeCheck = now + settings.get().sizeCheckInterval().toMillis();
                }
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.warn("Housekeeping problem (retrying): {}", e.getMessage());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** Replaces each policy rather than adding a second one. */
    void applyPolicies(StoreSettings s) {
        String raw = interval(s.rawSafetyLimit());
        String summary = interval(s.summaryRetention());
        jdbc.execute("SELECT remove_retention_policy('metrics.points', if_exists => true)");
        jdbc.execute("SELECT add_retention_policy('metrics.points', drop_after => INTERVAL '" + raw + "')");
        jdbc.execute("SELECT remove_retention_policy('metrics.points_1m', if_exists => true)");
        jdbc.execute("SELECT add_retention_policy('metrics.points_1m', drop_after => INTERVAL '" + summary + "')");
        jdbc.execute("SELECT remove_continuous_aggregate_policy('metrics.points_1m', if_exists => true)");
        jdbc.execute("SELECT add_continuous_aggregate_policy('metrics.points_1m', start_offset => INTERVAL '3 hours', "
                + "end_offset => INTERVAL '1 minute', schedule_interval => INTERVAL '1 minute')");
        compress();
        log.info("Storage limits applied: raw points {} , per-minute summaries {}; compressed after {} and {}", raw, summary,
                RAW_COMPRESS_AFTER, SUMMARY_COMPRESS_AFTER);
    }

    /** Raw values compressed after 6 hours, per-minute ones after 3 days (past any backfill), keep-ten-days T1. */
    static final String RAW_COMPRESS_AFTER = "6 hours";
    static final String SUMMARY_COMPRESS_AFTER = "3 days";

    /**
     * Turns on column-wise compression once (one line's values side by side: segment by series, order by time) and
     * sets the policies; new raw chunks cover 6 hours, so a chunk is compressed soon after it is full.
     */
    void compress() {
        Boolean raw = jdbc.queryForObject("SELECT compression_enabled FROM timescaledb_information.hypertables "
                + "WHERE hypertable_schema = 'metrics' AND hypertable_name = 'points'", Boolean.class);
        if (!Boolean.TRUE.equals(raw)) {
            jdbc.execute("ALTER TABLE metrics.points SET (timescaledb.compress, timescaledb.compress_segmentby = 'series_id', "
                    + "timescaledb.compress_orderby = 'ts')");
        }
        jdbc.execute("SELECT set_chunk_time_interval('metrics.points', INTERVAL '6 hours')");
        jdbc.execute("SELECT remove_compression_policy('metrics.points', if_exists => true)");
        jdbc.execute("SELECT add_compression_policy('metrics.points', compress_after => INTERVAL '" + RAW_COMPRESS_AFTER + "')");
        Boolean summary = jdbc.queryForObject("SELECT compression_enabled FROM timescaledb_information.continuous_aggregates "
                + "WHERE view_schema = 'metrics' AND view_name = 'points_1m'", Boolean.class);
        if (!Boolean.TRUE.equals(summary)) {
            jdbc.execute("ALTER MATERIALIZED VIEW metrics.points_1m SET (timescaledb.compress = true)");
        }
        jdbc.execute("SELECT remove_compression_policy('metrics.points_1m', if_exists => true)");
        jdbc.execute("SELECT add_compression_policy('metrics.points_1m', compress_after => INTERVAL '" + SUMMARY_COMPRESS_AFTER + "')");
    }

    /** Values saved for times the 3-hour refresh policy no longer covers (backfill, late data): refreshed once. */
    void refreshLate() {
        long[] late = stats.takeLate();
        if (late == null) {
            return;
        }
        long minute = 60_000;
        java.sql.Timestamp from = new java.sql.Timestamp((late[0] / minute) * minute);
        java.sql.Timestamp to = new java.sql.Timestamp((late[1] / minute + 1) * minute);
        try {
            // The procedure takes "any": PostgreSQL cannot infer ? without a cast (fix-backfill-faults F1)
            jdbc.update("CALL refresh_continuous_aggregate('metrics.points_1m', ?::timestamptz, ?::timestamptz)", from, to);
        } catch (RuntimeException e) {
            stats.noteLate(late[0]);                         // give the range back: tried again next run (F1)
            stats.noteLate(late[1]);
            throw e;
        }
        log.info("Per-minute summaries refreshed for late values {} .. {}", from.toInstant(), to.toInstant());
    }

    void measure() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("metrics.points", jdbc.queryForObject("SELECT hypertable_size('metrics.points')", Long.class));
        sizes.put("metrics.points_1m", jdbc.queryForObject("""
                SELECT coalesce(hypertable_size(format('%I.%I', materialization_hypertable_schema,
                       materialization_hypertable_name)::regclass), 0)
                FROM timescaledb_information.continuous_aggregates
                WHERE view_schema = 'metrics' AND view_name = 'points_1m'""", Long.class));
        sizes.put("metrics.series", jdbc.queryForObject("SELECT pg_total_relation_size('metrics.series')", Long.class));
        Map<String, long[]> comp = new LinkedHashMap<>();
        String summaryTable = jdbc.queryForObject("SELECT format('%I.%I', materialization_hypertable_schema, materialization_hypertable_name) "
                + "FROM timescaledb_information.continuous_aggregates WHERE view_schema = 'metrics' AND view_name = 'points_1m'", String.class);
        for (String[] t : new String[][] {{"metrics.points", "metrics.points"}, {"metrics.points_1m", summaryTable}}) {
            try {
                comp.put(t[0], jdbc.query("SELECT coalesce(sum(before_compression_total_bytes), 0), coalesce(sum(after_compression_total_bytes), 0) "
                        + "FROM hypertable_compression_stats(?::regclass)", rs -> rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : new long[2], t[1]));
            } catch (RuntimeException e) {
                comp.put(t[0], new long[2]);
            }
        }
        compression = comp;
        tableBytes = sizes;
        totalBytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
        sizeError = "";
    }

    /** "2 days 3600 seconds" style text that Postgres reads as an interval. */
    private static String interval(java.time.Duration d) {
        return d.toSeconds() + " seconds";
    }

    public long totalBytes() {
        return totalBytes;
    }

    /** Per table: bytes of compressed chunks before and after compression. */
    public Map<String, long[]> compression() {
        return compression;
    }

    public Map<String, Long> tableBytes() {
        return tableBytes;
    }

    public boolean overLimit() {
        return totalBytes > settings.get().maxDatabaseSize().toBytes();
    }

    public String largestTable() {
        return tableBytes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
    }

    public String sizeError() {
        return sizeError;
    }

    public long appliedVersion() {
        return appliedVersion;
    }
}
