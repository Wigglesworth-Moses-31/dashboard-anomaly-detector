package lab.anomaly.kit.settings;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One check in the validator chain (Chain of Responsibility, design D13).
 * Returns a problem description, or empty when the settings pass this check.
 * New checks are added to a service's list without touching existing ones.
 */
@FunctionalInterface
public interface SettingsValidator<T> {

    Optional<String> check(T settings);

    /** A check from a yes/no test and the problem to report when it says no. */
    static <T> SettingsValidator<T> require(String name, Predicate<T> ok, String problem) {
        return settings -> ok.test(settings) ? Optional.empty() : Optional.of(name + ": " + problem);
    }

    /** The duration must be present and greater than zero. */
    static <T> SettingsValidator<T> positive(String name, Function<T, Duration> getter) {
        return settings -> {
            Duration value = getter.apply(settings);
            return value != null && value.isPositive()
                    ? Optional.empty()
                    : Optional.of(name + ": must be a positive duration, got " + value);
        };
    }
}
