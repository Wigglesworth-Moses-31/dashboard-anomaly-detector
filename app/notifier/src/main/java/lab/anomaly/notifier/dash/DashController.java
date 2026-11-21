package lab.anomaly.notifier.dash;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab.anomaly.contracts.Sites;
import lab.anomaly.notifier.NotifierSettings;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The dashboard pages' answers (add-dashboard-view D2–D4):
 * <pre>
 * GET /dash/catalogue                                 sites → dashboards → groups → panels → lines (no values)
 * GET /dash/range?dashboard=&lt;id&gt;&range=1h&width=600  every line of a dashboard, one Store call, thinned
 * GET /dash/panel?panel=&lt;id&gt;&range=1h&width=1000     every line of one panel
 * GET /dash/line?id=&lt;line&gt;&range=1h&width=1000       one line, with where it is shown
 * GET /dash/live?lines=a,b,c                          Server-Sent Events: newest points of those lines, every second
 * </pre>
 */
@RestController
public class DashController implements HealthIndicator {

    private final DashService dash;
    private final LivePoints live;

    public DashController(DashService dash, LivePoints live) {
        this.dash = dash;
        this.live = live;
    }

    @GetMapping("/dash/catalogue")
    public Map<String, Object> catalogue() {
        Map<String, Object> out = new LinkedHashMap<>();
        NotifierSettings.Dashboards s = dash.settings();
        out.put("ranges", s.ranges());
        out.put("lines_per_panel", s.linesPerPanel());
        out.put("problem", dash.catalogueProblem());
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Catalogue.Site site : dash.catalogue().sites()) {
            sites.add(Map.of("site", site.site(), "label", Sites.label(site.site()), "dashboards", site.dashboards()));
        }
        out.put("sites", sites);
        return out;
    }

    @GetMapping("/dash/range")
    public ResponseEntity<Map<String, Object>> range(@RequestParam String dashboard, @RequestParam(defaultValue = "1h") String range,
                                                     @RequestParam(defaultValue = "600") int width) {
        Catalogue.Dashboard d = dash.catalogue().dashboard(dashboard);
        if (d == null) {
            return ResponseEntity.status(404).body(Map.of("problem", "no such dashboard"));
        }
        Set<String> ids = new LinkedHashSet<>();
        d.groups().forEach(g -> g.panels().forEach(p -> p.lines().forEach(l -> ids.add(l.id()))));
        return answer(new ArrayList<>(ids), range, width);
    }

    @GetMapping("/dash/panel")
    public ResponseEntity<Map<String, Object>> panel(@RequestParam String panel, @RequestParam(defaultValue = "1h") String range,
                                                     @RequestParam(defaultValue = "1000") int width) {
        Catalogue.Panel p = dash.catalogue().panel(panel);
        if (p == null) {
            return ResponseEntity.status(404).body(Map.of("problem", "no such panel"));
        }
        return answer(p.lines().stream().map(Catalogue.Line::id).toList(), range, width);
    }

    @GetMapping("/dash/line")
    public ResponseEntity<Map<String, Object>> line(@RequestParam String id, @RequestParam(defaultValue = "1h") String range,
                                                    @RequestParam(defaultValue = "1000") int width) {
        Catalogue.Line l = dash.catalogue().line(id);
        if (l == null) {
            return ResponseEntity.status(404).body(Map.of("problem", "no such line"));
        }
        ResponseEntity<Map<String, Object>> r = answer(List.of(id), range, width);
        Map<String, Object> body = new LinkedHashMap<>(r.getBody());
        body.put("line", l);
        body.put("places", dash.catalogue().places(id));
        return ResponseEntity.status(r.getStatusCode()).body(body);
    }

    @GetMapping(value = "/dash/live", produces = "text/event-stream")
    public SseEmitter live(@RequestParam(defaultValue = "") String lines) {
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(lines.split(",")).map(String::strip).filter(s -> s.matches("[0-9a-f]{16}")).limit(5_000).forEach(ids::add);
        return live.open(ids);
    }

    private ResponseEntity<Map<String, Object>> answer(List<String> ids, String range, int width) {
        Duration d;
        try {
            d = NotifierSettings.Dashboards.parse(range);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("problem", "a range is like 15m, 1h or 7d"));
        }
        if (!dash.settings().ranges().contains(range)) {
            return ResponseEntity.badRequest().body(Map.of("problem", "ranges offered: " + dash.settings().ranges()));
        }
        long to = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", to - d.toMillis());
        out.put("to", to);
        long interval = DashService.autoInterval(d);                      // add-datadog-look K8: like Datadog
        out.put("interval_ms", interval);
        try {
            Map<String, List<double[]>> lines = new LinkedHashMap<>();
            dash.rolled(ids, d, to, interval).forEach(s -> lines.put(s.id(), s.points()));
            out.put("lines", lines);
            return ResponseEntity.ok(out);
        } catch (RuntimeException e) {
            out.put("problem", "history unavailable");
            return ResponseEntity.status(503).body(out);
        }
    }

    @Override
    public Health health() {
        Health.Builder b = dash.catalogueProblem() == null ? Health.up() : Health.status("DEGRADED");
        return b.withDetail("catalogueLines", dash.catalogue().lineCount())
                .withDetail("catalogueProblem", dash.catalogueProblem() == null ? "" : dash.catalogueProblem())
                .withDetail("livePages", live.streams())
                .withDetail("liveLinesShown", live.linesShown())
                .withDetail("livePointsPushed", live.pushed())
                .withDetail("storeCalls", dash.storeCalls())
                .withDetail("slowestStoreCallMs", dash.slowestStoreCallMs())
                .build();
    }
}
