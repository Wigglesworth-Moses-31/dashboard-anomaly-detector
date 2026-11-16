package lab.anomaly.kit.settings;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

/** Settings every service shares (config-repo/application.yml, prefix {@code anomaly.shared}). */
public record SharedSettings(String timeZone, String environment) {

    static List<SettingsValidator<? super SharedSettings>> validators() {
        return List.of(
                SettingsValidator.require("time-zone", s -> isZone(s.timeZone()), "not a known time zone, e.g. Asia/Kolkata"),
                SettingsValidator.require("environment", s -> s.environment() != null && !s.environment().isBlank(),
                        "must not be empty"));
    }

    private static boolean isZone(String zone) {
        try {
            return zone != null && ZoneId.of(zone) != null;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
