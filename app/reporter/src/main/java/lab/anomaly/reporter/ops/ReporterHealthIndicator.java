package lab.anomaly.reporter.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lab.anomaly.kit.settings.SettingsHealthIndicator;
import lab.anomaly.reporter.run.ReportScheduler;
import lab.anomaly.reporter.window.ReportWindow;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Health check "reporter" (design R9). DEGRADED when a window has waited more than an hour. */
@Component("reporter")
public class ReporterHealthIndicator implements HealthIndicator {

    private final ReportScheduler scheduler;

    public ReporterHealthIndicator(ReportScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Health health() {
        long now = System.currentTimeMillis();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("timeZone", scheduler.zone().getId());
        d.put("lastReport", scheduler.lastReport);
        List<ReportWindow> due = scheduler.due(now);
        d.put("windowsWaiting", scheduler.waiting(now));
        ReportWindow last = due.isEmpty() ? null : due.get(due.size() - 1);
        if (last != null && scheduler.entry(last.key()) != null) {
            var e = scheduler.entry(last.key());
            d.put("lastCleanUp", Map.of("window", last.key(), "removedPoints", e.removedPoints(),
                    "storeRemovedBefore", e.effectiveBeforeMs() == 0 ? "-" : Instant.ofEpochMilli(e.effectiveBeforeMs()).toString()));
        }
        d.put("reportFolder", scheduler.dir().toAbsolutePath().toString());
        d.put("reportFolderKB", sizeKb(scheduler.dir()));
        if (!scheduler.lastError.isEmpty()) {
            d.put("lastError", scheduler.lastError);
        }
        boolean late = due.stream().anyMatch(w -> scheduler.waiting(now).stream().anyMatch(x -> x.startsWith(w.key())) && now - w.endMs() > 3_600_000);
        if (late) {
            d.put("degradedBecause", "a report has waited more than an hour");
            return Health.status(SettingsHealthIndicator.DEGRADED).withDetails(d).build();
        }
        return Health.up().withDetails(d).build();
    }

    private static long sizeKb(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.mapToLong(p -> p.toFile().length()).sum() / 1024;
        } catch (IOException e) {
            return 0;
        }
    }
}
