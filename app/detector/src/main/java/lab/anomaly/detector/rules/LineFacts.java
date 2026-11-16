package lab.anomaly.detector.rules;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.View;

/** What rules and grouping look at for one metric line, taken from its description. */
public record LineFacts(String seriesId, String metric, String kind, String unit, String query,
                        Map<String, String> tags, List<View> views, long stepMs, String site, String queryId) {

    public static LineFacts of(SeriesDescriptor d) {
        String kind = d.getKind().name().replace("KIND_", "").toLowerCase(Locale.ROOT);
        long step = d.getStepSeconds() > 0 ? d.getStepSeconds() * 1000L : 10_000L;
        return new LineFacts(d.getSeriesId(), d.getMetric(), kind, d.getUnit(), d.getQueryText(),
                Map.copyOf(d.getGroupTagsMap()), List.copyOf(d.getViewsList()), step,
                d.getSource().getInstance(), d.getQueryId());
    }

    /** Link to a dashboard at a time window, for Datadog sources; empty otherwise. */
    public String dashboardUrl(String viewId, long fromMs, long toMs) {
        if (site == null || site.isBlank() || viewId.isBlank()) {
            return "";
        }
        String host = site.equals("datadoghq.com") ? "app.datadoghq.com" : site;
        return "https://" + host + "/dashboard/" + viewId + "?from_ts=" + fromMs + "&to_ts=" + toMs + "&live=false";
    }

    /** The first panel showing this line (for incidents and grouping by dashboard). */
    public View primaryView() {
        return views.isEmpty() ? View.getDefaultInstance() : views.get(0);
    }
}
