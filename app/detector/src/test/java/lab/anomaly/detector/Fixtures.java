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
import org.springframework.core.io.FileSystemResource;

/** Test helpers: the real default settings from config-repo, the 226 recorded lines, and made-up lines. */
public final class Fixtures {

    public static final Path DETECTOR_YML = Path.of("../../config-repo/detector.yml");

    /** Loads config-repo/detector.yml exactly as the service would, with its validators. */
    public static SettingsHolder<DetectorSettings> holder(Map<String, Object> overrides) {
        try {
            StandardEnvironment env = new StandardEnvironment();
            new YamlPropertySourceLoader().load("detector.yml", new FileSystemResource(DETECTOR_YML))
                    .forEach(env.getPropertySources()::addLast);
            if (!overrides.isEmpty()) {
                env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", overrides));
            }
            return new SettingsHolder<>("anomaly.detector", DetectorSettings.class, DetectorSettings.validators(), env);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Loads config-repo/detector.yml after editing its text, as an operator would. */
    public static SettingsHolder<DetectorSettings> holderEdited(java.util.function.UnaryOperator<String> edit) {
        try {
            String text = edit.apply(java.nio.file.Files.readString(DETECTOR_YML));
            StandardEnvironment env = new StandardEnvironment();
            new YamlPropertySourceLoader().load("detector.yml",
                            new org.springframework.core.io.ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)))
                    .forEach(env.getPropertySources()::addLast);
            return new SettingsHolder<>("anomaly.detector", DetectorSettings.class, DetectorSettings.validators(), env);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static DetectorSettings defaults() {
        return holder(Map.of()).get();
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
