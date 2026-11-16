package lab.anomaly.collector.source;

import java.util.List;
import lab.anomaly.collector.source.Dashboard.Query;

/** One request for numbers: the questions, the formulas to compute, a time window and the step length. */
public record TimeseriesRequest(List<Query> queries, List<String> formulas, long fromMs, long toMs, long intervalMs) {

    public TimeseriesRequest {
        queries = List.copyOf(queries);
        formulas = List.copyOf(formulas);
    }

    /** The questions and formulas only, without the window: the same for every poll of one panel. */
    public String canonicalQuestion() {
        StringBuilder text = new StringBuilder();
        for (Query query : queries) {
            text.append(query.name()).append('=').append(query.text()).append('\n');
        }
        text.append("formulas=").append(String.join(";", formulas));
        return text.toString();
    }
}
