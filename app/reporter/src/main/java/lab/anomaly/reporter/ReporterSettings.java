package lab.anomaly.reporter;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import lab.anomaly.kit.settings.SettingsValidator;

/**
 * The Reporter's settings (config-repo/reporter.yml, prefix {@code anomaly.reporter}), design R8. Immutable.
 * The time zone (where midnight is, and the times in the report) is the shared {@code anomaly.shared.time-zone}.
 *
 * @param window        report window: 1, 2, 3, 4, 6, 8, 12 or 24 hours (divides a day; never more than a day)
 * @param grace         wait after a window ends before reporting it
 * @param keepBeforeEnd raw points kept before the window's end (the Store keeps the last 3 hours anyway)
 * @param catchUp       how far back missed windows are reported (at most 2 days: what Kafka and the Store keep)
 * @param outputDir     folder of the reports and the state file
 * @param keepReports   report files kept, oldest removed first
 * @param storeAddress  host:port of the Store's gRPC
 */
public record ReporterSettings(Duration window, Duration grace, Duration keepBeforeEnd, Duration catchUp, String outputDir,
                               int keepReports, String storeAddress) {

    static final Set<Long> WINDOW_HOURS = Set.of(1L, 2L, 3L, 4L, 6L, 8L, 12L, 24L);

    static List<SettingsValidator<? super ReporterSettings>> validators() {
        return List.of(
                SettingsValidator.require("window", s -> s.window() != null && s.window().toMinutes() % 60 == 0
                        && WINDOW_HOURS.contains(s.window().toHours()), "must be 1h, 2h, 3h, 4h, 6h, 8h, 12h or 24h (a report covers at most a day)"),
                SettingsValidator.positive("grace", ReporterSettings::grace),
                SettingsValidator.positive("keep-before-end", ReporterSettings::keepBeforeEnd),
                SettingsValidator.positive("catch-up", ReporterSettings::catchUp),
                SettingsValidator.require("catch-up", s -> s.catchUp() == null || s.catchUp().compareTo(Duration.ofDays(2)) <= 0,
                        "at most 2 days: older data is no longer kept"),
                SettingsValidator.require("output-dir", s -> s.outputDir() != null && !s.outputDir().isBlank(), "must not be empty"),
                SettingsValidator.require("keep-reports", s -> s.keepReports() >= 1, "must be at least 1"),
                SettingsValidator.require("store-address", s -> s.storeAddress() != null && s.storeAddress().matches("[^:]+:\\d+"), "must be host:port"));
    }
}
