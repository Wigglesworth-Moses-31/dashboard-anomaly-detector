package lab.anomaly.detector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.ContractJson;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import lab.anomaly.contracts.v1.View;
import lab.anomaly.kit.settings.SettingsHolder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;

/** Test helpers: the default settings that ship (config-template), the 226 recorded lines, and made-up lines. */
public final class Fixtures {

    public static final Path DETECTOR_YML = Path.of("../../config-template/detector.yml");   // what ships, not this machine's settings

    /** Loads config-template/detector.yml exactly as the service would, with its validators. */
    public static SettingsHolder<DetectorSettings> holder(Map<String, Object> overrides) {
        return load(template(), overrides);
    }

    /** Loads config-template/detector.yml after editing its text, as an operator would, judged every step. */
    public static SettingsHolder<DetectorSettings> holderEdited(java.util.function.UnaryOperator<String> edit) {
        return load(edit.apply(everyStepText(template())), Map.of());
    }

    /** Loads config-template/detector.yml as it ships, after editing its text. */
    public static SettingsHolder<DetectorSettings> shippedEdited(java.util.function.UnaryOperator<String> edit) {
        return load(edit.apply(template()), Map.of());
    }

    /**
     * The shipped settings judged every step, with the step counts from before calmer-anomalies, so the recorded
     * scenarios keep their meaning. {@link #shipped()} is the template as it ships (5-minute averages).
     */
    public static DetectorSettings defaults() {
        return load(everyStepText(template()), Map.of()).get();
    }

    /** config-template/detector.yml exactly as it ships. */
    public static DetectorSettings shipped() {
        return holder(Map.of()).get();
    }

    /** As {@link #defaults()} with the older grouping by service tag (incident-scope: service). */
    public static DetectorSettings serviceScope() {
        return load(everyStepText(template()), Map.of("anomaly.detector.incident-scope", "service")).get();
    }

    /**
     * The template's text with every rule back to every step and its old step count. Lists cannot be overridden
     * item by item (a property source with rules[i] replaces the whole list), so the text is edited instead.
     */
    static String everyStepText(String text) {
        int[] oldSteps = {3, 3, 2, 2, 2, 2, 3, 2};           // success-rate, error-rate, errors, latency x3, saturation, traffic
        StringBuilder out = new StringBuilder();
        int rule = -1;
        boolean general = false;
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("- signal:") && line.startsWith("      - ")) {
                rule++;
            }
            if (t.startsWith("general:")) {
                general = true;
            }
            String indent = line.substring(0, line.length() - line.stripLeading().length());
            if (t.startsWith("judge-every:")) {
                line = indent + "judge-every: 10s";
            } else if (t.startsWith("steps:") && (rule >= 0 || general)) {
                line = indent + "steps: " + (general ? 3 : oldSteps[rule]);
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String template() {
        try {
            return java.nio.file.Files.readString(DETECTOR_YML);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SettingsHolder<DetectorSettings> load(String text, Map<String, Object> overrides) {
        try {
            StandardEnvironment env = new StandardEnvironment();
            new YamlPropertySourceLoader().load("detector.yml",
                            new org.springframework.core.io.ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)))
                    .forEach(env.getPropertySources()::addLast);
            if (!overrides.isEmpty()) {
                env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", overrides));
            }
            return new SettingsHolder<>("anomaly.detector", DetectorSettings.class, DetectorSettings.validators(), env);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<SeriesDescriptor> recordedLines() {
        try (InputStream in = Fixtures.class.getResourceAsStream("/lines-226.jsonl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank())
                    .map(l -> ContractJson.fromJson(l, SeriesDescriptor.newBuilder()).build()).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A made-up line: id must be 16 hex characters. */
    public static SeriesDescriptor line(String id, String metric, Kind kind, String unit, Map<String, String> tags,
                                        String dashboardId, String dashboardTitle, String panel) {
        return SeriesDescriptor.newBuilder().setSeriesId(id).setSource(Source.newBuilder().setSystem("datadog").setInstance("datadoghq.com"))
                .setQueryId(String.format("%016x", (long) (metric + "|" + panel).hashCode() & 0xffffffffL)).setQueryText("sum:" + metric + "{*}").setMetric(metric).setKind(kind).setUnit(unit)
                .putAllGroupTags(tags).setStepSeconds(10)
                .addViews(View.newBuilder().setViewId(dashboardId).setViewTitle(dashboardTitle).setPanelTitle(panel))
                .setFirstSeenMs(1).setUpdatedMs(1).build();
    }

    private Fixtures() {
    }
}
