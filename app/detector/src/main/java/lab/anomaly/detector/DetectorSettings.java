package lab.anomaly.detector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lab.anomaly.kit.settings.SettingsValidator;

/**
 * The Detector's settings (config-repo/detector.yml, prefix {@code anomaly.detector}), design D4. Immutable;
 * replaced as a whole on every accepted change.
 *
 * @param history            window for "normal" (at most 2 days: the Store keeps no more)
 * @param minHistory         a line is judged only after it has been known this long
 * @param calmPeriod         all lines of an incident calm this long before it closes
 * @param silenceAfter       a line with no value this long behind its dashboard is silent
 * @param silenceMinPresence only lines with a value in at least this share of steps can go silent
 * @param silenceSkipSignals signals never judged on silence (an error count that stops reporting is good news)
 * @param groupBy            tags deciding an incident's group, first one a line has; else its dashboard
 * @param upstreamTags       group tags that mean "a service being called"
 * @param incidentRetention  closed incidents kept this long (at most 10 days)
 * @param storeAddress       host:port of the Store's gRPC service (warm start)
 * @param rules              ordered; the first that matches a line watches it
 * @param general            the rule for lines no rule matches
 * @param incidentScope      "graph" (default, anomaly-per-graph): one incident per region, dashboard, panel and small
 *                           graph; "service": by the first {@code groupBy} tag a line has, else its dashboard
 * @param slowHistory        calmer-anomalies: normal of a rule judged on averages is the median of this much (default 2 h)
 * @param slowMinHistory     a line of such a rule is judged only after this much (default 30 min)
 */
