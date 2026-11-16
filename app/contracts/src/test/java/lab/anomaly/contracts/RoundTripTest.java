package lab.anomaly.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.junit.jupiter.api.Test;

/** Each message survives the trip to bytes (what travels on Kafka or gRPC) and back unchanged (task 1.3). */
class RoundTripTest {

    @Test
    void seriesDescriptor() throws Exception {
        SeriesDescriptor sent = Examples.descriptor();
        assertThat(SeriesDescriptor.parseFrom(sent.toByteArray())).isEqualTo(sent);
    }

    @Test
    void metricPoints() throws Exception {
        MetricPoints sent = Examples.points();
        byte[] wire = sent.toByteArray();
        assertThat(MetricPoints.parseFrom(wire)).isEqualTo(sent);
        // Flyweight (design C1): the numbers message is much smaller than the description it refers to
        assertThat(wire.length).isLessThan(Examples.descriptor().toByteArray().length / 2);
    }

    @Test
    void incidentEvent() throws Exception {
        IncidentEvent sent = Examples.incidentEvent();
        assertThat(IncidentEvent.parseFrom(sent.toByteArray())).isEqualTo(sent);
    }
}
