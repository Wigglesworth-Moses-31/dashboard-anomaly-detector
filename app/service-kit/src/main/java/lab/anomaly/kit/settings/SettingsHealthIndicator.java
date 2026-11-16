package lab.anomaly.kit.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Health check "settings": UP when the latest change was accepted; DEGRADED (still serving, on the last good
 * settings) when a change was refused, with the problems listed (spec: invalid settings are rejected safely).
 */
public class SettingsHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED =
            new Status("DEGRADED", "A settings change was rejected; the last valid settings are in use");

    private final List<SettingsHolder<?>> holders;

    public SettingsHealthIndicator(List<SettingsHolder<?>> holders) {
        this.holders = holders;
    }

    @Override
    public Health health() {
        boolean anyRejected = false;
        Map<String, Object> details = new LinkedHashMap<>();
        for (SettingsHolder<?> holder : holders) {
            SettingsHolder.Rejection rejection = holder.lastRejection();
            if (rejection == null) {
                details.put(holder.prefix(), "version " + holder.snapshot().version());
            } else {
                anyRejected = true;
                details.put(holder.prefix(), Map.of(
                        "inUse", "version " + holder.snapshot().version(),
                        "rejected", rejection.problems()));
            }
        }
        return (anyRejected ? Health.status(DEGRADED) : Health.up()).withDetails(details).build();
    }
}
