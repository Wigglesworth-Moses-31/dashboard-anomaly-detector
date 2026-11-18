package lab.anomaly.notifier;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One address for the Settings tab (add-settings-page S6): {@code /settings/**} is passed on, on this machine only, to
 * <pre>
 * /settings/keys/**      → Collector      /keys/**            (keys: checked, written to .env)
 * /settings/check        → Collector      /dashboards/check   (an upload read with the Collector's code)
 * /settings/collector    → Collector      /actuator/health    (keys present, lanes)
 * /settings/files/**     → Config Service /files/**           (download a committed dashboard)
 * /settings/**           → Config Service /settings/**        (writes, one commit each)
 * </pre>
 * Bodies are streamed through unread and never logged; only Content-Type and If-Match travel with them.
 */
@RestController
class SettingsProxyController {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String configService;
    private final String collector;

    SettingsProxyController(@Value("${anomaly.notifier.config-service-url:http://127.0.0.1:8888}") String configService,
                            @Value("${anomaly.notifier.collector-url:http://127.0.0.1:8101}") String collector) {
        this.configService = configService;
        this.collector = collector;
    }

    @RequestMapping("/settings/**")
    ResponseEntity<byte[]> pass(HttpServletRequest request) throws Exception {
        String path = request.getRequestURI();
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
        if (!METHODS.contains(request.getMethod()) || path.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        String target;
        if (path.startsWith("/settings/keys")) {
            target = collector + path.substring("/settings".length());
        } else if (path.equals("/settings/check")) {
            target = collector + "/dashboards/check";
        } else if (path.equals("/settings/collector")) {
            target = collector + "/actuator/health";
        } else if (path.startsWith("/settings/files/")) {
            target = configService + path.substring("/settings".length());
        } else {
            target = configService + path;
        }
        byte[] body = request.getInputStream().readAllBytes();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target + query)).timeout(Duration.ofSeconds(60))
                .method(request.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (request.getContentType() != null) {
            b.header("Content-Type", request.getContentType());
        }
        if (request.getHeader("If-Match") != null) {
            b.header("If-Match", request.getHeader("If-Match"));
        }
        HttpResponse<byte[]> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"problem\":\"" + (target.startsWith(collector) ? "the Collector" : "the Config Service") + " cannot be reached\"}").getBytes());
        }
        HttpHeaders headers = new HttpHeaders();
        r.headers().firstValue("Content-Type").ifPresent(t -> headers.set("Content-Type", t));
        headers.setCacheControl("no-store");
        return ResponseEntity.status(r.statusCode()).headers(headers).body(r.body());
    }
}
