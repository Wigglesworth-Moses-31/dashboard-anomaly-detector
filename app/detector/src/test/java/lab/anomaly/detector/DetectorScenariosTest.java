package lab.anomaly.detector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.detector.incident.IncidentBook.Change;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tasks 3.2, 3.3 and 4.1: the spec's judging, silence and grouping scenarios on the detection core, with the real
 * default settings from config-template/detector.yml. Times are data times (10-second steps), never the clock.
 */
class DetectorScenariosTest {

    static final long T0 = 1_763_200_000_000L;          // a step boundary
    static final long STEP = 10_000;
    static final String LAB = "w2f-pqs-ghd";
    static final String LAB_TITLE = "Lab: API platform";

    private Detector detector;
    private final List<Change> changes = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        detector = new Detector(() -> "inc-" + ids.incrementAndGet());
        detector.configure(Fixtures.serviceScope(), 1);   // these scenarios test grouping by service tag
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    static SeriesDescriptor latency(String id, String api) {
        return Fixtures.line(id, "lab.api.latency", Kind.KIND_LATENCY, "ms", Map.of("api", api, "http_method", "get"), LAB, LAB_TITLE, "Latencies");
    }

    static SeriesDescriptor successRate(String id, Map<String, String> tags, String panel) {
        return Fixtures.line(id, "lab.api.request.count", Kind.KIND_COUNT, "requests", tags, LAB, LAB_TITLE, panel);
    }

    static SeriesDescriptor count(String id, Map<String, String> tags, String panel) {
        return Fixtures.line(id, "lab.api.request.count", Kind.KIND_COUNT, "requests", tags, LAB, LAB_TITLE, panel);
    }

    private SeriesDescriptor add(SeriesDescriptor d) {
        detector.describe(d);
        return d;
    }

    /** Feeds one value; returns the changes it caused (also kept in {@link #changes}). */
    private List<Change> value(SeriesDescriptor d, long ts, double v) {
        List<Change> c = detector.accept(d.getSeriesId(), ts, v, false, ts + 20_000);
        changes.addAll(c);
        return c;
    }

    /** {@code n} normal values from {@code from}, wobbling by +-2 around {@code normal}. Returns the next step time. */
    private long normal(SeriesDescriptor d, long from, int n, double normal) {
        for (int i = 0; i < n; i++) {
            value(d, from + i * STEP, normal + (i % 5) - 2);
        }
        return from + n * STEP;
    }

    private List<Change> of(EventType type) {
        return changes.stream().filter(c -> c.type() == type).toList();
    }

    /** Changes that made an incident calm (add-incident-resolution: calm, not closed, until a reason is given). */
    private List<Change> calmed() {
        return changes.stream().filter(c -> c.incident().getState() == lab.anomaly.contracts.v1.IncidentState.INCIDENT_STATE_CALM).toList();
    }

    private static final int WARM = 90;                  // 15 minutes of history, more than min-history (10 min)

    // ---- judging ----------------------------------------------------------------------------------------------

