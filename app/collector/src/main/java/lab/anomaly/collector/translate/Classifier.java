package lab.anomaly.collector.translate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lab.anomaly.collector.CollectorSettings.ClassificationRule;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides kind and unit (add-collector design K8), a Chain of Responsibility with three links:
 * settings rules (first match wins) → the source's metadata (cached) → unknown.
 * {@code classified_by} records which link decided.
 */
public final class Classifier {

    private static final Logger log = LoggerFactory.getLogger(Classifier.class);
    private static final Optional<MetricMetadata> NOTHING = Optional.empty();

    private final MetricSource source;
    private final ExpiringLruCache<String, Optional<MetricMetadata>> metadata;

    public Classifier(MetricSource source, int cacheSize, long ttlMs, Clock clock) {
        this.source = source;
        this.metadata = new ExpiringLruCache<>(cacheSize, ttlMs, clock);
    }

    public SeriesDescriptor.Builder classify(TranslatedLine line, List<ClassificationRule> rules) {
        SeriesDescriptor.Builder d = line.description();
        Kind kind = null;
        String unit = null;
        String by = null;

        for (int i = 0; i < rules.size() && (kind == null || unit == null); i++) {
            ClassificationRule rule = rules.get(i);
            if (matches(rule.match(), d.getMetric(), line)) {
                if (kind == null && rule.set().kind() != null) {
                    kind = kindOf(rule.set().kind());
                }
                if (unit == null && rule.set().unit() != null) {
                    unit = rule.set().unit();
                }
                by = by == null ? "rule:" + (i + 1) : by;
            }
        }
        if ((kind == null || unit == null) && line.singleMetric() != null) {
            Optional<MetricMetadata> meta = lookup(line.singleMetric());
            if (meta.isPresent()) {
                if (kind == null) {
                    kind = kindFromMetadata(meta.get());
                }
                if (unit == null) {
                    unit = meta.get().unit();
                }
                by = by == null && (kind != null || unit != null) ? "metadata" : by;
            }
        }
        return d.setKind(kind == null ? Kind.KIND_UNKNOWN : kind)
                .setUnit(unit == null ? "" : unit)
                .setClassifiedBy(by == null ? "default" : by);
    }

    private Optional<MetricMetadata> lookup(String metric) {
        Optional<Optional<MetricMetadata>> cached = metadata.get(metric);
        if (cached.isPresent()) {
            return cached.get();
        }
        try {
            Optional<MetricMetadata> found = source.metadata(metric);
            metadata.put(metric, found);                          // "nothing known" is cached too
            return found;
        } catch (SourceException e) {
            log.warn("No metadata for '{}' this time: {}", metric, e.getMessage());
            return NOTHING;                                       // not cached: try again next time
        }
    }

    static boolean matches(ClassificationRule.Match match, String metric, TranslatedLine line) {
        if (match.metric() != null && !glob(match.metric()).matcher(metric).matches()) {
            return false;
        }
        if (match.panelTitle() != null && line.panelTitles().stream().noneMatch(t -> glob(match.panelTitle()).matcher(t).matches())) {
            return false;
        }
        return match.hasFormula() == null || match.hasFormula() == line.hasFormula();
    }

    /** '*' matches any characters (dots included); everything else is literal. Case-sensitive. */
    static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    static Kind kindOf(String name) {
        return switch (name) {
            case "count" -> Kind.KIND_COUNT;
            case "rate" -> Kind.KIND_RATE;
            case "gauge" -> Kind.KIND_GAUGE;
            case "latency" -> Kind.KIND_LATENCY;
            case "ratio" -> Kind.KIND_RATIO;
            default -> Kind.KIND_UNKNOWN;
        };
    }

    static Kind kindFromMetadata(MetricMetadata meta) {
        String type = meta.type() == null ? "" : meta.type();
        String unit = meta.unit() == null ? "" : meta.unit();
        return switch (type) {
            case "count", "rate" -> Kind.KIND_COUNT;
            case "gauge" -> Kind.KIND_GAUGE;
            case "distribution" -> List.of("nanosecond", "microsecond", "millisecond", "second", "minute").contains(unit)
                    ? Kind.KIND_LATENCY : null;                   // a distribution without a time unit: leave it to a rule
            default -> null;
        };
    }
}
