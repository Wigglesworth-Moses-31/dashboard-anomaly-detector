package lab.anomaly.collector.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;

/** A source for tests: metadata from a map, calls counted. */
public class FakeSource implements MetricSource {

    public final Map<String, MetricMetadata> metadata = new HashMap<>();
    public final AtomicInteger metadataCalls = new AtomicInteger();

    @Override
    public Dashboard dashboard(String id) throws SourceException {
        throw new SourceException(SourceException.Kind.BAD_REQUEST, "no dashboards in FakeSource");
    }

    @Override
    public TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException {
        throw new SourceException(SourceException.Kind.BAD_REQUEST, "no timeseries in FakeSource");
    }

    @Override
    public Optional<MetricMetadata> metadata(String metric) {
        metadataCalls.incrementAndGet();
        return Optional.ofNullable(metadata.get(metric));
    }
}
