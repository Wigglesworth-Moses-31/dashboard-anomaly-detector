package lab.anomaly.detector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import lab.anomaly.contracts.v1.View;
import lab.anomaly.detector.incident.IncidentBook.Change;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** anomaly-per-graph 1.3: with incident-scope graph (the shipped default) every graph has its own anomalies. */
class GraphScopeTest {

    static final long T0 = 1_763_200_000_000L, STEP = 10_000;
    static final int WARM = 90;

    private Detector detector;
    private final List<Change> changes = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        detector = new Detector(() -> "inc-" + ids.incrementAndGet());
        detector.configure(Fixtures.defaults(), 1);
    }

    /** A count line on one panel (widget) of a dashboard in a site, optionally inside a split group. */
    static SeriesDescriptor line(String id, String site, String dash, long widget, String panel, String splitTag, Map<String, String> tags) {
        return SeriesDescriptor.newBuilder().setSeriesId(id)
                .setSource(Source.newBuilder().setSystem("datadog").setInstance(site))
                .setQueryId(String.format("%016x", (long) (panel + widget).hashCode() & 0xffffffffL)).setQueryText("sum:lab.requests{*}")
                .setMetric("lab.requests").setKind(Kind.KIND_COUNT).setUnit("requests").putAllGroupTags(tags).setStepSeconds(10)
                .addViews(View.newBuilder().setViewId(dash).setViewTitle("Shop").setPanelTitle(panel).setWidgetId(widget).setSplitTag(splitTag))
                .setFirstSeenMs(1).setUpdatedMs(1).build();
    }

    /** Warms every line at 100, then feeds 1,000 for two steps: each line spikes. */
    private void spikeAll(SeriesDescriptor... lines) {
        for (SeriesDescriptor d : lines) {
            detector.describe(d);
        }
        long t = T0;
        for (int i = 0; i < WARM + 3; i++) {
            for (SeriesDescriptor d : lines) {
                changes.addAll(detector.accept(d.getSeriesId(), t, i < WARM ? 100 : 1000, false, t + 20_000));
            }
            t += STEP;
        }
    }

    private List<String> openedKeys() {
        return changes.stream().filter(c -> c.type() == EventType.EVENT_TYPE_OPENED).map(c -> c.incident().getGroupKey()).distinct().toList();
    }

    private List<String> openedTitles() {
        return changes.stream().filter(c -> c.type() == EventType.EVENT_TYPE_OPENED).map(c -> c.incident().getTitle()).distinct().toList();
    }

    @Test
    void linesOfThreePanelsWithoutServiceTagsAreThreeAnomalies() {
        spikeAll(line("ffff000000000101", "datadoghq.com", "d1", 11, "Request Counts", "", Map.of("client", "a")),
                line("ffff000000000102", "datadoghq.com", "d1", 11, "Request Counts", "", Map.of("client", "b")),
                line("ffff000000000103", "datadoghq.com", "d1", 12, "Request Counts by Partner", "", Map.of("partner", "north")),
                line("ffff000000000104", "datadoghq.com", "d1", 13, "Fallback Results", "", Map.of("status_code", "500")));
        assertThat(openedKeys()).containsExactlyInAnyOrder("datadoghq.com|graph=d1|w11", "datadoghq.com|graph=d1|w12", "datadoghq.com|graph=d1|w13");
        assertThat(changes.stream().filter(c -> c.incident().getGroupKey().endsWith("|w11")).reduce((a, b) -> b).orElseThrow().incident().getSeriesCount())
                .as("two lines of one graph share one anomaly").isEqualTo(2);
    }

    @Test
    void eachSmallGraphOfASplitGroupIsItsOwnAnomaly() {
        spikeAll(line("ffff000000000201", "datadoghq.com", "d1", 21, "Count", "api", Map.of("api", "/v1/devices")),
                line("ffff000000000202", "datadoghq.com", "d1", 21, "Count", "api", Map.of("api", "/v1/invoices")));
        assertThat(openedKeys()).containsExactlyInAnyOrder("datadoghq.com|graph=d1|w21|api=/v1/devices", "datadoghq.com|graph=d1|w21|api=/v1/invoices");
        assertThat(openedTitles()).allMatch(t -> t.startsWith("Count · api=/v1/"));
    }

    @Test
    void theSameDashboardInTwoRegionsIsTwoAnomalies() {
        spikeAll(line("ffff000000000301", "datadoghq.com", "d1", 31, "Request Counts", "", Map.of("client", "a")),
                line("ffff000000000302", "datadoghq.eu", "d1", 31, "Request Counts", "", Map.of("client", "a")));
        assertThat(openedKeys()).containsExactlyInAnyOrder("datadoghq.com|graph=d1|w31", "datadoghq.eu|graph=d1|w31");
    }

    @Test
    void serviceScopeKeepsTheOldGrouping() {
        detector.configure(Fixtures.serviceScope(), 2);
        spikeAll(line("ffff000000000401", "datadoghq.com", "d1", 41, "Request Counts", "", Map.of("client", "a")),
                line("ffff000000000402", "datadoghq.com", "d1", 42, "Fallback Results", "", Map.of("status_code", "500")));
        assertThat(openedKeys()).containsExactly("datadoghq.com|dashboard=d1");
    }

    @Test
    void aWrongScopeIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Fixtures.holder(Map.of("anomaly.detector.incident-scope", "panel")).get())
                .hasMessageContaining("incident-scope");
    }
}
