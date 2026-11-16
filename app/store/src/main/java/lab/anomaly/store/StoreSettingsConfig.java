package lab.anomaly.store;

import lab.anomaly.kit.settings.SettingsHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Registers this service's settings as a validated, live-reloading snapshot (design D13). */
@Configuration
class StoreSettingsConfig {

    @Bean
    SettingsHolder<StoreSettings> storeSettings(Environment environment) {
        return new SettingsHolder<>("anomaly.store", StoreSettings.class, StoreSettings.validators(), environment);
    }
}
