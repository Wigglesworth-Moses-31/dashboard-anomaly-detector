package lab.anomaly.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Task 3.2: bad settings are rejected (an unknown shared time zone is checked live in task 5.3). */
class NotifierSettingsTest {

    private static List<String> problems(NotifierSettings s) {
        return NotifierSettings.validators().stream().map(v -> v.check(s)).flatMap(Optional::stream).toList();
    }

    @Test
    void defaultsPassAndBadValuesAreRejected() {
        assertThat(problems(new NotifierSettings(7104, "started", 2000))).isEmpty();
        assertThat(problems(new NotifierSettings(7104, "yesterday", 2000))).containsExactly("day-by: must be started or received");
        assertThat(problems(new NotifierSettings(80, "started", 2000))).hasSize(1);
        assertThat(problems(new NotifierSettings(7104, "started", 1))).hasSize(1);
    }
}
