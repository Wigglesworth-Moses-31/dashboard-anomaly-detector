package lab.anomaly.collector.translate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.contracts.SeriesIds;
import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import lab.anomaly.contracts.v1.View;

/**
 * Turns one source answer into metric lines (add-collector design K6).
 * <ul>
 *   <li>A step with no value gives no point (spec: gaps are left out).</li>
 *   <li>A step whose end is after the moment the answer arrived is partial.</li>
 *   <li>Each formula of a unit is its own line family; tag groups make the individual lines.</li>
 * </ul>
 */
public final class AnswerTranslator {

    private static final Pattern PLAIN = Pattern.compile("^([a-z0-9]+):([A-Za-z0-9_.]+)\\{");
    private static final Pattern ROLLUP = Pattern.compile("\\.rollup\\(\\s*([a-z0-9]+)");

    private final String system;
    private final String instance;

    /** Steps that ended less than this before the answer arrived stay partial (fix-false-drops design F1). */
    private final long settleMs;

    public AnswerTranslator(String system, String instance) {
        this(system, instance, 0);
    }

    public AnswerTranslator(String system, String instance, long settleMs) {
        this.settleMs = Math.max(0, settleMs);
        this.system = system;
        this.instance = instance;
    }

    public List<TranslatedLine> translate(PollUnit unit, TimeseriesAnswer answer, Duration step) {
        long stepMs = step.toMillis();
        List<TranslatedLine> lines = new ArrayList<>();
        for (TimeseriesAnswer.Series series : answer.series()) {
            int formulaIndex = series.formulaIndex();
            if (formulaIndex < 0 || formulaIndex >= unit.formulas().size()) {
                continue;                                         // the source answered something we did not ask
            }
            String formula = unit.formulas().get(formulaIndex);
            List<Query> used = referenced(unit, formula);
            Set<String> metrics = new LinkedHashSet<>();
            used.forEach(q -> metricOf(q.text()).ifPresent(metrics::add));
            String metric = metrics.isEmpty() ? formula : String.join(", ", metrics);
            boolean hasFormula = unit.hasFormula(formulaIndex);

            String queryId = SeriesIds.queryId(unit.key() + "\nformula#" + formulaIndex + "=" + formula);
            String seriesId = SeriesIds.seriesId(system, instance, queryId, series.groupTags());

            SeriesDescriptor.Builder description = SeriesDescriptor.newBuilder()
                    .setSeriesId(seriesId)
                    .setSource(Source.newBuilder().setSystem(system).setInstance(instance))
                    .setQueryId(queryId)
                    .setQueryText(hasFormula ? formula + "  where  " + describe(used) : used.get(0).text())
                    .setMetric(metric)
                    .putAllGroupTags(series.groupTags())
                    .setSpaceAggregation(spaceAggregation(used))
                    .setTimeAggregation(timeAggregation(used))
                    .setStepSeconds((int) step.toSeconds());
            unit.views().forEach(v -> description.addViews(View.newBuilder()
                    .setViewId(v.dashboardId()).setViewTitle(v.dashboardTitle()).setPanelTitle(v.panelTitle())
                    .setPanelPath(v.path() == null ? "" : v.path())));               // add-incident-history H2

            List<Point> points = new ArrayList<>();
            for (int i = 0; i < answer.times().size() && i < series.values().size(); i++) {
                Double value = series.values().get(i);
                if (value == null || !Double.isFinite(value)) {
                    continue;                                     // no data for this step: no point, never 0
                }
                long start = answer.times().get(i);
                points.add(Point.newBuilder().setTsMs(start).setValue(value)
                        .setPartial(start + stepMs + settleMs > answer.receivedMs()).build());
            }
            lines.add(new TranslatedLine(description, points, hasFormula,
                    !hasFormula && metrics.size() == 1 ? metrics.iterator().next() : null,
                    unit.views().stream().map(PollUnit.View::panelTitle).toList(), answer.receivedMs()));
        }
        return lines;
    }

    /** The queries a formula uses (by name), in the unit's order; all of them if none is named. */
    private static List<Query> referenced(PollUnit unit, String formula) {
        List<Query> used = new ArrayList<>();
        for (Query q : unit.queries()) {
            if (Pattern.compile("\\b" + Pattern.quote(q.name()) + "\\b").matcher(formula).find()) {
                used.add(q);
            }
        }
        return used.isEmpty() ? unit.queries() : used;
    }

    private static String describe(List<Query> queries) {
        return String.join("; ", queries.stream().map(q -> q.name() + " = " + q.text()).toList());
    }

    private static java.util.Optional<String> metricOf(String query) {
        Matcher m = PLAIN.matcher(query.strip());
        return m.find() ? java.util.Optional.of(m.group(2)) : java.util.Optional.empty();
    }

    private static Aggregation spaceAggregation(List<Query> used) {
        Matcher m = PLAIN.matcher(used.get(0).text().strip());
        return m.find() ? aggregation(m.group(1)) : Aggregation.AGGREGATION_AVG;
    }

    private static Aggregation timeAggregation(List<Query> used) {
        Matcher m = ROLLUP.matcher(used.get(0).text());
        String method = null;
        while (m.find()) {
            method = m.group(1);
        }
        return method == null ? Aggregation.AGGREGATION_AVG : aggregation(method);
    }

    static Aggregation aggregation(String name) {
        return switch (name) {
            case "sum" -> Aggregation.AGGREGATION_SUM;
            case "avg" -> Aggregation.AGGREGATION_AVG;
            case "min" -> Aggregation.AGGREGATION_MIN;
            case "max" -> Aggregation.AGGREGATION_MAX;
            case "count" -> Aggregation.AGGREGATION_COUNT;
            case "last" -> Aggregation.AGGREGATION_LAST;
            case "p50" -> Aggregation.AGGREGATION_P50;
            case "p75" -> Aggregation.AGGREGATION_P75;
            case "p90" -> Aggregation.AGGREGATION_P90;
            case "p95" -> Aggregation.AGGREGATION_P95;
            case "p99" -> Aggregation.AGGREGATION_P99;
            default -> Aggregation.AGGREGATION_AVG;
        };
    }
}
