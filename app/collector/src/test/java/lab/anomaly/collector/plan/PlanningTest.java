package lab.anomaly.collector.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Panel;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.Dashboard.TemplateVariable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Tasks 3.2 to 3.4: rollups, filter filling and duplicate merging. */
class PlanningTest {

    private static final Duration STEP = Duration.ofSeconds(10);
    private static final List<TemplateVariable> VARS = List.of(
            new TemplateVariable("env", "env", "prod"),
            new TemplateVariable("team", "team", "*"),
            new TemplateVariable("svc", "service", "*"));

    // ---- 3.3 filter filling ----------------------------------------------------------------------

    private static String fill(String query, Map<String, String> settings) {
        return FilterFiller.fill(query, VARS, FilterFiller.values(VARS, settings));
    }

    @Test
    void valueFromSettingsWins() {
        assertThat(fill("sum:requests{$env,$team}", Map.of("env", "stage"))).isEqualTo("sum:requests{env:stage}");
    }

    @Test
    void dashboardDefaultIsUsedWithoutASetting() {
        assertThat(fill("sum:requests{$env}", Map.of())).isEqualTo("sum:requests{env:prod}");
    }

    @Test
    void allValueIsDroppedWithoutStrayCommas() {
        assertThat(fill("sum:requests{$team,$env,status:2*,$svc}", Map.of())).isEqualTo("sum:requests{env:prod,status:2*}");
    }

    @Test
    void emptyFilterBecomesStar() {
        assertThat(fill("sum:requests{$team,$svc} by {api}", Map.of())).isEqualTo("sum:requests{*} by {api}");
    }

    @Test
    void dotValueFormAndPrefixDifferentFromName() {
        assertThat(fill("sum:requests{$env.value,$svc}", Map.of("svc", "checkout"))).isEqualTo("sum:requests{env:prod,service:checkout}");
    }

    @Test
    void groupByListIsNeverTouched() {
        assertThat(fill("sum:requests{$env} by {env,api}", Map.of())).isEqualTo("sum:requests{env:prod} by {env,api}");
    }

    // ---- 3.2 rollups (rules checked on the real API) ---------------------------------------------

    @Test
    void countsGetASumRollup() {
        assertThat(QueryPlanner.withRollup("sum:requests{env:prod} by {api}.as_count()", STEP))
                .isEqualTo("sum:requests{env:prod} by {api}.as_count().rollup(sum, 10)");
    }

    @Test
    void otherQueriesGetAMaxRollup() {
        assertThat(QueryPlanner.withRollup("p95:latency{env:prod} by {api}", STEP)).isEqualTo("p95:latency{env:prod} by {api}.rollup(max, 10)");
    }

    @Test
    void neverASumRollupWithoutAsCount() {
        // rollup(sum) on a plain count adds per-second rates: ten times too small (task 3.2)
        assertThat(QueryPlanner.withRollup("sum:requests{env:prod}", STEP)).doesNotContain("rollup(sum");
    }

    @Test
    void existingRollupAndWrappedQueriesAreLeftAlone() {
        assertThat(QueryPlanner.withRollup("avg:cpu{*}.rollup(avg, 60)", STEP)).isEqualTo("avg:cpu{*}.rollup(avg, 60)");
        assertThat(QueryPlanner.withRollup("top(sum:requests{*} by {api}, 10, 'mean', 'desc')", STEP))
                .isEqualTo("top(sum:requests{*} by {api}, 10, 'mean', 'desc')");
    }

    // ---- 3.4 duplicate merging ---------------------------------------------------------------------

    @Test
    void oneRequestOnTwoPanelsBecomesOneUnitWithTwoViews() {
        Panel panel = new Panel("Errors", "", List.of(new Query("query1", "sum:errors{$env}.as_count()")), List.of("query1"));
        Dashboard a = new Dashboard("a", "A", VARS, List.of(panel), 0);
        Dashboard b = new Dashboard("b", "B", VARS, List.of(new Panel("Errors again", "Group", panel.queries(), panel.formulas())), 0);

        List<PollUnit> units = PollPlan.build(List.of(a, b), Map.of(), STEP);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).views()).extracting(PollUnit.View::panelTitle).containsExactly("Errors", "Errors again");
        assertThat(units.get(0).queries().get(0).text()).isEqualTo("sum:errors{env:prod}.as_count().rollup(sum, 10)");
    }

    @Test
    void differentFilterValuesAreDifferentUnits() {
        Panel panel = new Panel("Errors", "", List.of(new Query("query1", "sum:errors{$env}.as_count()")), List.of("query1"));
        Dashboard a = new Dashboard("a", "A", VARS, List.of(panel), 0);
        Dashboard b = new Dashboard("b", "B", VARS, List.of(panel), 0);

        assertThat(PollPlan.build(List.of(a, b), Map.of("b", Map.of("env", "dev")), STEP)).hasSize(2);
    }

    @Test
    void labDashboardsGivePlainRequestsDatadogCanRun() throws Exception {
        Method read = Class.forName("lab.anomaly.collector.source.datadog.DatadogDashboardReader").getDeclaredMethod("read", tools.jackson.databind.JsonNode.class);
        read.setAccessible(true);
        Dashboard skeleton;
        try (InputStream in = getClass().getResourceAsStream("/dashboards/lab-skeleton-dashboard.json")) {
            skeleton = (Dashboard) read.invoke(null, JsonMapper.builder().build().readTree(in));
        }
        List<PollUnit> units = PollPlan.build(List.of(skeleton), Map.of(), STEP);

        assertThat(units).isNotEmpty();
        assertThat(units).flatExtracting(PollUnit::queries).extracting(Query::text)
                .allSatisfy(text -> assertThat(text).doesNotContain("$").contains("env:prod").contains(".rollup("));
        assertThat(units).flatExtracting(PollUnit::queries).extracting(Query::text)
                .filteredOn(t -> t.contains("rollup(sum")).allMatch(t -> t.contains(".as_count()"));
    }
}
