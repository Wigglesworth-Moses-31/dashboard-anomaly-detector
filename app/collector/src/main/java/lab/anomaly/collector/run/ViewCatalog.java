package lab.anomaly.collector.run;

import java.util.List;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.plan.WatchedView;

/**
 * Where a lane's views come from (add-dashboard-json design J3): the accepted dashboard files when running, the
 * dashboards a source returns by id in tests and the older id path. Strategy pattern.
 */
@FunctionalInterface
public interface ViewCatalog {
    List<WatchedView> views(Lane lane, CollectorSettings settings);
}
