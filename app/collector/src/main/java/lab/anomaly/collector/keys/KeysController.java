package lab.anomaly.collector.keys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lab.anomaly.collector.run.CollectorEngine;
import lab.anomaly.contracts.Regions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A region's Datadog keys from the Settings tab (add-settings-page S4), local only:
 * <pre>
 * GET    /keys                  → [{"region": "us", "site": "...", "present": true}]   never a key
 * POST   /keys/check            {"region","api_key","app_key"} → 200 {"ok": true} | 400 {"problem"}
 * PUT    /keys/{region}         {"api_key","app_key"} → checked again, written to .env, the lane re-reads them
 * DELETE /keys/{region}         → both lines removed from .env
 * </pre>
 * The body is a plain map read once; nothing here logs it, and no answer or error repeats it.
 */
@RestController
public class KeysController {

    private static final Logger log = LoggerFactory.getLogger(KeysController.class);

    private final Optional<EnvFile> envFile;
    private final KeyChecker checker;
    private final Runnable keysChanged;

    @Autowired
    KeysController(Environment environment, CollectorEngine engine) {
        this(EnvFile.fromEnvironment(environment), new KeyChecker(), engine::keysChanged);
    }

    public KeysController(Optional<EnvFile> envFile, KeyChecker checker, Runnable keysChanged) {
        this.envFile = envFile;
        this.checker = checker;
        this.keysChanged = keysChanged;
    }

    @GetMapping("/keys")
    List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Regions r : Regions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("region", r.region());
            m.put("site", r.site());
            m.put("present", envFile.map(f -> f.get(r.apiKeyName()).isPresent() && f.get(r.appKeyName()).isPresent()).orElse(false));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/keys/check")
    ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, String> body) {
        Optional<Regions> region = Regions.of(body == null ? null : body.get("region"));
        if (region.isEmpty()) {
            return problem(400, "region must be us or eu");
        }
        return checked(region.get(), body).map(p -> problem(400, p)).orElseGet(() -> ResponseEntity.ok(Map.of("ok", true)));
    }

    @PutMapping("/keys/{region}")
    ResponseEntity<Map<String, Object>> save(@PathVariable String region, @RequestBody Map<String, String> body) {
        Optional<Regions> r = Regions.of(region);
        if (r.isEmpty()) {
            return problem(400, "region must be us or eu");
        }
        if (envFile.isEmpty()) {
            return problem(503, "no .env to write to: start the system with scripts/system.sh");
        }
        Optional<String> bad = checked(r.get(), body);
        if (bad.isPresent()) {
            return problem(400, bad.get());
        }
        Map<String, String> lines = new HashMap<>();
        lines.put(r.get().apiKeyName(), body.get("api_key").strip());
        lines.put(r.get().appKeyName(), body.get("app_key").strip());
        try {
            envFile.get().write(lines);
        } catch (Exception e) {
            log.warn("Keys of region {} not written to .env: {}", r.get().region(), e.getClass().getSimpleName());
            return problem(500, "the keys could not be written to .env (" + e.getClass().getSimpleName() + ")");
        }
        keysChanged.run();
        log.info("Keys of region {} saved to .env ({}, {})", r.get().region(), r.get().apiKeyName(), r.get().appKeyName());
        return ResponseEntity.ok(Map.of("saved", true));
    }

    @DeleteMapping("/keys/{region}")
    ResponseEntity<Map<String, Object>> remove(@PathVariable String region) {
        Optional<Regions> r = Regions.of(region);
        if (r.isEmpty()) {
            return problem(400, "region must be us or eu");
        }
        if (envFile.isEmpty()) {
            return problem(503, "no .env to write to: start the system with scripts/system.sh");
        }
        Map<String, String> lines = new HashMap<>();
        lines.put(r.get().apiKeyName(), null);
        lines.put(r.get().appKeyName(), null);
        try {
            envFile.get().write(lines);
        } catch (Exception e) {
            return problem(500, "the keys could not be removed from .env (" + e.getClass().getSimpleName() + ")");
        }
        keysChanged.run();
        log.info("Keys of region {} removed from .env", r.get().region());
        return ResponseEntity.ok(Map.of("removed", true));
    }

    /** Both keys given (one line, 1-200 characters) and accepted by Datadog; else the reason. */
    private Optional<String> checked(Regions region, Map<String, String> body) {
        String api = body == null ? null : body.get("api_key");
        String app = body == null ? null : body.get("app_key");
        if (!oneLine(api) || !oneLine(app)) {
            return Optional.of("both keys are needed, each one line of 1 to 200 characters");
        }
        return checker.check(region.site(), api.strip(), app.strip());
    }

    private static boolean oneLine(String s) {
        return s != null && !s.isBlank() && s.strip().length() <= 200 && s.strip().chars().noneMatch(c -> c < 32);
    }

    private static ResponseEntity<Map<String, Object>> problem(int status, String text) {
        return ResponseEntity.status(status).body(Map.of("problem", text));
    }
}
