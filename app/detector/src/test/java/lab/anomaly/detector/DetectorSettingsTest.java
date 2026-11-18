package lab.anomaly.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Task 1.2: the shipped config-template/detector.yml is valid, and bad values are rejected with a reason. */
class DetectorSettingsTest {

    @Test
    void theDefaultSettingsFileIsValid() {
        DetectorSettings s = Fixtures.defaults();
        assertThat(s.history().toMinutes()).isEqualTo(30);
        assertThat(s.rules()).extracting(DetectorSettings.RuleSettings::signal)
                .containsExactly("success-rate", "error-rate", "errors", "latency", "latency", "latency", "saturation", "traffic");
        assertThat(s.groupBy()).containsExactly("api", "upstream_service", "peer.rpc.service", "service", "resource_name");
    }

    private static void rejected(Map<String, Object> change, String reason) {
        assertThatThrownBy(() -> Fixtures.holder(change)).hasMessageContaining(reason);
    }

    @Test
    void aRuleWithoutSignalIsRejected() {
        rejected(Map.of("anomaly.detector.rules[0].signal", ""), "rules[0].signal: is required");
    }

    @Test
    void historyOverTwoDaysIsRejected() {
        rejected(Map.of("anomaly.detector.history", "3d"), "history: at most 2 days");
    }

    @Test
    void factorOneIsRejected() {
        assertThatThrownBy(() -> Fixtures.holderEdited(t -> t.replaceFirst("        factor: 3", "        factor: 1")))
                .hasMessageContaining("rules[2].factor: must be above 1");
    }

    @Test
    void keepingIncidentsForAMonthIsRejected() {
        rejected(Map.of("anomaly.detector.incident-retention", "30d"), "incident-retention: at most 7 days");
    }

    @Test
    void aBadTagPatternIsRejected() {
        assertThatThrownBy(() -> Fixtures.holderEdited(t -> t.replace("\"status_code=4*\"", "\"status_code\"")))
                .hasMessageContaining("'status_code' must be key=pattern");
    }

    @Test
    void aBauEntryWithAnUnknownDirectionIsRejected() {
        rejected(Map.of("anomaly.detector.bau[0].signal", "errors", "anomaly.detector.bau[0].direction", "sideways"),
                "bau");
    }
}
