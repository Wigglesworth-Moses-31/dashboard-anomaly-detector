package lab.anomaly.kit.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** add-display-time-zones tasks 1.1, 1.2. */
class TimesTest {

    private static final List<ZoneId> ZONES = new SharedSettings("UTC", "lab").displayZones();

    @Test
    void winterSummerAndAnotherDay() {
        assertThat(Times.format(Instant.parse("2026-11-17T13:04:10Z"), ZONES, true)).isEqualTo("13:04:10 UTC · 18:34:10 IST · 08:04:10 EST");
        assertThat(Times.format(Instant.parse("2026-07-17T13:04:10Z"), ZONES, true)).isEqualTo("13:04:10 UTC · 18:34:10 IST · 09:04:10 EDT");
        assertThat(Times.format(Instant.parse("2026-11-17T23:10:00Z"), ZONES, false)).isEqualTo("23:10 UTC · 04:40 IST (18 Nov) · 18:10 EST");
        assertThat(Times.format(Instant.parse("2026-11-17T23:10:00Z"), List.of(ZoneId.of("UTC")), false)).isEqualTo("23:10 UTC");
    }

    @Test
    void displayZonesHaveADefaultAndAreChecked() {
        assertThat(new SharedSettings("UTC", "lab").displayTimeZones()).containsExactly("UTC", "Asia/Kolkata", "America/New_York");
        assertThat(SharedSettings.validDisplayZones(new SharedSettings("UTC", "lab", List.of("UTC", "Asia/Kolkatta"))))
                .contains("display-time-zones: 'Asia/Kolkatta' is not a known time zone");
        assertThat(SharedSettings.validDisplayZones(new SharedSettings("UTC", "lab", List.of())).isPresent()).isTrue();
        assertThat(SharedSettings.validDisplayZones(new SharedSettings("UTC", "lab", List.of("UTC", "UTC", "UTC", "UTC", "UTC"))).isPresent()).isTrue();
        assertThat(SharedSettings.validDisplayZones(new SharedSettings("UTC", "lab", List.of("America/New_York", "UTC")))).isEmpty();
    }
}
