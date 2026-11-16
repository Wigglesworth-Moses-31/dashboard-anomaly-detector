package lab.anomaly.collector.source.datadog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Panel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The tree walk on the two real lab dashboards (task 3.1). */
class DatadogDashboardReaderTest {

    private static Dashboard read(String file) throws Exception {
        try (InputStream in = DatadogDashboardReaderTest.class.getResourceAsStream("/dashboards/" + file)) {
            return DatadogDashboardReader.read(JsonMapper.builder().build().readTree(in));
        }
    }

    private static int queries(Dashboard dashboard) {
        return dashboard.panels().stream().mapToInt(p -> p.queries().size()).sum();
    }

    @Test
    void findsEveryMetricQueryOnBothLabDashboards() throws Exception {
        Dashboard swagstore = read("swagstore-dashboard.json");
        Dashboard skeleton = read("lab-skeleton-dashboard.json");

        assertThat(queries(swagstore) + queries(skeleton)).isEqualTo(48);
        assertThat(queries(swagstore)).isEqualTo(31);
        assertThat(queries(skeleton)).isEqualTo(17);
    }

    @Test
    void skipsTheServiceMapWhichHasNoMetricQuery() throws Exception {
        assertThat(read("swagstore-dashboard.json").skippedWidgets()).isEqualTo(1);
        assertThat(read("lab-skeleton-dashboard.json").skippedWidgets()).isZero();
    }

    @Test
    void findsPanelsInsideGroupsAndSplitGroupsWithTheirPath() throws Exception {
        List<Panel> panels = read("lab-skeleton-dashboard.json").panels();

        Panel splitLatency = panels.stream().filter(p -> p.title().equals("Latencies") && p.path().isEmpty()).findFirst().orElseThrow();
        assertThat(splitLatency.queries()).extracting(q -> q.text()).allMatch(t -> t.contains("lab.api.latency"));
        assertThat(panels).anyMatch(p -> p.path().equals("API Gateway Metrics") && p.title().equals("Latency"));
        assertThat(panels).anyMatch(p -> p.path().equals("Service Analytics") && p.title().equals("Total Service Traffic by API"));
    }

    @Test
    void readsTemplateVariablesWithDefaults() throws Exception {
        Dashboard skeleton = read("lab-skeleton-dashboard.json");
        assertThat(skeleton.variables()).anyMatch(v -> v.name().equals("env") && v.prefix().equals("env") && v.defaultValue().equals("prod"));
        assertThat(skeleton.variables()).anyMatch(v -> v.name().equals("team") && v.defaultValue().equals("*"));
    }

    @Test
    void keepsFormulasAndQueriesTogether() throws Exception {
        Panel successRate = read("lab-skeleton-dashboard.json").panels().stream()
                .filter(p -> p.title().equals("Success Rate")).findFirst().orElseThrow();
        assertThat(successRate.formulas()).containsExactly("query1 / query2 * 100");
        assertThat(successRate.queries()).extracting(q -> q.name()).containsExactly("query1", "query2");
    }
}
