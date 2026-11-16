package lab.anomaly.reporter.run;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.kit.settings.SharedSettings;
import lab.anomaly.reporter.ReporterSettings;
import lab.anomaly.reporter.source.Sources;
import lab.anomaly.reporter.window.ReportWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Once a minute (design R1): every window that ended (plus grace) within the catch-up horizon and is not cleaned yet
 * is taken through its steps, oldest first; then old report files are removed (design R7). Also runs windows of a
 * chosen day on request. One thread does all of it, so two runs never overlap.
 */
@Component
public class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    private final SettingsHolder<ReporterSettings> settings;
    private final SettingsHolder<SharedSettings> shared;
    private final Sources sources;
    private volatile Thread thread;
    private ReportState state;
    private Path stateDir;
    public volatile String lastError = "";
    public volatile String lastReport = "";
    public volatile long lastCheckMs;

    public ReportScheduler(SettingsHolder<ReporterSettings> settings, SettingsHolder<SharedSettings> shared, Sources sources) {
        this.settings = settings;
        this.shared = shared;
        this.sources = sources;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        thread = Thread.ofVirtual().name("reporter").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                runDue(System.currentTimeMillis());
                try {
                    Thread.sleep(Duration.ofMinutes(1));
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    public ZoneId zone() {
        return ZoneId.of(shared.get().timeZone());
    }

    public Path dir() {
        return Path.of(settings.get().outputDir());
    }

    synchronized ReportState state() {
        Path dir = dir();
        if (state == null || !dir.equals(stateDir)) {
            state = new ReportState(dir);
            stateDir = dir;
        }
        return state;
    }

    /** The windows that should have a report by now, oldest first. */
    public List<ReportWindow> due(long nowMs) {
        ReporterSettings s = settings.get();
        return ReportWindow.due(nowMs, s.window(), s.grace(), s.catchUp(), zone());
    }

    public synchronized void runDue(long nowMs) {
        lastCheckMs = nowMs;
        for (ReportWindow w : due(nowMs)) {
            if (state().step(w.key()) != ReportState.Step.CLEANED) {
                runOne(w, nowMs);
            }
        }
        rotate();
    }

    /** On request: every ended window of that day. Returns the file names. */
    public synchronized List<String> runDay(LocalDate day, long nowMs) {
        List<String> files = new ArrayList<>();
        for (ReportWindow w : ReportWindow.ofDay(day, settings.get().window(), zone())) {
            if (w.endMs() <= nowMs) {
                runOne(w, nowMs);
                files.add(w.fileName());
            }
        }
        rotate();
        return files;
    }

    private void runOne(ReportWindow w, long nowMs) {
        try {
            var step = new ReportJob(sources, state(), dir()).run(w, settings.get().keepBeforeEnd().toMillis(), nowMs);
            lastReport = w.fileName() + " (" + step.name().toLowerCase() + ")";
            lastError = "";
        } catch (IOException | RuntimeException e) {
            lastError = w.key() + ": " + e.getMessage();
            log.error("Report {} failed; nothing removed; tried again in a minute: {}", w.key(), e.toString());
        }
    }

    /** Keeps the newest {@code keep-reports} files (names sort by time). */
    private void rotate() {
        try (Stream<Path> files = Files.list(dir())) {
            List<Path> reports = files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}(_\\d{2}-\\d{2})?\\.md")).sorted().toList();
            int extra = reports.size() - settings.get().keepReports();
            for (int i = 0; i < extra; i++) {
                Files.deleteIfExists(reports.get(i));
                log.info("Old report removed: {}", reports.get(i).getFileName());
            }
        } catch (IOException e) {
            // no folder yet
        }
    }

    public List<String> waiting(long nowMs) {
        List<String> out = new ArrayList<>();
        for (ReportWindow w : due(nowMs)) {
            if (state().step(w.key()) != ReportState.Step.CLEANED) {
                out.add(w.key() + " (" + state().step(w.key()).name().toLowerCase() + ", due " + Duration.ofMillis(nowMs - w.endMs()).toMinutes() + " min ago)");
            }
        }
        return out;
    }

    public ReportState.Entry entry(String key) {
        return state().get(key);
    }

    public java.util.Map<String, ReportState.Entry> all() {
        return state().all();
    }
}
