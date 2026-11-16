package lab.anomaly.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import lab.anomaly.contracts.v1.IncidentEvent;
import org.junit.jupiter.api.Test;

class ContractJsonAndTopicsTest {

    @Test
    void jsonKeepsTheContractsFieldNames() {
        String json = ContractJson.toJson(Examples.incidentEvent());
        assertThat(json).contains("\"incident_id\"", "\"event_id\"", "\"peak_value\"", "\"group_tags\"")
                .doesNotContain("incidentId", "eventId");
    }

    @Test
    void sameMessageAlwaysGivesTheSameJson() {
        // Tags inserted in opposite orders must print identically (map keys are sorted)
        var one = Examples.descriptor().toBuilder().clearGroupTags().putGroupTags("api", "/v1").putGroupTags("status_code", "500").build();
        var other = Examples.descriptor().toBuilder().clearGroupTags().putGroupTags("status_code", "500").putGroupTags("api", "/v1").build();
        assertThat(ContractJson.toJson(one)).isEqualTo(ContractJson.toJson(other));
    }

    @Test
    void jsonReadsBackToTheSameMessage() {
        IncidentEvent sent = Examples.incidentEvent();
        IncidentEvent read = ContractJson.fromJson(ContractJson.toJson(sent), IncidentEvent.newBuilder()).build();
        assertThat(read).isEqualTo(sent);
    }

    @Test
    void jsonReaderIgnoresUnknownFields() {
        String json = "{\"event_id\":\"e1\",\"incident_id\":\"i1\",\"field_from_the_future\":true}";
        assertThat(ContractJson.fromJson(json, IncidentEvent.newBuilder()).getEventId()).isEqualTo("e1");
    }

    @Test
    void descriptorAndPointsOfOneLineShareAKey() {
        assertThat(Topics.key(Examples.descriptor())).isEqualTo(Topics.key(Examples.points()));
        assertThat(Topics.key(Examples.incidentEvent())).isEqualTo(Examples.incidentEvent().getIncidentId());
    }
}
