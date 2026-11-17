package lab.anomaly.detector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.detector.incident.IncidentBook.Change;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Task 6.1, second view: the live run's values as the Store kept them (07:59-08:42, all 226 lines), fed to the
 * Detector in time order with the default settings and a silence check every step. Unlike the Collector's replay
 * of run-1, the Store's copy has no gaps. Needs -Dlive.db=true and PLATFORM_DB_PASSWORD.
 *
 * <p>The traffic generator breaks /v1/invoices (and billing, orders, /v1/telemetry 20 s later) every 8 minutes
 * for 3 minutes, starting at whole multiples of 8 minutes (08:00, 08:08, ...).
 */
@EnabledIfSystemProperty(named = "live.db", matches = "true")
class LiveDataCheckTest {

    static final long CYCLE = 480_000;

    @Test
    void theRecordedProblemsAreFoundFromTheLiveData() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:55432/anomaly", "anomaly",
                System.getenv("PLATFORM_DB_PASSWORD"));
        Detector detector = new Detector(new java.util.function.Supplier<>() {
            int n;
            @Override public String get() { return "inc-" + (++n); }
        });
        detector.configure(Fixtures.defaults(), 1);
        for (SeriesDescriptor d : Fixtures.recordedLines()) {
            detector.describe(d);
            detector.warm(d.getSeriesId(), new long[0], new double[0]);
        }
        Long recorded = new JdbcTemplate(ds).queryForObject("SELECT count(*) FROM metrics.points WHERE ts >= '2026-11-15 07:59:00+00' AND ts < '2026-11-15 08:42:00+00'", Long.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(recorded != null && recorded > 40_000,
                "the live run's raw values of 15 Nov are gone (the Reporter cleans reported days); results are in docs/verification/add-detector.md");
        List<Change> changes = new ArrayList<>();
        long[] last = {0};
        new JdbcTemplate(ds).query("""
                SELECT series_id, (extract(epoch FROM ts) * 1000)::bigint, value FROM metrics.points
                WHERE ts >= '2026-11-15 07:59:00+00' AND ts < '2026-11-15 08:42:00+00' AND series_id NOT LIKE 'ffff%'
                ORDER BY ts, series_id""", rs -> {
            long ts = rs.getLong(2);
            if (ts != last[0]) {
                changes.addAll(detector.checkSilence(0));
                last[0] = ts;
            }
            changes.addAll(detector.accept(rs.getString(1), ts, rs.getDouble(3), false, 0));
        });

        // The final state of every incident, by id.
        Map<String, Incident> incidents = new LinkedHashMap<>();
        for (Change c : changes) {
            incidents.put(c.incident().getIncidentId(), c.incident());
        }
        Map<String, List<String>> byGroup = new TreeMap<>();
        for (Incident i : incidents.values()) {
            byGroup.computeIfAbsent(i.getGroupKey(), k -> new ArrayList<>()).add(time(i.getStartedMs()) + "-"
                    + (i.getEndedMs() == 0 ? "open" : time(i.getEndedMs())) + " " + i.getTitle());
        }
        byGroup.forEach((g, list) -> list.forEach(x -> System.out.println("LIVE-DATA " + g + " | " + x)));
        System.out.println("LIVE-DATA incidents=" + incidents.size() + " events=" + changes.size()
                + " opened=" + changes.stream().filter(c -> c.type() == EventType.EVENT_TYPE_OPENED).count());

        // /v1/invoices: one incident per cycle once lines have 10 minutes of history (08:08 to 08:40). From 08:16 each
        // also lists its 500 errors; at 08:08 the error line's history already holds two broken cycles (08:00, 08:08).
        List<Incident> invoices = incidents.values().stream().filter(i -> i.getGroupKey().equals("datadoghq.com|api=/v1/invoices")).toList();
        assertThat(invoices).extracting(i -> i.getStartedMs() / CYCLE * CYCLE).extracting(LiveDataCheckTest::time)
                .containsExactly("08:08:00", "08:16:00", "08:24:00", "08:32:00", "08:40:00");
        for (Incident i : invoices.subList(1, invoices.size())) {
            assertThat(i.getSeriesList()).extracting(AffectedSeries::getSignal).as(i.getTitle()).contains("latency", "errors");
        }
        // The other groups the generator breaks are found in every judgeable cycle too.
        for (String group : List.of("api=/v1/telemetry", "service=billing", "service=orders", "upstream_service=billing-core",
                "upstream_service=telemetry-ingest", "dashboard=w2f-pqs-ghd")) {
            assertThat(incidents.values().stream().filter(i -> i.getGroupKey().equals("datadoghq.com|" + group)).count()).as(group).isEqualTo(5);
        }
    }

    static String time(long ms) {
        return Instant.ofEpochMilli(ms).toString().substring(11, 19);
    }
}
