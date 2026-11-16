package lab.anomaly.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import org.junit.jupiter.api.Test;

class ContractChecksTest {

    @Test
    void goodExamplesPass() {
        assertThat(ContractChecks.problems(Examples.descriptor())).isEmpty();
        assertThat(ContractChecks.problems(Examples.points())).isEmpty();
        assertThat(ContractChecks.problems(Examples.incidentEvent())).isEmpty();
    }

    @Test
    void missingSeriesIdIsReported() {
        MetricPoints message = Examples.points().toBuilder().clearSeriesId().build();
        assertThat(ContractChecks.problems(message)).containsExactly("series_id: missing or not 16 hex characters");
    }

    @Test
    void pointsOutOfOrderAreReported() {
        MetricPoints message = Examples.points().toBuilder()
                .addPoints(Point.newBuilder().setTsMs(1_763_182_000_000L).setValue(1)).build();
        assertThat(ContractChecks.problems(message)).containsExactly("points: not in time order at position 3");
    }

    @Test
    void nonFiniteValueIsReported() {
        MetricPoints message = Examples.points().toBuilder()
                .setPoints(0, Point.newBuilder().setTsMs(1_763_182_790_000L).setValue(Double.NaN)).build();
        assertThat(ContractChecks.problems(message)).containsExactly("points: value at position 0 is not a finite number");
    }

    @Test
    void unsetEnumIsReported() {
        var message = Examples.descriptor().toBuilder().setTimeAggregation(Aggregation.AGGREGATION_UNSPECIFIED).build();
        assertThat(ContractChecks.problems(message)).containsExactly("time_aggregation: not set");
    }

    @Test
    void seriesIdThatDoesNotMatchItsDescriptorIsReported() {
        var message = Examples.descriptor().toBuilder().putGroupTags("status_code", "200").build();
        assertThat(ContractChecks.problems(message))
                .containsExactly("series_id: does not match the id computed from source, query_id and group_tags");
    }

    @Test
    void incidentEventWithoutIncidentIsReported() {
        IncidentEvent message = Examples.incidentEvent().toBuilder().clearIncident().build();
        assertThat(ContractChecks.problems(message))
                .containsExactly("incident: missing (every event carries the full incident)");
    }
}
