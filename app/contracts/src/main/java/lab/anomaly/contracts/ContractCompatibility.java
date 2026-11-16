package lab.anomaly.contracts;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab.anomaly.contracts.v1.IncidentProto;
import lab.anomaly.contracts.v1.MetricPointsProto;
import lab.anomaly.contracts.v1.MetricSeriesProto;
import lab.anomaly.contracts.v1.StoreServiceProto;

/**
 * The compatibility guard (design C6). Compares the contracts with a saved baseline and lists every change
 * that would make an older reader misread a newer message, or a newer reader misread an older one.
 *
 * <p>Not allowed: a field number with a different name, type or repeat-ness; a removed field whose number is
 * not reserved; an enum value number with a different name, or removed without being reserved; a removed
 * message, enum, service or method; a method with different message types or streaming.
 * Allowed: anything new.
 *
 * <p>Cost: every element is indexed once in hash maps, so the check is O(number of fields).
 */
public final class ContractCompatibility {

    /** The contracts as compiled into this module. */
    public static FileDescriptorSet current() {
        Set<FileDescriptor> files = new LinkedHashSet<>();
        for (FileDescriptor root : List.of(MetricSeriesProto.getDescriptor(), MetricPointsProto.getDescriptor(),
                IncidentProto.getDescriptor(), StoreServiceProto.getDescriptor())) {
            collect(root, files);
        }
        FileDescriptorSet.Builder set = FileDescriptorSet.newBuilder();
        files.stream().filter(f -> f.getPackage().startsWith("lab.anomaly")).forEach(f -> set.addFile(f.toProto()));
        return set.build();
    }

    /** Every incompatible difference between the baseline and the current contracts; empty means safe. */
    public static List<String> problems(FileDescriptorSet baseline, FileDescriptorSet current) {
        Index before = new Index(baseline);
        Index after = new Index(current);
        List<String> problems = new ArrayList<>();
        before.messages.forEach((name, old) -> {
            DescriptorProto now = after.messages.get(name);
            if (now == null) {
                problems.add(name + ": message removed");
            } else {
                compareFields(name, old, now, problems);
            }
        });
        before.enums.forEach((name, old) -> {
            EnumDescriptorProto now = after.enums.get(name);
            if (now == null) {
                problems.add(name + ": enum removed");
            } else {
                compareEnumValues(name, old, now, problems);
            }
        });
        before.methods.forEach((name, old) -> {
            MethodDescriptorProto now = after.methods.get(name);
            if (now == null) {
                problems.add(name + ": method removed");
            } else if (!old.getInputType().equals(now.getInputType()) || !old.getOutputType().equals(now.getOutputType())
                    || old.getClientStreaming() != now.getClientStreaming()
                    || old.getServerStreaming() != now.getServerStreaming()) {
                problems.add(name + ": request, reply or streaming changed");
            }
        });
        return problems;
    }

    private static void compareFields(String message, DescriptorProto old, DescriptorProto now, List<String> problems) {
        Map<Integer, FieldDescriptorProto> byNumber = new HashMap<>();
        now.getFieldList().forEach(f -> byNumber.put(f.getNumber(), f));
        for (FieldDescriptorProto field : old.getFieldList()) {
            String where = message + " field " + field.getNumber() + " (" + field.getName() + ")";
            FieldDescriptorProto match = byNumber.get(field.getNumber());
            if (match == null) {
                boolean reserved = now.getReservedRangeList().stream()
                        .anyMatch(r -> field.getNumber() >= r.getStart() && field.getNumber() < r.getEnd());
                if (!reserved) {
                    problems.add(where + ": removed without reserving its number");
                }
                continue;
            }
            if (!match.getName().equals(field.getName())) {
                problems.add(where + ": renamed to " + match.getName());
            }
            if (match.getType() != field.getType() || !match.getTypeName().equals(field.getTypeName())) {
                problems.add(where + ": type changed from " + typeOf(field) + " to " + typeOf(match));
            }
            if (match.getLabel() != field.getLabel()) {
                problems.add(where + ": changed between single and repeated");
            }
        }
    }

    private static void compareEnumValues(String name, EnumDescriptorProto old, EnumDescriptorProto now, List<String> problems) {
        Map<Integer, EnumValueDescriptorProto> byNumber = new HashMap<>();
        now.getValueList().forEach(v -> byNumber.put(v.getNumber(), v));
        for (EnumValueDescriptorProto value : old.getValueList()) {
            EnumValueDescriptorProto match = byNumber.get(value.getNumber());
            String where = name + " value " + value.getNumber() + " (" + value.getName() + ")";
            if (match == null) {
                boolean reserved = now.getReservedRangeList().stream()
                        .anyMatch(r -> value.getNumber() >= r.getStart() && value.getNumber() <= r.getEnd());
                if (!reserved) {
                    problems.add(where + ": removed without reserving its number");
                }
            } else if (!match.getName().equals(value.getName())) {
                problems.add(where + ": renamed to " + match.getName());
            }
        }
    }

    private static String typeOf(FieldDescriptorProto field) {
        return field.getTypeName().isEmpty() ? field.getType().name() : field.getTypeName();
    }

    private static void collect(FileDescriptor file, Set<FileDescriptor> into) {
        if (into.add(file)) {
            file.getDependencies().forEach(dependency -> collect(dependency, into));
        }
    }

    /** Every message (also nested), enum and method by full name, for O(1) lookups. */
    private static final class Index {
        final Map<String, DescriptorProto> messages = new HashMap<>();
        final Map<String, EnumDescriptorProto> enums = new HashMap<>();
        final Map<String, MethodDescriptorProto> methods = new HashMap<>();

        Index(FileDescriptorSet set) {
            for (FileDescriptorProto file : set.getFileList()) {
                String prefix = file.getPackage().isEmpty() ? "" : file.getPackage() + ".";
                file.getMessageTypeList().forEach(m -> addMessage(prefix + m.getName(), m));
                file.getEnumTypeList().forEach(e -> enums.put(prefix + e.getName(), e));
                for (ServiceDescriptorProto service : file.getServiceList()) {
                    service.getMethodList().forEach(m -> methods.put(prefix + service.getName() + "." + m.getName(), m));
                }
            }
        }

        private void addMessage(String name, DescriptorProto message) {
            messages.put(name, message);
            message.getNestedTypeList().forEach(nested -> addMessage(name + "." + nested.getName(), nested));
            message.getEnumTypeList().forEach(e -> enums.put(name + "." + e.getName(), e));
        }
    }

    private ContractCompatibility() {
    }
}
