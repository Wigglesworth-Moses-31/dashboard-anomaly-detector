package lab.anomaly.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.v1.DaySummaryRequest;
import lab.anomaly.contracts.v1.HistoryChunk;
import lab.anomaly.contracts.v1.HistoryRequest;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.Resolution;
import lab.anomaly.contracts.v1.SeriesDaySummary;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.contracts.v1.SummaryRequest;
import lab.anomaly.store.read.HistoryRepository;
import lab.anomaly.store.write.BatchPlanner.PointRow;
import lab.anomaly.store.write.PointRepository;
import lab.anomaly.store.write.SeriesRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tasks 3.1, 5.1, 5.3 against the platform database (-Dlive.db=true, PLATFORM_DB_PASSWORD in the environment).
 * Uses its own line ids starting with "ffff" and deletes them afterwards; migrations are applied as the Store does.
 */
@EnabledIfSystemProperty(named = "live.db", matches = "true")
class StoreDatabaseLiveTest {

    static final String A = "ffff000000000001";
    static final String B = "ffff000000000002";
    static final long DAY0 = LocalDate.parse("2026-11-01").atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();

    private JdbcTemplate jdbc;
    private PointRepository points;
    private SeriesRepository series;
    private HistoryRepository history;
    private StoreGrpcService grpc;

    @BeforeEach
    void connect() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:55432/anomaly?reWriteBatchedInserts=true",
                "anomaly", System.getenv("PLATFORM_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("metrics").createSchemas(false).load().migrate();
        jdbc = new JdbcTemplate(ds);
        points = new PointRepository(jdbc);
        series = new SeriesRepository(jdbc);
        history = new HistoryRepository(ds, new DataSourceTransactionManager(ds));
        cleanUp();
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("anomaly.store.grpc-port", "9102")
                .withProperty("anomaly.store.raw-safety-limit", "2d").withProperty("anomaly.store.summary-retention", "2d")
                .withProperty("anomaly.store.max-batch-records", "500").withProperty("anomaly.store.poll-timeout", "200ms")
                .withProperty("anomaly.store.history-max-range", "31d").withProperty("anomaly.store.max-database-size", "1GB")
                .withProperty("anomaly.store.size-check-interval", "1m");
        var holder = new lab.anomaly.kit.settings.SettingsHolder<>("anomaly.store", lab.anomaly.store.StoreSettings.class, List.of(), env);
        grpc = new StoreGrpcService(history, holder, new lab.anomaly.store.write.RawPointRemover(jdbc));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metrics.points WHERE series_id LIKE 'ffff%'");
        jdbc.update("DELETE FROM metrics.series WHERE series_id LIKE 'ffff%'");
        refreshPerMinute();                                                      // drop test minutes from the view too
    }

    /** The refresh policy covers only the last 3 hours; test data lives on 2026-11-01, so refresh that window here. */
    private void refreshPerMinute() {
        jdbc.execute("CALL refresh_continuous_aggregate('metrics.points_1m', '2026-10-31', '2026-11-03')");
    }

    private long count(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM metrics.points WHERE series_id = ?", Long.class, id);
    }

    // ---- 3.1 ------------------------------------------------------------------------------------------

