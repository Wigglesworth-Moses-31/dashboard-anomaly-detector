package lab.anomaly.collector.plan;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Asks for one value per step (add-collector design K5, checked on the real API in task 3.2).
 * <ul>
 *   <li>Already has {@code .rollup(...)}: left as it is (the dashboard chose).</li>
 *   <li>Ends in {@code .as_count()}: {@code .rollup(sum, step)}, the true count per step.</li>
 *   <li>Otherwise: {@code .rollup(max, step)}, the highest value per step.</li>
 *   <li>Wrapped in a function such as {@code top(...)}: left as it is, because appending would break it.</li>
 * </ul>
 * Never {@code rollup(sum)} without {@code .as_count()}: that adds per-second rates and is ten times too small.
 */
public final class QueryPlanner {

    /** A plain query starts with an aggregator and a metric, e.g. "sum:metric{...}" or "p95:metric{...}". */
    private static final Pattern PLAIN = Pattern.compile("^[a-z0-9]+:[A-Za-z0-9_.]+\\{.*");

    public static String withRollup(String query, Duration step) {
        String text = query.strip();
        long seconds = Math.max(1, step.toSeconds());
        if (text.contains(".rollup(") || !PLAIN.matcher(text).matches()) {
            return text;
        }
        if (text.endsWith(".as_count()")) {
            return text + ".rollup(sum, " + seconds + ")";
        }
        return text + ".rollup(max, " + seconds + ")";
    }

    private QueryPlanner() {
    }
}
