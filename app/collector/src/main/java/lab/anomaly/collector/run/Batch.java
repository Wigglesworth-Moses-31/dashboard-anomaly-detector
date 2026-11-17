package lab.anomaly.collector.run;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;

/**
 * Many panels in one Datadog call (add-dashboard-json design J14). Measured on the trial: one request with up to 80
 * formulas costs one unit of the rate limit, and every series comes back tagged with its formula index.
 *
 * <ul>
 *   <li>{@link #pack}: greedy packing of due units, in due order, into batches of at most {@code maxFormulas}
 *       formulas. O(units).</li>
 *   <li>{@link #request}: unit k's {@code query1} becomes {@code u3_query1}; its formulas are rewritten with a
 *       word-boundary match so {@code anomalies(query1 / query2 * 100, 'basic', 2)} keeps working. Offsets remember
 *       where each unit's formulas start.</li>
 *   <li>{@link #split}: a series with formula index i belongs to the unit whose offset range holds i, found by binary
 *       search, O(log units); it gets the index i − offset, so each unit is translated exactly as if asked alone
 *       (same keys, same line ids).</li>
 * </ul>
 */
public final class Batch {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final List<PollUnit> units;
    private final int[] offsets;                 // offsets[k] = index of unit k's first formula in the combined request

    private Batch(List<PollUnit> units) {
        this.units = List.copyOf(units);
        this.offsets = new int[units.size()];
        int at = 0;
        for (int k = 0; k < units.size(); k++) {
            offsets[k] = at;
            at += units.get(k).formulas().size();
        }
    }

    public static Batch of(List<PollUnit> units) {
        return new Batch(units);
    }

    public List<PollUnit> units() {
        return units;
    }

    public int size() {
        return units.size();
    }

    /** Greedy packing in the given order; a unit with more formulas than the limit goes alone. O(units). */
    public static List<Batch> pack(List<PollUnit> due, int maxFormulas) {
        List<Batch> batches = new ArrayList<>();
        List<PollUnit> current = new ArrayList<>();
        int formulas = 0;
        for (PollUnit unit : due) {
            int n = unit.formulas().size();
            if (!current.isEmpty() && formulas + n > maxFormulas) {
                batches.add(new Batch(current));
                current = new ArrayList<>();
                formulas = 0;
            }
            current.add(unit);
            formulas += n;
        }
        if (!current.isEmpty()) {
            batches.add(new Batch(current));
        }
        return batches;
    }

    /** One request for every unit of the batch; a batch of one is exactly the unit's own request. */
    public TimeseriesRequest request(long fromMs, long toMs, long stepMs) {
        if (units.size() == 1) {
            PollUnit u = units.get(0);
            return new TimeseriesRequest(u.queries(), u.formulas(), fromMs, toMs, stepMs);
        }
        List<Query> queries = new ArrayList<>();
        List<String> formulas = new ArrayList<>();
        for (int k = 0; k < units.size(); k++) {
            PollUnit u = units.get(k);
            String prefix = "u" + k + "_";
            List<String> names = u.queries().stream().map(Query::name).toList();
            for (Query q : u.queries()) {
                queries.add(new Query(prefix + q.name(), q.text()));
            }
            for (String f : u.formulas()) {
                formulas.add(rename(f, names, prefix));
            }
        }
        return new TimeseriesRequest(queries, formulas, fromMs, toMs, stepMs);
    }

    /** Renames whole-word query names only: "query1" in "query1 / query12" but not inside "query12". */
    static String rename(String formula, List<String> names, String prefix) {
        Matcher m = NAME.matcher(formula);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(names.contains(m.group()) ? prefix + m.group() : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Each unit's own answer: its series with their own formula index, the shared times and rate-limit status. */
    public List<TimeseriesAnswer> split(TimeseriesAnswer answer) {
        if (units.size() == 1) {
            return List.of(answer);
        }
        List<List<TimeseriesAnswer.Series>> perUnit = new ArrayList<>();
        units.forEach(u -> perUnit.add(new ArrayList<>()));
        for (TimeseriesAnswer.Series s : answer.series()) {
            int k = unitOf(s.formulaIndex());
            if (k >= 0) {
                perUnit.get(k).add(new TimeseriesAnswer.Series(s.formulaIndex() - offsets[k], s.groupTags(), s.values()));
            }
        }
        List<TimeseriesAnswer> out = new ArrayList<>();
        for (List<TimeseriesAnswer.Series> series : perUnit) {
            out.add(new TimeseriesAnswer(answer.times(), series, answer.receivedMs(), answer.rateLimit()));
        }
        return out;
    }

    /** Binary search over the offsets: the unit whose formulas hold index i, or -1. O(log units). */
    int unitOf(int formulaIndex) {
        if (formulaIndex < 0) {
            return -1;
        }
        int k = Arrays.binarySearch(offsets, formulaIndex);
        if (k < 0) {
            k = -k - 2;                                   // insertion point − 1: the last offset below i
        }
        while (k > 0 && offsets[k] == offsets[k - 1]) {
            k--;                                          // never land on a unit with no formulas
        }
        return k >= 0 && formulaIndex < offsets[k] + units.get(k).formulas().size() ? k : -1;
    }

    /** The two halves, for bisection when Datadog refuses the whole batch for one bad question. */
    public List<Batch> halves() {
        int mid = units.size() / 2;
        return List.of(new Batch(units.subList(0, mid)), new Batch(units.subList(mid, units.size())));
    }

    /** Number of formulas in the batch. */
    public int formulas() {
        return units.stream().mapToInt(u -> u.formulas().size()).sum();
    }
}
