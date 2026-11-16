package lab.anomaly.reporter.report;

import java.util.List;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.reporter.window.ReportWindow;

/**
 * Everything one report shows, collected before writing.
 *
 * @param incidents      incidents that started in the window, in their final state
 * @param previousCount  the previous window's number of incidents, or null when unknown
 * @param summaries      every line's numbers over the window (Store)
 * @param lines          every line's description (Kafka metric-series)
 */
public record ReportData(ReportWindow window, List<Incident> incidents, Integer previousCount, List<SeriesSummary> summaries,
                         List<SeriesDescriptor> lines, long stepMs) {
}
