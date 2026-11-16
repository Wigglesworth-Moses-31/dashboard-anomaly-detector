package lab.anomaly.notifier;

import java.util.Map;
import lab.anomaly.kit.settings.SettingsHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the page where the RSocket stream is, so the port lives only in config-repo/notifier.yml. */
@RestController
class PageConfigController {

    private final SettingsHolder<NotifierSettings> settings;

    PageConfigController(SettingsHolder<NotifierSettings> settings) {
        this.settings = settings;
    }

    @GetMapping("/page-config.json")
    Map<String, Object> pageConfig() {
        return Map.of("rsocketPort", settings.get().rsocketPort(), "route", "incidents.live");
    }
}
