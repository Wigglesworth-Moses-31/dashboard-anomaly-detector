package lab.anomaly.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.detector.incident.IncidentBook.Change;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * calmer-anomalies: with the settings as they ship, success rates, latency and traffic are judged on 5-minute
 * averages on the clock, while errors stay judged every 10 seconds. Times are data times.
 */
class SlowJudgingTest {

    static final long STEP = 10_000;
    static final long PERIOD = 300_000;
    static final long P0 = (1_763_200_000_000L / PERIOD + 1) * PERIOD;      // a 5-minute boundary
    static final int PER_PERIOD = 30;

    private Detector detector;
    private final List<Change> changes = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        detector = new Detector(() -> "inc-" + ids.incrementAndGet());
        detector.configure(Fixtures.shipped(), 1);
    }

    static SeriesDescriptor successRate(String id, String api) {
        return Fixtures.line(id, "lab.api.request.count", Kind.KIND_COUNT, "requests", Map.of("api", api), "dash1", "Shop", "Success Rates");
    }

    static SeriesDescriptor latency(String id, String api) {
        return Fixtures.line(id, "lab.api.latency", Kind.KIND_LATENCY, "ms", Map.of("api", api), "dash1", "Shop", "Latencies");
    }

    static SeriesDescriptor errors(String id) {
        return Fixtures.line(id, "lab.api.request.count", Kind.KIND_COUNT, "requests", Map.of("api", "/v1/invoices", "status_code", "500"),
                "dash1", "Shop", "Request Counts by Status");
    }

    private SeriesDescriptor add(SeriesDescriptor d) {
        detector.describe(d);
        return d;
    }

    private List<Change> value(SeriesDescriptor d, long ts, double v) {
        List<Change> c = detector.accept(d.getSeriesId(), ts, v, false, ts + 20_000);
        changes.addAll(c);
        return c;
    }

    /** {@code n} values wobbling by +-1 around {@code normal}; returns the next step time. */
    private long normal(SeriesDescriptor d, long from, int n, double normal) {
        for (int i = 0; i < n; i++) {
            value(d, from + i * STEP, normal + (i % 3) - 1);
        }
        return from + n * STEP;
    }

    private List<Change> of(EventType type) {
        return changes.stream().filter(c -> c.type() == type).toList();
    }

    /** Two and a half hours of normal values: more than slow-min-history, a full slow-history of averages. */
    private long warmUp(SeriesDescriptor d, double normal) {
        return normal(d, P0, 30 * PER_PERIOD, normal);
    }

    @Test
    void aThirtySecondDipOpensNothing() {
        SeriesDescriptor sr = add(successRate("aaaa000000000001", "/v1/invoices"));
        long t = warmUp(sr, 99);
        for (int i = 0; i < 3; i++) {
            value(sr, t + i * STEP, 60);                           // 30 seconds at 60%
        }
        t = normal(sr, t + 3 * STEP, PER_PERIOD - 3, 99);          // rest of the period: average about 95%
        normal(sr, t, PER_PERIOD, 99);                             // the next period judges it
        assertThat(changes).isEmpty();
    }

    @Test
    void aDipThatLastsOpensOneAnomalyAtThePeriodsStart() {
        SeriesDescriptor sr = add(successRate("aaaa000000000001", "/v1/invoices"));
        long start = warmUp(sr, 99);
        long t = start;
        for (int i = 0; i < PER_PERIOD; i++) {
            assertThat(value(sr, t, 60)).as("nothing is decided before the period is complete").isEmpty();
            t += STEP;
        }
        List<Change> first = value(sr, t, 99);                    // first value of the next period
        assertThat(first).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_OPENED);
            assertThat(c.incident().getStartedMs()).isEqualTo(start);
            assertThat(c.incident().getSeries(0).getPeakValue()).isEqualTo(60.0);
            assertThat(c.incident().getSeries(0).getNormalValue()).isEqualTo(99.0);
        });
    }

    @Test
    void oneCalmAverageMakesTheAnomalyCalm() {
        SeriesDescriptor sr = add(successRate("aaaa000000000001", "/v1/invoices"));
        long t = warmUp(sr, 99);
        for (int i = 0; i < PER_PERIOD; i++) {
            value(sr, t + i * STEP, 60);
        }
        long calmStart = t + PER_PERIOD * STEP;
        t = normal(sr, calmStart, PER_PERIOD, 99);                 // one calm period...
        value(sr, t, 99);                                          // ...judged by the next one's first value
        assertThat(changes.stream().filter(c -> c.incident().getState() == IncidentState.INCIDENT_STATE_CALM).toList())
                .singleElement().satisfies(c -> assertThat(c.incident().getEndedMs()).isEqualTo(calmStart));
    }

    @Test
    void errorsStayJudgedEveryTenSeconds() {
        SeriesDescriptor e = add(errors("aaaa000000000002"));
        long t = normal(e, P0, 90, 2);
        assertThat(value(e, t, 30)).isEmpty();
        assertThat(value(e, t + STEP, 31)).singleElement()
                .satisfies(c -> assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_OPENED));
    }

    @Test
    void aShortLatencySpikeIsAveragedAway() {
        SeriesDescriptor lat = add(latency("aaaa000000000003", "/v1/invoices"));
        long t = warmUp(lat, 140);
        value(lat, t, 1213);                                       // one odd step: the average is about 175 ms
        t = normal(lat, t + STEP, PER_PERIOD - 1, 140);
        normal(lat, t, PER_PERIOD, 140);
        assertThat(changes).isEmpty();
    }

    @Test
    void aWarmStartFillsAveragesSoTheLineIsJudgedAtOnce() {
        SeriesDescriptor lat = add(latency("aaaa000000000003", "/v1/invoices"));
        assertThat(detector.needsWarm(lat.getSeriesId())).isTrue();
        assertThat(detector.warmSpanMs(lat.getSeriesId())).isEqualTo(7_200_000L);   // slow-history, not history (30 min)
        int n = 36 * PER_PERIOD;                                    // 3 hours from the Store
        long[] times = new long[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            times[i] = P0 + i * STEP;
            values[i] = 140 + (i % 3) - 1;
        }
        detector.warm(lat.getSeriesId(), times, values);
        long t = P0 + n * STEP;
        for (int i = 0; i < PER_PERIOD; i++) {
            value(lat, t + i * STEP, 1200);
        }
        value(lat, t + PER_PERIOD * STEP, 140);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).as("judged on its first live period").hasSize(1);
    }

    @Test
    void movingARuleBackToEveryStepRefillsTheLine() {
        SeriesDescriptor lat = add(latency("aaaa000000000003", "/v1/invoices"));
        long t = warmUp(lat, 140);
        assertThat(detector.needsWarm(lat.getSeriesId())).isFalse();
        detector.configure(Fixtures.defaults(), 2);                // every step again
        assertThat(detector.needsWarm(lat.getSeriesId())).as("its window is refilled from the Store").isTrue();
        long[] times = new long[90];
        double[] values = new double[90];
        for (int i = 0; i < 90; i++) {
            times[i] = t - (90 - i) * STEP;
            values[i] = 140;
        }
        detector.warm(lat.getSeriesId(), times, values);
        assertThat(value(lat, t, 1213)).isEmpty();
        assertThat(value(lat, t + STEP, 1194)).singleElement()
                .satisfies(c -> assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_OPENED));
    }

    @Test
    void aJudgingTimeThatDoesNotDivideAnHourIsRejected() {
        assertThatThrownBy(() -> Fixtures.shippedEdited(t -> t.replaceFirst("judge-every: 5m\n        steps: 1\n      - signal: latency",
                        "judge-every: 7m\n        steps: 1\n      - signal: latency")))
                .hasMessageContaining("rules[3].judge-every: must be 10s or 1 to 30 minutes dividing an hour");
        assertThatThrownBy(() -> Fixtures.holder(Map.of("anomaly.detector.slow-history", "10m")))
                .hasMessageContaining("slow-history");
    }

    @Test
    void theShippedRulesJudgeErrorsFastAndTheRestOnAverages() {
        DetectorSettings s = Fixtures.shipped();
        assertThat(s.rules()).extracting(r -> r.signal() + "=" + (r.judgeEvery() == null ? "10s" : r.judgeEvery().toMinutes() + "m"))
                .containsExactly("success-rate=5m", "error-rate=10s", "errors=10s", "latency=5m", "latency=5m", "latency=5m",
                        "saturation=5m", "traffic=5m");
        assertThat(s.general().judgeEvery().toMinutes()).isEqualTo(5);
        assertThat(s.slowHistory().toHours()).isEqualTo(2);
    }
}
