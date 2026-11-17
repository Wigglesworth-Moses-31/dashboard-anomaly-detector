package lab.anomaly.collector.dashboards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.CollectorSettings.DashboardRef;
import lab.anomaly.collector.CollectorSettings.LaneConfig;
import lab.anomaly.collector.plan.WatchedView;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.datadog.DatadogDashboardReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard JSON files of the settings (add-dashboard-json design J2, J3).
 *
 * <ul>
 *   <li>Files are read through {@link Reader} (the Config Service over HTTP when running; the config-repo working
 *       tree in the check), all of them in parallel on virtual threads.</li>
 *   <li>A cache keyed by (path, SHA-256 of the content) keeps parsed dashboards (Cache-aside): an unchanged file is
 *       never parsed again; files no longer listed are evicted.</li>
 *   <li>{@link #check(CollectorSettings)} is a settings validator: it loads every file of a candidate and turns every
 *       problem into a message, so the YAML and the files are accepted together or not at all. The views of an
 *       accepted candidate are remembered for exactly that settings object (identity), so the engine never reads a
 *       file twice or sees a newer file than the one that was checked.</li>
 * </ul>
 */
public final class DashboardFiles {

    /** Reads a file of the settings repository by its relative path. */
    @FunctionalInterface
    public interface Reader {
        String read(String path) throws Exception;
    }

    private record Parsed(String sha, Dashboard dashboard) {}

    private final Reader reader;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Parsed> cache = new ConcurrentHashMap<>();
    private final Map<CollectorSettings, Map<String, List<WatchedView>>> accepted =
            Collections.synchronizedMap(new WeakHashMap<>());

    public DashboardFiles(Reader reader) {
        this.reader = reader;
    }

    /** Validator: empty when every file of every source loads and fits its reference; else the first problem. */
    public Optional<String> check(CollectorSettings settings) {
        if ("replay".equals(settings.source())) {
            return Optional.empty();                                  // replay plays the recorded dashboards (J7)
        }
        try {
            accepted.put(settings, load(settings));
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of("dashboard files: " + e.getMessage());
        }
    }

    /** The views of each source of accepted settings, by source name; loads them if this object was never checked. */
    public Map<String, List<WatchedView>> views(CollectorSettings settings) {
        Map<String, List<WatchedView>> views = accepted.get(settings);
        return views != null ? views : load(settings);
    }

    /** Views of one source (empty when it has none). */
    public List<WatchedView> views(CollectorSettings settings, String source) {
        return views(settings).getOrDefault(source, List.of());
    }

    /** Loads every referenced file in parallel, then expands views. O(changed bytes) to parse; O(views) to expand. */
    public Map<String, List<WatchedView>> load(CollectorSettings settings) {
        Map<String, DashboardRef> unique = new LinkedHashMap<>();
        for (LaneConfig lane : settings.lanes()) {
            for (DashboardRef ref : lane.dashboards()) {
                if (ref.idOnly()) {
                    throw new IllegalArgumentException(lane.name() + ": '" + ref.id() + "' is an id, not a file");
                }
                unique.putIfAbsent(ref.file().strip(), ref);
            }
        }
        Map<String, Dashboard> dashboards = parseAll(List.copyOf(unique.keySet()));
        cache.keySet().retainAll(unique.keySet());                    // evict files no longer listed
        Map<String, List<WatchedView>> out = new LinkedHashMap<>();
        for (LaneConfig lane : settings.lanes()) {
            List<WatchedView> views = new ArrayList<>();
            for (DashboardRef ref : lane.dashboards()) {
                views.addAll(WatchedView.of(dashboards.get(ref.file().strip()), ref));
            }
            out.put(lane.name(), List.copyOf(views));
        }
        return out;
    }

    private Map<String, Dashboard> parseAll(List<String> files) {
        Map<String, Future<Dashboard>> pending = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String file : files) {
                pending.put(file, pool.submit(() -> parse(file)));
            }
            Map<String, Dashboard> out = new LinkedHashMap<>();
            for (Map.Entry<String, Future<Dashboard>> e : pending.entrySet()) {
                try {
                    out.put(e.getKey(), e.getValue().get(10, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    throw cause instanceof IllegalArgumentException iae ? iae
                            : new IllegalArgumentException(e.getKey() + ": cannot be read (" + cause + ")");
                } catch (java.util.concurrent.TimeoutException | InterruptedException ex) {
                    throw new IllegalArgumentException(e.getKey() + ": not read within 10 s");
                }
            }
            return out;
        }
    }

    private Dashboard parse(String file) throws Exception {
        String text = reader.read(file);
        if (text == null) {
            throw new IllegalArgumentException(file + ": file not found in the settings repository");
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        Parsed cached = cache.get(file);
        if (cached != null && cached.sha().equals(sha)) {
            return cached.dashboard();
        }
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(file + ": not valid JSON (" + firstLine(e.getMessage()) + ")");
        }
        if (root == null || !root.isObject() || !root.path("widgets").isArray() || root.path("widgets").isEmpty()) {
            throw new IllegalArgumentException(file + ": no widgets; is this an exported dashboard JSON?");
        }
        Dashboard dashboard = DatadogDashboardReader.read(root);
        cache.put(file, new Parsed(sha, dashboard));
        parses.incrementAndGet();
        return dashboard;
    }

    private final java.util.concurrent.atomic.AtomicLong parses = new java.util.concurrent.atomic.AtomicLong();

    /** How many times a file was actually parsed (for tests and health: unchanged files are not). */
    public long parses() {
        return parses.get();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
