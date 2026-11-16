package lab.anomaly.detector.ops;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.anomaly.detector.Detector;
import lab.anomaly.detector.incident.IncidentRepository;
import lab.anomaly.detector.run.DetectorEngine;
import lab.anomaly.kit.settings.SettingsHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health check "detector" (design D10). DEGRADED when an event has waited more than 30 s to be sent (announcing is
 * stuck) or a dashboard is not arriving.
 */
@Component("detector")
public class DetectorHealthIndicator implements HealthIndicator {

    private final DetectorEngine engine;
    private final DetectorStats stats;
    private final IncidentRepository repository;

    public DetectorHealthIndicator(DetectorEngine engine, DetectorStats stats, IncidentRepository repository) {
        this.engine = engine;
        this.stats = stats;
        this.repository = repository;
    }

    @Override
    public Health health() {
        Detector.Snapshot v = engine.snapshot();
        Map<String, Object> d = new LinkedHashMap<>();
        double waiting = 0;
        if (v != null) {
            d.put("linesWatched", v.lines());
            d.put("linesWarmingUp", v.warming());
            d.put("linesPerRule", v.linesPerRule());
            d.put("linesOnGeneralRuleByPanel", v.generalByPanel());
            d.put("linesGroupedByDashboard", v.groupedByDashboard());
            d.put("lineStates", v.states());
            d.put("dashboardsNotArriving", v.dashboardsNotArriving());
            d.put("openIncidents", v.openIncidents().stream().map(i -> Map.of("id", i.id(), "title", i.title(),
                    "started", Instant.ofEpochMilli(i.startedMs()).toString())).toList());
            d.put("valuesWithoutDescription", v.valuesWithoutDescription());
        }
        d.put("settingsVersion", engine.settingsVersion());
        d.put("kafkaLag", stats.lagByTopic);
        d.put("valuesJudged", stats.valuesJudged.get());
        d.put("messagesSetAside", stats.messagesSetAside.get());
        d.put("eventsSaved", stats.eventsSaved.get());
        d.put("eventsSent", stats.eventsSent.get());
        d.put("warmStarts", stats.warmCalls.get());
        d.put("warmStartFailures", stats.warmFailures.get());
        d.put("readToAnnounceMsP50", stats.announcePercentile(50));
        d.put("readToAnnounceMsP95", stats.announcePercentile(95));
        d.put("waitInKafkaMsP95", stats.readDelayPercentile(95));
        try {
            d.put("eventsWaiting", repository.unpublishedCount());
            waiting = repository.oldestUnpublishedSeconds();
            d.put("oldestWaitingSeconds", waiting);
        } catch (RuntimeException e) {
            d.put("database", e.getClass().getSimpleName());
        }
        if (!stats.lastError.isEmpty()) {
            d.put("lastError", stats.lastError);
        }
        List<String> why = new java.util.ArrayList<>();
        if (waiting > 30) {
            why.add("incident events have waited " + Math.round(waiting) + " s to be sent");
        }
        if (v != null && !v.dashboardsNotArriving().isEmpty()) {
            why.add("dashboards not arriving: " + String.join(", ", v.dashboardsNotArriving()));
        }
        if (!why.isEmpty()) {
            d.put("degradedBecause", String.join("; ", why));
            return Health.status(SettingsHealthIndicator.DEGRADED).withDetails(d).build();
        }
        return Health.up().withDetails(d).build();
    }
}