    @Test
    void latencyJumpOpensAnIncidentOnTheSecondStep() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        assertThat(value(inv, t, 1213)).isEmpty();
        List<Change> second = value(inv, t + STEP, 1194);
        assertThat(second).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_OPENED);
            assertThat(c.incident().getTitle()).isEqualTo("/v1/invoices: latency up");
            assertThat(c.incident().getStartedMs()).isEqualTo(t);
            assertThat(c.incident().getSeries(0).getNormalValue()).isEqualTo(138.0);
            assertThat(c.incident().getSeries(0).getPeakValue()).isEqualTo(1213.0);
            assertThat(c.incident().getSeries(0).getPanelTitle()).isEqualTo("Latencies");
            assertThat(c.incident().getLinks(0).getUrl()).startsWith("https://app.datadoghq.com/dashboard/" + LAB + "?from_ts=");
        });
    }

    @Test
    void oneOddStepOpensNothing() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        value(inv, t, 1213);
        normal(inv, t + STEP, 30, 138);
        assertThat(changes).isEmpty();
    }

    @Test
    void aStricterLatencyRuleNeedsThreeSteps() {
        DetectorSettings strict = Fixtures.holderEdited(t -> t.replace("min-change: 50\n        steps: 2", "min-change: 50\n        steps: 3")
                .replace("incident-scope: graph", "incident-scope: service")).get();
        detector.configure(strict, 2);
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        value(inv, t, 1213);
        value(inv, t + STEP, 1194);
        assertThat(changes).as("2 steps no longer enough").isEmpty();
        t = normal(inv, t + 2 * STEP, 30, 138);
        value(inv, t, 1213);
        value(inv, t + STEP, 1194);
        value(inv, t + 2 * STEP, 1300);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).hasSize(1);
    }

    @Test
    void successRateIsJudgedInPoints() {
        SeriesDescriptor sr = add(successRate("ffff000000000002", Map.of("api", "/v1/invoices", "http_method", "get"), "Success Rates"));
        long t = normal(sr, T0, WARM, 99);
        for (int i = 0; i < 5; i++) {
            value(sr, t + i * STEP, 95);                          // 4 points below 99: a wobble
        }
        assertThat(changes).isEmpty();
        t = normal(sr, t + 5 * STEP, 30, 99);
        value(sr, t, 74);
        value(sr, t + STEP, 74);
        assertThat(changes).as("rates need 3 steps").isEmpty();
        value(sr, t + 2 * STEP, 74);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getTitle()).isEqualTo("/v1/invoices: success rate down"));
    }

    @Test
    void aLowTrafficSuccessRateDoesNotAlarmOnChance() {
        SeriesDescriptor sr = add(successRate("ffff000000000012", Map.of("api", "/v1/firmware", "http_method", "get"), "Success Rates"));
        double[] noisy = {100, 100, 85.7, 100, 100, 71.4, 100, 100, 100, 85.7};   // one failure in 7 = 14 points
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(sr, t, noisy[i % noisy.length]);
            t += STEP;
        }
        value(sr, t, 85);
        value(sr, t + STEP, 85);
        value(sr, t + 2 * STEP, 85);
        assertThat(changes).isEmpty();
        value(sr, t + 3 * STEP, 40);
        value(sr, t + 4 * STEP, 40);
        value(sr, t + 5 * STEP, 40);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).as("a real fall still alarms").hasSize(1);
    }

    @Test
    void errorRateRisingOpensAnIncident() {
        SeriesDescriptor er = add(Fixtures.line("ffff000000000003", "lab.http.requests", Kind.KIND_COUNT, "requests",
                Map.of("service", "billing"), "jrp-8af-7xd", "Swagstore", "Server error (5xx) rate % per service"));
        long t = normal(er, T0, WARM, 3);
        value(er, t, 26);
        value(er, t + STEP, 26);
        value(er, t + 2 * STEP, 26);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getTitle()).isEqualTo("billing: error rate up"));
    }

    @Test
    void trafficIsWatchedBothWays() {
        SeriesDescriptor partner = add(count("ffff000000000004", Map.of("api", "/v1/invoices", "partner", "globex"), "API Requests by Partner"));
        SeriesDescriptor svc = add(Fixtures.line("ffff000000000005", "lab.http.requests", Kind.KIND_COUNT, "requests",
                Map.of("service", "orders"), "jrp-8af-7xd", "Swagstore", "Requests per service"));
        long t = normal(partner, T0, WARM, 30);
        normal(svc, T0, WARM, 50);
        value(partner, t, 8);
        value(partner, t + STEP, 8);
        value(svc, t, 200);
        value(svc, t + STEP, 200);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).extracting(c -> c.incident().getTitle())
                .containsExactlyInAnyOrder("/v1/invoices: traffic down", "orders: traffic up");
    }

    @Test
    void fromZeroErrorsToOneIsNotAnIncident() {
        SeriesDescriptor e = add(count("ffff000000000006", Map.of("api", "/v1/invoices", "status_code", "500"), "Count"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(e, t, 0);
            t += STEP;
        }
        value(e, t, 1);
        value(e, t + STEP, 1);
        assertThat(changes).isEmpty();
    }

    @Test
    void aPartialStepIsNeverJudged() {
        SeriesDescriptor sr = add(successRate("ffff000000000002", Map.of("api", "/v1/invoices"), "Success Rates"));
        long t = normal(sr, T0, WARM, 99);
        changes.addAll(detector.accept(sr.getSeriesId(), t, 40, true, t));
        changes.addAll(detector.accept(sr.getSeriesId(), t, 40, true, t));
        value(sr, t, 99);
        value(sr, t + STEP, 99);
        assertThat(changes).isEmpty();
    }

    @Test
    void theSameStepTwiceCountsOnce() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        value(inv, t, 1213);
        value(inv, t, 1213);                                       // a repeat: must not count as the second step
        assertThat(changes).isEmpty();
    }

    @Test
    void anUnknownLineIsWatchedByTheGeneralRule() {
        SeriesDescriptor q = add(Fixtures.line("ffff000000000007", "lab.queue.depth", Kind.KIND_GAUGE, "", Map.of(), LAB, LAB_TITLE, "Queue depth"));
        long t = normal(q, T0, WARM, 100);
        for (int i = 0; i < 5; i++) {
            value(q, t + i * STEP, 150);                          // half again: not twice
        }
        assertThat(changes).isEmpty();
        t = normal(q, t + 5 * STEP, 30, 100);
        for (int i = 0; i < 3; i++) {
            value(q, t + i * STEP, 300);
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).hasSize(1);
    }

    // ---- calm period ------------------------------------------------------------------------------------------

    @Test
    void aFlappingLineKeepsOneIncidentWhichClosesAfterTwoCalmMinutes() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        value(inv, t, 1213);
        value(inv, t + STEP, 1194);                                // opened
        t = normal(inv, t + 2 * STEP, 3, 138);                     // 30 s calm
        value(inv, t, 1300);                                       // spikes again
        value(inv, t + STEP, 1250);
        long firstCalm = t + 2 * STEP;
        normal(inv, firstCalm, 13, 138);                           // 130 s calm
        assertThat(of(EventType.EVENT_TYPE_OPENED)).hasSize(1);
        assertThat(of(EventType.EVENT_TYPE_CLOSED)).as("nothing closes without a reason").isEmpty();
        assertThat(calmed()).singleElement().satisfies(c -> {
            assertThat(c.incident().getEndedMs()).isEqualTo(firstCalm);
            assertThat(c.incident().getSeries(0).getPeakValue()).isEqualTo(1300.0);
        });
    }

    @Test
    void aSpikeReadAfterItsGroupClosedReopensTheSameIncident() {
        SeriesDescriptor lat = add(latency("ffff000000000001", "/v1/telemetry"));
        SeriesDescriptor p95 = add(Fixtures.line("ffff000000000013", "lab.api.latency", Kind.KIND_LATENCY, "ms",
                Map.of("api", "/v1/telemetry", "http_method", "post"), LAB, LAB_TITLE, "Latencies"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(lat, t + i * STEP, 60);
            value(p95, t + i * STEP, 60);
        }
        t += WARM * STEP;
        long spike = t;
        // Line 1 is read ahead: spike, then calm for 2+ minutes, so the incident closes.
        value(lat, t, 600);
        value(lat, t + STEP, 600);
        normal(lat, t + 2 * STEP, 14, 60);
        assertThat(calmed()).hasSize(1);
        // Line 2's values from the same moments arrive later: it spiked 20 s after line 1 started.
        normal(p95, t, 2, 60);
        value(p95, spike + 2 * STEP, 600);
        value(p95, spike + 3 * STEP, 600);
        // The calm incident opens again: announced as OPENED (pages notify, the problem is back), the same incident
        assertThat(of(EventType.EVENT_TYPE_OPENED)).extracting(c -> c.incident().getIncidentId()).as("no second incident")
                .containsOnly(of(EventType.EVENT_TYPE_OPENED).get(0).incident().getIncidentId());
        Change last = changes.get(changes.size() - 1);
        assertThat(last.incident().getIncidentId()).isEqualTo(of(EventType.EVENT_TYPE_OPENED).get(0).incident().getIncidentId());
        assertThat(last.incident().getState()).isEqualTo(lab.anomaly.contracts.v1.IncidentState.INCIDENT_STATE_OPEN);
        assertThat(last.incident().getSeriesCount()).isEqualTo(2);
    }

    @Test
    void aLastingChangeBecomesTheNewNormalAndTheIncidentCloses() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        normal(inv, t, 6 * 60, 800);                               // an hour at a new level
        assertThat(of(EventType.EVENT_TYPE_OPENED)).hasSize(1);
        assertThat(calmed()).hasSize(1);
    }

    // ---- silence ----------------------------------------------------------------------------------------------

    @Test
    void anUpstreamThatStopsWhileItsDashboardContinuesIsSilent() {
        SeriesDescriptor up = add(count("ffff000000000008", Map.of("upstream_service", "payments", "status_code", "200"), "Request Counts"));
        SeriesDescriptor other = add(count("ffff000000000009", Map.of("upstream_service", "billing-core", "status_code", "200"), "Request Counts"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(up, t, 30 + i % 3);
            value(other, t, 40 + i % 3);
            t += STEP;
        }
        for (int i = 0; i < 8; i++) {                              // 80 s: only the other line keeps arriving
            value(other, t, 40);
            changes.addAll(detector.checkSilence(t));
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement().satisfies(c -> {
            assertThat(c.incident().getTitle()).isEqualTo("upstream payments: traffic silent");
            assertThat(c.incident().getUpstream()).isTrue();
            assertThat(c.incident().getSeries(0).getReason()).isEqualTo("silent");
        });
    }

    @Test
    void aRareErrorCodeDoesNotAlarmOnSilence() {
        SeriesDescriptor rare = add(count("ffff00000000000a", Map.of("upstream_service", "payments", "status_code", "503"), "Request Counts"));
        SeriesDescriptor other = add(count("ffff000000000009", Map.of("upstream_service", "billing-core", "status_code", "200"), "Request Counts"));
        long t = T0;
        for (int i = 0; i < WARM + 60; i++) {
            if (i % 10 == 0 && i < WARM) {
                value(rare, t, 1);
            }
            value(other, t, 40);
            changes.addAll(detector.checkSilence(t));
            t += STEP;
        }
        assertThat(changes).isEmpty();
    }

    @Test
    void anErrorLineThatStopsReportingIsNotAnIncident() {
        SeriesDescriptor err = add(count("ffff000000000014", Map.of("upstream_service", "payments", "status_code", "500"), "Request Counts"));
        SeriesDescriptor other = add(count("ffff000000000009", Map.of("upstream_service", "billing-core", "status_code", "200"), "Request Counts"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(err, t, 1);
            value(other, t, 40);
            t += STEP;
        }
        for (int i = 0; i < 12; i++) {
            value(other, t, 40);
            changes.addAll(detector.checkSilence(t));
            t += STEP;
        }
        assertThat(changes).isEmpty();
    }

    @Test
    void aWholeDashboardStoppingIsACollectionProblemNotIncidents() {
        SeriesDescriptor a = add(count("ffff00000000000b", Map.of("service", "a"), "Requests per service"));
        SeriesDescriptor b = add(Fixtures.line("ffff00000000000c", "lab.http.requests", Kind.KIND_COUNT, "requests",
                Map.of("service", "b"), "jrp-8af-7xd", "Swagstore", "Requests per service"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(a, t, 30);
            value(b, t, 30);
            t += STEP;
        }
        for (int i = 0; i < 12; i++) {                             // the lab dashboard stops; Swagstore continues
            value(b, t, 30);
            changes.addAll(detector.checkSilence(t));
            t += STEP;
        }
        assertThat(changes).isEmpty();
        assertThat(detector.view().dashboardsNotArriving()).containsExactly(LAB_TITLE);
    }

    // ---- grouping ---------------------------------------------------------------------------------------------

    @Test
    void latencySuccessRateAndErrorsOfOneApiAreOneIncident() {
        SeriesDescriptor lat = add(latency("ffff000000000001", "/v1/invoices"));
        SeriesDescriptor sr = add(successRate("ffff000000000002", Map.of("api", "/v1/invoices", "http_method", "get"), "Success Rates"));
        SeriesDescriptor err = add(count("ffff000000000006", Map.of("api", "/v1/invoices", "status_code", "500"), "Count"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(lat, t, 138 + i % 3);
            value(sr, t, 99);
            value(err, t, 2);
            t += STEP;
        }
        for (int i = 0; i < 3; i++) {
            value(lat, t, 1213);
            value(sr, t, 74);
            value(err, t, 30);
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).hasSize(1);
        Incident last = changes.get(changes.size() - 1).incident();
        assertThat(last.getTitle()).isEqualTo("/v1/invoices: latency up, errors up, success rate down");
        assertThat(last.getSeriesCount()).isEqualTo(3);
        assertThat(last.getGroupKey()).isEqualTo("datadoghq.com|api=/v1/invoices");   // site first (add-multi-site M9)
    }

    @Test
    void twoApisAtOnceAreTwoIncidents() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        SeriesDescriptor ord = add(latency("ffff00000000000d", "/v1/orders"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(inv, t, 138);
            value(ord, t, 90);
            t += STEP;
        }
        for (int i = 0; i < 2; i++) {
            value(inv, t, 1213);
            value(ord, t, 900);
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).extracting(c -> c.incident().getGroupKey())
                .containsExactlyInAnyOrder("datadoghq.com|api=/v1/invoices", "datadoghq.com|api=/v1/orders");
    }

    @Test
    void anUpstreamIncidentSaysUpstream() {
        SeriesDescriptor sr = add(successRate("ffff00000000000e", Map.of("upstream_service", "payments"), "Success Rates"));
        SeriesDescriptor lat = add(Fixtures.line("ffff00000000000f", "lab.upstream.latency", Kind.KIND_LATENCY, "ms",
                Map.of("upstream_service", "payments"), LAB, LAB_TITLE, "Latencies"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(sr, t, 99);
            value(lat, t, 138);
            t += STEP;
        }
        for (int i = 0; i < 3; i++) {
            value(sr, t, 74);
            value(lat, t, 1213);
            t += STEP;
        }
        Incident last = changes.get(changes.size() - 1).incident();
        assertThat(last.getTitle()).isEqualTo("upstream payments: latency up, success rate down");
        assertThat(last.getUpstream()).isTrue();
    }

    @Test
    void aNewDashboardWithAnEndpointTagIsGroupedByDashboardUntilTheTagIsAdded() {
        SeriesDescriptor a = add(Fixtures.line("ffff000000000010", "pay.latency", Kind.KIND_LATENCY, "ms",
                Map.of("endpoint", "/pay"), "pay-dash", "Payments platform", "Latency"));
        SeriesDescriptor b = add(Fixtures.line("ffff000000000011", "pay.latency", Kind.KIND_LATENCY, "ms",
                Map.of("endpoint", "/refund"), "pay-dash", "Payments platform", "Latency"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(a, t, 100);
            value(b, t, 100);
            t += STEP;
        }
        for (int i = 0; i < 2; i++) {
            value(a, t, 900);
            value(b, t, 900);
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getTitle()).isEqualTo("Payments platform: latency up"));
        assertThat(detector.view().groupedByDashboard()).isEqualTo(2);

        detector.configure(Fixtures.holderEdited(text -> text.replace("group-by: [api,", "group-by: [endpoint, api,")
                .replace("incident-scope: graph", "incident-scope: service")).get(), 2);
        t = normal(a, t, 14, 100);                                 // calm: the first incident closes
        normal(b, t - 14 * STEP, 14, 100);
        assertThat(calmed()).hasSize(1);
        changes.clear();
        for (int i = 0; i < 2; i++) {
            value(a, t, 900);
            value(b, t, 900);
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).extracting(c -> c.incident().getTitle())
                .containsExactlyInAnyOrder("/pay: latency up", "/refund: latency up");
    }

    // ---- restart ----------------------------------------------------------------------------------------------

    @Test
    void afterARestartTheOpenIncidentContinuesAndClosesWithoutASecondOne() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        value(inv, t, 1213);
        value(inv, t + STEP, 1194);
        Change opened = of(EventType.EVENT_TYPE_OPENED).get(0);

        Detector restarted = new Detector(() -> "inc-new");
        restarted.configure(Fixtures.serviceScope(), 1);
        restarted.restore(opened.incident(), opened.sequence());
        restarted.describe(inv);
        long[] times = new long[WARM];
        double[] values = new double[WARM];
        for (int i = 0; i < WARM; i++) {
            times[i] = T0 + i * STEP;
            values[i] = 138;
        }
        restarted.warm(inv.getSeriesId(), times, values);
        List<Change> after = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            after.addAll(restarted.accept(inv.getSeriesId(), t + (2 + i) * STEP, 138, false, 0));
        }
        assertThat(after).singleElement().satisfies(c -> {
            assertThat(c.incident().getState()).isEqualTo(lab.anomaly.contracts.v1.IncidentState.INCIDENT_STATE_CALM);
            assertThat(c.incident().getIncidentId()).isEqualTo(opened.incident().getIncidentId());
            assertThat(c.sequence()).isEqualTo(opened.sequence() + 1);
        });
    }

    // ---- add-multi-site (design M9) ---------------------------------------------------------------------------

    private static SeriesDescriptor onSite(SeriesDescriptor d, String site) {
        return d.toBuilder().setSource(d.getSource().toBuilder().setInstance(site)).build();
    }

    @Test
    void sameApiInTwoRegionsIsTwoIncidentsNamedBySite() {
        SeriesDescriptor us = add(latency("ffff000000000001", "/v1/invoices"));
        SeriesDescriptor eu = add(onSite(latency("eeee000000000001", "/v1/invoices"), "datadoghq.eu"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(us, t, 138);
            value(eu, t, 140);
            t += STEP;
        }
        for (int i = 0; i < 2; i++) {
            value(us, t, 1213);
            value(eu, t, 1300);
            t += STEP;
        }
        List<Incident> opened = of(EventType.EVENT_TYPE_OPENED).stream().map(Change::incident).toList();
        assertThat(opened).extracting(Incident::getTitle)
                .containsExactlyInAnyOrder("US1 · /v1/invoices: latency up", "EU · /v1/invoices: latency up");
        assertThat(opened).extracting(Incident::getGroupKey)
                .containsExactlyInAnyOrder("datadoghq.com|api=/v1/invoices", "datadoghq.eu|api=/v1/invoices");
        Incident euIncident = opened.stream().filter(i -> i.getTitle().startsWith("EU")).findFirst().orElseThrow();
        assertThat(euIncident.getLinksList()).isNotEmpty()
                .allMatch(l -> l.getUrl().startsWith("https://app.datadoghq.eu/dashboard/"));
    }

    @Test
    void oneSiteKeepsTitlesAsBefore() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(inv, t, 138);
            t += STEP;
        }
        value(inv, t, 1213);
        value(inv, t + STEP, 1213);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).extracting(c -> c.incident().getTitle())
                .containsExactly("/v1/invoices: latency up");
    }

    // ---- fix-false-drops (design F3): error counts tagged "status" ---------------------------------------------

    @Test
    void fewer404sOpenNothing() {
        SeriesDescriptor notFound = add(count("aaaa000000000404", Map.of("status", "404"), "Request Counts by Status Code"));
        long t = normal(notFound, T0, WARM, 13);
        for (int i = 0; i < 6; i++) {
            value(notFound, t, 0);
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).isEmpty();
    }

    @Test
    void more500sOpenErrorsUp() {
        SeriesDescriptor serverError = add(count("aaaa000000000500", Map.of("status", "500"), "Request Counts by Status Code"));
        long t = normal(serverError, T0, WARM, 2);
        for (int i = 0; i < 4; i++) {
            value(serverError, t, 40);
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getSeriesList()).singleElement()
                        .satisfies(s -> assertThat(s.getSignal() + " " + s.getReason()).isEqualTo("errors up")));
    }

    // ---- add-incident-history task 2.2 ---------------------------------------------------------------------

    @Test
    void incidentsKeepEachLinesPanelGroup() {
        SeriesDescriptor base = latency("ffff000000000001", "/v1/invoices");
        SeriesDescriptor grouped = add(base.toBuilder().setViews(0, base.getViews(0).toBuilder().setPanelPath("API Metrics")).build());
        long t = normal(grouped, T0, WARM, 138);
        value(grouped, t, 1213);
        value(grouped, t + STEP, 1213);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement().satisfies(c -> assertThat(c.incident().getSeries(0))
                .satisfies(s -> {
                    assertThat(s.getPanelPath()).isEqualTo("API Metrics");
                    assertThat(s.getPanelTitle()).isEqualTo("Latencies");
                }));
    }

    // ---- add-incident-resolution: calm, a reason closes, re-announce --------------------------------------------

    /** An incident on /v1/invoices latency that is calm after 2 normal minutes; returns the time after it. */
    private long calmIncident(SeriesDescriptor inv) {
        long t = normal(inv, T0, WARM, 138);
        value(inv, t, 1213);
        value(inv, t + STEP, 1213);
        return normal(inv, t + 2 * STEP, 14, 138);
    }

    @Test
    void aCalmIncidentWaitsAndASpikeAnHourLaterIsTheSameIncident() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = calmIncident(inv);
        String id = calmed().get(0).incident().getIncidentId();
        assertThat(of(EventType.EVENT_TYPE_CLOSED)).isEmpty();
        t = normal(inv, t, 360, 138);                                   // an hour of normal values
        value(inv, t, 1300);
        value(inv, t + STEP, 1300);
        Change back = changes.get(changes.size() - 1);
        assertThat(back.type()).isEqualTo(EventType.EVENT_TYPE_OPENED);
        assertThat(back.incident().getIncidentId()).isEqualTo(id);
        assertThat(back.incident().getState()).isEqualTo(lab.anomaly.contracts.v1.IncidentState.INCIDENT_STATE_OPEN);
        assertThat(back.incident().getEndedMs()).isZero();
    }

    @Test
    void aReasonClosesACalmIncidentAndTheNextSpikeIsANewOne() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = calmIncident(inv);
        String id = calmed().get(0).incident().getIncidentId();
        List<Change> closed = detector.resolve(id, "  certificate renewal on the gateway ", 1_794_900_000_000L);
        assertThat(closed).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_CLOSED);
            assertThat(c.incident().getState()).isEqualTo(lab.anomaly.contracts.v1.IncidentState.INCIDENT_STATE_CLOSED);
            assertThat(c.incident().getReason()).isEqualTo("certificate renewal on the gateway");
            assertThat(c.incident().getResolvedMs()).isEqualTo(1_794_900_000_000L);
            assertThat(c.incident().getEndedMs()).isPositive();
        });
        assertThat(detector.resolve(id, "again", 1_794_900_001_000L)).as("the same command twice: closed once").isEmpty();
        changes.addAll(closed);
        value(inv, t, 1300);
        value(inv, t + STEP, 1300);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).extracting(c -> c.incident().getIncidentId()).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void aReasonClosesAnOpenIncidentAtOnceAndBadRequestsChangeNothing() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = normal(inv, T0, WARM, 138);
        value(inv, t, 1213);
        value(inv, t + STEP, 1213);
        String id = of(EventType.EVENT_TYPE_OPENED).get(0).incident().getIncidentId();
        assertThat(detector.resolve(id, "   ", 1L)).isEmpty();
        assertThat(detector.resolve(id, "x".repeat(501), 1L)).isEmpty();
        assertThat(detector.resolve("no-such-incident", "why", 1L)).isEmpty();
        assertThat(detector.resolve(id, "test environment switched off", 2L)).singleElement()
                .satisfies(c -> assertThat(c.incident().getReason()).isEqualTo("test environment switched off"));
    }

    @Test
    void waitingIncidentsAreAnnouncedAgainEveryTwelveHours() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        calmIncident(inv);
        Change calm = calmed().get(0);
        assertThat(detector.reannounce(calm.arrivedMs() + 3_600_000, 12 * 3_600_000L)).isEmpty();
        assertThat(detector.reannounce(calm.arrivedMs() + 12 * 3_600_000L, 12 * 3_600_000L)).singleElement().satisfies(c -> {
            assertThat(c.incident().getIncidentId()).isEqualTo(calm.incident().getIncidentId());
            assertThat(c.incident().getState()).isEqualTo(lab.anomaly.contracts.v1.IncidentState.INCIDENT_STATE_CALM);
            assertThat(c.sequence()).isEqualTo(calm.sequence() + 1);
        });
    }

    @Test
    void aCalmIncidentIsRestoredAfterARestartAndCanStillOpenAgain() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = calmIncident(inv);
        Change calm = calmed().get(0);
        Detector restarted = new Detector(() -> "inc-new");
        restarted.configure(Fixtures.serviceScope(), 1);
        restarted.restore(calm.incident(), calm.sequence());
        restarted.describe(inv);
        double[] values = new double[WARM];
        long[] times = new long[WARM];
        for (int i = 0; i < WARM; i++) {
            times[i] = t - (WARM - i) * STEP;
            values[i] = 138;
        }
        restarted.warm(inv.getSeriesId(), times, values);
        List<Change> after = new ArrayList<>();
        after.addAll(restarted.accept(inv.getSeriesId(), t, 1300, false, t));
        after.addAll(restarted.accept(inv.getSeriesId(), t + STEP, 1300, false, t + STEP));
        assertThat(after).filteredOn(c -> c.type() == EventType.EVENT_TYPE_OPENED).singleElement()
                .satisfies(c -> assertThat(c.incident().getIncidentId()).isEqualTo(calm.incident().getIncidentId()));
    }

    // ---- add-line-reasons: a reason per line, BAU lines need none -----------------------------------------------

    private static final lab.anomaly.contracts.v1.IncidentState CALM = lab.anomaly.contracts.v1.IncidentState.INCIDENT_STATE_CALM;

    /** Latency and 500s of /v1/invoices spike together (one incident of two lines) and go calm; returns the time after. */
    private long calmTwoLines(SeriesDescriptor lat, SeriesDescriptor err) {
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(lat, t, 138 + i % 3);
            value(err, t, 2);
            t += STEP;
        }
        for (int i = 0; i < 3; i++) {
            value(lat, t, 1213);
            value(err, t, 30);
            t += STEP;
        }
        for (int i = 0; i < 14; i++) {
            value(lat, t, 138 + i % 3);
            value(err, t, 2);
            t += STEP;
        }
        return t;
    }

    @Test
    void eachLineGetsItsOwnReasonAndTheLastOneClosesTheCalmIncident() {
        SeriesDescriptor lat = add(latency("ffff000000000001", "/v1/invoices"));
        SeriesDescriptor err = add(count("ffff000000000006", Map.of("api", "/v1/invoices", "status_code", "500"), "Count"));
        calmTwoLines(lat, err);
        String id = calmed().get(0).incident().getIncidentId();
        List<Change> first = detector.resolve(id, lat.getSeriesId(), "slow database", 5L);
        assertThat(first).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_UPDATED);
            assertThat(c.incident().getState()).isEqualTo(CALM);
            assertThat(c.incident().getSeriesList()).filteredOn(s -> s.getSeriesId().equals(lat.getSeriesId()))
                    .singleElement().satisfies(s -> {
                        assertThat(s.getExplanation()).isEqualTo("slow database");
                        assertThat(s.getExplainedMs()).isEqualTo(5L);
                    });
        });
        assertThat(detector.resolve(id, lat.getSeriesId(), "again", 6L)).as("an explained line is not waiting").isNotEmpty();
        List<Change> last = detector.resolve(id, err.getSeriesId(), "bad deploy", 7L);
        assertThat(last).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_CLOSED);
            assertThat(c.incident().getReason()).isEqualTo("2 reasons");
        });
    }

    @Test
    void sameReasonForAllClosesEvenAnOpenIncidentButOneLineDoesNot() {
        SeriesDescriptor lat = add(latency("ffff000000000001", "/v1/invoices"));
        SeriesDescriptor err = add(count("ffff000000000006", Map.of("api", "/v1/invoices", "status_code", "500"), "Count"));
        long t = T0;
        for (int i = 0; i < WARM; i++) {
            value(lat, t, 138 + i % 3);
            value(err, t, 2);
            t += STEP;
        }
        for (int i = 0; i < 3; i++) {
            value(lat, t, 1213);
            value(err, t, 30);
            t += STEP;
        }
        String id = of(EventType.EVENT_TYPE_OPENED).get(0).incident().getIncidentId();
        assertThat(detector.resolve(id, lat.getSeriesId(), "slow database", 1L)).singleElement()
                .satisfies(c -> assertThat(c.type()).as("open: a single line waits for calm").isEqualTo(EventType.EVENT_TYPE_UPDATED));
        assertThat(detector.resolve(id, "", "gateway restart", 2L)).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(EventType.EVENT_TYPE_CLOSED);
            assertThat(c.incident().getSeriesList()).extracting(s -> s.getExplanation())
                    .containsExactlyInAnyOrder("slow database", "gateway restart");
            assertThat(c.incident().getReason()).isEqualTo("2 reasons");
        });
    }

    @Test
    void aGeneralLineIsBauAndABauOnlyIncidentClosesByItself() {
        SeriesDescriptor q = add(Fixtures.line("ffff000000000007", "lab.queue.depth", Kind.KIND_GAUGE, "", Map.of(), LAB, LAB_TITLE, "Queue depth"));
        long t = normal(q, T0, WARM, 100);
        for (int i = 0; i < 3; i++) {
            value(q, t + i * STEP, 300);
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement().satisfies(c -> assertThat(c.incident().getSeries(0)).satisfies(s -> {
            assertThat(s.getBau()).isTrue();
            assertThat(s.getExplanation()).isEqualTo("BAU (business as usual)");
        }));
        normal(q, t + 3 * STEP, 14, 100);
        assertThat(calmed()).isEmpty();
        assertThat(of(EventType.EVENT_TYPE_CLOSED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getReason()).isEqualTo("BAU"));
    }

    @Test
    void fewerServerErrorsAreBauButMoreAreNot() {
        SeriesDescriptor e = add(count("aaaa000000000500", Map.of("status", "500"), "Request Counts by Status Code"));
        assertThat(Fixtures.defaults().isBau("errors", "down")).isTrue();
        assertThat(Fixtures.defaults().isBau("errors", "up")).isFalse();
        assertThat(Fixtures.defaults().isBau("general", "silent")).isTrue();
        long t = normal(e, T0, WARM, 2);
        for (int i = 0; i < 4; i++) {
            value(e, t, 40);
            t += STEP;
        }
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getSeries(0).getBau()).isFalse());
    }

    @Test
    void anExplainedLineThatSpikesAgainNeedsANewReason() {
        SeriesDescriptor lat = add(latency("ffff000000000001", "/v1/invoices"));
        SeriesDescriptor err = add(count("ffff000000000006", Map.of("api", "/v1/invoices", "status_code", "500"), "Count"));
        long t = calmTwoLines(lat, err);
        String id = calmed().get(0).incident().getIncidentId();
        detector.resolve(id, lat.getSeriesId(), "slow database", 5L);
        value(lat, t, 1300);
        value(lat, t + STEP, 1300);
        Change back = changes.get(changes.size() - 1);
        assertThat(back.incident().getIncidentId()).isEqualTo(id);
        assertThat(back.incident().getSeriesList()).filteredOn(s -> s.getSeriesId().equals(lat.getSeriesId()))
                .singleElement().satisfies(s -> assertThat(s.getExplanation()).isEmpty());
    }

    // ---- add-backfill: past days of a new dashboard -------------------------------------------------------------

    private List<Change> past(SeriesDescriptor d, long ts, double v) {
        List<Change> c = detector.accept(d.getSeriesId(), ts, v, false, ts + 20_000, true);
        changes.addAll(c);
        return c;
    }

    private long pastNormal(SeriesDescriptor d, long from, int n, double normal) {
        for (int i = 0; i < n; i++) {
            past(d, from + i * STEP, normal + (i % 5) - 2);
        }
        return from + n * STEP;
    }

    @Test
    void aSpikeInTheBackfillIsRecordedAsPastAndClosesByItself() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = pastNormal(inv, T0, WARM, 138);
        past(inv, t, 1213);
        past(inv, t + STEP, 1213);
        pastNormal(inv, t + 2 * STEP, 14, 138);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getFoundInBackfill()).isTrue());
        assertThat(calmed()).as("never waits for a reason").isEmpty();
        assertThat(of(EventType.EVENT_TYPE_CLOSED)).singleElement().satisfies(c -> {
            assertThat(c.incident().getReason()).isEqualTo("found in backfill");
            assertThat(c.incident().getFoundInBackfill()).isTrue();
            assertThat(c.incident().getStartedMs()).isEqualTo(t);
            assertThat(c.incident().getEndedMs()).isPositive();
        });
    }

    @Test
    void anAnomalyStillGoingOnWhenLiveValuesArriveContinuesAsLive() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = pastNormal(inv, T0, WARM, 138);
        past(inv, t, 1213);
        past(inv, t + STEP, 1213);                                    // the backfill ends inside the anomaly
        String id = of(EventType.EVENT_TYPE_OPENED).get(0).incident().getIncidentId();
        value(inv, t + 2 * STEP, 1250);                               // the first live value, still abnormal
        List<Change> opened = of(EventType.EVENT_TYPE_OPENED);
        assertThat(opened).hasSize(2);
        assertThat(opened.get(1).incident().getIncidentId()).isEqualTo(id);
        assertThat(opened.get(1).incident().getFoundInBackfill()).isFalse();
        normal(inv, t + 3 * STEP, 14, 138);
        assertThat(calmed()).as("live now: it waits for a reason like any other").hasSize(1);
    }

    @Test
    void theBackfillWarmsTheLineSoLiveValuesAreJudgedAtOnce() {
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        long t = pastNormal(inv, T0, WARM, 138);                       // 15 minutes of past values only
        value(inv, t, 1213);
        value(inv, t + STEP, 1213);
        assertThat(of(EventType.EVENT_TYPE_OPENED)).singleElement()
                .satisfies(c -> assertThat(c.incident().getFoundInBackfill()).isFalse());
    }

    @Test
    void aRestoredWaitingIncidentIsAnnouncedAgainAtOnce() {                  // keep-one-day: Kafka keeps only 24 hours
        SeriesDescriptor inv = add(latency("ffff000000000001", "/v1/invoices"));
        calmIncident(inv);
        Change calm = calmed().get(0);
        Detector restarted = new Detector(() -> "inc-new");
        restarted.configure(Fixtures.serviceScope(), 1);
        restarted.restore(calm.incident(), calm.sequence());
        assertThat(restarted.reannounce(System.currentTimeMillis(), 12 * 3_600_000L)).singleElement()
                .satisfies(c -> assertThat(c.incident().getIncidentId()).isEqualTo(calm.incident().getIncidentId()));
        assertThat(restarted.reannounce(System.currentTimeMillis() + 60_000, 12 * 3_600_000L)).as("then every 12 hours").isEmpty();
    }
}
