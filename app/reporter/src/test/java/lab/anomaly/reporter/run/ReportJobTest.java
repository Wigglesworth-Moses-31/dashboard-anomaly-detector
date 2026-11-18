package lab.anomaly.reporter.run;

import static lab.anomaly.reporter.Fixtures.KOLKATA;
import static lab.anomaly.reporter.Fixtures.at;
import static lab.anomaly.reporter.Fixtures.incident;
import static lab.anomaly.reporter.Fixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import lab.anomaly.reporter.Fixtures.FakeSources;
import lab.anomaly.reporter.window.ReportWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Task 2.5: exactly once across restarts at every step; nothing removed unless the report is verified. */
class ReportJobTest {

    static final ReportWindow DAY = ReportWindow.of(LocalDate.parse("2026-11-16"), 0, Duration.ofHours(24), KOLKATA);
    static final long NOW = at("2026-11-17T00:02:00");
    static final long HOUR = 3_600_000;

    @TempDir
    Path dir;
    FakeSources sources;

    @BeforeEach
    void setUp() {
        sources = new FakeSources().add(incident("inv", "/v1/invoices: latency up", "2026-11-16T17:04:00", "2026-11-16T17:07:00",
                line("latency", "up", 1569, 138, "ms", "Latencies")));
    }

    private ReportState.Step run() throws Exception {
        return new ReportJob(sources, new ReportState(dir), dir).run(DAY, HOUR, NOW);   // a new state object = a restart
    }

    @Test
    void oneRunGoesAllTheWayAndRemovesNothing() throws Exception {                 // keep-ten-days T2
        assertThat(run()).isEqualTo(ReportState.Step.CLEANED);
        assertThat(sources.removeCalls).isEmpty();
        assertThat(Files.readString(dir.resolve("2026-11-16.md"))).contains("**1 incident**");
        assertThat(new ReportState(dir).get("2026-11-16").removedPoints()).isZero();
    }

    @Test
    void aRunAgainAfterCleaningChangesNothing() throws Exception {
        run();
        long written = Files.getLastModifiedTime(dir.resolve("2026-11-16.md")).toMillis();
        sources.events.clear();                                       // even if the data changed
        assertThat(run()).isEqualTo(ReportState.Step.CLEANED);
        assertThat(sources.removeCalls).isEmpty();
        assertThat(Files.getLastModifiedTime(dir.resolve("2026-11-16.md")).toMillis()).isEqualTo(written);
    }

    @Test
    void aRestartAfterWritingFinishesWithoutASecondReport() throws Exception {
        run();
        String first = Files.readString(dir.resolve("2026-11-16.md"));
        new ReportState(dir).put("2026-11-16", new ReportState.Entry(ReportState.Step.VERIFIED, 1, 0, 0, NOW));   // crash after verifying
        sources.events.clear();                                       // a second write would now say "No incidents"
        assertThat(run()).isEqualTo(ReportState.Step.CLEANED);
        assertThat(Files.readString(dir.resolve("2026-11-16.md"))).isEqualTo(first);
        assertThat(sources.removeCalls).isEmpty();
    }

    @Test
    void aWrittenButDamagedReportIsWrittenAgainBeforeAnythingIsRemoved() throws Exception {
        new ReportState(dir).put("2026-11-16", new ReportState.Entry(ReportState.Step.WRITTEN, 1, 0, 0, NOW));
        Files.writeString(dir.resolve("2026-11-16.md"), "# Incident report (cut off");
        assertThat(run()).isEqualTo(ReportState.Step.CLEANED);
        assertThat(lab.anomaly.reporter.report.Completion.verify(Files.readString(dir.resolve("2026-11-16.md")))).isEqualTo(1);
    }

    @Test
    void aFailedWriteRemovesNothing() throws Exception {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "a file where the folder should be");
        assertThatThrownBy(() -> new ReportJob(sources, new ReportState(dir), blocked).run(DAY, HOUR, NOW)).isInstanceOf(java.io.IOException.class);
        assertThat(sources.removeCalls).isEmpty();
        assertThat(new ReportState(dir).step("2026-11-16")).isEqualTo(ReportState.Step.PENDING);
    }
}
