package lab.anomaly.detector.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.Sites;
import org.junit.jupiter.api.Test;

/** add-multi-site task 4.1: each site's dashboard link opens that site's web pages. */
class DashboardLinkTest {

    private static String link(String site) {
        return new LineFacts("ffff000000000001", "m", "latency", "ms", "q", Map.of(), List.of(), 10_000, site, "q")
                .dashboardUrl("abc-def-ghi", 1000, 2000);
    }

    @Test
    void everySiteGetsItsOwnHost() {
        assertThat(link("datadoghq.com")).startsWith("https://app.datadoghq.com/dashboard/abc-def-ghi?from_ts=1000&to_ts=2000");
        assertThat(link("datadoghq.eu")).startsWith("https://app.datadoghq.eu/dashboard/");
        assertThat(link("us3.datadoghq.com")).startsWith("https://us3.datadoghq.com/dashboard/");
        assertThat(link("us5.datadoghq.com")).startsWith("https://us5.datadoghq.com/dashboard/");
        assertThat(link("ap1.datadoghq.com")).startsWith("https://ap1.datadoghq.com/dashboard/");
        assertThat(link("ddog-gov.com")).startsWith("https://app.ddog-gov.com/dashboard/");
        assertThat(link("")).isEmpty();
    }

    @Test
    void labelsAreShortAndUnknownSitesShowAsTheyAre() {
        assertThat(Sites.label("datadoghq.com")).isEqualTo("US1");
        assertThat(Sites.label("DatadogHQ.eu")).isEqualTo("EU");
        assertThat(Sites.label("us3.datadoghq.com")).isEqualTo("US3");
        assertThat(Sites.label("ap2.datadoghq.com")).isEqualTo("AP2");
        assertThat(Sites.label("ddog-gov.com")).isEqualTo("GOV");
        assertThat(Sites.label("example.org")).isEqualTo("example.org");
        assertThat(Sites.label("")).isEmpty();
    }

    @Test
    void noLinkWithoutAnIdAndPresetsLinkToTheirDashboard() {
        LineFacts f = new LineFacts("ffff000000000001", "m", "latency", "ms", "q", Map.of(), List.of(), 10_000, "datadoghq.com", "q");
        assertThat(f.dashboardUrl("file:dashboards/us/shop.json", 1000, 2000)).isEmpty();
        assertThat(f.dashboardUrl("file:dashboards/us/shop.json#Payments", 1000, 2000)).isEmpty();
        assertThat(f.dashboardUrl("abc-def-ghi#Payments", 1000, 2000)).startsWith("https://app.datadoghq.com/dashboard/abc-def-ghi?from_ts=1000");
    }
}
