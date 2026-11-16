package lab.anomaly.collector.translate;

import java.util.List;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;

/**
 * One metric line from one answer, before kind/unit are known: the description without them, the points, and
 * what the classifier needs (whether it is a computed formula, and the single metric it shows, if any).
 */
public record TranslatedLine(SeriesDescriptor.Builder description, List<Point> points, boolean hasFormula,
                             String singleMetric, List<String> panelTitles, long collectedMs) {

    public TranslatedLine {
        points = List.copyOf(points);
        panelTitles = List.copyOf(panelTitles);
    }
}
