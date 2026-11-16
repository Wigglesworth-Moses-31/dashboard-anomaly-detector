package lab.anomaly.reporter.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes a file so a crash leaves either the old or the new content, never half of it: temp file, flush, rename. */
final class AtomicFiles {

    static void write(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);                                           // on disk before the rename
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private AtomicFiles() {
    }
}
