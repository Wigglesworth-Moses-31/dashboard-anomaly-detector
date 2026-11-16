package lab.anomaly.detector.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lab.anomaly.contracts.v1.View;
import lab.anomaly.detector.DetectorSettings;
import lab.anomaly.detector.DetectorSettings.GeneralSettings;
import lab.anomaly.detector.DetectorSettings.MatchSettings;
import lab.anomaly.detector.DetectorSettings.RuleSettings;

/**
 * The ordered rules from the settings (design D4, Chain of Responsibility): the first rule that matches a line
 * watches it, else the general rule. Built once per settings version; lines cache their rule per version.
 * Cost: O(R x patterns) per line and settings version, nothing per value.
 */
public final class RuleBook {

    private record Compiled(Rule rule, Matcher matcher) {
    }

    private final List<Compiled> rules;
    private final Rule general;
    private final long version;

    public RuleBook(DetectorSettings settings, long version) {
        this.version = version;
        List<Compiled> list = new ArrayList<>();
        for (int i = 0; i < settings.rules().size(); i++) {
            RuleSettings r = settings.rules().get(i);
            list.add(new Compiled(compile("rules[" + i + "] " + r.signal(), r), new Matcher(r.match())));
        }
        this.rules = List.copyOf(list);
        GeneralSettings g = settings.general();
        this.general = new Rule("general", "general",
                new Measure.Wobble(g.madFactor(), g.atLeastFactor(), g.minChange(), up(g.direction()), down(g.direction())),
                g.steps(), true);
    }

    public long version() {
        return version;
    }

    public Rule ruleFor(LineFacts line) {
        for (Compiled c : rules) {
            if (c.matcher().matches(line)) {
                return c.rule();
            }
        }
        return general;
    }

    private static Rule compile(String name, RuleSettings r) {
        double minChange = r.minChange() == null ? 0 : r.minChange();
        double mad = r.madFactor() == null ? 0 : r.madFactor();
        Measure measure = r.factor() != null
                ? new Measure.Factor(r.factor(), minChange, up(r.direction()), down(r.direction()), mad)
                : new Measure.Points(r.points(), up(r.direction()), down(r.direction()), mad);
        return new Rule(name, r.signal(), measure, r.steps(), mad > 0);
    }

    private static boolean up(String direction) {
        return "up".equals(direction) || "both".equals(direction);
    }

    private static boolean down(String direction) {
        return "down".equals(direction) || "both".equals(direction);
    }

    /** One rule's patterns: any listed pattern matching is enough. */
    static final class Matcher {
        private final List<Glob> kind;
        private final List<Glob> unit;
        private final List<Glob> metric;
        private final List<Glob> title;
        private final List<Glob> query;
        private final List<TagPattern> tags;

        Matcher(MatchSettings m) {
            kind = m.kind().stream().map(Glob::of).toList();
            unit = m.unit().stream().map(Glob::of).toList();
            metric = m.metric().stream().map(Glob::of).toList();
            title = m.title().stream().map(Glob::of).toList();
            query = m.query().stream().map(Glob::of).toList();
            tags = m.tags().stream().map(TagPattern::of).toList();
        }

        boolean matches(LineFacts line) {
            if (any(kind, line.kind()) || any(unit, line.unit()) || any(metric, line.metric()) || any(query, line.query())) {
                return true;
            }
            for (View v : line.views()) {
                if (any(title, v.getPanelTitle())) {
                    return true;
                }
            }
            for (TagPattern t : tags) {
                if (t.matches(line.tags())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean any(List<Glob> patterns, String value) {
            for (Glob g : patterns) {
                if (g.matches(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code key=pattern} or {@code key!=pattern}; the line must have the tag either way. */
    record TagPattern(String key, Glob value, boolean negated) {
        static TagPattern of(String text) {
            int ne = text.indexOf("!=");
            if (ne > 0) {
                return new TagPattern(text.substring(0, ne).trim().toLowerCase(Locale.ROOT), Glob.of(text.substring(ne + 2).trim()), true);
            }
            int eq = text.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("tag pattern must be key=pattern or key!=pattern: " + text);
            }
            return new TagPattern(text.substring(0, eq).trim().toLowerCase(Locale.ROOT), Glob.of(text.substring(eq + 1).trim()), false);
        }

        boolean matches(Map<String, String> lineTags) {
            for (Map.Entry<String, String> e : lineTags.entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(key)) {
                    return negated != value.matches(e.getValue());
                }
            }
            return false;
        }
    }
}