    @Test
    void storedOnceCorrectedValueWinsAndPointsNeedNoDescription() {
        points.upsert(List.of(new PointRow(A, DAY0, 5, false)));
        points.upsert(List.of(new PointRow(A, DAY0, 5, false)));
        assertThat(count(A)).isEqualTo(1);

        points.upsert(List.of(new PointRow(A, DAY0, 6, false)));
        assertThat(jdbc.queryForObject("SELECT value FROM metrics.points WHERE series_id = ?", Double.class, A)).isEqualTo(6.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM metrics.series WHERE series_id = ?", Long.class, A))
                .as("no description yet, points stored anyway").isZero();
    }

    // ---- 5.1 ------------------------------------------------------------------------------------------

    private <T> List<T> call(String method, Object request) throws Exception {
        List<T> out = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        StreamObserver<T> observer = new StreamObserver<>() {
            @Override public void onNext(T value) { out.add(value); }
            @Override public void onError(Throwable t) { errors.add(t); }
            @Override public void onCompleted() { }
        };
        grpc.getClass().getMethod(method, request.getClass(), StreamObserver.class).invoke(grpc, request, observer);
        if (!errors.isEmpty()) {
            throw (StatusRuntimeException) errors.get(0);
        }
        return out;
    }

    @Test
    void longRawHistoryComesInChunksOfAtMost1000OldestFirst() throws Exception {
        List<PointRow> rows = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            rows.add(new PointRow(A, DAY0 + i * 10_000L, i, false));
        }
        points.upsert(rows);

        List<HistoryChunk> chunks = call("getHistory", HistoryRequest.newBuilder().setSeriesId(A).setFromMs(DAY0)
                .setToMs(DAY0 + 25_000_000L).setResolution(Resolution.RESOLUTION_RAW).build());

        assertThat(chunks).extracting(HistoryChunk::getPointsCount).containsExactly(1000, 1000, 500);
        List<Long> times = chunks.stream().flatMap(c -> c.getPointsList().stream()).map(Point::getTsMs).toList();
        assertThat(times).isSorted().hasSize(2500);
    }

    @Test
    void perMinuteHistoryAveragesEachMinute() throws Exception {
        List<PointRow> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {                                         // 2 minutes, 6 points each
            rows.add(new PointRow(A, DAY0 + i * 10_000L, i < 6 ? 10 : 20, false));
        }
        points.upsert(rows);
        refreshPerMinute();

        List<HistoryChunk> chunks = call("getHistory", HistoryRequest.newBuilder().setSeriesId(A).setFromMs(DAY0)
                .setToMs(DAY0 + 120_000).setResolution(Resolution.RESOLUTION_MINUTE).build());

        assertThat(chunks.get(0).getPointsList()).extracting(Point::getValue).containsExactly(10.0, 20.0);
    }

