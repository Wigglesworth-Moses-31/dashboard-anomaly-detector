package lab.anomaly.notifier.dash;

import java.util.ArrayList;
import java.util.List;
import lab.anomaly.contracts.v1.Point;

/**
 * Min-max thinning (add-dashboard-view D3): the range is cut into {@code width} columns and each column keeps its
 * lowest and highest point, in time order, never an average. A single 10-second spike therefore shows at any range.
 * One pass, O(points); at most 2 × width points come out.
 */
public final class Thinning {

    private Thinning() {}

    /** Points of one line (oldest first) in [from, to) → at most 2 × width [t, v] pairs. */
    public static List<double[]> minMax(List<Point> points, long fromMs, long toMs, int width) {
        List<double[]> out = new ArrayList<>(Math.min(points.size(), 2 * width));
        if (points.isEmpty() || toMs <= fromMs || width < 1) {
            return out;
        }
        if (points.size() <= 2L * width) {                       // nothing to thin
            for (Point p : points) {
                out.add(new double[] {p.getTsMs(), p.getValue()});
            }
            return out;
        }
        double span = toMs - fromMs;
        int column = -1;
        Point lo = null;
        Point hi = null;
        for (Point p : points) {
            int c = (int) Math.min(width - 1, Math.max(0, Math.floor((p.getTsMs() - fromMs) * width / span)));
            if (c != column) {
                flush(out, lo, hi);
                column = c;
                lo = p;
                hi = p;
            } else {
                if (p.getValue() < lo.getValue()) {
                    lo = p;
                }
                if (p.getValue() > hi.getValue()) {
                    hi = p;
                }
            }
        }
        flush(out, lo, hi);
        return out;
    }

    private static void flush(List<double[]> out, Point lo, Point hi) {
        if (lo == null) {
            return;
        }
        if (lo == hi) {
            out.add(new double[] {lo.getTsMs(), lo.getValue()});
        } else if (lo.getTsMs() <= hi.getTsMs()) {
            out.add(new double[] {lo.getTsMs(), lo.getValue()});
            out.add(new double[] {hi.getTsMs(), hi.getValue()});
        } else {
            out.add(new double[] {hi.getTsMs(), hi.getValue()});
            out.add(new double[] {lo.getTsMs(), lo.getValue()});
        }
    }
}
