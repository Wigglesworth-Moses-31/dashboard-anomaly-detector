package lab.anomaly.collector.source.recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decorator (design K2, K12): passes every call to the wrapped source and appends the request and its answer
 * (or its failure) as one JSON line to {@code <dir>/<UTC date>/<kind>.jsonl}. It records what the source port
 * returned, never HTTP headers, so recordings cannot contain keys. ReplaySource reads these files.
 */
public final class RecordingSource implements MetricSource {

    private final MetricSource inner;
    private final Path dir;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public RecordingSource(MetricSource inner, Path dir, Clock clock) {
        this.inner = inner;
        this.dir = dir;
        this.clock = clock;
    }

    @Override
    public Dashboard dashboard(String id) throws SourceException {
        try {
            Dashboard answer = inner.dashboard(id);
            write("dashboards", id, answer, null);
            return answer;
        } catch (SourceException e) {
            write("dashboards", id, null, e);
            throw e;
        }
    }

    @Override
    public TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException {
        try {
            TimeseriesAnswer answer = inner.timeseries(request);
            write("timeseries", request, answer, null);
            return answer;
        } catch (SourceException e) {
            write("timeseries", request, null, e);
            throw e;
        }
    }

    @Override
    public Optional<MetricMetadata> metadata(String metric) throws SourceException {
        try {
            Optional<MetricMetadata> answer = inner.metadata(metric);
            write("metadata", metric, answer.orElse(null), null);
            return answer;
        } catch (SourceException e) {
            write("metadata", metric, null, e);
            throw e;
        }
    }

    private synchronized void write(String kind, Object request, Object answer, SourceException error) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", clock.millis());
        line.put("request", request);
        line.put("answer", answer);
        if (error != null) {
            line.put("error", Map.of("kind", error.kind().name(), "message", error.getMessage(),
                    "retryAfterSeconds", error.retryAfterSeconds()));
        }
        Path file = dir.resolve(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString()).resolve(kind + ".jsonl");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write recording " + file, e);
        }
    }
}
