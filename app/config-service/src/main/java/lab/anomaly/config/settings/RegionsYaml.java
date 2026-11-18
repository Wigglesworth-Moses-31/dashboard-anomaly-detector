package lab.anomaly.config.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lab.anomaly.contracts.Regions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * {@code regions.yml}: the regions and their dashboards (add-settings-page S2), as plain maps so settings the page does
 * not know (for example {@code max-concurrent-requests}) are kept. Pure: text in, text out; no files, no Git.
 */
public final class RegionsYaml {

    static final String HEADER = """
            # The Datadog regions to watch and their dashboards (add-settings-page). Written by the Settings tab of the live page,
            # one commit per change; hand edits work too (commit them), but comments in this file are not kept by the page.
            # Read together with collector.yml; this file wins. Keys are given by the NAME of the variable in .env, never by value.
            """;

    private final List<Map<String, Object>> sources;

    private RegionsYaml(List<Map<String, Object>> sources) {
        this.sources = sources;
    }

    public static RegionsYaml empty() {
        return new RegionsYaml(new ArrayList<>());
    }

    /** Reads {@code anomaly.collector.sources} from a YAML text (regions.yml or collector.yml); missing → empty. */
    @SuppressWarnings("unchecked")
    public static RegionsYaml parse(String text) {
        Object sources = sourcesOf(text);
        List<Map<String, Object>> list = new ArrayList<>();
        if (sources instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    list.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        return new RegionsYaml(list);
    }

    /** The raw {@code anomaly.collector.sources} value of a YAML text, or null. */
    static Object sourcesOf(String text) {
        Object c = collectorOf(text).get("sources");
        return c;
    }

    /** The {@code anomaly.collector} map of a YAML text (empty when missing). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> collectorOf(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (root instanceof Map<?, ?> r && r.get("anomaly") instanceof Map<?, ?> a && a.get("collector") instanceof Map<?, ?> c) {
            return (Map<String, Object>) c;
        }
        return Map.of();
    }

    public List<Map<String, Object>> sources() {
        return sources;
    }

    public Optional<Map<String, Object>> region(String name) {
        return sources.stream().filter(s -> name.equals(s.get("name"))).findFirst();
    }

    /** Adds US or EU with its fixed site and key names; refuses a third region or a duplicate. */
    public void addRegion(Regions r) {
        if (region(r.region()).isPresent()) {
            throw new SettingsConflict("region " + r.region() + " already exists");
        }
        if (sources.size() >= Regions.MAX) {
            throw new SettingsConflict("at most " + Regions.MAX + " regions");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.region());
        m.put("site", r.site());
        m.put("api-key-env", r.apiKeyName());
        m.put("app-key-env", r.appKeyName());
        m.put("dashboards", new ArrayList<>());
        sources.add(m);
    }

    /** Removes a region; answers its dashboard files (to remove with it). */
    public List<String> removeRegion(String name) {
        Map<String, Object> m = region(name).orElseThrow(() -> new SettingsConflict("no region " + name));
        List<String> files = dashboards(m).stream().map(d -> String.valueOf(d.get("file"))).toList();
        sources.remove(m);
        return files;
    }

    /** Adds or replaces the entry of {@code file} in a region (same file → replaced in place). */
    public void putDashboard(String regionName, Map<String, Object> entry) {
        Map<String, Object> m = region(regionName).orElseThrow(() -> new SettingsConflict("no region " + regionName));
        List<Map<String, Object>> list = dashboards(m);
        for (int i = 0; i < list.size(); i++) {
            if (entry.get("file").equals(list.get(i).get("file"))) {
                list.set(i, entry);
                m.put("dashboards", list);
                return;
            }
        }
        list.add(entry);
        m.put("dashboards", list);
    }

    public Optional<Map<String, Object>> dashboard(String regionName, String file) {
        return region(regionName).flatMap(m -> dashboards(m).stream().filter(d -> file.equals(d.get("file"))).findFirst());
    }

    public void removeDashboard(String regionName, String file) {
        Map<String, Object> m = region(regionName).orElseThrow(() -> new SettingsConflict("no region " + regionName));
        List<Map<String, Object>> list = dashboards(m);
        if (!list.removeIf(d -> file.equals(d.get("file")))) {
            throw new SettingsConflict("no dashboard " + file + " in region " + regionName);
        }
        m.put("dashboards", list);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> dashboards(Map<String, Object> region) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (region.get("dashboards") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> d) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) d));
                } else if (o instanceof String s) {
                    out.add(new LinkedHashMap<>(Map.of("file", s)));
                }
            }
        }
        return out;
    }

    /** The whole file: header, then {@code anomaly.collector.sources} in block style. */
    public String render() {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setIndent(2);
        o.setIndicatorIndent(2);
        o.setIndentWithIndicator(true);
        o.setWidth(160);
        Map<String, Object> collector = new LinkedHashMap<>();
        collector.put("sources", sources);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("anomaly", Map.of("collector", collector));
        return HEADER + new Yaml(new Representer(o), o).dump(root);
    }
}
