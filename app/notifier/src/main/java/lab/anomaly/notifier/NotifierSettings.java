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
public record NotifierSettings(int rsocketPort, String dayBy, int maxQueuedUpdatesPerViewer) {

    static final Set<String> DAY_BY = Set.of("started", "received");

    public boolean byReceived() {
        return "received".equals(dayBy);
    }

    static List<SettingsValidator<? super NotifierSettings>> validators() {
        return List.of(
                SettingsValidator.require("rsocket-port", s -> s.rsocketPort() > 1024 && s.rsocketPort() < 65536, "must be between 1025 and 65535"),
                SettingsValidator.require("day-by", s -> DAY_BY.contains(s.dayBy()), "must be started or received"),
                SettingsValidator.require("max-queued-updates-per-viewer", s -> s.maxQueuedUpdatesPerViewer() >= 10,
                        "must be at least 10"));
    }
}
