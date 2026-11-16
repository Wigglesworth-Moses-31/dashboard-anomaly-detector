package lab.anomaly.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The build's compatibility guard (design C6, spec "Messages can grow without breaking readers").
 * After an accepted contract change, refresh the baseline on purpose:
 * {@code mvn -pl contracts test -Dcontracts.updateBaseline=true}
 */
class ContractCompatibilityTest {

    private static final Path BASELINE = Path.of("src/test/resources/contracts-baseline.binpb");

    @Test
    void contractsStayCompatibleWithTheBaseline() throws Exception {
        FileDescriptorSet current = ContractCompatibility.current();
        if (Boolean.getBoolean("contracts.updateBaseline")) {
            Files.createDirectories(BASELINE.getParent());
            Files.write(BASELINE, current.toByteArray());
        }
        assertThat(BASELINE).as("baseline file; create it with -Dcontracts.updateBaseline=true").exists();
        FileDescriptorSet baseline = FileDescriptorSet.parseFrom(Files.readAllBytes(BASELINE));

        assertThat(ContractCompatibility.problems(baseline, current))
                .as("contract changes that would break existing readers").isEmpty();
    }

    // ---- the guard itself, tested on edited copies of the current contracts (task 3.2) -----------

    private static FileDescriptorSet editMetricPoints(UnaryOperator<DescriptorProto> edit) {
        FileDescriptorSet.Builder set = ContractCompatibility.current().toBuilder();
        for (int f = 0; f < set.getFileCount(); f++) {
            FileDescriptorProto.Builder file = set.getFileBuilder(f);
            for (int m = 0; m < file.getMessageTypeCount(); m++) {
                if (file.getMessageType(m).getName().equals("MetricPoints")) {
                    file.setMessageType(m, edit.apply(file.getMessageType(m)));
                }
            }
        }
        return set.build();
    }

    private static DescriptorProto withField(DescriptorProto message, String name, UnaryOperator<FieldDescriptorProto> edit) {
        DescriptorProto.Builder builder = message.toBuilder();
        for (int i = 0; i < builder.getFieldCount(); i++) {
            if (builder.getField(i).getName().equals(name)) {
                builder.setField(i, edit.apply(builder.getField(i)));
            }
        }
        return builder.build();
    }

    @Test
    void renumberingAFieldIsCaughtAndNamed() {
        FileDescriptorSet edited = editMetricPoints(m -> withField(m, "collected_ms", f -> f.toBuilder().setNumber(9).build()));

        assertThat(ContractCompatibility.problems(ContractCompatibility.current(), edited))
                .containsExactly("lab.anomaly.v1.MetricPoints field 2 (collected_ms): removed without reserving its number");
    }

    @Test
    void retypingAFieldIsCaughtAndNamed() {
        FileDescriptorSet edited = editMetricPoints(m -> withField(m, "collected_ms",
                f -> f.toBuilder().setType(FieldDescriptorProto.Type.TYPE_STRING).build()));

        assertThat(ContractCompatibility.problems(ContractCompatibility.current(), edited))
                .containsExactly("lab.anomaly.v1.MetricPoints field 2 (collected_ms): type changed from TYPE_INT64 to TYPE_STRING");
    }

    @Test
    void addingAFieldIsAllowed() {
        FileDescriptorSet edited = editMetricPoints(m -> m.toBuilder().addField(FieldDescriptorProto.newBuilder()
                .setName("collector_version").setNumber(4)
                .setType(FieldDescriptorProto.Type.TYPE_STRING).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)).build());

        assertThat(ContractCompatibility.problems(ContractCompatibility.current(), edited)).isEmpty();
    }

    @Test
    void removingAFieldIsAllowedOnlyWhenItsNumberIsReserved() {
        FileDescriptorSet removed = editMetricPoints(m -> {
            DescriptorProto.Builder builder = m.toBuilder();
            builder.removeField(1);                                     // collected_ms is the second field
            return builder.build();
        });
        FileDescriptorSet removedAndReserved = editMetricPoints(m -> {
            DescriptorProto.Builder builder = m.toBuilder();
            builder.removeField(1);
            builder.addReservedRange(DescriptorProto.ReservedRange.newBuilder().setStart(2).setEnd(3));
            builder.addReservedName("collected_ms");
            return builder.build();
        });

        assertThat(ContractCompatibility.problems(ContractCompatibility.current(), removed)).hasSize(1);
        assertThat(ContractCompatibility.problems(ContractCompatibility.current(), removedAndReserved)).isEmpty();
    }
}
