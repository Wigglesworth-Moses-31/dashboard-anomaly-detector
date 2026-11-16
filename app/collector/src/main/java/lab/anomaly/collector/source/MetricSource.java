package lab.anomaly.collector.source;

import java.util.Optional;

/**
 * The port every data source plugs into (add-collector design K2, Ports and Adapters). Nothing outside an
 * adapter package knows which vendor answers. Adapters: DatadogSource (live), ReplaySource (recorded files);
 * RecordingSource wraps any of them (Decorator).
 */
public interface MetricSource {

    /** The definition of one dashboard, with every panel that asks a metric question. */
    Dashboard dashboard(String id) throws SourceException;

    /** The numbers for one panel request: one series per formula and tag group. */
    TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException;

    /** What the source knows about a metric (type and unit), or empty if it knows nothing. */
    Optional<MetricMetadata> metadata(String metric) throws SourceException;
}
