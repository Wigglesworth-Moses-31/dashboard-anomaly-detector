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

    /** Datadog-like auto intervals (add-datadog-look K8): the smallest giving at most 300 points over the range. */
    static final long[] INTERVALS_MS = {10_000, 20_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000, 3_600_000};

    public static long autoInterval(Duration range) {
        for (long i : INTERVALS_MS) {
            if (range.toMillis() / i <= 300) {
                return i;
            }
        }
        return INTERVALS_MS[INTERVALS_MS.length - 1];
    }

    /** Datadog's default rollup for a query: its own {@code .rollup(method)}, else sum for {@code .as_count()}, else avg. */
    static lab.anomaly.contracts.v1.Rollup method(String query) {
        int where = query == null ? -1 : query.indexOf("  where  ");
        if (where >= 0) {
            return formulaMethod(query.substring(0, where), query.substring(where + 9));
        }
        String q = query == null ? "" : query.replaceAll("\\.rollup\\(max, *10\\)\\s*$", "");   // our own step rollup
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.rollup\\(\\s*(avg|sum|min|max)").matcher(q);
        String found = null;
        while (m.find()) {
            found = m.group(1);
        }
        if (found != null) {
            return switch (found) {
                case "sum" -> lab.anomaly.contracts.v1.Rollup.ROLLUP_SUM;
                case "min" -> lab.anomaly.contracts.v1.Rollup.ROLLUP_MIN;
                case "max" -> lab.anomaly.contracts.v1.Rollup.ROLLUP_MAX;
                default -> lab.anomaly.contracts.v1.Rollup.ROLLUP_AVG;
            };
        }
        return q.contains(".as_count()") ? lab.anomaly.contracts.v1.Rollup.ROLLUP_SUM : lab.anomaly.contracts.v1.Rollup.ROLLUP_AVG;
    }

    /**
     * A formula line (fix-backfill-faults F5): its values are the formula's results, so a ratio or percentage is
     * averaged per graph step; only a plain sum or difference of queries that are themselves summed is summed.
     */
    static lab.anomaly.contracts.v1.Rollup formulaMethod(String formula, String queries) {
        boolean plain = formula.matches("[\\sA-Za-z0-9_.+\\-()]*") && !formula.matches(".*[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*");
        boolean allSums = !queries.isBlank();
        for (String part : queries.split(";")) {
            int eq = part.indexOf('=');
            allSums &= eq > 0 && method(part.substring(eq + 1).strip()) == lab.anomaly.contracts.v1.Rollup.ROLLUP_SUM;
        }
        return plain && allSums ? lab.anomaly.contracts.v1.Rollup.ROLLUP_SUM : lab.anomaly.contracts.v1.Rollup.ROLLUP_AVG;
    }

    /**
     * Every line rolled up to the range's auto interval with its own method, computed by the Store: one call per method
     * used (usually one or two). O(points returned).
     */
    public List<Series> rolled(List<String> ids, Duration range, long toMs, long intervalMs) {
        long from = toMs - range.toMillis();
        long start = ((from + intervalMs - 1) / intervalMs) * intervalMs;   // rounded up: never longer than the range
        Map<lab.anomaly.contracts.v1.Rollup, List<String>> byMethod = new java.util.EnumMap<>(lab.anomaly.contracts.v1.Rollup.class);
        for (String id : ids) {
            Catalogue.Line l = catalogue.line(id);
            byMethod.computeIfAbsent(method(l == null ? "" : l.query()), k -> new java.util.ArrayList<>()).add(id);
        }
        Map<String, List<Point>> points = new java.util.HashMap<>();
        byMethod.forEach((m, list) -> points.putAll(store.history(list, start, toMs, intervalMs, m)));
        return ids.stream().map(id -> new Series(id, points.getOrDefault(id, List.of()).stream()
                .map(p -> new double[] {p.getTsMs(), p.getValue()}).toList())).toList();
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
