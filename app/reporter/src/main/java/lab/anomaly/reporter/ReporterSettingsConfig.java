package lab.anomaly.reporter;

import lab.anomaly.kit.settings.SettingsHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Registers this service's settings as a validated, live-reloading snapshot (design D13). */
@Configuration
class ReporterSettingsConfig {

    @Bean
    SettingsHolder<ReporterSettings> reporterSettings(Environment environment) {
        return new SettingsHolder<>("anomaly.reporter", ReporterSettings.class, ReporterSettings.validators(), environment);
    }
}
