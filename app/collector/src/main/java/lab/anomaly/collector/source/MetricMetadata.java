package lab.anomaly.collector.source;

/** What the source knows about a metric: its type (count, rate, gauge, distribution) and unit (may be null). */
public record MetricMetadata(String type, String unit) {}
