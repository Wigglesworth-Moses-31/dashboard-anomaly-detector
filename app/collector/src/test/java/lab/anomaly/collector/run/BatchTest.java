package lab.anomaly.collector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.emit.MessageSink;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import lab.anomaly.collector.translate.AnswerTranslator;
import lab.anomaly.collector.translate.TranslatedLine;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** add-dashboard-json tasks 2.7, 2.8, 3.2: batching, bisection, the budget guard and empty panels. */
class BatchTest {

    private static PollUnit unit(String key, List<Query> queries, List<String> formulas) {
        return new PollUnit(key, queries, formulas, List.of(new PollUnit.View("d", "Dashboard", "Panel " + key, "")), "us");
    }

    private static PollUnit simple(int i) {
        return unit("k" + i, List.of(new Query("query1", "sum:m" + i + "{*}")), List.of("query1"));
    }

    // ---- renaming, packing, mapping back ----------------------------------------------------------------------

    @Test
    void queryNamesAreRenamedAsWholeWordsOnly() {
        assertThat(Batch.rename("anomalies(query1 / query2 * 100, 'basic', 2)", List.of("query1", "query2"), "u3_"))
                .isEqualTo("anomalies(u3_query1 / u3_query2 * 100, 'basic', 2)");
        assertThat(Batch.rename("query1 + query12", List.of("query1"), "u0_")).isEqualTo("u0_query1 + query12");
    }

