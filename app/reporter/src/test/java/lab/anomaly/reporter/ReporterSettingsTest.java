package lab.anomaly.reporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Task 2.1: windows that do not divide a day, longer than a day, or a catch-up beyond 2 days are rejected. */
class ReporterSettingsTest {

    static ReporterSettings with(Duration window, Duration catchUp) {
        return new ReporterSettings(window, Duration.ofMinutes(2), Duration.ofHours(1), catchUp, "reports", 30, "localhost:9102");
    }

    private static List<String> problems(ReporterSettings s) {
        return ReporterSettings.validators().stream().map(v -> v.check(s)).flatMap(Optional::stream).toList();
    }

    @Test
    void validAndInvalidWindows() {
        assertThat(problems(with(Duration.ofHours(24), Duration.ofDays(2)))).isEmpty();
        assertThat(problems(with(Duration.ofHours(6), Duration.ofDays(2)))).isEmpty();
        assertThat(problems(with(Duration.ofHours(48), Duration.ofDays(2)))).singleElement().asString().startsWith("window:");
        assertThat(problems(with(Duration.ofHours(5), Duration.ofDays(2)))).singleElement().asString().startsWith("window:");
        assertThat(problems(with(Duration.ofMinutes(90), Duration.ofDays(2)))).singleElement().asString().startsWith("window:");
    }

    @Test
    void aCatchUpBeyondTwoDaysIsRejected() {
        assertThat(problems(with(Duration.ofHours(24), Duration.ofDays(3)))).containsExactly("catch-up: at most 2 days: older data is no longer kept");
    }
}
