package lab.anomaly.store;

import java.time.Duration;
import java.util.List;
import lab.anomaly.kit.settings.SettingsValidator;
import org.springframework.util.unit.DataSize;

/**
 * The Store's settings (config-repo/store.yml, prefix {@code anomaly.store}). Immutable.
 * Lab system, never production (user, 15 Nov 2026): nothing may be kept longer than 10 days; the default is 24 hours (user, 21 Nov 2026).
 */
public record StoreSettings(
        int grpcPort,
        Duration rawSafetyLimit,
        Duration summaryRetention,
        int maxBatchRecords,
        Duration pollTimeout,
        Duration historyMaxRange,
        DataSize maxDatabaseSize,
        Duration sizeCheckInterval) {

    public static final Duration CEILING = Duration.ofDays(10);

    static List<SettingsValidator<? super StoreSettings>> validators() {
        return List.of(
                SettingsValidator.require("grpc-port", s -> s.grpcPort() > 1024 && s.grpcPort() < 65536, "must be between 1025 and 65535"),
                SettingsValidator.positive("raw-safety-limit", StoreSettings::rawSafetyLimit),
                SettingsValidator.positive("summary-retention", StoreSettings::summaryRetention),
                SettingsValidator.require("raw-safety-limit", s -> s.rawSafetyLimit() == null || s.rawSafetyLimit().compareTo(CEILING) <= 0,
                        "at most 10 days: this is a lab system and the database must stay small"),
                SettingsValidator.require("summary-retention", s -> s.summaryRetention() == null || s.summaryRetention().compareTo(CEILING) <= 0,
                        "at most 10 days: this is a lab system and the database must stay small"),
                SettingsValidator.require("raw-safety-limit", s -> s.rawSafetyLimit() == null || s.rawSafetyLimit().compareTo(Duration.ofDays(1)) >= 0,
                        "at least 1 day: a report covers up to one day of raw points"),
                SettingsValidator.require("max-batch-records", s -> s.maxBatchRecords() >= 1 && s.maxBatchRecords() <= 5000, "must be between 1 and 5000"),
                SettingsValidator.positive("poll-timeout", StoreSettings::pollTimeout),
                SettingsValidator.positive("history-max-range", StoreSettings::historyMaxRange),
                SettingsValidator.require("max-database-size", s -> s.maxDatabaseSize() != null && s.maxDatabaseSize().toMegabytes() >= 1,
                        "must be at least 1MB"),
                SettingsValidator.positive("size-check-interval", StoreSettings::sizeCheckInterval));
    }
}
