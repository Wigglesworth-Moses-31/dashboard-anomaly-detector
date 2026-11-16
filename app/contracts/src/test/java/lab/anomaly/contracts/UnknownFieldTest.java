package lab.anomaly.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import lab.anomaly.contracts.v1.MetricPoints;
import org.junit.jupiter.api.Test;

/** An older reader ignores a field added by a newer sender (task 3.3, Tolerant Reader). */
class UnknownFieldTest {

    @Test
    void olderReaderIgnoresANewField() throws Exception {
        MetricPoints sent = Examples.points();

        // What a newer Collector would send: the same message plus field 99, which this reader does not know
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(sent.toByteArray());
        CodedOutputStream extra = CodedOutputStream.newInstance(wire);
        extra.writeString(99, "from a newer version");
        extra.flush();

        MetricPoints read = MetricPoints.parseFrom(wire.toByteArray());

        assertThat(read.getSeriesId()).isEqualTo(sent.getSeriesId());
        assertThat(read.getCollectedMs()).isEqualTo(sent.getCollectedMs());
        assertThat(read.getPointsList()).isEqualTo(sent.getPointsList());
        assertThat(read.getUnknownFields().hasField(99)).isTrue();     // kept, not lost, if passed on
    }
}
