package lab.anomaly.notifier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * calmer-anomalies: how often the Detector judges each signal ("10s" or "5m"), so each card can say "5-min average"
 * or "every 10 s". Read from the Detector's settings as the Config Service serves them ({@code /detector/default}),
 * so the page never repeats what lives in {@code detector.yml}. Only signal names and periods are passed on.
 * Cached for a minute; when the Config Service cannot be reached the answer is empty and the cards show no tag.
 */
@RestController
class JudgingController {

    private static final Pattern RULE = Pattern.compile("^anomaly\\.detector\\.rules\\[(\\d+)]\\.(signal|judge-every)$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String configService;
    private volatile Map<String, String> cached = Map.of();
    private volatile long cachedAt;

    JudgingController(@Value("${anomaly.notifier.config-service-url:http://127.0.0.1:8888}") String configService) {
        this.configService = configService;
    }

    @GetMapping("/judging.json")
    Map<String, String> judging() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > 60_000 || cached.isEmpty()) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(configService + "/detector/default"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    cached = periods(json.readTree(r.body()));
                    cachedAt = now;
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return cached;
    }

    /** Signal → period from a Config Service answer; the first property source wins, the first rule of a signal wins. */
    static Map<String, String> periods(JsonNode env) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (JsonNode source : env.path("propertySources")) {
            Iterator<Map.Entry<String, JsonNode>> it = source.path("source").properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                flat.putIfAbsent(e.getKey(), e.getValue().asString());
            }
        }
        Map<Integer, String[]> rules = new TreeMap<>();
        for (Map.Entry<String, String> e : flat.entrySet()) {
            Matcher m = RULE.matcher(e.getKey());
            if (m.matches()) {
                String[] r = rules.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new String[2]);
                r[m.group(2).equals("signal") ? 0 : 1] = e.getValue();
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] r : rules.values()) {
            if (r[0] != null) {
                out.putIfAbsent(r[0], r[1] == null || r[1].isBlank() ? "10s" : r[1]);
            }
        }
        String general = flat.get("anomaly.detector.general.judge-every");
        out.putIfAbsent("general", general == null || general.isBlank() ? "10s" : general);
        return out;
    }
}