public record DetectorSettings(Duration history, Duration minHistory, Duration calmPeriod, Duration silenceAfter,
                               double silenceMinPresence, List<String> silenceSkipSignals, List<String> groupBy,
                               List<String> upstreamTags,
                               Duration incidentRetention, String storeAddress, List<RuleSettings> rules,
                               GeneralSettings general, List<BauSettings> bau, String incidentScope,
                               Duration slowHistory, Duration slowMinHistory) {

    /**
     * Anomalies that are business as usual (add-line-reasons L2): a line whose signal (and, if given, direction: up,
     * down or silent) matches needs no reason. Missing setting: "general" in any direction, and errors going down.
     */
    public record BauSettings(String signal, String direction) {
        public boolean matches(String lineSignal, String lineDirection) {
            return signal != null && signal.equalsIgnoreCase(lineSignal)
                    && (direction == null || direction.isBlank() || direction.equalsIgnoreCase(lineDirection));
        }
    }

    public static final List<BauSettings> DEFAULT_BAU = List.of(new BauSettings("general", null), new BauSettings("errors", "down"));
    static final Set<String> BAU_DIRECTIONS = Set.of("up", "down", "silent");

    /** True when this line's anomaly is business as usual. O(entries). */
    public boolean isBau(String signal, String direction) {
        for (BauSettings b : bau) {
            if (b.matches(signal, direction)) {
                return true;
            }
        }
        return false;
    }

    public static final Duration MAX_HISTORY = Duration.ofDays(2);
    public static final Duration MAX_RETENTION = Duration.ofDays(10);
    static final Set<String> DIRECTIONS = Set.of("up", "down", "both");

    public DetectorSettings {
        silenceSkipSignals = silenceSkipSignals == null ? List.of() : List.copyOf(silenceSkipSignals);
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        upstreamTags = upstreamTags == null ? List.of() : List.copyOf(upstreamTags);
        rules = rules == null ? List.of() : List.copyOf(rules);
        bau = bau == null ? DEFAULT_BAU : List.copyOf(bau);
        incidentScope = incidentScope == null || incidentScope.isBlank() ? SCOPE_GRAPH : incidentScope.strip().toLowerCase(java.util.Locale.ROOT);
        slowHistory = slowHistory == null ? Duration.ofHours(2) : slowHistory;
        slowMinHistory = slowMinHistory == null ? Duration.ofMinutes(30) : slowMinHistory;
    }

    /** calmer-anomalies: periods {@code judge-every} may take besides one step; each divides an hour. */
    public static final java.util.Set<Long> SLOW_MINUTES = java.util.Set.of(1L, 2L, 3L, 4L, 5L, 6L, 10L, 12L, 15L, 20L, 30L);
    public static final String JUDGE_EVERY_PROBLEM = "must be 10s or 1 to 30 minutes dividing an hour";

    /** True for a missing {@code judge-every}, {@code 10s}, or whole minutes from {@link #SLOW_MINUTES}. */
    static boolean validJudgeEvery(Duration d) {
        if (d == null || d.equals(Duration.ofSeconds(10))) {
            return true;
        }
        return d.toSecondsPart() == 0 && d.toMillisPart() == 0 && SLOW_MINUTES.contains(d.toMinutes());
    }

    /** The longest {@code judge-every} of any rule, in milliseconds (0 when every rule judges every step). */
    public long longestJudgeEveryMs() {
        long max = general == null || general.judgeEvery() == null ? 0 : general.judgeEvery().toMillis();
        for (RuleSettings r : rules) {
            if (r.judgeEvery() != null) {
                max = Math.max(max, r.judgeEvery().toMillis());
            }
        }
        return max;
    }

    public static final String SCOPE_GRAPH = "graph";
    public static final String SCOPE_SERVICE = "service";

    /**
     * One rule. Exactly one of {@code factor} (times normal) or {@code points} (percentage points) is set.
     * {@code madFactor}, when set, also requires the change to be that many times the line's usual wobble (MAD),
     * so noisy low-volume lines do not alarm on chance.
     * Fields are boxed so a missing value can be told apart from zero.
     */
    public record RuleSettings(String signal, MatchSettings match, String direction, Double factor, Double points,
                               Integer steps, Double minChange, Double madFactor, Duration judgeEvery) {
    }

    /**
     * What a rule matches; any listed pattern matching is enough. Patterns ignore case; {@code *} is a wildcard.
     * Tags are {@code key=pattern} or {@code key!=pattern} (the line has the tag with a value not matching).
     */
    public record MatchSettings(List<String> kind, List<String> unit, List<String> metric, List<String> tags,
                                List<String> title, List<String> query) {
        public MatchSettings {
            kind = kind == null ? List.of() : List.copyOf(kind);
            unit = unit == null ? List.of() : List.copyOf(unit);
            metric = metric == null ? List.of() : List.copyOf(metric);
            tags = tags == null ? List.of() : List.copyOf(tags);
            title = title == null ? List.of() : List.copyOf(title);
            query = query == null ? List.of() : List.copyOf(query);
        }

        boolean isEmpty() {
            return kind.isEmpty() && unit.isEmpty() && metric.isEmpty() && tags.isEmpty() && title.isEmpty() && query.isEmpty();
        }
    }

    /** The rule for lines no other rule matches: far outside the line's usual wobble (MAD). */
    public record GeneralSettings(String direction, double madFactor, double atLeastFactor, int steps, double minChange,
                                  Duration judgeEvery) {
    }

    static List<SettingsValidator<? super DetectorSettings>> validators() {
        return List.of(
                SettingsValidator.require("bau", s -> s.bau().stream().allMatch(b -> b.signal() != null && !b.signal().isBlank()
                        && (b.direction() == null || b.direction().isBlank() || BAU_DIRECTIONS.contains(b.direction().toLowerCase(java.util.Locale.ROOT)))),
                        "each entry needs a signal, and a direction of up, down or silent when given"),
                SettingsValidator.require("incident-scope", s -> SCOPE_GRAPH.equals(s.incidentScope()) || SCOPE_SERVICE.equals(s.incidentScope()),
                        "must be graph or service"),
                SettingsValidator.positive("history", DetectorSettings::history),
                SettingsValidator.require("history", s -> s.history() == null || s.history().compareTo(MAX_HISTORY) <= 0,
                        "at most 2 days: the Store keeps no more (lab limit)"),
                SettingsValidator.positive("min-history", DetectorSettings::minHistory),
                SettingsValidator.require("min-history", s -> s.minHistory() == null || s.history() == null
                        || s.minHistory().compareTo(s.history()) <= 0, "must not be longer than history"),
                SettingsValidator.positive("calm-period", DetectorSettings::calmPeriod),
                SettingsValidator.positive("silence-after", DetectorSettings::silenceAfter),
                SettingsValidator.require("silence-min-presence", s -> s.silenceMinPresence() > 0 && s.silenceMinPresence() <= 1,
                        "must be above 0 and at most 1"),
                SettingsValidator.positive("incident-retention", DetectorSettings::incidentRetention),
                SettingsValidator.require("incident-retention", s -> s.incidentRetention() == null
                        || s.incidentRetention().compareTo(MAX_RETENTION) <= 0,
                        "at most 10 days: this is a lab system and the database must stay small"),
                SettingsValidator.require("store-address", s -> s.storeAddress() != null && s.storeAddress().matches("[^:]+:\\d+"),
                        "must be host:port"),
                SettingsValidator.require("rules", s -> !s.rules().isEmpty(), "at least one rule is needed"),
                s -> {
                    List<String> problems = new ArrayList<>();
                    for (int i = 0; i < s.rules().size(); i++) {
                        problems.addAll(ruleProblems("rules[" + i + "]", s.rules().get(i)));
                    }
                    return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
                },
                SettingsValidator.require("general", s -> s.general() != null, "is required"),
                SettingsValidator.require("general.direction", s -> s.general() == null || DIRECTIONS.contains(s.general().direction()),
                        "must be up, down or both"),
                SettingsValidator.require("general.mad-factor", s -> s.general() == null || s.general().madFactor() > 0, "must be above 0"),
                SettingsValidator.require("general.at-least-factor", s -> s.general() == null || s.general().atLeastFactor() > 1,
                        "must be above 1"),
                SettingsValidator.require("general.steps", s -> s.general() == null || s.general().steps() >= 1, "must be at least 1"),
                SettingsValidator.require("general.judge-every", s -> s.general() == null || validJudgeEvery(s.general().judgeEvery()),
                        JUDGE_EVERY_PROBLEM),
                SettingsValidator.require("slow-history", s -> s.slowHistory().compareTo(MAX_HISTORY) <= 0
                        && s.slowHistory().toMillis() >= 3 * s.longestJudgeEveryMs() && !s.slowHistory().isNegative() && !s.slowHistory().isZero(),
                        "at most 2 days and at least 3 times the longest judge-every"),
                SettingsValidator.require("slow-min-history", s -> !s.slowMinHistory().isNegative()
                        && s.slowMinHistory().compareTo(s.slowHistory()) <= 0, "must not be longer than slow-history"));
    }

    static List<String> ruleProblems(String name, RuleSettings r) {
        List<String> p = new ArrayList<>();
        if (r.signal() == null || r.signal().isBlank()) {
            p.add(name + ".signal: is required");
        }
        if (r.direction() == null || !DIRECTIONS.contains(r.direction())) {
            p.add(name + ".direction: must be up, down or both");
        }
        if (r.match() == null || r.match().isEmpty()) {
            p.add(name + ".match: needs at least one pattern");
        }
        if ((r.factor() == null) == (r.points() == null)) {
            p.add(name + ": set exactly one of factor or points");
        }
        if (r.factor() != null && r.factor() <= 1) {
            p.add(name + ".factor: must be above 1 (1 would flag every value)");
        }
        if (r.points() != null && r.points() <= 0) {
            p.add(name + ".points: must be above 0");
        }
        if (r.steps() == null || r.steps() < 1) {
            p.add(name + ".steps: must be at least 1");
        }
        if (r.match() != null) {
            for (String tag : r.match().tags()) {
                if (tag == null || tag.indexOf('=') <= 0) {
                    p.add(name + ".match.tags: '" + tag + "' must be key=pattern or key!=pattern");
                }
            }
        }
        if (r.madFactor() != null && r.madFactor() <= 0) {
            p.add(name + ".mad-factor: must be above 0");
        }
        if (!validJudgeEvery(r.judgeEvery())) {
            p.add(name + ".judge-every: " + JUDGE_EVERY_PROBLEM);
        }
        if (r.minChange() != null && r.minChange() < 0) {
            p.add(name + ".min-change: must not be negative");
        }
        return p;
    }
}
