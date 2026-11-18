package lab.anomaly.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** Task 2.1: lab limits (user, 15 Nov 2026). */
class StoreSettingsTest {

    static StoreSettings with(Duration rawSafety, Duration summary) {
        return new StoreSettings(9102, rawSafety, summary, 500, Duration.ofMillis(200),
                Duration.ofDays(7), DataSize.ofGigabytes(1), Duration.ofMinutes(1));
    }

    private static List<String> problems(StoreSettings s) {
        return StoreSettings.validators().stream().map(v -> v.check(s)).flatMap(Optional::stream).toList();
    }

    @Test
    void twoDayDefaultsPass() {
        assertThat(problems(with(Duration.ofDays(2), Duration.ofDays(2)))).isEmpty();
    }

    @Test
    void keepingRawPointsForAMonthIsRejected() {
        assertThat(problems(with(Duration.ofDays(30), Duration.ofDays(2))))
                .containsExactly("raw-safety-limit: at most 10 days: this is a lab system and the database must stay small");
    }

    @Test
    void keepingSummariesForTwoWeeksIsRejected() {
        assertThat(problems(with(Duration.ofDays(2), Duration.ofDays(14))))
                .containsExactly("summary-retention: at most 10 days: this is a lab system and the database must stay small");
    }

    @Test
    void lessThanADayOfRawPointsIsRejectedBecauseAReportNeedsADay() {
        assertThat(problems(with(Duration.ofHours(6), Duration.ofDays(2))))
                .containsExactly("raw-safety-limit: at least 1 day: a report covers up to one day of raw points");
    }
}
