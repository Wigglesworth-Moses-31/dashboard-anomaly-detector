package lab.anomaly.notifier;

import java.util.List;
import java.util.Set;
import lab.anomaly.kit.settings.SettingsValidator;

/**
 * The Notifier's settings (config-repo/notifier.yml, prefix {@code anomaly.notifier}), design N7. Immutable.
 * The time zone ("today") is the shared setting {@code anomaly.shared.time-zone}, used by the Reporter too.
 *
 * @param rsocketPort               WebSocket port for pages
 * @param dayBy                     {@code started}: an incident's day is the day it started; {@code received}: the day
 *                                  the Notifier received it (demos of older recordings)
 * @param maxQueuedUpdatesPerViewer cap of each viewer's latest-wins tray
 */
public record NotifierSettings(int rsocketPort, String dayBy, int maxQueuedUpdatesPerViewer, Dashboards dashboards) {

    /**
     * The dashboard pages (add-dashboard-view D6): the ranges offered (the first is the default; up to 1 hour they use
     * the 10-second values, longer ones the per-minute values), the most points per line sent to a page, the most
     * lines a panel shows on the region page, the Store's address and how often the line catalogue is rebuilt.
     */
    public record Dashboards(List<String> ranges, Integer pointsPerLine, Integer linesPerPanel, String storeAddress,
                             java.time.Duration catalogueRefresh) {
        public Dashboards {
            ranges = ranges == null || ranges.isEmpty() ? List.of("15m", "1h", "4h", "1d", "7d") : List.copyOf(ranges);
            pointsPerLine = pointsPerLine == null ? 600 : pointsPerLine;
            linesPerPanel = linesPerPanel == null ? 20 : linesPerPanel;
            storeAddress = storeAddress == null || storeAddress.isBlank() ? "localhost:9102" : storeAddress;
            catalogueRefresh = catalogueRefresh == null ? java.time.Duration.ofMinutes(1) : catalogueRefresh;
        }

        public static java.time.Duration parse(String range) {
            String r = range.strip().toLowerCase(java.util.Locale.ROOT);
            long n = Long.parseLong(r.substring(0, r.length() - 1));
            return switch (r.charAt(r.length() - 1)) {
                case 'm' -> java.time.Duration.ofMinutes(n);
                case 'h' -> java.time.Duration.ofHours(n);
                case 'd' -> java.time.Duration.ofDays(n);
                default -> throw new IllegalArgumentException("a range is like 15m, 1h or 7d");
            };
        }
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public NotifierSettings {
        dashboards = dashboards == null ? new Dashboards(null, null, null, null, null) : dashboards;
    }

    public NotifierSettings(int rsocketPort, String dayBy, int maxQueuedUpdatesPerViewer) {
        this(rsocketPort, dayBy, maxQueuedUpdatesPerViewer, null);
    }

    static final Set<String> DAY_BY = Set.of("started", "received");

    public boolean byReceived() {
        return "received".equals(dayBy);
    }

    static List<SettingsValidator<? super NotifierSettings>> validators() {
        return List.of(
                SettingsValidator.require("rsocket-port", s -> s.rsocketPort() > 1024 && s.rsocketPort() < 65536, "must be between 1025 and 65535"),
                SettingsValidator.require("day-by", s -> DAY_BY.contains(s.dayBy()), "must be started or received"),
                SettingsValidator.require("max-queued-updates-per-viewer", s -> s.maxQueuedUpdatesPerViewer() >= 10,
                        "must be at least 10"),
                SettingsValidator.require("dashboards.ranges", s -> s.dashboards().ranges().stream().allMatch(NotifierSettings::validRange),
                        "each range like 15m, 1h, 4h, 1d or 7d, at most 7 days (what is kept)"),
                SettingsValidator.require("dashboards.points-per-line", s -> s.dashboards().pointsPerLine() >= 100 && s.dashboards().pointsPerLine() <= 5000,
                        "must be between 100 and 5000"),
                SettingsValidator.require("dashboards.lines-per-panel", s -> s.dashboards().linesPerPanel() >= 1 && s.dashboards().linesPerPanel() <= 500,
                        "must be between 1 and 500"));
    }

    static boolean validRange(String r) {
        try {
            java.time.Duration d = Dashboards.parse(r);
            return !d.isNegative() && !d.isZero() && d.compareTo(java.time.Duration.ofDays(7)) <= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
