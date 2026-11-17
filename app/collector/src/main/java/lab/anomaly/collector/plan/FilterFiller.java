package lab.anomaly.collector.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lab.anomaly.collector.source.Dashboard.TemplateVariable;

/**
 * Fills a dashboard's placeholders such as {@code $env} into a query (add-collector design K4).
 *
 * <p>Works item by item inside each filter {@code {...}} (group-by lists after {@code by} are left alone):
 * an item that is just {@code $name} or {@code $name.value} becomes {@code prefix:value}, or is dropped when the
 * value is {@code *}; a placeholder inside a longer item (boolean filters) becomes {@code prefix:value}.
 * An empty filter becomes {@code *}. Plain text replacement would leave stray commas.
 */
public final class FilterFiller {

    private static final Pattern BRACES = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$([A-Za-z0-9_\\-]+)(?:\\.value)?");

    /** Value per placeholder: from settings first, else the dashboard's default. */
    public static Map<String, String> values(List<TemplateVariable> variables, Map<String, String> fromSettings) {
        Map<String, String> values = new java.util.HashMap<>();
        for (TemplateVariable v : variables) {
            values.put(v.name(), fromSettings.getOrDefault(v.name(), v.defaultValue() == null || v.defaultValue().isBlank() ? "*" : v.defaultValue()));
        }
        return values;
    }

    /**
     * Fills with several values per variable (add-dashboard-json design J11). When no variable of a filter has more
     * than one value the result is exactly {@link #fill(String, List, Map)}'s, so existing questions and line ids stay
     * the same; otherwise that filter is written in Datadog's boolean form: {@code prefix IN (a,b)}, {@code NOT tag},
     * items joined with {@code AND}. O(length of the query).
     */
    public static String fillMulti(String query, List<TemplateVariable> variables, Map<String, List<String>> values) {
        Map<String, String> single = new java.util.HashMap<>();
        boolean multi = false;
        for (Map.Entry<String, List<String>> e : values.entrySet()) {
            List<String> v = e.getValue();
            boolean all = v == null || v.isEmpty() || v.contains("*");
            single.put(e.getKey(), all ? "*" : v.get(0));
            multi |= !all && v.size() > 1;
        }
        if (!multi) {
            return fill(query, variables, single);
        }
        Map<String, String> prefixes = new java.util.HashMap<>();
        variables.forEach(v -> prefixes.put(v.name(), v.prefix() == null || v.prefix().isBlank() ? v.name() : v.prefix()));
        StringBuilder out = new StringBuilder();
        Matcher braces = BRACES.matcher(query);
        int last = 0;
        while (braces.find()) {
            out.append(query, last, braces.start());
            String before = query.substring(0, braces.start()).stripTrailing();
            boolean groupBy = before.endsWith(" by") || before.equals("by");
            String inner = braces.group(1);
            out.append('{').append(groupBy ? inner
                    : usesMulti(inner, prefixes, values) ? booleanItems(inner, prefixes, values) : fillItems(inner, prefixes, single)).append('}');
            last = braces.end();
        }
        out.append(query.substring(last));
        return out.toString();
    }

    private static boolean usesMulti(String filter, Map<String, String> prefixes, Map<String, List<String>> values) {
        Matcher m = PLACEHOLDER.matcher(filter);
        while (m.find()) {
            List<String> v = values.get(m.group(1));
            if (prefixes.containsKey(m.group(1)) && v != null && v.size() > 1 && !v.contains("*")) {
                return true;
            }
        }
        return false;
    }

    private static String term(String prefix, List<String> v) {
        return v.size() == 1 ? prefix + ":" + v.get(0) : prefix + " IN (" + String.join(",", v) + ")";
    }

    private static String booleanItems(String filter, Map<String, String> prefixes, Map<String, List<String>> values) {
        List<String> kept = new ArrayList<>();
        for (String raw : filter.split(",")) {
            String item = raw.strip();
            if (item.isEmpty()) {
                continue;
            }
            Matcher whole = PLACEHOLDER.matcher(item);
            if (whole.matches() && prefixes.containsKey(whole.group(1))) {
                List<String> v = values.getOrDefault(whole.group(1), List.of("*"));
                if (!v.isEmpty() && !v.contains("*")) {
                    kept.add(term(prefixes.get(whole.group(1)), v));
                }
                continue;
            }
            if (item.startsWith("!")) {
                kept.add("NOT " + item.substring(1));
                continue;
            }
            Matcher inside = PLACEHOLDER.matcher(item);
            StringBuilder replaced = new StringBuilder();
            while (inside.find()) {
                String name = inside.group(1);
                List<String> v = values.getOrDefault(name, List.of("*"));
                String replacement = prefixes.containsKey(name) && !v.contains("*") ? term(prefixes.get(name), v) : inside.group();
                inside.appendReplacement(replaced, Matcher.quoteReplacement(replacement));
            }
            inside.appendTail(replaced);
            kept.add(replaced.toString());
        }
        return kept.isEmpty() ? "*" : String.join(" AND ", kept);
    }

    public static String fill(String query, List<TemplateVariable> variables, Map<String, String> values) {
        Map<String, String> prefixes = new java.util.HashMap<>();
        variables.forEach(v -> prefixes.put(v.name(), v.prefix() == null || v.prefix().isBlank() ? v.name() : v.prefix()));
        StringBuilder out = new StringBuilder();
        Matcher braces = BRACES.matcher(query);
        int last = 0;
        while (braces.find()) {
            out.append(query, last, braces.start());
            String before = query.substring(0, braces.start()).stripTrailing();
            boolean groupBy = before.endsWith(" by") || before.equals("by");
            out.append('{').append(groupBy ? braces.group(1) : fillItems(braces.group(1), prefixes, values)).append('}');
            last = braces.end();
        }
        out.append(query.substring(last));
        return out.toString();
    }

    private static String fillItems(String filter, Map<String, String> prefixes, Map<String, String> values) {
        List<String> kept = new ArrayList<>();
        for (String raw : filter.split(",")) {
            String item = raw.strip();
            if (item.isEmpty()) {
                continue;
            }
            Matcher whole = PLACEHOLDER.matcher(item);
            if (whole.matches() && prefixes.containsKey(whole.group(1))) {
                String value = values.getOrDefault(whole.group(1), "*");
                if (!"*".equals(value)) {
                    kept.add(prefixes.get(whole.group(1)) + ":" + value);
                }
                continue;
            }
            // A placeholder inside a longer item (e.g. "$env AND service:x"): replace it in place
            Matcher inside = PLACEHOLDER.matcher(item);
            StringBuilder replaced = new StringBuilder();
            while (inside.find()) {
                String name = inside.group(1);
                String replacement = prefixes.containsKey(name) ? prefixes.get(name) + ":" + values.getOrDefault(name, "*") : inside.group();
                inside.appendReplacement(replaced, Matcher.quoteReplacement(replacement));
            }
            inside.appendTail(replaced);
            kept.add(replaced.toString());
        }
        return kept.isEmpty() ? "*" : String.join(",", kept);
    }

    private FilterFiller() {
    }
}
