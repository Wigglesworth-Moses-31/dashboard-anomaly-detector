package lab.anomaly.notifier.dash;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import lab.anomaly.contracts.v1.View;
import org.junit.jupiter.api.Test;

/** add-dashboard-view tasks 2.1–2.3: thinning keeps spikes, the catalogue keeps Datadog's order, live latest wins. */
class DashTest {

    static Point p(long ts, double v) {
        return Point.newBuilder().setTsMs(ts).setValue(v).build();
    }

    @Test
    void oneSpikeInTwoDaysSurvivesAndAtMostTwoPointsPerColumn() {
        long step = 60_000, from = 0, to = 2 * 24 * 3_600_000L;
        List<Point> points = new ArrayList<>();
        for (long t = from; t < to; t += step) {
            points.add(p(t, t == 1_000 * step ? 900 : 100 + (t / step) % 3));
        }
        List<double[]> thin = Thinning.minMax(points, from, to, 300);
        assertThat(thin.size()).isLessThanOrEqualTo(600);
        assertThat(thin).anySatisfy(x -> assertThat(x[1]).isEqualTo(900));
        for (int i = 1; i < thin.size(); i++) {
            assertThat(thin.get(i)[0]).as("time order").isGreaterThanOrEqualTo(thin.get(i - 1)[0]);
        }
        assertThat(Thinning.minMax(points.subList(0, 10), from, to, 300)).hasSize(10);   // few points: all kept
    }

    static SeriesDescriptor line(String id, String site, String group, int gi, String panel, int pi, String tag) {
        return SeriesDescriptor.newBuilder().setSeriesId(id).setMetric("svc.api.duration").setSource(Source.newBuilder().setSystem("datadog").setInstance(site))
                .putGroupTags("status", tag).addViews(View.newBuilder().setViewId("abc-def-ghi").setViewTitle("Shop")
                        .setPanelPath(group).setGroupIndex(gi).setPanelTitle(panel).setPanelIndex(pi)).build();
    }

    @Test
    void theCatalogueFollowsDatadogsOrderPerSite() {
        Catalogue c = Catalogue.of(List.of(
                line("00000000000000b1", "datadoghq.com", "API Metrics", 2, "Latencies", 5, "200"),
                line("00000000000000a1", "datadoghq.com", "API Gateway Metrics", 1, "Request Counts", 1, "500"),
                line("00000000000000a2", "datadoghq.com", "API Gateway Metrics", 1, "Request Counts", 1, "404"),
                line("00000000000000a3", "datadoghq.com", "API Gateway Metrics", 1, "Success Rate", 2, "200"),
                line("00000000000000e1", "datadoghq.eu", "API Metrics", 1, "Latencies", 1, "200")));
        assertThat(c.sites()).extracting(Catalogue.Site::site).containsExactly("datadoghq.com", "datadoghq.eu");
        Catalogue.Dashboard us = c.sites().get(0).dashboards().get(0);
        assertThat(us.groups()).extracting(Catalogue.Group::title).containsExactly("API Gateway Metrics", "API Metrics");
        assertThat(us.groups().get(0).panels()).extracting(Catalogue.Panel::title).containsExactly("Request Counts", "Success Rate");
        assertThat(us.groups().get(0).panels().get(0).lines()).hasSize(2);
        assertThat(us.panelCount()).isEqualTo(3);
        assertThat(c.places("00000000000000a1")).singleElement().satisfies(pl -> assertThat(pl.panel()).isEqualTo("Request Counts"));
        Catalogue.Dashboard eu = c.sites().get(1).dashboards().get(0);
        assertThat(us.id()).as("the same Datadog dashboard in two regions stays two dashboards").isNotEqualTo(eu.id());
        assertThat(c.dashboard(us.id()).lineCount()).isEqualTo(4);
        assertThat(c.dashboard(eu.id()).lineCount()).isEqualTo(1);
        assertThat(us.groups().get(1).panels().get(0).id()).isNotEqualTo(eu.groups().get(0).panels().get(0).id());
    }

    @Test
    void liveValuesGoOnlyToPagesShowingTheLineAndTheNewestWins() {
        LivePoints live = new LivePoints("unused:1", false);
        try {
            live.open(Set.of("00000000000000a1"));
            assertThat(live.streams()).isEqualTo(1);
            assertThat(live.linesShown()).isEqualTo(1);
            LivePoints.Stream s = new LivePoints.Stream(Set.of("x"), null);
            s.offer("x", List.of(p(1, 1)));
            s.offer("x", List.of(p(2, 2)));
            assertThat(s.drain()).containsExactly(Map.entry("x", List.of(p(2, 2))));
            assertThat(s.drain()).isEmpty();
            live.accept(MetricPoints.newBuilder().setSeriesId("00000000000000zz").addPoints(p(1, 1)).build());   // not shown: dropped
            live.accept(MetricPoints.newBuilder().setSeriesId("00000000000000a1").addPoints(p(1, 1)).build());
            live.flush();
            assertThat(live.pushed()).isEqualTo(1);
        } finally {
            live.destroy();
        }
    }

