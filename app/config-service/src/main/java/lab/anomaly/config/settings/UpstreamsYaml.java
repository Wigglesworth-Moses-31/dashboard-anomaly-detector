package lab.anomaly.config.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab.anomaly.contracts.Regions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code upstreams.yml} (upstream-callers U1): which APIs call which upstream service, per region. Checked here so a
 * wrong shape is refused before it is committed. Shape:
 * <pre>
 * caller-tag: api                       # the tag whose value names an API (default api)
 * regions:
 *   us:
 *     billing-core: [/v1/invoices, /v1/payments]
 * </pre>
 */
public final class UpstreamsYaml {

    public record Parsed(String callerTag, Map<String, Map<String, List<String>>> regions) {}

    static final int MAX_CHARS = 200_000;
    private static final Set<String> KEYS = Set.of("caller-tag", "regions");

    /** Parses and checks; a wrong shape throws IllegalArgumentException with the reason. A missing file is empty. */
    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return new Parsed("api", Map.of());
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException("larger than 200 kB");
        }
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            throw new IllegalArgumentException("not valid YAML: " + m.lines().findFirst().orElse(m));
        }
        if (root == null) {
            return new Parsed("api", Map.of());
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("the file must have caller-tag and regions");
        }
        for (Object k : map.keySet()) {
            if (!KEYS.contains(String.valueOf(k))) {
                throw new IllegalArgumentException("unknown key '" + k + "' (only caller-tag and regions)");
            }
        }
        Object tag = map.get("caller-tag");
        if (tag != null && (!(tag instanceof String s) || s.isBlank())) {
            throw new IllegalArgumentException("caller-tag must be a tag name, for example api");
        }
        Map<String, Map<String, List<String>>> regions = new LinkedHashMap<>();
        Object rs = map.get("regions");
        if (rs != null && !(rs instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("regions must list us and/or eu");
        }
        if (rs instanceof Map<?, ?> rmap) {
            for (Map.Entry<?, ?> r : rmap.entrySet()) {
                String region = String.valueOf(r.getKey());
                if (Regions.of(region).isEmpty()) {
                    throw new IllegalArgumentException("regions must be us or eu (found '" + region + "')");
                }
                Map<String, List<String>> ups = new LinkedHashMap<>();
                if (r.getValue() != null && !(r.getValue() instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(region + ": list each upstream with its APIs, for example billing-core: [/v1/invoices]");
                }
                if (r.getValue() instanceof Map<?, ?> umap) {
                    for (Map.Entry<?, ?> u : umap.entrySet()) {
                        String upstream = String.valueOf(u.getKey()).strip();
                        if (upstream.isEmpty()) {
                            throw new IllegalArgumentException(region + ": an upstream has no name");
                        }
                        if (u.getValue() != null && !(u.getValue() instanceof List<?>)) {
                            throw new IllegalArgumentException(region + "." + upstream + " must be a list of APIs, for example [/v1/invoices]");
                        }
                        List<String> apis = u.getValue() == null ? List.of()
                                : ((List<?>) u.getValue()).stream().map(String::valueOf).map(String::strip).filter(x -> !x.isEmpty()).distinct().toList();
                        ups.put(upstream, apis);
                    }
                }
                regions.put(Regions.of(region).orElseThrow().region(), ups);
            }
        }
        return new Parsed(tag == null ? "api" : ((String) tag).strip(), regions);
    }

    /** {@code anomaly.detector.upstream-tags} from detector.yml, else the shipped pair. */
    @SuppressWarnings("unchecked")
    public static List<String> upstreamTags(String detectorYml) {
        try {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(detectorYml == null ? "" : detectorYml);
            if (root instanceof Map<?, ?> m && m.get("anomaly") instanceof Map<?, ?> a && a.get("detector") instanceof Map<?, ?> d
                    && d.get("upstream-tags") instanceof List<?> l && !l.isEmpty()) {
                return l.stream().map(String::valueOf).toList();
            }
        } catch (RuntimeException e) {
            // fall through: a broken detector.yml is the Detector's to report
        }
        return List.of("upstream_service", "peer.rpc.service");
    }

    private UpstreamsYaml() {
    }
}
