package lab.anomaly.kit.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * The page {@code /actuator/settings}: the settings each service is using right now, their version, and
 * the last refused change. Settings never contain secrets (spec: runtime-configuration).
 */
@Endpoint(id = "settings")
public class SettingsEndpoint {

    private final List<SettingsHolder<?>> holders;

    public SettingsEndpoint(List<SettingsHolder<?>> holders) {
        this.holders = holders;
    }

    @ReadOperation
    public Map<String, Object> settings() {
        Map<String, Object> page = new LinkedHashMap<>();
        for (SettingsHolder<?> holder : holders) {
            SettingsHolder.Snapshot<?> snapshot = holder.snapshot();
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("version", snapshot.version());
            group.put("loadedAt", snapshot.loadedAt().toString());
            group.put("values", readable(snapshot.value()));
            SettingsHolder.Rejection rejection = holder.lastRejection();
            if (rejection != null) {
                group.put("lastRejected", Map.of("at", rejection.at().toString(), "problems", rejection.problems()));
            }
            page.put(holder.prefix(), group);
        }
        return page;
    }

    /** Settings records as name -> value; sizes such as 1GB shown as text instead of their Java fields. */
    static Object readable(Object value) {
        if (value == null || !value.getClass().isRecord()) {
            return value;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var component : value.getClass().getRecordComponents()) {
            try {
                Object v = component.getAccessor().invoke(value);
                out.put(component.getName(), v instanceof org.springframework.util.unit.DataSize size ? size.toString() : readable(v));
            } catch (ReflectiveOperationException e) {
                out.put(component.getName(), "?");
            }
        }
        return out;
    }
}
