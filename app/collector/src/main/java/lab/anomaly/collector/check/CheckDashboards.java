package lab.anomaly.collector.check;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.CollectorSettings.LaneConfig;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.dashboards.DashboardFiles;
import lab.anomaly.collector.plan.PollPlan;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.plan.WatchedView;
import lab.anomaly.collector.run.Batch;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.datadog.DatadogSource;
import lab.anomaly.kit.settings.SettingsValidator;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The check before running (add-dashboard-json design J6): asks every panel of every watched view once, read-only,
 * over the last 15 minutes, with the Collector's own reader, filling, planning and batching, and prints per view and
 * panel how many lines came back and the question that was asked. Reads {@code collector.yml} and the dashboard files
 * from the config-repo <b>working tree</b>, so changes can be checked before they are committed.
 *
 * <p>Usage (through scripts/check-dashboards.sh): {@code CheckDashboards <config-repo> [source] [--at <ISO time>]};
 * {@code --at} checks the 15 minutes before that time instead of now (for example the moment of an incident). Exit code 1 when a
 * view returns no lines at all, 2 when the settings or files are refused.
 */
public final class CheckDashboards {

    private static final long WINDOW_MS = 15 * 60_000;
    private static final long PAUSE_MS = 500;                         // 2 calls per second: never competes with a running Collector

