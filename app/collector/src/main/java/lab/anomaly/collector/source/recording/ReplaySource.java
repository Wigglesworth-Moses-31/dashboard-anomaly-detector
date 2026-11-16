package lab.anomaly.collector.source.recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers from recordings made by RecordingSource (design K12), with no network. Timeseries answers are given in
 * recorded order per question; {@link #timeseriesInOrder()} lists every recorded poll by time for a replay run.
 */
public final class ReplaySource implements MetricSource {

    /** One recorded timeseries call: when, what was asked, and the answer or failure. */
    public record Recorded(long atMs, TimeseriesRequest request, TimeseriesAnswer answer, String errorKind, String errorMessage, int retryAfterSeconds) {}

    private static final java.util.Set<String> KINDS = java.util.Set.of("dashboards", "metadata", "timeseries");

    private final Map<String, Dashboard> dashboards = new HashMap<>();
    private final Map<String, Optional<MetricMetadata>> metadata = new HashMap<>();
    private final Map<String, Deque<Recorded>> byQuestion = new HashMap<>();
    private final List<Recorded> inOrder = new ArrayList<>();

    public ReplaySource(Path dir) {
        JsonMapper json = JsonMapper.builder().build();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".jsonl")).sorted().toList()) {
                String kind = file.getFileName().toString().replace(".jsonl", "");
                if (!KINDS.contains(kind)) {
                    continue;                           // e.g. published.jsonl: the Collector's output, not answers
                }
                for (String text : Files.readAllLines(file)) {
                    if (text.isBlank()) {
                        continue;
                    }
                    JsonNode line = json.readTree(text);
                    JsonNode answer = line.path("answer");
                    boolean failed = !line.path("error").isMissingNode() && !line.path("error").isNull();
                    switch (kind) {
                        case "dashboards" -> {
                            if (!failed) {
                                dashboards.put(line.path("request").asString(), json.treeToValue(answer, Dashboard.class));
                            }
                        }
                        case "metadata" -> {
                            if (!failed) {
                                metadata.put(line.path("request").asString(), answer.isNull() || answer.isMissingNode()
                                        ? Optional.empty() : Optional.of(json.treeToValue(answer, MetricMetadata.class)));
                            }
                        }
                        case "timeseries" -> {
                            TimeseriesRequest request = json.treeToValue(line.path("request"), TimeseriesRequest.class);
                            Recorded recorded = failed
                                    ? new Recorded(line.path("at").asLong(), request, null, line.path("error").path("kind").asString(),
                                            line.path("error").path("message").asString(), line.path("error").path("retryAfterSeconds").asInt())
                                    : new Recorded(line.path("at").asLong(), request, json.treeToValue(answer, TimeseriesAnswer.class), null, null, 0);
                            inOrder.add(recorded);
                        }
                        default -> { }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read recordings in " + dir, e);
        }
        inOrder.sort(Comparator.comparingLong(Recorded::atMs));
        inOrder.forEach(r -> byQuestion.computeIfAbsent(r.request().canonicalQuestion(), k -> new ArrayDeque<>()).add(r));
    }

    @Override
    public Dashboard dashboard(String id) throws SourceException {
        Dashboard dashboard = dashboards.get(id);
        if (dashboard == null) {
            throw new SourceException(SourceException.Kind.BAD_REQUEST, "no recording of dashboard " + id);
        }
        return dashboard;
    }

    @Override
    public TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException {
        Deque<Recorded> queue = byQuestion.get(request.canonicalQuestion());
        Recorded next = queue == null ? null : queue.poll();
        if (next == null) {
            throw new SourceException(SourceException.Kind.NETWORK, "end of recording for this question");
        }
        if (next.errorKind() != null) {
            throw new SourceException(SourceException.Kind.valueOf(next.errorKind()), next.errorMessage(), next.retryAfterSeconds(), null);
        }
        return next.answer();
    }

    @Override
    public Optional<MetricMetadata> metadata(String metric) {
        return metadata.getOrDefault(metric, Optional.empty());
    }

    public List<Recorded> timeseriesInOrder() {
        return List.copyOf(inOrder);
    }
}
