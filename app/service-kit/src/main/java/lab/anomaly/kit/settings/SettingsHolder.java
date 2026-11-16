package lab.anomaly.kit.settings;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Holds one group of settings (for example everything under {@code anomaly.collector}) as an immutable
 * snapshot (design D13).
 *
 * <ul>
 *   <li>Readers call {@link #get()} and always see a complete, validated set: O(1), no locks.</li>
 *   <li>When settings change (Observer: Spring Cloud publishes an {@link EnvironmentChangeEvent}), the new
 *       values are bound, run through the validator chain, and swapped in with one atomic write only if every
 *       check passes.</li>
 *   <li>Otherwise the last good snapshot stays in use and the rejection is kept for the logs, the settings
 *       page and the health check.</li>
 * </ul>
 * At start there is no "last good" set yet, so invalid settings stop the service instead.
 */
public final class SettingsHolder<T> implements ApplicationListener<EnvironmentChangeEvent> {

    /** One accepted set of settings. {@code version} counts accepted changes, starting at 1. */
    public record Snapshot<T>(T value, long version, Instant loadedAt) {}

    /** The most recent change that was refused, and why. */
    public record Rejection(Instant at, List<String> problems) {}

    private record Loaded<T>(T value, List<String> problems) {}

    private static final Logger log = LoggerFactory.getLogger(SettingsHolder.class);

    private final String prefix;
    private final Class<T> type;
    private final List<SettingsValidator<? super T>> validators;
    private final Environment environment;
    private final AtomicReference<Snapshot<T>> current = new AtomicReference<>();
    private final AtomicReference<Rejection> lastRejection = new AtomicReference<>();

    public SettingsHolder(String prefix, Class<T> type, List<SettingsValidator<? super T>> validators,
                          Environment environment) {
        this.prefix = Objects.requireNonNull(prefix);
        this.type = Objects.requireNonNull(type);
        this.validators = List.copyOf(validators);
        this.environment = Objects.requireNonNull(environment);

        Loaded<T> first = load();
        if (!first.problems().isEmpty()) {
            throw new IllegalStateException(
                    "Invalid settings under '" + prefix + "': " + String.join("; ", first.problems()));
        }
        current.set(new Snapshot<>(first.value(), 1, Instant.now()));
        log.info("Settings '{}' loaded (version 1)", prefix);
    }

    /** The current, validated settings. */
    public T get() {
        return current.get().value();
    }

    public Snapshot<T> snapshot() {
        return current.get();
    }

    /** The last refused change, or null if the latest change was accepted. */
    public Rejection lastRejection() {
        return lastRejection.get();
    }

    public String prefix() {
        return prefix;
    }

    /** Reloads only when a changed key belongs to this group, so unrelated changes cost nothing. */
    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        if (event.getKeys().stream().anyMatch(key -> key.startsWith(prefix + "."))) {
            reload();
        }
    }

    /**
     * Reads the settings again. Returns true if the new snapshot was accepted.
     * Synchronized so two reloads never interleave; readers are never blocked.
     */
    public synchronized boolean reload() {
        Loaded<T> loaded = load();
        if (loaded.problems().isEmpty()) {
            Snapshot<T> next = new Snapshot<>(loaded.value(), current.get().version() + 1, Instant.now());
            current.set(next);
            lastRejection.set(null);
            log.info("Settings '{}' updated to version {}", prefix, next.version());
            return true;
        }
        lastRejection.set(new Rejection(Instant.now(), loaded.problems()));
        log.warn("Settings '{}' change rejected; still using version {}. Problems: {}",
                prefix, current.get().version(), String.join("; ", loaded.problems()));
        return false;
    }

    private Loaded<T> load() {
        T value;
        try {
            value = Binder.get(environment).bind(prefix, Bindable.of(type)).orElse(null);
        } catch (BindException e) {
            return new Loaded<>(null, List.of(describe(e)));
        }
        if (value == null) {
            return new Loaded<>(null, List.of("no settings found under '" + prefix + "'"));
        }
        // The chain: every validator runs, all problems are collected (more useful than stopping at the first)
        List<String> problems = validators.stream()
                .map(validator -> validator.check(value))
                .flatMap(Optional::stream)
                .toList();
        return new Loaded<>(value, problems);
    }

    private static String describe(BindException e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String property = e.getProperty() != null ? e.getProperty().getName().toString() : "(unknown setting)";
        return property + ": " + root.getMessage();
    }
}
