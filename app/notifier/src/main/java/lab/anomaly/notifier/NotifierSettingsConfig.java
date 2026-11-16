package lab.anomaly.notifier;

import lab.anomaly.kit.settings.SettingsHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Registers this service's settings as a validated, live-reloading snapshot (design D13). */
@Configuration
class NotifierSettingsConfig {

    @Bean
    SettingsHolder<NotifierSettings> notifierSettings(Environment environment) {
        return new SettingsHolder<>("anomaly.notifier", NotifierSettings.class, NotifierSettings.validators(), environment);
    }
}
