package lab.anomaly.notifier.dash;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.notifier.NotifierSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The dashboard pages' data (add-dashboard-view D2, D3): the catalogue, rebuilt from the Store every minute and swapped
 * in one reference, and ranges thinned for the browser.
 */
@Service
public class DashService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DashService.class);

    /** One line's points as sent to a page. */
    public record Series(String id, List<double[]> points) {}

    private final SettingsHolder<NotifierSettings> settings;
    private final StoreClient client;
    private final StoreClient.Store store;
    private final ScheduledExecutorService refresher;
    private volatile Catalogue catalogue = Catalogue.EMPTY;
    private volatile String catalogueProblem = "not read yet";

    @Autowired
    DashService(SettingsHolder<NotifierSettings> settings) {
        this.settings = settings;
        this.client = new StoreClient();
        this.store = client.store(() -> settings.get().dashboards().storeAddress());
        this.refresher = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("dash-catalogue").factory());
        refresher.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.SECONDS);
    }

    /** For tests: a fixed store, no background refresh. */
    public DashService(SettingsHolder<NotifierSettings> settings, StoreClient.Store store) {
        this.settings = settings;
        this.client = null;
        this.store = store;
        this.refresher = null;
        refreshNow();
    }

    private volatile long nextRefreshMs;

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now < nextRefreshMs) {
            return;
        }
        refreshNow();
        nextRefreshMs = now + (catalogueProblem == null ? settings.get().dashboards().catalogueRefresh().toMillis() : 10_000);
    }

    /** Rebuilds the catalogue now (also when Settings changed a dashboard). */
    public void refreshNow() {
        try {
            Catalogue c = Catalogue.of(store.listSeries());
            catalogue = c;
            if (catalogueProblem != null) {
                log.info("Dashboard catalogue: {} lines in {} sites", c.lineCount(), c.sites().size());
            }
            catalogueProblem = null;
        } catch (RuntimeException e) {
            if (catalogueProblem == null) {
                log.warn("Dashboard catalogue not read from the Store: {}", e.toString());
            }
            catalogueProblem = "the Store cannot be reached";
        }
    }

    public Catalogue catalogue() {
        return catalogue;
    }

    public String catalogueProblem() {
        return catalogueProblem;
    }

    public NotifierSettings.Dashboards settings() {
        return settings.get().dashboards();
    }

    /** One Store call for the lines, then min-max thinning per line. Up to 1 hour: raw values; longer: per minute. */
    public List<Series> range(List<String> ids, Duration range, long toMs, int width) {
        long from = toMs - range.toMillis();
        boolean raw = range.compareTo(Duration.ofHours(1)) <= 0;
        int w = Math.max(50, Math.min(width, settings().pointsPerLine() / 2));
        Map<String, List<Point>> points = store.history(ids, from, toMs, raw);
        return ids.stream().map(id -> new Series(id, Thinning.minMax(points.getOrDefault(id, List.of()), from, toMs, w))).toList();
    }

    public long storeCalls() {
        return client == null ? 0 : client.calls();
    }

    public long slowestStoreCallMs() {
        return client == null ? 0 : client.slowestMs();
    }

    @Override
    public void destroy() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
        if (client != null) {
            client.close();
        }
    }
}
