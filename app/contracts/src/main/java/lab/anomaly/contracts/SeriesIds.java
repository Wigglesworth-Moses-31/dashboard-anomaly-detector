package lab.anomaly.contracts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import lab.anomaly.contracts.v1.SeriesDescriptor;

/**
 * Stable ids for metric lines and queries (design C3).
 *
 * <p>series id = first 8 bytes (16 hex characters) of SHA-256 over a canonical form: source system, source
 * instance, query id, then every tag name and value with tags sorted by name. Each piece is written as
 * {@code <UTF-8 byte length>:<UTF-8 bytes>}, which makes the form unambiguous even when a value contains
 * separators. The definition uses only standard building blocks, so any language can reproduce it.
 *
 * <p>Cost: sorting k tags O(k log k), hashing O(total length). Collision chance for n lines ≈ n²/2⁶⁵.
 */
public final class SeriesIds {

    private static final int ID_BYTES = 8;
    private static final HexFormat HEX = HexFormat.of();

    /** The id of a metric line. Tag order does not matter. */
    public static String seriesId(String system, String instance, String queryId, Map<String, String> tags) {
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        piece(canonical, system);
        piece(canonical, instance);
        piece(canonical, queryId);
        // TreeMap sorts by tag name, so any input order gives the same canonical form
        for (Map.Entry<String, String> tag : new TreeMap<>(tags == null ? Map.of() : tags).entrySet()) {
            piece(canonical, tag.getKey());
            piece(canonical, tag.getValue());
        }
        return hash(canonical.toByteArray());
    }

    /** The id the descriptor's own fields produce; ContractChecks compares it with {@code series_id}. */
    public static String seriesId(SeriesDescriptor descriptor) {
        return seriesId(descriptor.getSource().getSystem(), descriptor.getSource().getInstance(),
                descriptor.getQueryId(), descriptor.getGroupTagsMap());
    }

    /** The id of a query: the same hash over the query text with spaces collapsed and trimmed. */
    public static String queryId(String queryText) {
        String normalised = queryText == null ? "" : queryText.strip().replaceAll("\\s+", " ");
        return hash(normalised.getBytes(StandardCharsets.UTF_8));
    }

    /** True for 16 lowercase hex characters. */
    public static boolean isId(String value) {
        if (value == null || value.length() != 2 * ID_BYTES) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static void piece(ByteArrayOutputStream out, String text) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        out.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        out.write(':');
        out.writeBytes(bytes);
    }

    private static String hash(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            return HEX.formatHex(Arrays.copyOf(digest, ID_BYTES));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }

    private SeriesIds() {
    }
}
