package lab.anomaly.reporter.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The state of every window (design R2): pending -> written -> verified -> cleaned, kept in {@code .state.json} next
 * to the reports and written atomically (temporary file, then rename). The window key is the idempotency key.
 */
public final class ReportState {

    public enum Step { PENDING, WRITTEN, VERIFIED, CLEANED }

    /** One window's progress; {@code incidents} also gives "previous window 29" in the next report. */
    public record Entry(Step step, int incidents, long removedPoints, long effectiveBeforeMs, long updatedMs) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path file;
    private final Map<String, Entry> windows;

    public ReportState(Path dir) {
        this.file = dir.resolve(".state.json");
        Map<String, Entry> loaded = new TreeMap<>();
        if (Files.exists(file)) {
            try {
                loaded.putAll(JSON.readValue(Files.readString(file), new TypeReference<Map<String, Entry>>() { }));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Report state " + file + " is not readable: " + e.getMessage(), e);
            }
        }
        this.windows = loaded;
    }

    public synchronized Entry get(String key) {
        return windows.get(key);
    }

    public synchronized Step step(String key) {
        Entry e = windows.get(key);
        return e == null ? Step.PENDING : e.step();
    }

    public synchronized void put(String key, Entry entry) throws IOException {
        windows.put(key, entry);
        Files.createDirectories(file.getParent());
        AtomicFiles.write(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(windows));
    }

    public synchronized Map<String, Entry> all() {
        return Map.copyOf(windows);
    }
}
