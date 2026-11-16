package lab.anomaly.reporter.window;

import static lab.anomaly.reporter.Fixtures.KOLKATA;
import static lab.anomaly.reporter.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Task 2.2: window keys, six-hour windows, grace, catch-up oldest first. */
class ReportWindowTest {

    @Test
    void aDayStartsAtMidnightInTheZoneAndIsNamedByItsDate() {
        ReportWindow w = ReportWindow.of(LocalDate.parse("2026-11-16"), 0, Duration.ofHours(24), KOLKATA);
        assertThat(w.key()).isEqualTo("2026-11-16");
        assertThat(w.startMs()).isEqualTo(at("2026-11-16T00:00:00"));
        assertThat(w.endMs()).isEqualTo(at("2026-11-17T00:00:00"));
        assertThat(w.previous().key()).isEqualTo("2026-11-15");
    }

    @Test
    void sixHourWindowsAreFourADay() {
        assertThat(ReportWindow.ofDay(LocalDate.parse("2026-11-16"), Duration.ofHours(6), KOLKATA)).extracting(ReportWindow::key)
                .containsExactly("2026-11-16_00-06", "2026-11-16_06-12", "2026-11-16_12-18", "2026-11-16_18-24");
        ReportWindow first = ReportWindow.of(LocalDate.parse("2026-11-16"), 0, Duration.ofHours(6), KOLKATA);
        assertThat(first.previous().key()).isEqualTo("2026-11-15_18-24");
    }

    @Test
    void aWindowIsNotDueBeforeItsGrace() {
        Duration day = Duration.ofHours(24);
        assertThat(ReportWindow.due(at("2026-11-17T00:01:00"), day, Duration.ofMinutes(2), Duration.ofDays(2), KOLKATA))
                .extracting(ReportWindow::key).doesNotContain("2026-11-16");
        assertThat(ReportWindow.due(at("2026-11-17T00:02:00"), day, Duration.ofMinutes(2), Duration.ofDays(2), KOLKATA))
                .extracting(ReportWindow::key).contains("2026-11-16");
    }

    @Test
    void missedWindowsOfANightComeOldestFirstWithinTheCatchUp() {
        assertThat(ReportWindow.due(at("2026-11-17T09:00:00"), Duration.ofHours(6), Duration.ofMinutes(2), Duration.ofHours(12), KOLKATA))
                .extracting(ReportWindow::key).containsExactly("2026-11-16_18-24", "2026-11-17_00-06");
    }
}
