package lab.anomaly.reporter.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The report's last line: {@code <!-- report complete: 37 incidents, sha256 <hash> -->}, the hash of everything above
 * it (design R2). A report is verified only when read back with this line present and the hash equal: a truncated or
 * changed file is never taken as written.
 */
public final class Completion {

    private static final Pattern LINE = Pattern.compile("\n<!-- report complete: (\\d+) incidents, sha256 ([0-9a-f]{64}) -->\n$");

    public static String seal(String body, int incidents) {
        return body + "\n<!-- report complete: " + incidents + " incidents, sha256 " + sha256(body) + " -->\n";
    }

    /** The number of incidents when the text is complete and unchanged; -1 otherwise. */
    public static int verify(String text) {
        Matcher m = LINE.matcher(text);
        if (!m.find()) {
            return -1;
        }
        String body = text.substring(0, m.start());
        return sha256(body).equals(m.group(2)) ? Integer.parseInt(m.group(1)) : -1;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Completion() {
    }
}
