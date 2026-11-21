package lab.anomaly.collector.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lab.anomaly.collector.check.DashboardCheck;
import lab.anomaly.collector.dashboards.DashboardJsonTest;
import lab.anomaly.collector.source.Dashboard;
import org.junit.jupiter.api.Test;

/** add-dashboard-view task 1.2: every view of a line records its group's and its panel's place in Datadog's order. */
class PanelOrderTest {

    @Test
    void groupsAndPanelsAreNumberedInDashboardOrder() throws Exception {
        Dashboard d = DashboardCheck.parse("lab.json", DashboardJsonTest.withPresets());
        List<WatchedView> views = DashboardCheck.views(d, "lab.json", List.of(), Map.of());
        List<PollUnit> units = PollPlan.buildViews("us", views, Duration.ofSeconds(10));
        List<PollUnit.View> vs = units.stream().map(u -> u.views().get(0)).toList();
        assertThat(vs).allSatisfy(v -> {
            assertThat(v.groupIndex()).isPositive();
            assertThat(v.panelIndex()).isPositive();
        });
        for (int i = 1; i < vs.size(); i++) {
            assertThat(vs.get(i).panelIndex()).isGreaterThan(vs.get(i - 1).panelIndex());
            assertThat(vs.get(i).groupIndex()).isGreaterThanOrEqualTo(vs.get(i - 1).groupIndex());
        }
        assertThat(vs.get(0).panelIndex()).isEqualTo(1);
        assertThat(vs.get(0).groupIndex()).isEqualTo(1);
        assertThat(vs).as("each view names its widget (add-datadog-look K1)").allSatisfy(v -> assertThat(v.widgetId()).isPositive());
    }
}
