package lab.anomaly.collector.emit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lab.anomaly.contracts.ContractJson;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;

/** Decorator: passes messages on and appends each to {@code published.jsonl}, so a replay can be compared. */
public final class RecordingSink implements MessageSink {

    private final MessageSink inner;
    private final Path file;

    public RecordingSink(MessageSink inner, Path dir) {
        this.inner = inner;
        this.file = dir.resolve("published.jsonl");
    }

    @Override
    public void send(SeriesDescriptor description) {
        write("SeriesDescriptor\t" + ContractJson.toJson(description));
        inner.send(description);
    }

    @Override
    public void send(MetricPoints points) {
        write("MetricPoints\t" + ContractJson.toJson(points));
        inner.send(points);
    }

    private synchronized void write(String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
