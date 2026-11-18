package lab.anomaly.collector.keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The machine's {@code .env} file, where Datadog keys live (add-settings-page S4). Read on every settings reload, so new
 * keys need no restart; written whole (other lines and comments kept) to a temp file readable by this user only, then
 * renamed over the old one, so a reader never sees half a file. Only Datadog key names may be read or written here:
 * the database password and anything else in the file stay untouched and unread. Values never leave in readable form
 * except to {@link lab.anomaly.collector.DatadogCredentials}.
 */
public final class EnvFile {

    /** DD_API_KEY, DD_APP_KEY, DD_<NAME>_API_KEY, DD_<NAME>_APP_KEY, the same names settings may give. */
    public static final Pattern KEY_NAME = Pattern.compile("DD_([A-Z0-9]+_)?(API|APP)_KEY");

    private final Path path;

    public EnvFile(Path path) {
        this.path = path;
    }

    /** From {@code LAB_ENV_FILE} (set by system.sh); empty when not set, then keys come from the environment only. */
    public static Optional<EnvFile> fromEnvironment(org.springframework.core.env.Environment environment) {
        String p = environment.getProperty("LAB_ENV_FILE");
        return p == null || p.isBlank() ? Optional.empty() : Optional.of(new EnvFile(Path.of(p)));
    }

    public Path path() {
        return path;
    }

    /** A key's value, or empty when the file or the line is missing. O(lines). */
    public synchronized Optional<String> get(String name) {
        requireKeyName(name);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.startsWith(name + "=")) {
                    String v = line.substring(name.length() + 1).strip();
                    return v.isEmpty() ? Optional.empty() : Optional.of(v);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Sets ({@code value} non-null) or removes ({@code null}) the given keys in one rewrite: lines of those names are
     * dropped, new ones appended, everything else kept in order. O(lines).
     */
    public synchronized void write(Map<String, String> changes) throws IOException {
        changes.keySet().forEach(EnvFile::requireKeyName);
        for (String v : changes.values()) {
            if (v != null && (v.isBlank() || v.contains("\n") || v.contains("\r") || v.length() > 200)) {
                throw new IllegalArgumentException("a key must be one line of 1 to 200 characters");
            }
        }
        List<String> lines = Files.isRegularFile(path) ? Files.readAllLines(path, StandardCharsets.UTF_8) : List.of();
        List<String> out = new ArrayList<>(lines.size() + changes.size());
        Set<String> names = new HashSet<>(changes.keySet());
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0 && names.contains(line.substring(0, eq))) {
                continue;
            }
            out.add(line);
        }
        new LinkedHashMap<>(changes).forEach((k, v) -> {
            if (v != null) {
                out.add(k + "=" + v);
            }
        });
        Path dir = path.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".env.", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(tmp, out, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void requireKeyName(String name) {
        if (name == null || !KEY_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("only Datadog key names may be read or written");
        }
    }
}
