package lab.anomaly.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.contracts.v1.ResolveIncident;
import lab.anomaly.notifier.board.LineHistory;
import lab.anomaly.notifier.live.CommandPublisher;
import org.junit.jupiter.api.Test;

/** add-incident-resolution tasks 3.1, 3.2. */
class ReasonControllerTest {

    private final List<ResolveIncident> sent = new ArrayList<>();
    private final ReasonController controller = new ReasonController(new CommandPublisher(sent::add));

    @Test
    void aReasonBecomesACommand() {
        var r = controller.reason("inc-7", Map.of("reason", "  deploy rolled back  "));
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        assertThat(sent).singleElement().satisfies(c -> {
            assertThat(c.getIncidentId()).isEqualTo("inc-7");
            assertThat(c.getReason()).isEqualTo("deploy rolled back");
            assertThat(c.getCommandId()).isEqualTo(r.getBody().get("command_id"));
        });
    }

    @Test
    void emptyAndTooLongReasonsAreRefused() {
        assertThat(controller.reason("inc-7", Map.of("reason", "   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.reason("inc-7", Map.of("reason", "x".repeat(501))).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.reason("inc-7", Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(sent).isEmpty();
    }

    @Test
    void kafkaNotTakingItIsSaid() {
        ReasonController down = new ReasonController(new CommandPublisher(c -> { throw new IllegalStateException("timeout"); }));
        assertThat(down.reason("inc-7", Map.of("reason", "why")).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void theLinesHistoryShowsTheReason() {
        LineHistory h = new LineHistory();
        h.accept(Incident.newBuilder().setIncidentId("i1").setState(IncidentState.INCIDENT_STATE_CLOSED).setStartedMs(1000).setEndedMs(2000)
                .setReason("deploy rolled back").setResolvedMs(3000)
                .addSeries(AffectedSeries.newBuilder().setSeriesId("ffff000000000001")).build());
        assertThat(h.past("ffff000000000001", null, 5000).all()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo("deploy rolled back"));
    }
}
