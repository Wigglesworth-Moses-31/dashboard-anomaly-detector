package lab.anomaly.collector.check;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.dashboards.DashboardFiles;
import lab.anomaly.collector.keys.EnvFile;
import lab.anomaly.collector.plan.WatchedView;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.datadog.DatadogSource;
import lab.anomaly.contracts.Regions;
import lab.anomaly.kit.settings.SettingsHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Settings tab's dashboard check (add-settings-page S5), local only:
 * <pre>
 * POST /dashboards/check {"region": "eu", "file_name": "shop.json", "content": "&lt;exported JSON&gt;",
 *                         "current": "dashboards/eu/shop.json", "presets": ["all"], "filters": {}, "ask": false}
 *   → 200 {summary, views, diff?, lines?, calls?} | 400 {"problem"}
 * </pre>
 * Nothing is saved here; saving is the Config Service's job.
 */
@RestController
public class DashboardCheckController {

    /** How a region's source is made for "ask each panel once" (tests give a stub). */
    public interface Sources {
        MetricSource of(Regions region);
    }

    private final DashboardFiles files;
    private final SettingsHolder<CollectorSettings> settings;
    private final Sources sources;
    private final long pauseMs;

    @Autowired
    DashboardCheckController(DashboardFiles files, SettingsHolder<CollectorSettings> settings, Environment environment) {
        this(files, settings, r -> new DatadogSource(r.site(), DatadogCredentials.from(EnvFile.fromEnvironment(environment),
                environment, r.apiKeyName(), r.appKeyName(), r.region()), java.time.Clock.systemUTC()), 500);
    }

    public DashboardCheckController(DashboardFiles files, SettingsHolder<CollectorSettings> settings, Sources sources, long pauseMs) {
        this.files = files;
        this.settings = settings;
        this.sources = sources;
        this.pauseMs = pauseMs;
    }

    @PostMapping("/dashboards/check")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, Object> body) {
        Optional<Regions> region = Regions.of(body == null ? null : (String) body.get("region"));
        if (region.isEmpty()) {
            return problem("region must be us or eu");
        }
        String fileName = String.valueOf(body.getOrDefault("file_name", "dashboard.json"));
        try {
            Dashboard d = DashboardCheck.parse(fileName, (String) body.get("content"));
            List<String> presets = body.get("presets") instanceof List<?> l ? (List<String>) l : List.of("all");
            Map<String, String> filters = body.get("filters") instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
            List<WatchedView> views = DashboardCheck.views(d, fileName, presets, filters);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", DashboardCheck.summary(d));
            out.put("views", views.stream().map(WatchedView::viewTitle).toList());
            Object current = body.get("current");
            if (current instanceof String c && !c.isBlank()) {
                String old = files.read(c);
                if (old != null) {
                    out.put("diff", DashboardCheck.diff(DashboardFiles.parseText(c, old), d));
                }
            }
            if (Boolean.TRUE.equals(body.get("ask"))) {
                out.put("lines", DashboardCheck.ask(sources.of(region.get()), region.get().region(), views, settings.get(), pauseMs));
            }
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException | ClassCastException e) {
            return problem(e.getMessage() == null ? "not a dashboard export" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return problem("interrupted");
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("problem", "the current file could not be read: " + e.getClass().getSimpleName()));
        }
    }

    private static ResponseEntity<Map<String, Object>> problem(String text) {
        return ResponseEntity.badRequest().body(Map.of("problem", text));
    }
}
