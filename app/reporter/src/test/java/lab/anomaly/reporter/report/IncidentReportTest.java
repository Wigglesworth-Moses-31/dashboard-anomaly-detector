package lab.anomaly.reporter.report;

import static lab.anomaly.reporter.Fixtures.KOLKATA;
import static lab.anomaly.reporter.Fixtures.descriptor;
import static lab.anomaly.reporter.Fixtures.incident;
import static lab.anomaly.reporter.Fixtures.line;
import static lab.anomaly.reporter.Fixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.reporter.window.ReportWindow;
import org.junit.jupiter.api.Test;

/** Task 2.4: what a report says. */
class IncidentReportTest {

    static final ReportWindow DAY = ReportWindow.of(LocalDate.parse("2026-11-16"), 0, Duration.ofHours(24), KOLKATA);

    static List<Incident> incidents() {
        return List.of(
                incident("inv1", "/v1/invoices: latency up, errors up", "2026-11-16T16:56:00", "2026-11-16T16:59:50",
                        line("latency", "up", 1213, 138, "ms", "Latencies"), line("errors", "up", 8, 0, "requests", "Count")),
                incident("bill", "billing: errors up", "2026-11-16T17:04:00", "2026-11-16T17:07:00",
                        line("errors", "up", 30, 2, "requests", "Status codes")),
                incident("inv2", "/v1/invoices: latency up, errors up", "2026-11-16T17:12:00", "2026-11-16T17:16:00",
                        line("latency", "up", 1569, 138, "ms", "Latencies"), line("success-rate", "down", 74, 99, "percent", "Success Rates")),
                incident("late", "Lab: API platform | gateway: latency up", "2026-11-16T23:58:00", null,
                        line("latency", "up", 900, 100, "ms", "Latency")));
    }

    static String render(List<Incident> inc, Integer previous) {
        List<SeriesSummary> sums = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sums.add(summary("l" + i, 8640, 100 + i * 10, 100));               // max/mean 1.0 .. 2.9
        }
        sums.add(summary("u1", 10, 1, 1));                                     // unclassified lines with values
        sums.add(summary("u2", 10, 1, 1));
        return new IncidentReport().render(new ReportData(DAY, inc, previous, sums,
                List.of(descriptor("l19", Kind.KIND_LATENCY, "Latencies"), descriptor("u1", Kind.KIND_UNKNOWN, "Success rate % per service"),
                        descriptor("u2", Kind.KIND_UNKNOWN, "Success rate % per service")), 10_000));
    }

    @Test
    void theHeadlineSaysWhatHappened() {
        String md = render(incidents(), 29);
        assertThat(md).startsWith("# Incident report · Mon 16 Nov 2026 · Asia/Kolkata (00:00–24:00)");
        assertThat(md).contains("**4 incidents** (previous window 29) · longest 4 min (/v1/invoices) · 1 still open at the window's end");
        assertThat(md).contains("Data: 22 lines, ");
    }

    @Test
    void theTableIsOldestFirstAndEachIncidentHasItsEvidence() {
        String md = render(incidents(), null);
        assertThat(md.indexOf("| 16:56:00 | 16:59:50 | 3 min |")).isLessThan(md.indexOf("| 17:04:00 | 17:07:00 | 3 min |"));
        assertThat(md).contains("| 23:58:00 | open | – |");
        assertThat(md).contains("| latency up | 1,569 ms | 138 ms | 11.4× | Latencies (api=/v1/invoices) |");
        assertThat(md).contains("| errors up | 8.0 | 0 | new | Count (api=/v1/invoices) |");
        assertThat(md).contains("| success rate down | 74.0% | 99.0% | −25.0 pts | Success Rates (api=/v1/invoices) |");
        assertThat(md).contains("[Lab: API platform at 16:56](https://app.datadoghq.com/dashboard/w2f-pqs-ghd?from_ts=1&to_ts=2)");
    }

    @Test
    void patternsBrokeMostMovedMostAndUnclassified() {
        String md = render(incidents(), null);
        assertThat(md).contains("## Broke most often\n\n/v1/invoices 2 · Lab: API platform \\| gateway 1 · billing 1");
        assertThat(md).contains("## Lines that moved most");
        String moved = md.substring(md.indexOf("## Lines that moved most"), md.indexOf("## Lines not classified"));
        assertThat(moved.lines().filter(l -> l.startsWith("| ") && !l.startsWith("| Line")).count())
                .as("top 10 of 20").isEqualTo(10);
        assertThat(md).contains("| m.l19 | 290 ms | 100 ms | 2.9× | Latencies |");
        assertThat(md).contains("| Swagstore / Success rate % per service | 2 |");
    }

    @Test
    void aQuietWindowStillGetsAReport() {
        String md = render(List.of(), 37);
        assertThat(md).contains("**No incidents** in this window (previous window 37)");
        assertThat(new IncidentReport().render(new ReportData(DAY, List.of(), null, List.of(),
                List.of(descriptor("u1", Kind.KIND_UNKNOWN, "x")), 10_000))).as("no values in the window: not listed")
                .doesNotContain("Lines not classified");
        assertThat(md).doesNotContain("| Start | End |");
        assertThat(Completion.verify(md)).isZero();
    }

    @Test
    void titlesAreEscapedAndTheCompletionLineSealsTheReport() {
        String md = render(incidents(), null);
        assertThat(md).contains("Lab: API platform \\| gateway: latency up");
        assertThat(Completion.verify(md)).isEqualTo(4);
        assertThat(Completion.verify(md.replace("1,569 ms", "1,570 ms"))).as("changed").isEqualTo(-1);
        assertThat(Completion.verify(md.substring(0, md.length() / 2))).as("truncated").isEqualTo(-1);
    }

    // ---- add-multi-site task 5.1 ------------------------------------------------------------------------------

    private static lab.anomaly.contracts.v1.SeriesDescriptor on(lab.anomaly.contracts.v1.SeriesDescriptor d, String site) {
        return d.toBuilder().setSource(lab.anomaly.contracts.v1.Source.newBuilder().setSystem("datadog").setInstance(site)).build();
    }

    @Test
    void twoSitesAreNamedInTheTables() {
        List<SeriesSummary> sums = List.of(summary("l1", 8640, 900, 100), summary("u1", 10, 1, 1), summary("u2", 10, 1, 1));
        String md = new IncidentReport().render(new ReportData(DAY, List.of(), null, sums,
                List.of(on(descriptor("l1", Kind.KIND_LATENCY, "Latencies"), "datadoghq.eu"),
                        on(descriptor("u1", Kind.KIND_UNKNOWN, "Rate"), "datadoghq.com"),
                        on(descriptor("u2", Kind.KIND_UNKNOWN, "Rate"), "datadoghq.eu")), 10_000));
        assertThat(md).contains("| EU · Latencies |");
        assertThat(md).contains("| US1 · Swagstore / Rate | 1 |").contains("| EU · Swagstore / Rate | 1 |");
    }

    @Test
    void oneSiteKeepsTheTablesAsBefore() {
        String md = render(incidents(), null);
        assertThat(md).doesNotContain("US1 · ").contains("| Swagstore / Success rate % per service | 2 |");
    }
}
