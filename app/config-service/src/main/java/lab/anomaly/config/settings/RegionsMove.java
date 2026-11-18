package lab.anomaly.config.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one-time move of {@code sources} from {@code collector.yml} to {@code regions.yml} (add-settings-page S2). The
 * block is cut as text, so every other line of {@code collector.yml}, comments included, stays as it was. The move is
 * used only when both files, parsed again, give exactly the same settings as before.
 */
public final class RegionsMove {

    private static final Pattern SOURCES = Pattern.compile("^( +)sources:\\s*(#.*)?$");

    public record Result(String collectorYml, String regionsYml) {}

    private RegionsMove() {}

    /** Empty when collector.yml has no sources; else the two new texts, already checked. */
    public static Optional<Result> of(String collectorYml) {
        if (RegionsYaml.sourcesOf(collectorYml) == null) {
            return Optional.empty();
        }
        List<String> lines = collectorYml.lines().toList();
        int start = -1;
        int indent = 0;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = SOURCES.matcher(lines.get(i));
            if (m.matches()) {
                start = i;
                indent = m.group(1).length();
                break;
            }
        }
        if (start < 0) {
            throw new SettingsConflict("collector.yml has regions written in a form the page cannot move; move 'sources:' to regions.yml by hand");
        }
        int end = start + 1;
        while (end < lines.size()) {
            String l = lines.get(end);
            String stripped = l.strip();
            int lead = l.length() - l.stripLeading().length();
            if (!stripped.isEmpty() && lead <= indent && !(stripped.startsWith("-") && lead == indent)) {
                break;                                                   // next setting at the same level
            }
            end++;
        }
        while (end > start + 1 && lines.get(end - 1).strip().isEmpty()) {
            end--;                                                       // blank lines stay in collector.yml
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i < start || i >= end) {
                kept.append(lines.get(i)).append('\n');
            }
        }
        String pad = " ".repeat(Math.max(0, 4 - indent));
        StringBuilder regions = new StringBuilder(RegionsYaml.HEADER).append("anomaly:\n  collector:\n");
        for (int i = start; i < end; i++) {
            String l = lines.get(i);
            regions.append(l.isBlank() ? "" : indent <= 4 ? pad + l : l.substring(indent - 4)).append('\n');
        }
        Result r = new Result(kept.toString(), regions.toString());
        check(collectorYml, r);
        return Optional.of(r);
    }

    /** Before and after must read the same: sources equal, and collector.yml unchanged apart from sources. */
    private static void check(String before, Result after) {
        Map<String, Object> old = new LinkedHashMap<>(RegionsYaml.collectorOf(before));
        Object oldSources = old.remove("sources");
        Map<String, Object> rest = RegionsYaml.collectorOf(after.collectorYml());
        Object newSources = RegionsYaml.sourcesOf(after.regionsYml());
        if (!oldSources.equals(newSources) || rest.containsKey("sources") || !old.equals(rest)) {
            throw new SettingsConflict("the regions could not be moved from collector.yml without changing them;"
                    + " move 'sources:' to regions.yml by hand");
        }
    }
}