    @Test
    void shortRangesUseRawValuesAndLongOnesPerMinute() {
        AtomicBoolean lastRaw = new AtomicBoolean();
        StoreClient.Store store = new StoreClient.Store() {
            @Override public List<SeriesDescriptor> listSeries() {
                return List.of(line("00000000000000a1", "datadoghq.com", "", 0, "P", 0, "1"));
            }
            @Override public Map<String, List<Point>> history(List<String> ids, long fromMs, long toMs, boolean raw) {
                lastRaw.set(raw);
                return Map.of(ids.get(0), List.of(p(fromMs + 1, 5)));
            }
        };
        lab.anomaly.kit.settings.SettingsHolder<lab.anomaly.notifier.NotifierSettings> holder =
                new lab.anomaly.kit.settings.SettingsHolder<>("anomaly.notifier", lab.anomaly.notifier.NotifierSettings.class, List.of(),
                        new org.springframework.mock.env.MockEnvironment().withProperty("anomaly.notifier.rsocket-port", "7104")
                                .withProperty("anomaly.notifier.day-by", "started").withProperty("anomaly.notifier.max-queued-updates-per-viewer", "2000"));
        DashService d = new DashService(holder, store);
        assertThat(d.catalogue().lineCount()).isEqualTo(1);
        assertThat(d.range(List.of("00000000000000a1"), Duration.ofHours(1), 10_000_000, 600)).singleElement()
                .satisfies(s -> assertThat(s.points()).hasSize(1));
        assertThat(lastRaw).isTrue();
        d.range(List.of("00000000000000a1"), Duration.ofDays(1), 100_000_000, 600);
        assertThat(lastRaw).isFalse();
    }

    // ---- add-datadog-look K8: auto interval and rollup method like Datadog -------------------------------------

    @Test
    void theAutoIntervalGivesAtMostThreeHundredPoints() {
        assertThat(DashService.autoInterval(Duration.ofMinutes(15))).isEqualTo(10_000);
        assertThat(DashService.autoInterval(Duration.ofMinutes(30))).isEqualTo(10_000);
        assertThat(DashService.autoInterval(Duration.ofHours(1))).isEqualTo(20_000);
        assertThat(DashService.autoInterval(Duration.ofHours(3))).isEqualTo(60_000);
        assertThat(DashService.autoInterval(Duration.ofHours(6))).isEqualTo(120_000);
        assertThat(DashService.autoInterval(Duration.ofHours(12))).isEqualTo(300_000);
        assertThat(DashService.autoInterval(Duration.ofDays(1))).isEqualTo(300_000);
    }

    @Test
    void theRollupMethodFollowsTheQueryLikeDatadog() {
        assertThat(DashService.method("avg:lab.api.latency{env:prod} by {api}.rollup(max, 10)")).isEqualTo(lab.anomaly.contracts.v1.Rollup.ROLLUP_AVG);
        assertThat(DashService.method("sum:lab.api.request.count{*} by {status}.as_count().rollup(max, 10)")).isEqualTo(lab.anomaly.contracts.v1.Rollup.ROLLUP_SUM);
        assertThat(DashService.method("avg:x{*}.rollup(min, 60).rollup(max, 10)")).isEqualTo(lab.anomaly.contracts.v1.Rollup.ROLLUP_MIN);
        assertThat(DashService.method("")).isEqualTo(lab.anomaly.contracts.v1.Rollup.ROLLUP_AVG);
    }

    @Test
    void linesAreAskedOncePerMethodAtTheRangesInterval() {
        java.util.List<String> asked = new java.util.ArrayList<>();
        StoreClient.Store store = new StoreClient.Store() {
            @Override public List<SeriesDescriptor> listSeries() {
                return List.of(line("00000000000000a1", "datadoghq.com", "", 0, "P", 0, "1").toBuilder().setQueryText("avg:lat{*}").build(),
                        line("00000000000000a2", "datadoghq.com", "", 0, "P", 0, "2").toBuilder().setQueryText("sum:req{*}.as_count()").build());
            }
            @Override public Map<String, List<Point>> history(List<String> ids, long fromMs, long toMs, boolean raw) { return Map.of(); }
            @Override public Map<String, List<Point>> history(List<String> ids, long fromMs, long toMs, long intervalMs, lab.anomaly.contracts.v1.Rollup m) {
                asked.add(m + " " + intervalMs + " " + ids);
                return Map.of(ids.get(0), List.of(p(fromMs, 1)));
            }
        };
        DashService d = new DashService(new lab.anomaly.kit.settings.SettingsHolder<>("anomaly.notifier", lab.anomaly.notifier.NotifierSettings.class, List.of(),
                new org.springframework.mock.env.MockEnvironment().withProperty("anomaly.notifier.rsocket-port", "7104")
                        .withProperty("anomaly.notifier.day-by", "started").withProperty("anomaly.notifier.max-queued-updates-per-viewer", "2000")), store);
        List<DashService.Series> out = d.rolled(List.of("00000000000000a1", "00000000000000a2"), Duration.ofHours(6), 100_000_000, 120_000);
        assertThat(asked).containsExactlyInAnyOrder("ROLLUP_AVG 120000 [00000000000000a1]", "ROLLUP_SUM 120000 [00000000000000a2]");
        assertThat(out).allSatisfy(s -> assertThat(s.points()).hasSize(1));
    }
}
