package lab.anomaly.collector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lab.anomaly.collector.dashboards.DashboardFiles;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.kit.settings.SettingsValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers the Collector's settings and its dashboard files (add-dashboard-json design J2, J3). The files are read
 * from the Config Service like every other setting; their check is one more validator, so the YAML and the files are
 * accepted together or not at all. Keys are read per source by the engine.
 */
@Configuration
class CollectorSettingsConfig {

    @Bean
    DashboardFiles dashboardFiles(Environment environment) {
        String base = configServerUri(environment) + "/files/";            // committed files only (Config Service)
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new DashboardFiles(path -> {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                return null;
            }
            if (r.statusCode() != 200) {
                throw new IllegalStateException("Config Service answered HTTP " + r.statusCode());
            }
            return r.body();
        });
    }

    @Bean
    SettingsHolder<CollectorSettings> collectorSettings(Environment environment, DashboardFiles files) {
        List<SettingsValidator<? super CollectorSettings>> validators = new ArrayList<>(CollectorSettings.validators());
        validators.add(files::check);                         // last: only settings that pass the YAML rules load files
        return new SettingsHolder<>("anomaly.collector", CollectorSettings.class, validators, environment)
                .alsoReloadOn("config.client.version");
    }

    /** "configserver:http://localhost:8888" or spring.cloud.config.uri. */
    static String configServerUri(Environment environment) {
        String uri = environment.getProperty("spring.cloud.config.uri");
        if (uri == null || uri.isBlank()) {
            String imports = environment.getProperty("spring.config.import", "configserver:http://localhost:8888");
            int at = imports.indexOf("configserver:");
            uri = at < 0 ? "http://localhost:8888" : imports.substring(at + "configserver:".length()).split(",")[0].strip();
        }
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }
}