    @Test
    void badRangesAreRefusedWithAReason() {
        var request = SummaryRequest.newBuilder().setFromMs(DAY0).setToMs(DAY0 + Duration.ofDays(40).toMillis()).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> call("getSummary", request))
                .hasMessageContaining("INVALID_ARGUMENT").hasMessageContaining("longer than 31 days");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> call("getHistory", HistoryRequest.newBuilder().setSeriesId(A)
                .setFromMs(DAY0 + 1).setToMs(DAY0).setResolution(Resolution.RESOLUTION_RAW).build()))
                .hasMessageContaining("to must be after from");
    }

    // ---- 5.3 ------------------------------------------------------------------------------------------

    /** Postgres percentile_cont: linear interpolation between the two closest ranks. Computed independently here. */
    static double percentileCont(double[] values, double p) {
        double[] v = values.clone();
        Arrays.sort(v);
        double rank = p * (v.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        return v[lo] + (v[hi] - v[lo]) * (rank - lo);
    }

    @Test
    void summaryMatchesAnIndependentComputationAndASixHourWindowHoldsExactlyItsPoints() throws Exception {
        java.util.Random random = new java.util.Random(7);
        List<PointRow> rows = new ArrayList<>();
        List<Double> inWindow = new ArrayList<>();
        long sixHoursStart = DAY0 + Duration.ofHours(6).toMillis();
        long sixHoursEnd = DAY0 + Duration.ofHours(12).toMillis();
        for (long t = DAY0; t < DAY0 + Duration.ofHours(24).toMillis(); t += 10_000) {
            double v = Math.round(random.nextGaussian() * 100 + 500);
            rows.add(new PointRow(A, t, v, false));
            if (t >= sixHoursStart && t < sixHoursEnd) {
                inWindow.add(v);
            }
        }
        rows.add(new PointRow(B, sixHoursStart + 5_000, 1, false));
        rows.add(new PointRow(B, sixHoursEnd, 999, false));                      // exactly at the end: excluded
        points.upsert(rows);

        List<SeriesSummary> six = call("getSummary", SummaryRequest.newBuilder().setFromMs(sixHoursStart).setToMs(sixHoursEnd).build());
        SeriesSummary a = six.stream().filter(s -> s.getSeriesId().equals(A)).findFirst().orElseThrow();
        SeriesSummary b = six.stream().filter(s -> s.getSeriesId().equals(B)).findFirst().orElseThrow();
        double[] values = inWindow.stream().mapToDouble(Double::doubleValue).toArray();

        assertThat(a.getCount()).isEqualTo(values.length).isEqualTo(2160);      // 6 h of 10-s steps
        assertThat(a.getMin()).isEqualTo(Arrays.stream(values).min().orElseThrow());
        assertThat(a.getMax()).isEqualTo(Arrays.stream(values).max().orElseThrow());
        assertThat(a.getMean()).isCloseTo(Arrays.stream(values).average().orElseThrow(), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(a.getP95()).isCloseTo(percentileCont(values, 0.95), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(b.getCount()).as("the point at exactly the end is outside [from, to)").isEqualTo(1);
    }

    @Test
    void aDayEqualsItsMidnightToMidnightWindow() throws Exception {
        List<PointRow> rows = new ArrayList<>();
        for (long t = DAY0 - Duration.ofHours(2).toMillis(); t < DAY0 + Duration.ofHours(26).toMillis(); t += 60_000) {
            rows.add(new PointRow(A, t, (t / 60_000) % 97, false));
        }
        points.upsert(rows);
        long end = LocalDate.parse("2026-11-02").atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();

        List<SeriesDaySummary> day = call("getDaySummary", DaySummaryRequest.newBuilder().setDate("2026-11-01").setTimeZone("Asia/Kolkata").build());
        List<SeriesSummary> window = call("getSummary", SummaryRequest.newBuilder().setFromMs(DAY0).setToMs(end).build());

        SeriesDaySummary d = day.stream().filter(s -> s.getSeriesId().equals(A)).findFirst().orElseThrow();
        SeriesSummary w = window.stream().filter(s -> s.getSeriesId().equals(A)).findFirst().orElseThrow();
        assertThat(d.getCount()).isEqualTo(w.getCount()).isEqualTo(1440);
        assertThat(List.of(d.getMin(), d.getMax(), d.getMean(), d.getP95())).isEqualTo(List.of(w.getMin(), w.getMax(), w.getMean(), w.getP95()));
    }

    /** redesign-dashboard R1: the newest saved time of each line in the last 25 hours. */
    @Test
    void newestTimeOfEachLine() throws Exception {
        long now = (System.currentTimeMillis() / 10_000) * 10_000;
        long nineHoursAgo = now - 9 * 3_600_000L;
        for (long t : new long[] {nineHoursAgo - 20_000, nineHoursAgo - 10_000, nineHoursAgo}) {
            jdbc.update("INSERT INTO metrics.points (series_id, ts, value) VALUES (?, ?, 1)", A, new java.sql.Timestamp(t));
        }
        jdbc.update("INSERT INTO metrics.points (series_id, ts, value) VALUES (?, ?, 1)", B, new java.sql.Timestamp(now - 30 * 3_600_000L));
        List<lab.anomaly.contracts.v1.SeriesNewest> answer = call("getNewest", lab.anomaly.contracts.v1.NewestRequest.getDefaultInstance());
        Map<String, Long> mine = new java.util.HashMap<>();
        answer.stream().filter(n -> n.getSeriesId().startsWith("ffff")).forEach(n -> mine.put(n.getSeriesId(), n.getNewestMs()));
        assertThat(mine).as("A's newest value; B is older than 25 hours").containsExactly(Map.entry(A, nineHoursAgo));
    }
}
