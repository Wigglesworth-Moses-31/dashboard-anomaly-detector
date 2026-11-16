package lab.anomaly.collector.source.datadog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.SourceException.Kind;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesAnswer.RateLimitStatus;
import lab.anomaly.collector.source.TimeseriesRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The live Datadog adapter (design K2). Read-only: only GET of dashboards and metric metadata, and the
 * timeseries query endpoint (a POST that reads). Keys go into request headers here and nowhere else; nothing
 * that is logged or returned contains them.
 */
public final class DatadogSource implements MetricSource {

    private static final Logger log = LoggerFactory.getLogger(DatadogSource.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String baseUrl;
    private final DatadogCredentials credentials;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor())      // waiting on the network costs no platform thread
            .build();

    public DatadogSource(String site, DatadogCredentials credentials, Clock clock) {
        this.baseUrl = "https://api." + site;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Override
    public Dashboard dashboard(String id) throws SourceException {
        JsonNode root = parse(send(get("/api/v1/dashboard/" + encode(id))));
        return DatadogDashboardReader.read(root);
    }

    @Override
    public TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException {
        ObjectNode attributes = json.createObjectNode()
                .put("from", request.fromMs())
                .put("to", request.toMs())
                .put("interval", request.intervalMs());
        ArrayNode queries = attributes.putArray("queries");
        for (Query query : request.queries()) {
            queries.addObject().put("data_source", "metrics").put("name", query.name()).put("query", query.text());
        }
        ArrayNode formulas = attributes.putArray("formulas");
        request.formulas().forEach(f -> formulas.addObject().put("formula", f));
        ObjectNode body = json.createObjectNode();
        body.putObject("data").put("type", "timeseries_request").set("attributes", attributes);

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v2/query/timeseries"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        long receivedMs = clock.millis();
        JsonNode attr = parse(response).path("data").path("attributes");

        List<Long> times = new ArrayList<>();
        attr.path("times").forEach(t -> times.add(t.asLong()));
        List<TimeseriesAnswer.Series> series = new ArrayList<>();
        JsonNode values = attr.path("values");
        int i = 0;
        for (JsonNode s : attr.path("series")) {
            Map<String, String> tags = new LinkedHashMap<>();
            for (JsonNode tag : s.path("group_tags")) {
                String item = DatadogDashboardReader.text(tag);
                int colon = item.indexOf(':');
                tags.put(colon < 0 ? item : item.substring(0, colon), colon < 0 ? "" : item.substring(colon + 1));
            }
            List<Double> points = new ArrayList<>();
            for (JsonNode v : values.path(i)) {
                points.add(v.isNull() ? null : v.asDouble());
            }
            series.add(new TimeseriesAnswer.Series(s.path("query_index").asInt(), tags, points));
            i++;
        }
        return new TimeseriesAnswer(times, series, receivedMs, rateLimit(response));
    }

    @Override
    public Optional<MetricMetadata> metadata(String metric) throws SourceException {
        HttpResponse<String> response;
        try {
            response = send(get("/api/v1/metrics/" + encode(metric)));
        } catch (SourceException e) {
            if (e.getMessage().startsWith("HTTP 404")) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode node = parse(response);
        String type = DatadogDashboardReader.text(node.path("type"));
        String unit = DatadogDashboardReader.text(node.path("unit"));
        return type.isEmpty() && unit.isEmpty()
                ? Optional.empty()
                : Optional.of(new MetricMetadata(type.isEmpty() ? null : type, unit.isEmpty() ? null : unit));
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT).GET();
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws SourceException {
        if (!credentials.complete()) {
            throw new SourceException(Kind.AUTH, "Datadog keys are missing from the environment");
        }
        HttpRequest request = builder
                .header("Accept", "application/json")
                .header("DD-API-KEY", credentials.apiKey())
                .header("DD-APPLICATION-KEY", credentials.applicationKey())
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SourceException(Kind.NETWORK, "network: " + e.getClass().getSimpleName(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(Kind.NETWORK, "interrupted", 0, e);
        }
        int status = response.statusCode();
        log.debug("{} {} -> {}", request.method(), request.uri().getPath(), status);   // path only, never headers
        if (status >= 200 && status < 300) {
            return response;
        }
        String message = "HTTP " + status + " " + request.method() + " " + request.uri().getPath() + ": " + shortBody(response);
        if (status == 429) {
            RateLimitStatus limits = rateLimit(response);
            int reset = limits.resetSeconds() == null ? 10 : Math.max(1, limits.resetSeconds());
            throw new SourceException(Kind.THROTTLED, message, reset, null);
        }
        if (status == 401 || status == 403) {
            throw new SourceException(Kind.AUTH, message);
        }
        if (status >= 500) {
            throw new SourceException(Kind.SERVER, message);
        }
        throw new SourceException(Kind.BAD_REQUEST, message);
    }

    private JsonNode parse(HttpResponse<String> response) throws SourceException {
        try {
            return json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new SourceException(Kind.SERVER, "unreadable answer from Datadog", 0, e);
        }
    }

    static RateLimitStatus rateLimit(HttpResponse<?> response) {
        return new RateLimitStatus(header(response, "x-ratelimit-limit"), header(response, "x-ratelimit-period"),
                header(response, "x-ratelimit-remaining"), header(response, "x-ratelimit-reset"));
    }

    private static Integer header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).map(v -> {
            try {
                return Integer.valueOf(v.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }

    private static String shortBody(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8);
    }
}
