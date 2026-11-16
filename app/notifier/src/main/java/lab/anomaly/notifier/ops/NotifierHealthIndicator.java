package lab.anomaly.notifier.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import lab.anomaly.kit.settings.SettingsHealthIndicator;
import lab.anomaly.notifier.live.Hub;
import lab.anomaly.notifier.live.NotifierEngine;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Health check "notifier" (design N8). DEGRADED until the first read of the topic has finished. */
@Component("notifier")
public class NotifierHealthIndicator implements HealthIndicator {

    private final NotifierEngine engine;

    public NotifierHealthIndicator(NotifierEngine engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        Hub hub = engine.hub();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("viewers", hub.viewers());
        d.put("day", hub.day());
        d.put("timeZone", hub.zone());
        d.put("incidentsToday", hub.incidents());
        d.put("open", hub.open());
        d.put("changesAccepted", hub.accepted.get());
        d.put("repeatsOrLateDropped", hub.dropped.get());
        d.put("updatesMergedForSlowViewers", hub.merged.get());
        d.put("eventsSetAside", engine.setAside.get());
        d.put("largestViewerTray", hub.maxWaiting());
        long last = hub.lastChangeMs();
        d.put("secondsSinceLastChange", last == 0 ? null : (System.currentTimeMillis() - last) / 1000);
        d.put("caughtUp", engine.caughtUp());
        if (!engine.caughtUp()) {
            d.put("degradedBecause", "still reading incident-events from the beginning");
            return Health.status(SettingsHealthIndicator.DEGRADED).withDetails(d).build();
        }
        return Health.up().withDetails(d).build();
    }
}
