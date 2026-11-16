package lab.anomaly.collector;

import lab.anomaly.kit.settings.SettingsHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Registers the Collector's settings (design D13) and its Datadog keys (from the environment only). */
@Configuration
class CollectorSettingsConfig {

    @Bean
    SettingsHolder<CollectorSettings> collectorSettings(Environment environment) {
        return new SettingsHolder<>("anomaly.collector", CollectorSettings.class, CollectorSettings.validators(), environment);
    }

    @Bean
    DatadogCredentials datadogCredentials(Environment environment) {
        return DatadogCredentials.fromEnvironment(environment);
    }
}
