package lab.anomaly.config.settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lab.anomaly.contracts.Regions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Settings tab's writes (add-settings-page S3), local only. Every change: on top of the commit the page was shown
 * ({@code If-Match}), one commit, then the bus refresh so every service reloads.
 * <pre>
 * GET    /settings/state                                   → {commit, form, regions, max, can_add}
 * POST   /settings/regions                    {"region"}   → add US or EU (no dashboards yet)
 * DELETE /settings/regions/{r}                             → the entry and its dashboard files
 * POST   /settings/regions/{r}/dashboards     {"file_name","content","id"?,"presets","filters"?,"replace"?}
 * PATCH  /settings/regions/{r}/dashboards     {"file","presets","id"?}   → which saved views are watched
 * DELETE /settings/regions/{r}/dashboards?file=dashboards/eu/shop.json
 * </pre>
 * 409 for a conflict (changed meanwhile, hand edits, third region, duplicate), 400 for bad input.
 */
@RestController
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_CHARS = 5_000_000;

    private final SettingsRepo repo;
    private final Runnable refresh;

    @Autowired
    SettingsController(@Value("${spring.cloud.config.server.git.uri}") String uri,
                       @Value("${spring.cloud.config.server.git.default-label:main}") String label, Environment environment) {
        this(SettingsRepo.fromUri(uri, label), () -> busRefresh(environment));
    }

    public SettingsController(SettingsRepo repo, Runnable refresh) {
        this.repo = repo;
        this.refresh = refresh;
    }

    @GetMapping("/settings/state")
    public ResponseEntity<Map<String, Object>> state() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("commit", repo.head());
        String collector = repo.committed("collector.yml");
        String regionsText = repo.committed("regions.yml");
        String form = form(collector, regionsText);
        out.put("form", form);
        RegionsYaml regions = "collector.yml".equals(form) ? RegionsYaml.parse(collector) : RegionsYaml.parse(regionsText);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> s : regions.sources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.get("name"));
            m.put("site", s.get("site"));
            m.put("dashboards", RegionsYaml.dashboards(s));
            list.add(m);
        }
        out.put("regions", list);
        if ("old".equals(form)) {                                          // shown read-only
            Map<String, Object> c = RegionsYaml.collectorOf(collector);
            Map<String, Object> old = new LinkedHashMap<>();
            old.put("site", c.get("datadog-site"));
            old.put("dashboards", c.get("dashboards") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
            out.put("old", old);
        }
        out.put("max", Regions.MAX);
        out.put("can_add", "old".equals(form) ? List.of() : java.util.Arrays.stream(Regions.values())
                .map(Regions::region).filter(r -> regions.region(r).isEmpty()).limit(Math.max(0, Regions.MAX - list.size())).toList());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/settings/regions")
    public ResponseEntity<Map<String, Object>> addRegion(@RequestHeader(value = "If-Match", required = false) String seen,
                                                         @RequestBody Map<String, Object> body) {
        return write(seen, () -> {
            Regions r = region(String.valueOf(body.get("region")));
            return edit(model -> model.addRegion(r), files -> { }, "settings page: add region " + r.region());
        });
    }

    @DeleteMapping("/settings/regions/{name}")
    public ResponseEntity<Map<String, Object>> removeRegion(@RequestHeader(value = "If-Match", required = false) String seen,
                                                            @PathVariable String name) {
        return write(seen, () -> {
            Regions r = region(name);
            List<String> removed = new ArrayList<>();
            return edit(model -> removed.addAll(model.removeRegion(r.region())),
                    files -> removed.forEach(f -> files.put(dashboardPath(r, f), null)), "settings page: remove region " + r.region());
        });
    }

    @PostMapping("/settings/regions/{name}/dashboards")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> putDashboard(@RequestHeader(value = "If-Match", required = false) String seen,
                                                            @PathVariable String name, @RequestBody Map<String, Object> body) {
        return write(seen, () -> {
            Regions r = region(name);
            String content = (String) body.get("content");
            checkExport(content);
            String file = "dashboards/" + r.region() + "/" + slug(String.valueOf(body.getOrDefault("file_name", "dashboard.json")));
            boolean replace = Boolean.TRUE.equals(body.get("replace"));
            Map<String, Object> entry = entry(file, body.get("id"), body.get("presets"), body.get("filters"));
            return edit(model -> {
                boolean exists = model.dashboard(r.region(), file).isPresent();
                if (exists && !replace) {
                    throw new SettingsConflict(file + " is already in region " + r.region() + "; use Replace");
                }
                if (!exists && replace) {
                    throw new SettingsConflict(file + " is not in region " + r.region());
                }
                if (exists) {                                                  // a newer export or an edit keeps the entry
                    Map<String, Object> old = model.dashboard(r.region(), file).get();
                    for (String key : List.of("presets", "filters", "id")) {
                        if (!body.containsKey(key) && old.containsKey(key)) {
                            entry.put(key, old.get(key));
                        }
                    }
                }
                model.putDashboard(r.region(), entry);
            }, files -> files.put(file, content.endsWith("\n") ? content : content + "\n"),
                    "settings page: " + (Boolean.TRUE.equals(body.get("edited")) ? "edit " : replace ? "update " : "add ")
                            + file.substring("dashboards/".length()));
        });
    }

    @PatchMapping("/settings/regions/{name}/dashboards")
    public ResponseEntity<Map<String, Object>> watch(@RequestHeader(value = "If-Match", required = false) String seen,
                                                     @PathVariable String name, @RequestBody Map<String, Object> body) {
        return write(seen, () -> {
            Regions r = region(name);
            String file = String.valueOf(body.get("file"));
            return edit(model -> {
                Map<String, Object> old = model.dashboard(r.region(), file)
                        .orElseThrow(() -> new SettingsConflict("no dashboard " + file + " in region " + r.region()));
                Map<String, Object> entry = entry(file, body.containsKey("id") ? body.get("id") : old.get("id"),
                        body.get("presets"), old.get("filters"));
                model.putDashboard(r.region(), entry);
            }, files -> { }, "settings page: watch " + describe(body.get("presets")) + " of " + file.substring(file.lastIndexOf('/') + 1));
        });
    }

    @DeleteMapping("/settings/regions/{name}/dashboards")
    public ResponseEntity<Map<String, Object>> deleteDashboard(@RequestHeader(value = "If-Match", required = false) String seen,
                                                               @PathVariable String name, @RequestParam String file) {
        return write(seen, () -> {
            Regions r = region(name);
            return edit(model -> model.removeDashboard(r.region(), file), files -> files.put(dashboardPath(r, file), null),
                    "settings page: delete " + file.substring(Math.min(file.length(), "dashboards/".length())));
        });
    }

    // ---- the pieces --------------------------------------------------------------------------------------------

    private interface Work {
        java.util.function.Function<SettingsRepo, List<SettingsRepo.Change>> changes();
    }

    private ResponseEntity<Map<String, Object>> write(String seen, Work work) {
        if (repo == null) {
            return ResponseEntity.status(503).body(Map.of("problem", "the settings repository is not a local folder"));
        }
        try {
            String head = repo.apply(seen == null || seen.isBlank() ? null : seen.replace("\"", ""), work.changes());
            refresh.run();
            return ResponseEntity.ok(Map.of("commit", head));
        } catch (SettingsConflict e) {
            return ResponseEntity.status(409).body(Map.of("problem", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("problem", e.getMessage()));
        } catch (Exception e) {
            log.warn("Settings change failed: {}", e.toString());
            return ResponseEntity.status(500).body(Map.of("problem", "not saved: " + e.getClass().getSimpleName()));
        }
    }

    /** The one-time move when needed, then the change to regions.yml (and files) as one commit. */
    private java.util.function.Function<SettingsRepo, List<SettingsRepo.Change>> edit(
            java.util.function.Consumer<RegionsYaml> change, java.util.function.Consumer<Map<String, String>> files, String message) {
        return r -> {
            try {
                String collector = r.committed("collector.yml");
                String regionsText = r.committed("regions.yml");
                String form = form(collector, regionsText);
                if ("old".equals(form)) {
                    throw new SettingsConflict("collector.yml uses the old single-site form (datadog-site with dashboards);"
                            + " the page can change regions only after it is set up again with regions (scripts/setup.sh)");
                }
                List<SettingsRepo.Change> out = new ArrayList<>();
                if ("collector.yml".equals(form)) {
                    RegionsMove.Result moved = RegionsMove.of(collector).orElseThrow();
                    out.add(new SettingsRepo.Change(SettingsRepo.files("collector.yml", moved.collectorYml(), "regions.yml",
                            moved.regionsYml()), "settings page: move regions to regions.yml"));
                    regionsText = moved.regionsYml();
                }
                RegionsYaml model = RegionsYaml.parse(regionsText);
                change.accept(model);
                Map<String, String> written = new LinkedHashMap<>();
                written.put("regions.yml", model.render());
                files.accept(written);
                out.add(new SettingsRepo.Change(written, message));
                return out;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    /** Where the regions live: "regions.yml", "collector.yml" (before the move), "old" (single-site form) or "none". */
    static String form(String collector, String regions) {
        if (RegionsYaml.sourcesOf(regions) != null) {
            if (RegionsYaml.sourcesOf(collector) != null) {
                return "both";
            }
            return "regions.yml";
        }
        if (RegionsYaml.sourcesOf(collector) != null) {
            return "collector.yml";
        }
        Map<String, Object> c = RegionsYaml.collectorOf(collector);
        return c.get("datadog-site") != null || (c.get("dashboards") instanceof List<?> l && !l.isEmpty()) ? "old" : "none";
    }

    private static Regions region(String name) {
        return Regions.of(name).orElseThrow(() -> new IllegalArgumentException("region must be us or eu"));
    }

    private static String dashboardPath(Regions r, String file) {
        if (file == null || !file.startsWith("dashboards/" + r.region() + "/") || file.contains("..") || !file.endsWith(".json")) {
            throw new IllegalArgumentException("not a dashboard file of region " + r.region() + ": " + file);
        }
        return file;
    }

    /** "My Shop (EU).json" → "my-shop-eu.json". */
    static String slug(String fileName) {
        String base = fileName.strip().toLowerCase(Locale.ROOT).replaceAll("\\.json$", "");
        base = base.replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (base.isEmpty()) {
            base = "dashboard";
        }
        return (base.length() > 60 ? base.substring(0, 60) : base) + ".json";
    }

    /** Only the shape: a JSON object with widgets. The Collector's check (shown first in the page) reads it fully. */
    static void checkExport(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("the file is empty");
        }
        if (content.length() > MAX_CHARS) {
            throw new IllegalArgumentException("larger than 5 MB");
        }
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not valid JSON");
        }
        if (root == null || !root.isObject() || !root.path("widgets").isArray() || root.path("widgets").isEmpty()) {
            throw new IllegalArgumentException("no widgets; is this an exported dashboard JSON?");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(String file, Object id, Object presets, Object filters) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("file", file);
        if (id instanceof String s && !s.isBlank()) {
            if (!s.strip().matches("[a-z0-9]{3}-[a-z0-9]{3}-[a-z0-9]{3}")) {
                throw new IllegalArgumentException("id must look like abc-def-ghi");
            }
            e.put("id", s.strip());
        }
        if (presets instanceof List<?> l) {
            List<String> names = l.stream().map(String::valueOf).map(String::strip).filter(x -> !x.isEmpty()).toList();
            if (!names.isEmpty()) {
                e.put("presets", names);
            }
        }
        if (filters instanceof Map<?, ?> f && !f.isEmpty()) {
            e.put("filters", new LinkedHashMap<>((Map<String, Object>) f));
        }
        return e;
    }

    private static String describe(Object presets) {
        if (presets instanceof List<?> l) {
            return l.isEmpty() ? "no saved views" : l.size() == 1 && "all".equalsIgnoreCase(String.valueOf(l.get(0))) ? "all saved views"
                    : l.size() + " saved views";
        }
        return "no saved views";
    }

    private static void busRefresh(Environment environment) {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8888"));
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/busrefresh"))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Bus refresh after a settings change failed ({}); services pick it up on their next start", e.toString());
        }
    }
}
