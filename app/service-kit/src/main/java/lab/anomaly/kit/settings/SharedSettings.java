package lab.anomaly.kit.settings;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Settings every service shares (config-repo/application.yml, prefix {@code anomaly.shared}).
 * {@code timeZone} decides the day (the page's today, a daily report's window); {@code displayTimeZones} decides how
 * times are written: in each zone, the first being the main one (add-display-time-zones design Z1).
 */
public record SharedSettings(String timeZone, String environment, List<String> displayTimeZones) {

    public static final List<String> DEFAULT_DISPLAY_ZONES = List.of("UTC", "Asia/Kolkata", "America/New_York");

    @ConstructorBinding
    public SharedSettings {
        displayTimeZones = displayTimeZones == null ? DEFAULT_DISPLAY_ZONES : List.copyOf(displayTimeZones);
    }

    public SharedSettings(String timeZone, String environment) {
        this(timeZone, environment, null);
    }

    /** The display zones as zone ids (validated). */
    public List<ZoneId> displayZones() {
        return displayTimeZones.stream().map(ZoneId::of).toList();
    }

    static List<SettingsValidator<? super SharedSettings>> validators() {
        return List.of(
                SettingsValidator.require("time-zone", s -> isZone(s.timeZone()), "not a known time zone, e.g. UTC or Asia/Kolkata"),
                SettingsValidator.require("environment", s -> s.environment() != null && !s.environment().isBlank(),
                        "must not be empty"),
                SharedSettings::validDisplayZones);
    }

    static Optional<String> validDisplayZones(SharedSettings s) {
        if (s.displayTimeZones().isEmpty() || s.displayTimeZones().size() > 4) {
            return Optional.of("display-time-zones: list 1 to 4 time zones, the main one first, e.g. [UTC, Asia/Kolkata, America/New_York]");
        }
        for (String zone : s.displayTimeZones()) {
            if (!isZone(zone)) {
                return Optional.of("display-time-zones: '" + zone + "' is not a known time zone");
            }
        }
        return Optional.empty();
    }

    private static boolean isZone(String zone) {
        try {
            return zone != null && ZoneId.of(zone) != null;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
