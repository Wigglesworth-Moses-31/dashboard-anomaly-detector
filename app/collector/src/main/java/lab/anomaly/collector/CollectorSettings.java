package lab.anomaly.collector;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lab.anomaly.kit.settings.SettingsValidator;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The Collector's settings (config-repo/collector.yml, prefix {@code anomaly.collector}). Immutable: every
 * collection is copied on creation, so no one can change a snapshot after it was validated.
 * See add-collector design K13 for what each setting means.
 */
public record CollectorSettings(
        String source,
        String datadogSite,
        List<DashboardRef> dashboards,
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
        String replayDir,
        List<Source> sources,
        Integer batchFormulas,
        Duration settleDelay) {

    /** Kinds a rule may assign (the Kind enum of the contracts, in lower case). */
    public static final Set<String> KINDS = Set.of("unknown", "count", "rate", "gauge", "latency", "ratio");

    /** One kind/unit rule: first match wins (design K8). Every given match key must hold. */
    public record ClassificationRule(Match match, Assignment set) {
        public record Match(String metric, String panelTitle, Boolean hasFormula) {}
        public record Assignment(String kind, String unit) {}
    }

    /** Saving the source's answers to files (design K12). */
    public record Recording(boolean enabled, String dir) {}

    /** Name of the one source made from the old single-site settings (add-multi-site design M5). */
    public static final String DEFAULT_SOURCE = "default";

    /** A Datadog key name settings may refer to: DD_API_KEY, DD_APP_KEY, DD_<NAME>_API_KEY, DD_<NAME>_APP_KEY. */
    public static final Pattern KEY_NAME = Pattern.compile("^DD_([A-Z0-9]+_)?(API|APP)_KEY$");
    private static final Pattern SOURCE_NAME = Pattern.compile("^[a-z0-9]{1,12}$");

    /**
     * One Datadog account to watch (add-multi-site design M5). Keys are given by the NAME of the environment variable
     * holding them, never by value. {@code maxConcurrentRequests} and {@code rateLimitShare} are optional; when
     * missing, the shared values apply.
     */
    /**
     * One dashboard to watch (add-dashboard-json design J1, J10): its exported JSON file in config-repo, optionally the
     * dashboard's id (for links, when the export has none), filters that win over the file's defaults, and which saved
     * views to watch ({@code [all]}, names, or none for the file's defaults). A plain string binds as an id only, so the
     * validator can refuse it with a helpful message instead of a binding error.
     */
    public record DashboardRef(String file, String id, Map<String, String> filters, List<String> presets) {
        public DashboardRef {
            filters = filters == null ? Map.of() : Map.copyOf(filters);
            presets = presets == null ? List.of() : List.copyOf(presets);
        }

        /** Used by Spring's conversion for a plain string: an old dashboard id. */
        public static DashboardRef valueOf(String id) {
            return new DashboardRef(null, id, Map.of(), List.of());
        }

        public boolean idOnly() {
            return file == null || file.isBlank();
        }

        public boolean allPresets() {
            return presets.size() == 1 && "all".equalsIgnoreCase(presets.get(0).strip());
        }

        /** How it is named in messages: the file, or the id. */
        public String name() {
            return idOnly() ? id : file;
        }
    }

    public record Source(String name, String site, String apiKeyEnv, String appKeyEnv, List<DashboardRef> dashboards,
                         Map<String, Map<String, String>> dashboardFilters, Integer maxConcurrentRequests,
                         Double rateLimitShare) {
        public Source {
            dashboards = dashboards == null ? List.of() : List.copyOf(dashboards);
            dashboardFilters = copyFilters(dashboardFilters);
        }
    }

    /** Everything one lane needs, with shared defaults filled in. Equal configs mean the lane can be kept (design M7). */
    public record LaneConfig(String name, String site, String apiKeyEnv, String appKeyEnv, List<DashboardRef> dashboards,
                             Map<String, Map<String, String>> dashboardFilters, int maxConcurrentRequests,
                             double rateLimitShare) {
        /** True when only what to watch differs, so the lane keeps its connection state and only re-reads dashboards. */
        public boolean sameConnection(LaneConfig other) {
            return other != null && name.equals(other.name) && site.equals(other.site) && apiKeyEnv.equals(other.apiKeyEnv)
                    && appKeyEnv.equals(other.appKeyEnv) && maxConcurrentRequests == other.maxConcurrentRequests
                    && rateLimitShare == other.rateLimitShare;
        }
    }

    /** The settings without a {@code sources} list (single site), as before add-multi-site. */
    public CollectorSettings(String source, String datadogSite, List<DashboardRef> dashboards, Duration pollInterval, Duration step,
                             Duration backfill, Duration recentWindow, Duration dashboardRefresh,
                             Map<String, Map<String, String>> dashboardFilters, List<ClassificationRule> classification,
                             int maxConcurrentRequests, double rateLimitShare, int retries, Duration retryBase,
                             Duration retryCap, int breakerFailures, Duration breakerOpen, Duration metadataTtl,
                             int metadataCacheSize, Recording recording, String replayDir) {
        this(source, datadogSite, dashboards, pollInterval, step, backfill, recentWindow, dashboardRefresh, dashboardFilters,
                classification, maxConcurrentRequests, rateLimitShare, retries, retryBase, retryCap, breakerFailures,
                breakerOpen, metadataTtl, metadataCacheSize, recording, replayDir, null, null);
    }

    /** The settings with {@code sources} and without {@code batch-formulas} (its default applies). */
    public CollectorSettings(String source, String datadogSite, List<DashboardRef> dashboards, Duration pollInterval, Duration step,
                             Duration backfill, Duration recentWindow, Duration dashboardRefresh,
                             Map<String, Map<String, String>> dashboardFilters, List<ClassificationRule> classification,
                             int maxConcurrentRequests, double rateLimitShare, int retries, Duration retryBase,
                             Duration retryCap, int breakerFailures, Duration breakerOpen, Duration metadataTtl,
                             int metadataCacheSize, Recording recording, String replayDir, List<Source> sources) {
        this(source, datadogSite, dashboards, pollInterval, step, backfill, recentWindow, dashboardRefresh, dashboardFilters,
                classification, maxConcurrentRequests, rateLimitShare, retries, retryBase, retryCap, breakerFailures,
                breakerOpen, metadataTtl, metadataCacheSize, recording, replayDir, sources, null, null);
    }

    /** The settings with {@code sources} and {@code batch-formulas}, without {@code settle-delay} (its default applies). */
    public CollectorSettings(String source, String datadogSite, List<DashboardRef> dashboards, Duration pollInterval, Duration step,
                             Duration backfill, Duration recentWindow, Duration dashboardRefresh,
                             Map<String, Map<String, String>> dashboardFilters, List<ClassificationRule> classification,
                             int maxConcurrentRequests, double rateLimitShare, int retries, Duration retryBase,
                             Duration retryCap, int breakerFailures, Duration breakerOpen, Duration metadataTtl,
                             int metadataCacheSize, Recording recording, String replayDir, List<Source> sources,
                             Integer batchFormulas) {
        this(source, datadogSite, dashboards, pollInterval, step, backfill, recentWindow, dashboardRefresh, dashboardFilters,
                classification, maxConcurrentRequests, rateLimitShare, retries, retryBase, retryCap, breakerFailures,
                breakerOpen, metadataTtl, metadataCacheSize, recording, replayDir, sources, batchFormulas, null);
    }

    /**
     * How long after its end a step is still treated as unfinished (fix-false-drops design F1): the source may still be
     * receiving its values. Default 60 s.
     */
    public Duration effectiveSettleDelay() {
        return settleDelay == null ? DEFAULT_SETTLE_DELAY : settleDelay;
    }

    public static final Duration DEFAULT_SETTLE_DELAY = Duration.ofSeconds(60);

    /** Most formulas in one Datadog call (design J14); 1 = no batching. Recording turns batching off. */
    public int effectiveBatchFormulas() {
        if (recording.enabled()) {
            return 1;                       // recordings keep one answer per panel question, so replay finds them
        }
        return batchFormulas == null ? 40 : batchFormulas;
    }

    /**
     * The lanes to run, in settings order: the {@code sources} list, or one source {@value #DEFAULT_SOURCE} made from
     * {@code datadog-site}, {@code dashboards}, {@code dashboard-filters} and the keys DD_API_KEY / DD_APP_KEY. The
     * default source keeps the old site, so every line id stays the same (spec: old single-site settings).
     */
    public List<LaneConfig> lanes() {
        if (sources.isEmpty()) {
            return List.of(new LaneConfig(DEFAULT_SOURCE, datadogSite, "DD_API_KEY", "DD_APP_KEY", dashboards,
                    dashboardFilters, maxConcurrentRequests, rateLimitShare));
        }
        return sources.stream().map(s -> new LaneConfig(s.name(), s.site(), s.apiKeyEnv(), s.appKeyEnv(), s.dashboards(),
                s.dashboardFilters(), s.maxConcurrentRequests() != null ? s.maxConcurrentRequests() : maxConcurrentRequests,
                s.rateLimitShare() != null ? s.rateLimitShare() : rateLimitShare)).toList();
    }

    /** The one constructor settings are bound through (there are two since add-multi-site). */
    @ConstructorBinding
    public CollectorSettings {
        dashboards = dashboards == null ? List.of() : List.copyOf(dashboards);
        dashboardFilters = copyFilters(dashboardFilters);
        sources = sources == null ? List.of() : List.copyOf(sources);
        classification = classification == null ? List.of() : List.copyOf(classification);
        recording = recording == null ? new Recording(false, "recordings") : recording;
    }

    static List<SettingsValidator<? super CollectorSettings>> validators() {
        return List.of(
                SettingsValidator.require("source", s -> "datadog".equals(s.source()) || "replay".equals(s.source()),
                        "must be 'datadog' or 'replay'"),
                SettingsValidator.require("replay-dir", s -> !"replay".equals(s.source()) || notBlank(s.replayDir()),
                        "must be set when source is 'replay'"),
                SettingsValidator.require("sources", s -> s.sources().isEmpty() || (!notBlank(s.datadogSite()) && s.dashboards().isEmpty()),
                        "use either 'sources' or 'datadog-site' with 'dashboards', not both"),
                SettingsValidator.require("datadog-site", s -> !s.sources().isEmpty() || notBlank(s.datadogSite()),
                        "must not be empty (or list 'sources')"),
                SettingsValidator.require("dashboards", s -> !s.sources().isEmpty() || !s.dashboards().isEmpty() || "replay".equals(s.source()),
                        "list at least one dashboard file"),
                CollectorSettings::noDuplicateDashboards,
                CollectorSettings::validSources,
                SettingsValidator.positive("poll-interval", CollectorSettings::pollInterval),
                SettingsValidator.positive("step", CollectorSettings::step),
                SettingsValidator.positive("backfill", CollectorSettings::backfill),
                SettingsValidator.positive("recent-window", CollectorSettings::recentWindow),
                SettingsValidator.positive("dashboard-refresh", CollectorSettings::dashboardRefresh),
                SettingsValidator.require("settle-delay", s -> s.settleDelay() == null || !s.settleDelay().isNegative(),
                        "must be 0 or more"),
                SettingsValidator.require("settle-delay", s -> s.recentWindow() == null
                        || s.effectiveSettleDelay().compareTo(s.recentWindow()) < 0,
                        "must be shorter than recent-window, or a step would leave the window before it is finished"),
                SettingsValidator.require("recent-window",
                        s -> s.recentWindow() == null || s.backfill() == null || s.recentWindow().compareTo(s.backfill()) <= 0,
                        "must not be longer than backfill"),
                SettingsValidator.require("max-concurrent-requests", s -> s.maxConcurrentRequests() >= 1, "must be at least 1"),
                SettingsValidator.require("batch-formulas", s -> s.batchFormulas() == null || (s.batchFormulas() >= 1 && s.batchFormulas() <= 80),
                        "must be between 1 and 80 (80 measured to work in one Datadog call)"),
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

    /** A hash set finds a repeated dashboard in one pass: O(n) time, O(n) memory. Plus the file rules (design J1). */
    static Optional<String> noDuplicateDashboards(CollectorSettings settings) {
        return checkRefs("dashboards", settings.dashboards(), "replay".equals(settings.source()));
    }

    private static final Pattern FILE = Pattern.compile("^dashboards/[A-Za-z0-9._\\-]+(/[A-Za-z0-9._\\-]+)*\\.json$");

    /**
     * Each reference must be a JSON file under {@code dashboards/} (no {@code ..}, relative), listed once. A plain id is
     * refused with how to export the JSON (add-dashboard-json: JSON files only). In replay mode dashboards come from
     * the recording, so the list is not checked (design J7).
     */
    static Optional<String> checkRefs(String where, List<DashboardRef> refs, boolean replay) {
        if (replay) {
            return Optional.empty();
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < refs.size(); i++) {
            DashboardRef r = refs.get(i);
            String at = where + "[" + i + "]";
            if (r == null || r.idOnly()) {
                String id = r == null ? "" : r.id();
                return Optional.of(at + " '" + id + "' is an id; dashboards are now JSON files. Export it in Datadog "
                        + "(dashboard > Share > Export dashboard JSON) into config-repo/dashboards/ and list it as "
                        + "'- file: dashboards/<name>.json'");
            }
            String file = r.file().strip();
            if (file.contains("..") || !FILE.matcher(file).matches()) {
                return Optional.of(at + " file '" + file + "' must be a .json file under dashboards/, e.g. dashboards/us/shop.json");
            }
            if (!seen.add(file)) {
                return Optional.of(at + " file '" + file + "' is listed twice");
            }
            if (!r.presets().isEmpty() && r.presets().stream().anyMatch(p -> "all".equalsIgnoreCase(p.strip())) && r.presets().size() > 1) {
                return Optional.of(at + " presets: use 'all' alone, or a list of preset names");
            }
        }
        return Optional.empty();
    }

    /**
     * Rules of add-multi-site design M5. One HashSet pass per rule: O(sources + dashboards). The key-name rule keeps
     * settings from making the Collector read any secret other than a Datadog key (spec: settings name secrets).
     */
    public static Optional<String> validSources(CollectorSettings settings) {
        Map<String, Integer> names = new java.util.HashMap<>();
        Map<String, String> sites = new java.util.HashMap<>();
        for (int i = 0; i < settings.sources().size(); i++) {
            Source s = settings.sources().get(i);
            String where = "sources[" + i + "]: ";
            if (s.name() == null || !SOURCE_NAME.matcher(s.name()).matches()) {
                return Optional.of(where + "name must be 1-12 lower-case letters or digits, e.g. 'us' or 'eu'");
            }
            where = "sources[" + i + "] (" + s.name() + "): ";
            Integer before = names.putIfAbsent(s.name(), i);
            if (before != null) {
                return Optional.of(where + "the name is already used by sources[" + before + "]");
            }
            if (!notBlank(s.site())) {
                return Optional.of(where + "site must not be empty, e.g. datadoghq.com or datadoghq.eu");
            }
            String site = s.site().strip().toLowerCase(java.util.Locale.ROOT);
            String other = sites.putIfAbsent(site, s.name());
            if (other != null) {
                return Optional.of(where + "site " + site + " is already used by source '" + other
                        + "'; each source needs its own site (line ids are built from the site)");
            }
            Optional<String> keys = keyName(where + "api-key-env", s.apiKeyEnv(), "API")
                    .or(() -> keyName("sources (" + s.name() + "): app-key-env", s.appKeyEnv(), "APP"));
            if (keys.isPresent()) {
                return keys;
            }
            if (s.dashboards().isEmpty() && !"replay".equals(settings.source())) {
                return Optional.of(where + "list at least one dashboard file");
            }
            Optional<String> refs = checkRefs("sources[" + i + "] (" + s.name() + "): dashboards", s.dashboards(),
                    "replay".equals(settings.source()));
            if (refs.isPresent()) {
                return refs;
            }
            if (s.maxConcurrentRequests() != null && s.maxConcurrentRequests() < 1) {
                return Optional.of(where + "max-concurrent-requests must be at least 1");
            }
            if (s.rateLimitShare() != null && !(s.rateLimitShare() > 0 && s.rateLimitShare() <= 1)) {
                return Optional.of(where + "rate-limit-share must be more than 0 and at most 1");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> keyName(String where, String name, String role) {
        if (!notBlank(name)) {
            return Optional.of(where + " must name the environment variable holding the key, e.g. DD_EU_" + role + "_KEY");
        }
        java.util.regex.Matcher m = KEY_NAME.matcher(name);
        if (!m.matches() || !m.group(2).equals(role)) {
            return Optional.of(where + " '" + name + "' is not allowed: only DD_" + role + "_KEY or DD_<NAME>_" + role
                    + "_KEY may be named");
        }
        return Optional.empty();
    }

    private static Map<String, Map<String, String>> copyFilters(Map<String, Map<String, String>> filters) {
        return filters == null ? Map.of() : filters.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
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
