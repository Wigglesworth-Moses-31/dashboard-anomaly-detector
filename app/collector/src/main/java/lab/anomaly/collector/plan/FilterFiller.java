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
