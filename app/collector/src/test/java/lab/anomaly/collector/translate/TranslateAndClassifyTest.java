package lab.anomaly.collector.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lab.anomaly.collector.CollectorSettings.ClassificationRule;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.support.FakeSource;
import lab.anomaly.collector.support.MutableClock;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.contracts.SeriesIds;
import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.Kind;
import org.junit.jupiter.api.Test;

/** Tasks 4.1 and 4.2. */
class TranslateAndClassifyTest {

    private static final long T0 = 1_763_182_800_000L;
    private static final Duration STEP = Duration.ofSeconds(10);
    private final AnswerTranslator translator = new AnswerTranslator("datadog", "datadoghq.com");

    private static PollUnit unit(List<Query> queries, List<String> formulas, String panelTitle) {
        return new PollUnit("key:" + queries + formulas, queries, formulas,
                List.of(new PollUnit.View("w2f-pqs-ghd", "Lab", panelTitle, "")));
    }

    private static TimeseriesAnswer answer(long receivedMs, Double... values) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            times.add(T0 + i * 10_000L);
        }
        return new TimeseriesAnswer(times, List.of(new TimeseriesAnswer.Series(0, Map.of("api", "/v1/invoices"),
                new ArrayList<>(Arrays.asList(values)))), receivedMs, null);
    }

    // ---- 4.1 translation ---------------------------------------------------------------------------

    @Test
    void gapsGiveNoPointAndTheUnfinishedStepIsPartial() {
        PollUnit unit = unit(List.of(new Query("query1", "sum:lab.api.request.count{env:prod} by {api}.as_count().rollup(sum, 10)")),
                List.of("query1"), "Count");
        // steps at T0, T0+10s, T0+20s; the answer arrives 5 s into the last step
        TranslatedLine line = translator.translate(unit, answer(T0 + 25_000, 5.0, null, 7.0), STEP).get(0);

        assertThat(line.points()).extracting(p -> p.getTsMs()).containsExactly(T0, T0 + 20_000);   // no point for the gap
        assertThat(line.points()).extracting(p -> p.getPartial()).containsExactly(false, true);
        assertThat(line.description().getGroupTagsMap()).containsEntry("api", "/v1/invoices");
        assertThat(line.description().getMetric()).isEqualTo("lab.api.request.count");
        assertThat(line.description().getSpaceAggregation()).isEqualTo(Aggregation.AGGREGATION_SUM);
        assertThat(line.description().getTimeAggregation()).isEqualTo(Aggregation.AGGREGATION_SUM);
        assertThat(line.hasFormula()).isFalse();
        assertThat(line.description().getSeriesId()).isEqualTo(SeriesIds.seriesId(line.description().build()));
    }

    @Test
    void aFormulaIsItsOwnLineWithAllItsMetrics() {
        PollUnit unit = unit(List.of(new Query("query1", "sum:lab.api.request.count{status_code:2*}.as_count().rollup(sum, 10)"),
                new Query("query2", "sum:lab.api.request.count{*}.as_count().rollup(sum, 10)")), List.of("query1 / query2 * 100"), "Success Rate");
        TranslatedLine line = translator.translate(unit, answer(T0 + 100_000, 96.0), STEP).get(0);

        assertThat(line.hasFormula()).isTrue();
        assertThat(line.singleMetric()).isNull();                   // a computed value: no metadata lookup
        assertThat(line.description().getQueryText()).startsWith("query1 / query2 * 100  where  query1 = ");
    }

    /** anomaly-per-graph P2: a split group's lines carry its split tag (and widget id) in their place. */
    @Test
    void aSplitGroupLineNamesItsSplitTag() {
        PollUnit unit = new PollUnit("key:split", List.of(new Query("query1", "sum:lab.api.request.count{env:prod} by {api}.as_count().rollup(sum, 10)")),
                List.of("query1"), List.of(new PollUnit.View("w2f-pqs-ghd", "Lab", "Count", "API Metrics", "", 2, 5, 77L, "api")));
        TranslatedLine line = translator.translate(unit, answer(T0 + 25_000, 5.0), STEP).get(0);
        assertThat(line.description().getViews(0).getSplitTag()).isEqualTo("api");
        assertThat(line.description().getViews(0).getWidgetId()).isEqualTo(77L);
        assertThat(translator.translate(unit(List.of(new Query("query1", "sum:x{*}")), List.of("query1"), "Count"), answer(T0 + 25_000, 5.0), STEP)
                .get(0).description().getViews(0).getSplitTag()).as("not a split group").isEmpty();
    }

    // ---- 4.2 classification ------------------------------------------------------------------------

    private static final List<ClassificationRule> RULES = List.of(
            new ClassificationRule(new ClassificationRule.Match("lab.*.latency", null, null), new ClassificationRule.Assignment("latency", "ms")),
            new ClassificationRule(new ClassificationRule.Match(null, "*Success Rate*", true), new ClassificationRule.Assignment("ratio", "percent")));

    private TranslatedLine plain(String metric, String panel) {
        PollUnit unit = unit(List.of(new Query("query1", "p95:" + metric + "{*}.rollup(max, 10)")), List.of("query1"), panel);
        return translator.translate(unit, answer(T0 + 100_000, 1.0), STEP).get(0);
    }

    @Test
    void ruleFirstThenMetadataThenUnknown() {
        FakeSource source = new FakeSource();
        source.metadata.put("trace.http.request", new MetricMetadata("distribution", "second"));
        Classifier classifier = new Classifier(source, 100, 86_400_000, new MutableClock(Instant.ofEpochMilli(T0)));

        var byRule = classifier.classify(plain("lab.api.latency", "Latencies"), RULES);
        assertThat(byRule.getKind()).isEqualTo(Kind.KIND_LATENCY);
        assertThat(byRule.getUnit()).isEqualTo("ms");
        assertThat(byRule.getClassifiedBy()).isEqualTo("rule:1");

        var byMetadata = classifier.classify(plain("trace.http.request", "Latency"), RULES);
        assertThat(byMetadata.getKind()).isEqualTo(Kind.KIND_LATENCY);
        assertThat(byMetadata.getUnit()).isEqualTo("second");
        assertThat(byMetadata.getClassifiedBy()).isEqualTo("metadata");

        var unknown = classifier.classify(plain("something.else", "Other"), RULES);
        assertThat(unknown.getKind()).isEqualTo(Kind.KIND_UNKNOWN);
        assertThat(unknown.getClassifiedBy()).isEqualTo("default");
        assertThat(ContractChecks.problems(unknown.build())).isEmpty();
    }

    @Test
    void ruleOnPanelTitleAndFormula() {
        FakeSource source = new FakeSource();
        Classifier classifier = new Classifier(source, 100, 86_400_000, new MutableClock(Instant.ofEpochMilli(T0)));
        PollUnit unit = unit(List.of(new Query("query1", "sum:a{*}.as_count()"), new Query("query2", "sum:b{*}.as_count()")),
                List.of("query1 / query2 * 100"), "API Success Rate");
        var d = classifier.classify(translator.translate(unit, answer(T0 + 100_000, 99.0), STEP).get(0), RULES);

        assertThat(d.getKind()).isEqualTo(Kind.KIND_RATIO);
        assertThat(d.getClassifiedBy()).isEqualTo("rule:2");
        assertThat(source.metadataCalls.get()).isZero();            // formulas never look up metadata
    }

    @Test
    void metadataIsLookedUpOnceUntilItExpires() {
        FakeSource source = new FakeSource();
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(T0));
        Classifier classifier = new Classifier(source, 100, Duration.ofHours(24).toMillis(), clock);

        classifier.classify(plain("trace.http.request", "Latency"), RULES);
        classifier.classify(plain("trace.http.request", "Latency"), RULES);
        assertThat(source.metadataCalls.get()).isEqualTo(1);

        clock.advance(Duration.ofHours(25));
        classifier.classify(plain("trace.http.request", "Latency"), RULES);
        assertThat(source.metadataCalls.get()).isEqualTo(2);
    }

    @Test
    void cacheDropsTheLeastRecentlyUsedEntryWhenFull() {
        ExpiringLruCache<String, Integer> cache = new ExpiringLruCache<>(2, 60_000, new MutableClock(Instant.ofEpochMilli(T0)));
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a");                                              // "a" is now more recent than "b"
        cache.put("c", 3);

        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("a")).contains(1);
        assertThat(cache.get("c")).contains(3);
        assertThat(cache.size()).isEqualTo(2);
    }
}
