package lab.anomaly.collector;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lab.anomaly.kit.settings.SettingsValidator;

/**
 * The Collector's settings (config-repo/collector.yml, prefix {@code anomaly.collector}). Immutable: every
 * collection is copied on creation, so no one can change a snapshot after it was validated.
 * See add-collector design K13 for what each setting means.
 */
public record CollectorSettings(
        String source,
        String datadogSite,
        List<String> dashboards,
        Duration pollInterval,
        Duration step,
        Duration backfill,
        Duration recentWindow,
        Duration dashboardRefresh,
        Map<String, Map<String, String>> dashboardFilters,
        List<ClassificationRule> classification,
        int maxConcurrentRequests,
        double rateLimitShare,
        int retries,
        Duration retryBase,
        Duration retryCap,
        int breakerFailures,
        Duration breakerOpen,
        Duration metadataTtl,
        int metadataCacheSize,
        Recording recording,
        String replayDir) {

    /** Kinds a rule may assign (the Kind enum of the contracts, in lower case). */
    public static final Set<String> KINDS = Set.of("unknown", "count", "rate", "gauge", "latency", "ratio");

    /** One kind/unit rule: first match wins (design K8). Every given match key must hold. */
    public record ClassificationRule(Match match, Assignment set) {
        public record Match(String metric, String panelTitle, Boolean hasFormula) {}
        public record Assignment(String kind, String unit) {}
    }

    /** Saving the source's answers to files (design K12). */
    public record Recording(boolean enabled, String dir) {}

    public CollectorSettings {
        dashboards = dashboards == null ? List.of() : List.copyOf(dashboards);
        dashboardFilters = dashboardFilters == null ? Map.of() : dashboardFilters.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
        classification = classification == null ? List.of() : List.copyOf(classification);
        recording = recording == null ? new Recording(false, "recordings") : recording;
    }

    static List<SettingsValidator<? super CollectorSettings>> validators() {
        return List.of(
                SettingsValidator.require("source", s -> "datadog".equals(s.source()) || "replay".equals(s.source()),
                        "must be 'datadog' or 'replay'"),
                SettingsValidator.require("replay-dir", s -> !"replay".equals(s.source()) || notBlank(s.replayDir()),
                        "must be set when source is 'replay'"),
                SettingsValidator.require("datadog-site", s -> notBlank(s.datadogSite()), "must not be empty"),
                SettingsValidator.require("dashboards", s -> !s.dashboards().isEmpty(), "list at least one dashboard id"),
                CollectorSettings::noDuplicateDashboards,
                SettingsValidator.positive("poll-interval", CollectorSettings::pollInterval),
                SettingsValidator.positive("step", CollectorSettings::step),
                SettingsValidator.positive("backfill", CollectorSettings::backfill),
                SettingsValidator.positive("recent-window", CollectorSettings::recentWindow),
                SettingsValidator.positive("dashboard-refresh", CollectorSettings::dashboardRefresh),
                SettingsValidator.require("recent-window",
                        s -> s.recentWindow() == null || s.backfill() == null || s.recentWindow().compareTo(s.backfill()) <= 0,
                        "must not be longer than backfill"),
                SettingsValidator.require("max-concurrent-requests", s -> s.maxConcurrentRequests() >= 1, "must be at least 1"),
                SettingsValidator.require("rate-limit-share", s -> s.rateLimitShare() > 0 && s.rateLimitShare() <= 1,
                        "must be more than 0 and at most 1"),
                SettingsValidator.require("retries", s -> s.retries() >= 0 && s.retries() <= 10, "must be between 0 and 10"),
                SettingsValidator.positive("retry-base", CollectorSettings::retryBase),
                SettingsValidator.positive("retry-cap", CollectorSettings::retryCap),
                SettingsValidator.require("breaker-failures", s -> s.breakerFailures() >= 1, "must be at least 1"),
                SettingsValidator.positive("breaker-open", CollectorSettings::breakerOpen),
                SettingsValidator.positive("metadata-ttl", CollectorSettings::metadataTtl),
                SettingsValidator.require("metadata-cache-size", s -> s.metadataCacheSize() >= 1, "must be at least 1"),
                SettingsValidator.require("recording.dir", s -> !s.recording().enabled() || notBlank(s.recording().dir()),
                        "must be set when recording is enabled"),
                CollectorSettings::validRules);
    }

    /** A hash set finds a repeated id in one pass: O(n) time, O(n) memory. */
    static Optional<String> noDuplicateDashboards(CollectorSettings settings) {
        Set<String> seen = new HashSet<>();
        for (String id : settings.dashboards()) {
            if (!seen.add(id)) {
                return Optional.of("dashboards: '" + id + "' is listed twice");
            }
        }
        return Optional.empty();
    }

    static Optional<String> validRules(CollectorSettings settings) {
        for (int i = 0; i < settings.classification().size(); i++) {
            ClassificationRule rule = settings.classification().get(i);
            String where = "classification[" + i + "]: ";
            ClassificationRule.Match m = rule.match();
            if (m == null || (m.metric() == null && m.panelTitle() == null && m.hasFormula() == null)) {
                return Optional.of(where + "needs at least one of match.metric, match.panel-title, match.has-formula");
            }
            ClassificationRule.Assignment a = rule.set();
            if (a == null || (a.kind() == null && a.unit() == null)) {
                return Optional.of(where + "needs set.kind and/or set.unit");
            }
            if (a.kind() != null && !KINDS.contains(a.kind())) {
                return Optional.of(where + "unknown kind '" + a.kind() + "', use one of " + KINDS);
            }
        }
        return Optional.empty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
