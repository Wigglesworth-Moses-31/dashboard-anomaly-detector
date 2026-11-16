package lab.anomaly.detector.ops;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import lab.anomaly.detector.DetectorSettings;
import lab.anomaly.detector.incident.IncidentRepository;
import lab.anomaly.kit.settings.SettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Design D9: every hour, remove closed incidents past the retention and sent events older than a day. */
@Component
public class IncidentHousekeeping {

    private static final Logger log = LoggerFactory.getLogger(IncidentHousekeeping.class);
    private final IncidentRepository repository;
    private final SettingsHolder<DetectorSettings> settings;
    private volatile Thread thread;

    public IncidentHousekeeping(IncidentRepository repository, SettingsHolder<DetectorSettings> settings) {
        this.repository = repository;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        thread = Thread.ofVirtual().name("incident-housekeeping").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int[] removed = repository.removeOld(settings.get().incidentRetention());
                    if (removed[0] + removed[1] > 0) {
                        log.info("Removed {} closed incidents and {} sent events", removed[0], removed[1]);
                    }
                    Thread.sleep(Duration.ofHours(1));
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.warn("Incident clean-up failed: {}", e.toString());
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException ie) {
                        return;
                    }
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
}
