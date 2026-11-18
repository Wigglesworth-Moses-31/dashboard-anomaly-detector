package lab.anomaly.collector.keys;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Checks a region's two keys with Datadog before they are saved (add-settings-page S4), read-only: the API key with
 * {@code GET /api/v1/validate}, the application key with one small metrics query over the last minute. Answers a plain
 * reason; Datadog's own error text is never passed on (it may echo what was sent), and the keys are never logged.
 */
public class KeyChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Function<String, String> baseUrl;

    /** {@code baseUrl}: site → "https://api." + site (tests point it at a local fake). */
    public KeyChecker(Function<String, String> baseUrl) {
        this.baseUrl = baseUrl;
    }

    public KeyChecker() {
        this(site -> "https://api." + site);
    }

    /** Empty when both keys work for {@code site}; else the reason, safe to show. */
    public Optional<String> check(String site, String apiKey, String appKey) {
        String base = baseUrl.apply(site);
        try {
            int validate = status(HttpRequest.newBuilder(URI.create(base + "/api/v1/validate"))
                    .header("DD-API-KEY", apiKey));
            if (validate == 401 || validate == 403) {
                return Optional.of("Datadog (" + site + ") refused the API key");
            }
            if (validate != 200) {
                return Optional.of("Datadog (" + site + ") answered HTTP " + validate + " when checking the API key");
            }
            long now = System.currentTimeMillis() / 1000;
            int query = status(HttpRequest.newBuilder(URI.create(base + "/api/v1/query?from=" + (now - 60) + "&to=" + now
                            + "&query=avg:datadog.estimated_usage.metrics.custom%7B*%7D"))
                    .header("DD-API-KEY", apiKey).header("DD-APPLICATION-KEY", appKey));
            if (query == 401 || query == 403) {
                return Optional.of("Datadog (" + site + ") refused the application key (or it may not read metrics)");
            }
            if (query != 200) {
                return Optional.of("Datadog (" + site + ") answered HTTP " + query + " when checking the application key");
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.of("Datadog (" + site + ") could not be reached: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        }
    }

    private int status(HttpRequest.Builder b) throws IOException, InterruptedException {
        return http.send(b.timeout(TIMEOUT).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
