package lab.anomaly.collector.run;

import java.util.LinkedHashMap;
import java.util.Map;
import lab.anomaly.kit.settings.SettingsHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health check "collector" (task 5.4): mode, units, lines, last successful poll, open breakers by panel, the
 * rate-limit state, and message counts. DEGRADED while any panel's breaker is open (it names the panel).
 */
@Component("collector")
public class CollectorHealthIndicator implements HealthIndicator {

    private final CollectorEngine engine;

    public CollectorHealthIndicator(CollectorEngine engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", engine.mode());
        details.put("pollUnits", engine.unitCount());
        details.put("metricLines", engine.lineCount());
        details.put("polls", engine.polls());
        details.put("failedPolls", engine.failedPolls());
        details.put("skippedTurns", engine.skippedTurns());
        Long ago = engine.lastSuccessAgoMs();
        details.put("lastSuccessfulPollSecondsAgo", ago == null ? null : ago / 1000.0);
        Fetcher fetcher = engine.fetcher();
        if (fetcher != null) {
            Map<String, Object> rate = new LinkedHashMap<>();
            rate.put("calls", fetcher.calls());
            rate.put("throttled", fetcher.throttledCount());
            rate.put("sourceLimit", fetcher.lastLimit());
            rate.put("pausedUntilMs", fetcher.gate().closedUntilMs());
            details.put("rateLimit", rate);
        }
        if (engine.publisher() != null) {
            details.put("messagesSent", engine.publisher().sentCount());
            details.put("messagesDropped", engine.publisher().droppedCount());
        }
        Map<String, String> open = engine.openBreakers();
        details.put("openBreakers", open);
        return (open.isEmpty() ? Health.up() : Health.status(SettingsHealthIndicator.DEGRADED)).withDetails(details).build();
    }
}
