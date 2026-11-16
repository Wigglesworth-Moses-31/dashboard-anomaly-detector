package lab.anomaly.kit.settings;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Added to every service automatically: shared settings, the settings page and the settings health check. */
@AutoConfiguration
public class SettingsAutoConfiguration {

    @Bean
    SettingsHolder<SharedSettings> sharedSettings(Environment environment) {
        return new SettingsHolder<>("anomaly.shared", SharedSettings.class, SharedSettings.validators(), environment);
    }

    @Bean
    SettingsEndpoint settingsEndpoint(List<SettingsHolder<?>> holders) {
        return new SettingsEndpoint(holders);
    }

    @Bean
    SettingsHealthIndicator settingsHealthIndicator(List<SettingsHolder<?>> holders) {
        return new SettingsHealthIndicator(holders);
    }
}