    public static void main(String[] args) throws Exception {
        quietLogs();
        if (args.length < 1) {
            System.err.println("usage: CheckDashboards <config-repo> [source] [--at 2026-11-15T08:10:00Z]");
            System.exit(2);
        }
        String only = null;
        long at = System.currentTimeMillis();
        for (int i = 1; i < args.length; i++) {
            if ("--at".equals(args[i]) && i + 1 < args.length) {
                at = java.time.Instant.parse(args[++i]).toEpochMilli();
            } else {
                only = args[i];
            }
        }
        Path repo = Path.of(args[0]);
        CollectorSettings settings = read(repo);
        List<String> problems = new ArrayList<>();
        for (SettingsValidator<? super CollectorSettings> v : validators()) {
            v.check(settings).ifPresent(problems::add);
        }
        DashboardFiles files = new DashboardFiles(path -> {
            Path file = repo.resolve(path).normalize();
            return file.startsWith(repo.normalize()) && Files.isRegularFile(file) ? Files.readString(file) : null;
        });
        files.check(settings).ifPresent(problems::add);
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.out.println("✗ " + p));
            System.exit(2);
        }
        int code = run(settings, files, lane -> new DatadogSource(lane.site(),
                DatadogCredentials.fromEnvironment(new StandardEnvironment(), lane.apiKeyEnv(), lane.appKeyEnv(), lane.name()),
                java.time.Clock.systemUTC()), only, System.out, PAUSE_MS, at);
        System.exit(code);
    }

    /** The check itself, for any source (tests give a stub). Returns 1 when a view returns no lines at all. */
    public static int run(CollectorSettings settings, DashboardFiles files, Function<LaneConfig, MetricSource> sources,
                          String only, PrintStream out, long pauseMs) throws InterruptedException {
        return run(settings, files, sources, only, out, pauseMs, System.currentTimeMillis());
    }

    /** As {@link #run(CollectorSettings, DashboardFiles, Function, String, PrintStream, long)}, for the 15 minutes before {@code toMs}. */
    public static int run(CollectorSettings settings, DashboardFiles files, Function<LaneConfig, MetricSource> sources,
                          String only, PrintStream out, long pauseMs, long toMs) throws InterruptedException {
        boolean allEmptyView = false;
        for (LaneConfig lane : settings.lanes()) {
            if (only != null && !only.equals(lane.name())) {
                continue;
            }
            List<WatchedView> views = files.views(settings, lane.name());
            List<PollUnit> units = PollPlan.buildViews(lane.name(), views, settings.step());
            MetricSource source = sources.apply(lane);
            Map<String, Integer> lines = new LinkedHashMap<>();            // unit id → lines, or -1 for an error
            Map<String, String> errors = new LinkedHashMap<>();
            int calls = askAll(source, units, settings, toMs, pauseMs, lines, errors);
            out.printf("%n== %s (%s): %d views, %d questions, %d Datadog calls%n", lane.name(), lane.site(), views.size(), units.size(), calls);
            for (WatchedView view : views) {
                allEmptyView |= printView(out, view, units, lines, errors);
            }
        }
        out.println();
        out.println(allEmptyView ? "Some views return no lines at all: check their filters (see above)." : "Every view returned lines.");
        return allEmptyView ? 1 : 0;
    }

    /**
     * Asks every unit once over the 15 minutes before {@code toMs}, batched like a poll; fills unit id → lines (-1 for
     * an error) and the errors; returns the number of Datadog calls. Also used by the Settings tab's check.
     */
    public static int askAll(MetricSource source, List<PollUnit> units, CollectorSettings settings, long toMs, long pauseMs,
                             Map<String, Integer> lines, Map<String, String> errors) throws InterruptedException {
        long step = settings.step().toMillis();
        long from = ((toMs - WINDOW_MS) / step) * step;
        int calls = 0;
        for (Batch batch : Batch.pack(units, settings.effectiveBatchFormulas())) {
            calls += ask(source, batch, from, toMs, step, lines, errors, pauseMs);
        }
        return calls;
    }

    /** One call for the batch; when Datadog refuses it, the halves are asked again until the bad question is alone. */
    private static int ask(MetricSource source, Batch batch, long from, long to, long step, Map<String, Integer> lines,
                           Map<String, String> errors, long pauseMs) throws InterruptedException {
        Thread.sleep(pauseMs);
        try {
            TimeseriesAnswer answer = source.timeseries(batch.request(from, to, step));
            List<TimeseriesAnswer> split = batch.split(answer);
            for (int k = 0; k < batch.size(); k++) {
                lines.put(batch.units().get(k).id(), split.get(k).series().size());
            }
            return 1;
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.BAD_REQUEST && batch.size() > 1) {
                int calls = 1;
                for (Batch half : batch.halves()) {
                    calls += ask(source, half, from, to, step, lines, errors, pauseMs);
                }
                return calls;
            }
            for (PollUnit u : batch.units()) {
                lines.put(u.id(), -1);
                errors.put(u.id(), e.getMessage());
            }
            return 1;
        }
    }

    /** Prints a view's panels in dashboard order; true when none of them returned a line. */
    private static boolean printView(PrintStream out, WatchedView view, List<PollUnit> units, Map<String, Integer> lines,
                                     Map<String, String> errors) {
        out.printf("%n%s   filters: %s%n", view.viewTitle(), view.filtersText());
        int withLines = 0;
        int panels = 0;
        for (PollUnit unit : units) {
            Optional<PollUnit.View> v = unit.views().stream().filter(x -> x.dashboardId().equals(view.viewId())).findFirst();
            if (v.isEmpty()) {
                continue;
            }
            panels++;
            int n = lines.getOrDefault(unit.id(), -1);
            String question = unit.queries().get(0).text();
            if (n > 0) {
                withLines++;
                out.printf("  ✓ %-45s %4d lines   %s%n", cut(v.get().panelLabel(), 45), n, question);
            } else if (n == 0) {
                out.printf("  ✗ %-45s    0 lines   %s%n", cut(v.get().panelLabel(), 45), question);
            } else {
                out.printf("  ! %-45s  error: %s   %s%n", cut(v.get().panelLabel(), 45), errors.getOrDefault(unit.id(), "?"), question);
            }
        }
        out.printf("  %d of %d panels returned lines%n", withLines, panels);
        return panels > 0 && withLines == 0;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** The Collector's settings from a settings repository, merged as the Config Service does: regions.yml wins. */
    public static CollectorSettings read(Path repo) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader yaml = new YamlPropertySourceLoader();
        for (String name : List.of("regions.yml", "collector.yml", "application.yml")) {   // first wins, as in the Config Service
            Path f = repo.resolve(name);
            if (Files.isRegularFile(f)) {
                yaml.load(name, new FileSystemResource(f)).forEach(env.getPropertySources()::addLast);
            }
        }
        return Binder.get(env).bind("anomaly.collector", Bindable.of(CollectorSettings.class))
                .orElseThrow(() -> new IllegalStateException("no anomaly.collector settings in " + repo));
    }

    @SuppressWarnings("unchecked")
    private static List<SettingsValidator<? super CollectorSettings>> validators() throws Exception {
        var m = CollectorSettings.class.getDeclaredMethod("validators");
        m.setAccessible(true);
        return (List<SettingsValidator<? super CollectorSettings>>) m.invoke(null);
    }

    private static void quietLogs() {
        org.slf4j.Logger root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logback) {
            logback.setLevel(ch.qos.logback.classic.Level.WARN);
        }
    }

    private CheckDashboards() {
    }
}
