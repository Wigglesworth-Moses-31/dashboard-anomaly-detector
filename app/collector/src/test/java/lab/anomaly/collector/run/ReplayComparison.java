package lab.anomaly.collector.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.emit.Emitter;
import lab.anomaly.collector.emit.MessageSink;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.recording.ReplaySource;
import lab.anomaly.collector.translate.AnswerTranslator;
import lab.anomaly.collector.translate.Classifier;
import lab.anomaly.contracts.ContractJson;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays a recording through the Collector's own steps and compares, per metric line, with what the live run
 * published (published.jsonl). Lines are compared per series because live polls of different panels run in
 * parallel, so only the order within one line is fixed.
 */
final class ReplayComparison {

    record Result(int liveMessages, int replayMessages, int linesCompared, List<String> differences) {}

    static CollectorSettings settings(Path collectorYml) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("collector.yml", new FileSystemResource(collectorYml))
                .forEach(source -> environment.getPropertySources().addFirst(source));
        return Binder.get(environment).bind("anomaly.collector", Bindable.of(CollectorSettings.class)).get();
    }

    static Result compare(Path recordingDir, Path publishedFile, CollectorSettings s) throws Exception {
        ReplaySource replay = new ReplaySource(recordingDir);
        List<String> replayed = new ArrayList<>();
        MessageSink sink = new MessageSink() {
            // The live run of 15 Nov predates View.panel_path (add-incident-history): compare without it, so any
            // other difference still fails
            @Override public void send(SeriesDescriptor d) {
                SeriesDescriptor.Builder b = d.toBuilder();
                b.getViewsBuilderList().forEach(v -> v.clearPanelPath().clearGroupIndex().clearPanelIndex());   // + add-dashboard-view D1
                replayed.add("SeriesDescriptor\t" + ContractJson.toJson(b.build()));
            }
            @Override public void send(MetricPoints p) { replayed.add("MetricPoints\t" + ContractJson.toJson(p)); }
        };
        CollectorEngine.ReplayClock clock = new CollectorEngine.ReplayClock();
        PollProcessor processor = new PollProcessor(new AnswerTranslator("datadog", s.datadogSite()),
                new Classifier(replay, s.metadataCacheSize(), s.metadataTtl().toMillis(), clock),
                new Emitter(sink, (int) (s.backfill().toMillis() / s.step().toMillis()) + 6), s::classification);
        Map<String, PollUnit> plan = new HashMap<>();
        new Discovery(replay).plan(replay.dashboardIds(), s.dashboardFilters(), s.step()).forEach(u -> plan.put(u.key(), u));
        for (ReplaySource.Recorded recorded : replay.timeseriesInOrder()) {
            PollUnit unit = plan.get(recorded.request().canonicalQuestion());
            if (unit != null && recorded.answer() != null) {
                clock.set(recorded.atMs());
                processor.process(unit, recorded.answer(), s.step());
            }
        }
        List<String> live = Files.readAllLines(publishedFile);
        Map<String, List<String>> liveBySeries = bySeries(live);
        Map<String, List<String>> replayBySeries = bySeries(replayed);
        List<String> differences = new ArrayList<>();
        for (var entry : liveBySeries.entrySet()) {
            List<String> mine = replayBySeries.getOrDefault(entry.getKey(), List.of());
            List<String> theirs = entry.getValue();
            // The live run may have been stopped after recording an answer but before publishing it: the live
            // sequence must then be a prefix of the replayed one
            if (mine.size() < theirs.size() || !mine.subList(0, theirs.size()).equals(theirs)) {
                differences.add(entry.getKey() + ": live " + theirs.size() + " messages, replay " + mine.size()
                        + (mine.size() >= theirs.size() ? " (content differs)" : " (replay has fewer)"));
            }
        }
        return new Result(live.size(), replayed.size(), liveBySeries.size(), differences);
    }

    private static Map<String, List<String>> bySeries(List<String> lines) {
        JsonMapper json = JsonMapper.builder().build();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String line : lines) {
            String payload = line.substring(line.indexOf('\t') + 1);
            String id = json.readTree(payload).path("series_id").asString();
            out.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
        }
        return out;
    }

    private ReplayComparison() {
    }
}
