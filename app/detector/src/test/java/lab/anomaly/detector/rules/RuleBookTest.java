package lab.anomaly.detector.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.detector.Fixtures;
import org.junit.jupiter.api.Test;

/** Task 3.1: every recorded line gets the intended rule from the real default settings. */
class RuleBookTest {

    private final RuleBook book = new RuleBook(Fixtures.defaults(), 1);

    /** What a person would say each recorded line is, from its panel and tags (written independently of the rules). */
    static String expected(SeriesDescriptor d) {
        String panel = d.getViews(0).getPanelTitle().toLowerCase();
        String code = d.getGroupTagsMap().getOrDefault("status_code",
                d.getGroupTagsMap().getOrDefault("http.status_code", d.getGroupTagsMap().get("rpc.grpc.status_code")));
        if (panel.contains("success rate") || d.getQueryText().contains("1 - errs")) {
            return "success-rate";
        }
        if (panel.contains("error (5xx) rate")) {
            return "error-rate";
        }
        if (code != null) {
            boolean grpc = d.getGroupTagsMap().containsKey("rpc.grpc.status_code");
            boolean error = grpc ? !code.equals("0") : code.startsWith("4") || code.startsWith("5");
            return error ? "errors" : "traffic";
        }
        if (panel.contains("latenc") || panel.contains("slowest") || d.getMetric().equals("trace.grpc.client")) {
            return "latency";
        }
        if (panel.contains("cpu")) {
            return "saturation";
        }
        return "traffic";
    }

    @Test
    void everyRecordedLineGetsTheIntendedRuleAndNoneIsLeftToTheGeneralRule() {
        List<SeriesDescriptor> lines = Fixtures.recordedLines();
        assertThat(lines).hasSize(226);
        Map<String, Integer> perSignal = new TreeMap<>();
        for (SeriesDescriptor d : lines) {
            Rule rule = book.ruleFor(LineFacts.of(d));
            assertThat(rule.signal()).as("%s / %s %s", d.getViews(0).getPanelTitle(), d.getMetric(), d.getGroupTagsMap())
                    .isEqualTo(expected(d));
            perSignal.merge(rule.signal(), 1, Integer::sum);
        }
        System.out.println("RULES-226 " + perSignal);
        assertThat(perSignal).doesNotContainKey("general");
    }

    private Rule ruleOf(String metric, Kind kind, String unit, Map<String, String> tags, String panel) {
        return book.ruleFor(LineFacts.of(Fixtures.line("ffff000000000001", metric, kind, unit, tags, "d1", "Dash", panel)));
    }

    @Test
    void titleInDifferentCaseIsStillASuccessRate() {
        assertThat(ruleOf("trace.grpc.server.hits", Kind.KIND_UNKNOWN, "", Map.of("service", "cart"), "Success rate % per service").signal())
                .isEqualTo("success-rate");
    }

    @Test
    void aFiveHundredCountIsErrorsAndATwoHundredCountIsTraffic() {
        assertThat(ruleOf("lab.api.request.count", Kind.KIND_COUNT, "requests", Map.of("api", "/v1/invoices", "status_code", "500"), "Count").signal())
                .isEqualTo("errors");
        assertThat(ruleOf("lab.api.request.count", Kind.KIND_COUNT, "requests", Map.of("api", "/v1/invoices", "status_code", "200"), "Count").signal())
                .isEqualTo("traffic");
    }

    @Test
    void anUnknownMetricGetsTheGeneralRule() {
        Rule r = ruleOf("lab.queue.depth", Kind.KIND_GAUGE, "", Map.of(), "Queue depth");
        assertThat(r.isGeneral()).isTrue();
        assertThat(r.measure()).isInstanceOf(Measure.Wobble.class);
    }
}