    @Test
    void unitsArePackedUpToTheFormulaLimitInOrder() {
        List<PollUnit> due = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            due.add(simple(i));
        }
        List<Batch> batches = Batch.pack(due, 40);
        assertThat(batches).extracting(Batch::formulas).containsExactly(40, 40, 20);
        assertThat(batches.get(1).units().get(0).key()).isEqualTo("k40");
        PollUnit big = unit("big", List.of(new Query("query1", "x")), java.util.Collections.nCopies(50, "query1"));
        assertThat(Batch.pack(List.of(big, simple(1)), 40)).extracting(Batch::size).containsExactly(1, 1);
    }

    @Test
    void seriesGoBackToTheirUnitWithTheirOwnIndex() {
        PollUnit a = unit("a", List.of(new Query("query1", "avg:lat{*}"), new Query("query2", "p95:lat{*}")), List.of("query1", "query2"));
        PollUnit b = unit("b", List.of(new Query("query1", "sum:req{*}")), List.of("query1"));
        PollUnit c = unit("c", List.of(new Query("query1", "sum:a{*}"), new Query("query2", "sum:b{*}")), List.of("query1 / query2 * 100"));
        Batch batch = Batch.of(List.of(a, b, c));
        TimeseriesRequest r = batch.request(0, 10_000, 10_000);
        assertThat(r.formulas()).containsExactly("u0_query1", "u0_query2", "u1_query1", "u2_query1 / u2_query2 * 100");
        assertThat(r.queries()).extracting(Query::name).containsExactly("u0_query1", "u0_query2", "u1_query1", "u2_query1", "u2_query2");

        TimeseriesAnswer combined = new TimeseriesAnswer(List.of(0L), List.of(
                new TimeseriesAnswer.Series(0, Map.of("api", "/x"), List.of(1.0)), new TimeseriesAnswer.Series(1, Map.of(), List.of(2.0)),
                new TimeseriesAnswer.Series(2, Map.of("s", "1"), List.of(3.0)), new TimeseriesAnswer.Series(2, Map.of("s", "2"), List.of(4.0)),
                new TimeseriesAnswer.Series(3, Map.of(), List.of(99.0))), 5L, null);
        List<TimeseriesAnswer> split = batch.split(combined);
        assertThat(split.get(0).series()).extracting(TimeseriesAnswer.Series::formulaIndex).containsExactly(0, 1);
        assertThat(split.get(1).series()).extracting(TimeseriesAnswer.Series::formulaIndex).containsExactly(0, 0);
        assertThat(split.get(2).series()).singleElement().satisfies(s -> {
            assertThat(s.formulaIndex()).isZero();
            assertThat(s.values()).containsExactly(99.0);
        });
    }

    @Test
    void batchedAnswersGiveExactlyTheLineIdsOfUnbatchedOnes() {
        PollUnit a = unit("a", List.of(new Query("query1", "avg:lat{*} by {api}")), List.of("query1"));
        PollUnit b = unit("b", List.of(new Query("query1", "sum:req{*} by {api}")), List.of("query1"));
        AnswerTranslator translator = new AnswerTranslator("datadog", "datadoghq.com");
        TimeseriesAnswer aAlone = new TimeseriesAnswer(List.of(0L), List.of(new TimeseriesAnswer.Series(0, Map.of("api", "/x"), List.of(1.0))), 5L, null);
        TimeseriesAnswer bAlone = new TimeseriesAnswer(List.of(0L), List.of(new TimeseriesAnswer.Series(0, Map.of("api", "/x"), List.of(2.0))), 5L, null);
        TimeseriesAnswer combined = new TimeseriesAnswer(List.of(0L), List.of(new TimeseriesAnswer.Series(0, Map.of("api", "/x"), List.of(1.0)),
                new TimeseriesAnswer.Series(1, Map.of("api", "/x"), List.of(2.0))), 5L, null);
        List<TimeseriesAnswer> split = Batch.of(List.of(a, b)).split(combined);

        assertThat(ids(translator.translate(a, split.get(0), Duration.ofSeconds(10))))
                .isEqualTo(ids(translator.translate(a, aAlone, Duration.ofSeconds(10))));
        assertThat(ids(translator.translate(b, split.get(1), Duration.ofSeconds(10))))
                .isEqualTo(ids(translator.translate(b, bAlone, Duration.ofSeconds(10))));
    }

    private static List<String> ids(List<TranslatedLine> lines) {
        return lines.stream().map(l -> l.description().getSeriesId()).toList();
    }

    // ---- budget guard ----------------------------------------------------------------------------------------

    @Test
    void theGuardStretchesTheIntervalOnlyWhenNeeded() {
        // Design J13/J14 numbers: all presets of the example dashboard, 270 units, about 400 formulas
        int withBatching = CollectorEngine.callsPerRound(270, 400, 40, 15_000);
        assertThat(withBatching).isEqualTo(10);
        assertThat(CollectorEngine.guardedInterval(withBatching, 15_000, 24)).isEqualTo(15_000);
        int withoutBatching = CollectorEngine.callsPerRound(270, 400, 1, 15_000);
        assertThat(withoutBatching).isEqualTo(270);
        assertThat(CollectorEngine.guardedInterval(withoutBatching, 15_000, 24)).isEqualTo(15_000);       // 270/(0.9*24)=12.5 s
        assertThat(CollectorEngine.guardedInterval(withoutBatching, 15_000, 8)).isEqualTo(37_500);        // 270/(0.9*8)=37.5 s
        assertThat(CollectorEngine.guardedInterval(0, 15_000, 8)).isEqualTo(15_000);
    }

    // ---- bisection and empty panels, through the engine ------------------------------------------------------

    /** Panels 0..n-1; panel {@code bad} is a question Datadog refuses; panel {@code empty} returns no lines. */
    static final class Site implements MetricSource {
        final int panels;
        final int bad;
        final int empty;
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> sizes = java.util.Collections.synchronizedList(new ArrayList<>());

        Site(int panels, int bad, int empty) {
            this.panels = panels;
            this.bad = bad;
            this.empty = empty;
        }

        @Override
        public Dashboard dashboard(String id) {
            List<Dashboard.Panel> list = new ArrayList<>();
            for (int i = 0; i < panels; i++) {
                list.add(new Dashboard.Panel("Panel " + i, "", List.of(new Query("query1", "sum:lab.p" + i + "{*}")), List.of("query1")));
            }
            return new Dashboard(id, "Shop", List.of(), list, 0);
        }

        @Override
        public TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException {
            calls.incrementAndGet();
            sizes.add(request.formulas().size());
            List<TimeseriesAnswer.Series> series = new ArrayList<>();
            for (int i = 0; i < request.queries().size(); i++) {
                String q = request.queries().get(i).text();
                if (q.contains("lab.p" + bad + "{")) {
                    throw new SourceException(SourceException.Kind.BAD_REQUEST, "HTTP 400: bad query " + q);
                }
                if (!q.contains("lab.p" + empty + "{")) {
                    series.add(new TimeseriesAnswer.Series(i, Map.of(), List.of(1.0)));
                }
            }
            long t = (request.toMs() / 10_000) * 10_000 - 10_000;
            return new TimeseriesAnswer(List.of(t), series, System.currentTimeMillis(), null);
        }

        @Override
        public java.util.Optional<MetricMetadata> metadata(String metric) {
            return java.util.Optional.empty();
        }
    }

    private CollectorEngine engine;

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.stop();
        }
    }

    private CollectorEngine run(Site site, int panels) throws Exception {
        CollectorSettings s = new CollectorSettings("datadog", null, List.of(), Duration.ofSeconds(2), Duration.ofSeconds(10),
                Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(60), Map.of(), List.of(), 8, 1.0, 0,
                Duration.ofMillis(10), Duration.ofMillis(20), 50, Duration.ofMinutes(1), Duration.ofHours(24), 1000,
                new CollectorSettings.Recording(false, "recordings"), null,
                List.of(new CollectorSettings.Source("us", "datadoghq.com", "DD_US_API_KEY", "DD_US_APP_KEY",
                        List.of(CollectorSettings.DashboardRef.valueOf("d1")), Map.of(), null, null)), 40);
        MockEnvironment env = new MockEnvironment().withProperty("DD_US_API_KEY", "k").withProperty("DD_US_APP_KEY", "k");
        engine = new CollectorEngine(() -> s, () -> 1, c -> DatadogCredentials.fromEnvironment(env, c.apiKeyEnv(), c.appKeyEnv(), c.name()),
                (c, k, clock) -> site, null, Clock.systemUTC());
        engine.startLive(new MessageSink() {
            @Override public void send(SeriesDescriptor d) { }
            @Override public void send(MetricPoints p) { }
        });
        long end = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < end && engine.lastLines().size() < panels - 1) {
            Thread.sleep(50);
        }
        return engine;
    }

    @Test
    void oneBadQuestionIsIsolatedAndTheOthersGetTheirNumbers() throws Exception {
        Site site = new Site(32, 17, -1);
        run(site, 32);
        assertThat(engine.lastLines()).hasSize(31);                                    // every panel but the bad one
        assertThat(engine.lastLines().keySet()).noneMatch(id -> id.contains("lab.p17{"));
        assertThat(engine.openBreakers(engine.lane("us"))).isEmpty();                    // one failure, not yet open
        assertThat(engine.failedPolls()).isGreaterThanOrEqualTo(1);
        assertThat(site.calls.get()).as("far fewer calls than panels").isLessThan(32);
    }

    @Test
    void panelsThatReturnNothingAreReportedWithTheirFilters() throws Exception {
        Site site = new Site(6, -1, 3);
        run(site, 7);
        var empty = engine.emptyPanels(engine.lane("us"));
        assertThat(empty).singleElement().satisfies(e -> {
            assertThat(e.view()).isEqualTo("Shop");
            assertThat(e.empty()).containsExactly("Panel 3");
            assertThat(e.panels()).isEqualTo(6);
        });
        assertThat(new CollectorHealthIndicator(engine).health().getDetails().get("warnings").toString())
                .doesNotContain("return no lines");                                     // 1 of 6: listed, not a warning
    }
}
