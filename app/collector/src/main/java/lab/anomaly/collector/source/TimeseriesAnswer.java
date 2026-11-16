package lab.anomaly.collector.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The numbers for one request. All series share {@code times} (the start of each step); a series' value is
 * {@code null} where the source had no data for that step.
 */
public record TimeseriesAnswer(List<Long> times, List<Series> series, long receivedMs, RateLimitStatus rateLimit) {

    public TimeseriesAnswer {
        times = List.copyOf(times);
        series = List.copyOf(series);
    }

    /** One line of the answer: which formula, its tag group, and one value (or null) per step. */
    public record Series(int formulaIndex, Map<String, String> groupTags, List<Double> values) {
        public Series {
            groupTags = Map.copyOf(groupTags);
            values = Collections.unmodifiableList(new ArrayList<>(values));   // keeps nulls (= no data)
        }
    }

    /** What the source said about its request limit, from its response headers. Null fields if unknown. */
    public record RateLimitStatus(Integer limit, Integer periodSeconds, Integer remaining, Integer resetSeconds) {}
}
