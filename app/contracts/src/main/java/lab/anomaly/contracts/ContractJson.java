package lab.anomaly.contracts;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

/**
 * JSON for the web page (design C8), made from the Protobuf message itself so it can never drift from the
 * contract. Field names stay exactly as in the .proto files ({@code series_id}, not {@code seriesId}).
 * Map keys (such as {@code group_tags}) are always sorted, so the same message always gives the same JSON:
 * Protobuf otherwise keeps insertion order, which can differ from run to run.
 */
public final class ContractJson {

    private static final JsonFormat.Printer COMPACT =
            JsonFormat.printer().preservingProtoFieldNames().sortingMapKeys().omittingInsignificantWhitespace();
    private static final JsonFormat.Printer PRETTY = JsonFormat.printer().preservingProtoFieldNames().sortingMapKeys();
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();   // tolerant reader

    /** One line of JSON, for sending. */
    public static String toJson(MessageOrBuilder message) {
        return print(COMPACT, message);
    }

    /** Indented JSON, for documentation and logs. */
    public static String toPrettyJson(MessageOrBuilder message) {
        return print(PRETTY, message);
    }

    /** Reads JSON into a message builder, ignoring fields the reader does not know. */
    public static <B extends Message.Builder> B fromJson(String json, B builder) {
        try {
            PARSER.merge(json, builder);
            return builder;
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Not valid JSON for " + builder.getDescriptorForType().getFullName(), e);
        }
    }

    private static String print(JsonFormat.Printer printer, MessageOrBuilder message) {
        try {
            return printer.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Cannot print " + message.getDescriptorForType().getFullName(), e);
        }
    }

    private ContractJson() {
    }
}
