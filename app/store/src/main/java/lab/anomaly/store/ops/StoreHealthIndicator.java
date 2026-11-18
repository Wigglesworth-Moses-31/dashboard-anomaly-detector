package lab.anomaly.store.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import lab.anomaly.kit.settings.SettingsHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health check "store" (add-store design S6). DEGRADED when the size alarm is on, the last save failed, or the
 * Kafka lag has kept growing for a minute.
 */
@Component("store")
public class StoreHealthIndicator implements HealthIndicator {

    private final StoreStats stats;
    private final StorageHousekeeping housekeeping;

    public StoreHealthIndicator(StoreStats stats, StorageHousekeeping housekeeping) {
        this.stats = stats;
        this.housekeeping = housekeeping;
    }

    @Override
    public Health health() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kafkaLag", Map.copyOf(stats.lagByTopic));
        d.put("lastSaveSecondsAgo", stats.lastSaveMs == 0 ? null : (System.currentTimeMillis() - stats.lastSaveMs) / 1000.0);
        d.put("batches", stats.batches.get());
        d.put("pointsReceived", stats.pointsReceived.get());
        d.put("pointsSaved", stats.pointsSaved.get());
        d.put("descriptionsSaved", stats.descriptionsSaved.get());
        d.put("messagesSetAside", stats.setAside.get());
        d.put("failedBatches", stats.failedBatches.get());
        d.put("saveDelayMsP50", stats.delayPercentile(50));
        d.put("saveDelayMsP95", stats.delayPercentile(95));
        d.put("databaseMB", housekeeping.totalBytes() / 1_048_576.0);
        Map<String, String> tables = new LinkedHashMap<>();
        housekeeping.tableBytes().forEach((t, b) -> tables.put(t, String.format("%.1f MB", b / 1_048_576.0)));
        java.util.Map<String, String> compressed = new java.util.LinkedHashMap<>();       // keep-ten-days T1
        housekeeping.compression().forEach((t, b) -> compressed.put(t, b[0] == 0 ? "nothing compressed yet"
                : String.format("%.1f MB → %.1f MB (%.1f×)", b[0] / 1_048_576.0, b[1] / 1_048_576.0, b[0] / (double) Math.max(1, b[1]))));
        d.put("tables", tables);
        d.put("compressed", compressed);

        StringBuilder why = new StringBuilder();
        if (housekeeping.overLimit()) {
            why.append("database is ").append(String.format("%.1f MB", housekeeping.totalBytes() / 1_048_576.0))
                    .append(", over the limit; largest table ").append(housekeeping.largestTable()).append(". ");
        }
        if (stats.lastSaveFailed) {
            why.append("last save failed: ").append(stats.lastError).append(". ");
        }
        if (stats.lagGrowingSinceMs > 0 && System.currentTimeMillis() - stats.lagGrowingSinceMs > 60_000) {
            why.append("falling behind Kafka for over a minute (").append(stats.totalLag()).append(" waiting). ");
        }
        if (!why.isEmpty()) {
            d.put("degradedBecause", why.toString().trim());
            return Health.status(SettingsHealthIndicator.DEGRADED).withDetails(d).build();
        }
        return Health.up().withDetails(d).build();
    }
}
