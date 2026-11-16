package lab.anomaly.reporter.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.RemoveRawReply;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.reporter.report.Completion;
import lab.anomaly.reporter.report.IncidentReport;
import lab.anomaly.reporter.report.ReportData;
import lab.anomaly.reporter.source.Sources;
import lab.anomaly.reporter.window.ReportWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes one window through pending -> written -> verified -> cleaned (design R2). Each step is saved before the next
 * starts, so running again after a crash finishes the missing steps and never writes a second report.
 */
public final class ReportJob {

    private static final Logger log = LoggerFactory.getLogger(ReportJob.class);
    static final long STEP_MS = 10_000;

    private final Sources sources;
    private final ReportState state;
    private final Path dir;
    private final IncidentReport writer = new IncidentReport();

    public ReportJob(Sources sources, ReportState state, Path dir) {
        this.sources = sources;
        this.state = state;
        this.dir = dir;
    }

    /** Runs every step still missing for this window; returns the final step reached. */
    public ReportState.Step run(ReportWindow w, long keepBeforeEndMs, long nowMs) throws IOException {
        Path file = dir.resolve(w.fileName());
        ReportState.Step step = state.step(w.key());
        if (step == ReportState.Step.PENDING) {
            int incidents = write(w, file);
            state.put(w.key(), new ReportState.Entry(ReportState.Step.WRITTEN, incidents, 0, 0, nowMs));
            step = ReportState.Step.WRITTEN;
        }
        if (step == ReportState.Step.WRITTEN) {
            int incidents = Files.exists(file) ? Completion.verify(Files.readString(file, StandardCharsets.UTF_8)) : -1;
            if (incidents < 0) {
                log.warn("Report {} is missing or incomplete: written again", file);
                state.put(w.key(), new ReportState.Entry(ReportState.Step.PENDING, 0, 0, 0, nowMs));
                return run(w, keepBeforeEndMs, nowMs);
            }
            state.put(w.key(), new ReportState.Entry(ReportState.Step.VERIFIED, incidents, 0, 0, nowMs));
            step = ReportState.Step.VERIFIED;
        }
        if (step == ReportState.Step.VERIFIED) {
            long before = Math.min(w.endMs() - keepBeforeEndMs, nowMs);          // never data still arriving
            RemoveRawReply reply = sources.removeRawBefore(before);
            ReportState.Entry e = state.get(w.key());
            state.put(w.key(), new ReportState.Entry(ReportState.Step.CLEANED, e.incidents(), reply.getRemovedPoints(),
                    reply.getEffectiveBeforeMs(), nowMs));
            log.info("Window {} cleaned: {} raw points removed (asked {}, Store used {})", w.key(), reply.getRemovedPoints(),
                    java.time.Instant.ofEpochMilli(before), java.time.Instant.ofEpochMilli(reply.getEffectiveBeforeMs()));
            step = ReportState.Step.CLEANED;
        }
        return step;
    }

    private int write(ReportWindow w, Path file) throws IOException {
        List<Incident> incidents = sources.incidents().stream().map(IncidentEvent::getIncident)
                .filter(i -> i.getStartedMs() >= w.startMs() && i.getStartedMs() < w.endMs())
                .sorted(Comparator.comparingLong(Incident::getStartedMs)).toList();
        ReportState.Entry previous = state.get(w.previous().key());
        List<SeriesDescriptor> lines = List.copyOf(sources.lines());
        ReportData data = new ReportData(w, incidents, previous == null ? null : previous.incidents(),
                sources.summaries(w.startMs(), w.endMs()), lines, STEP_MS);
        Files.createDirectories(dir);
        AtomicFiles.write(file, writer.render(data));
        log.info("Report {} written: {} incidents", file, incidents.size());
        return incidents.size();
    }
}
