package lab.anomaly.collector.run;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import lab.anomaly.collector.CollectorSettings.ClassificationRule;
import lab.anomaly.collector.emit.Emitter;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.translate.AnswerTranslator;
import lab.anomaly.collector.translate.Classifier;
import lab.anomaly.collector.translate.TranslatedLine;

/**
 * One answer → messages: translate, classify, emit. Shared by the live engine and replay, so a replay produces
 * exactly what the live run produced (spec: record and replay).
 */
public final class PollProcessor {

    private final AnswerTranslator translator;
    private final Classifier classifier;
    private final Emitter emitter;
    private final Supplier<List<ClassificationRule>> rules;

    public PollProcessor(AnswerTranslator translator, Classifier classifier, Emitter emitter, Supplier<List<ClassificationRule>> rules) {
        this.translator = translator;
        this.classifier = classifier;
        this.emitter = emitter;
        this.rules = rules;
    }

    /** Returns the number of lines in the answer. */
    public int process(PollUnit unit, TimeseriesAnswer answer, Duration step) {
        List<TranslatedLine> lines = translator.translate(unit, answer, step);
        for (TranslatedLine line : lines) {
            emitter.emit(classifier.classify(line, rules.get()), line.points(), line.collectedMs());
        }
        return lines.size();
    }

    /** As {@link #process}, for a backfilled window: the points go out marked as backfill (add-backfill B3). */
    public int backfill(PollUnit unit, TimeseriesAnswer answer, Duration step) {
        List<TranslatedLine> lines = translator.translate(unit, answer, step);
        for (TranslatedLine line : lines) {
            emitter.emitBackfill(classifier.classify(line, rules.get()), line.points(), line.collectedMs());
        }
        return lines.size();
    }

    public Emitter emitter() {
        return emitter;
    }
}
