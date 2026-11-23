package lab.anomaly.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/** calmer-anomalies: each signal's judging time is read from the Detector's settings as the Config Service serves them. */
class JudgingControllerTest {

    @Test
    void signalsGetTheirRulesJudgingTimeAndTheFirstSourceWins() throws Exception {
        String answer = """
                {"name":"detector","propertySources":[
                  {"name":"newest","source":{"anomaly.detector.rules[0].signal":"success-rate","anomaly.detector.rules[0].judge-every":"5m",
                    "anomaly.detector.rules[1].signal":"errors","anomaly.detector.rules[2].signal":"latency",
                    "anomaly.detector.rules[2].judge-every":"5m","anomaly.detector.rules[3].signal":"latency",
                    "anomaly.detector.rules[3].judge-every":"10s","anomaly.detector.general.judge-every":"5m",
                    "spring.datasource.password":"${PLATFORM_DB_PASSWORD}"}},
                  {"name":"older","source":{"anomaly.detector.rules[0].judge-every":"10s"}}]}
                """;
        assertThat(JudgingController.periods(JsonMapper.builder().build().readTree(answer)))
                .containsEntry("success-rate", "5m")
                .containsEntry("errors", "10s")
                .containsEntry("latency", "5m")
                .containsEntry("general", "5m")
                .hasSize(4);
    }

    @Test
    void oldSettingsWithoutJudgingTimesJudgeEveryStep() throws Exception {
        String answer = """
                {"propertySources":[{"source":{"anomaly.detector.rules[0].signal":"traffic"}}]}
                """;
        assertThat(JudgingController.periods(JsonMapper.builder().build().readTree(answer)))
                .containsEntry("traffic", "10s").containsEntry("general", "10s");
    }
}
