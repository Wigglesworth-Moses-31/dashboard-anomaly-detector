package lab.anomaly.notifier.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.Incident;
import org.junit.jupiter.api.Test;

/** add-incident-history task 3.1: a line's incidents of the last 2 days, latest event wins, rebuilt the same way. */
class LineHistoryTest {

    private static final String LINE = "ffff000000000200";
    private static final long NOW = Instant.parse("2026-11-17T13:05:00Z").toEpochMilli();

    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static Incident incident(String id, String start, String end, double peak, double normal) {
        return Incident.newBuilder().setIncidentId(id).setTitle("Shop · Payments: traffic down").setStartedMs(at(start))
                .setEndedMs(end == null ? 0 : at(end))
                .addSeries(AffectedSeries.newBuilder().setSeriesId(LINE).setSignal("traffic").setReason("down")
                        .setPeakValue(peak).setNormalValue(normal).setUnit("requests").setPanelTitle("Request Counts by Status Code")
                        .setPanelPath("API Gateway Metrics"))
                .build();
    }

    private static List<Incident> threeIncidents() {
        List<Incident> events = new ArrayList<>();
        events.add(incident("i1", "2026-11-16T22:14:00Z", null, 70, 610));                  // opened...
        events.add(incident("i1", "2026-11-16T22:14:00Z", "2026-11-16T22:26:00Z", 55, 610)); // ...updated and closed
        events.add(incident("i2", "2026-11-17T09:03:00Z", "2026-11-17T09:09:00Z", 41, 650));
        events.add(incident("i3", "2026-11-17T13:04:10Z", null, 37, 670));                  // the one being looked at
        return events;
    }

    @Test
    void lastTimeAndTheTwoDayListNewestFirst() {
        LineHistory h = new LineHistory();
        threeIncidents().forEach(h::accept);
        LineHistory.Past past = h.past(LINE, "i3", NOW);

        assertThat(past.last().incidentId()).isEqualTo("i2");
        assertThat(past.last().endedMs() - past.last().startedMs()).isEqualTo(6 * 60_000);
        assertThat(past.all()).extracting(LineHistory.Occurrence::incidentId).containsExactly("i3", "i2", "i1");
        assertThat(past.all().get(2).peak()).as("the latest event of i1 wins").isEqualTo(55);
        assertThat(past.all()).allMatch(o -> o.reason().isEmpty());
    }

    @Test
    void olderThanTenDaysIsDroppedButOpenOnesStay() {                       // keep-ten-days T4
        LineHistory h = new LineHistory();
        h.accept(incident("old", "2026-11-06T10:00:00Z", "2026-11-06T10:05:00Z", 1, 10));
        h.accept(incident("open", "2026-11-06T11:00:00Z", null, 1, 10));
        assertThat(h.past(LINE, null, NOW).all()).extracting(LineHistory.Occurrence::incidentId).containsExactly("open");
        assertThat(h.sweep(NOW)).isEqualTo(1);
        assertThat(h.past("unknown", null, NOW).all()).isEmpty();
        assertThat(h.past("unknown", null, NOW).last()).isNull();
    }

    @Test
    void aLineKeepsAtMostTwoHundredIncidentsOldestDroppedFirst() {          // keep-ten-days T4: bounded memory
        LineHistory h = new LineHistory();
        for (int i = 0; i < 250; i++) {
            h.accept(incident("i" + i, java.time.Instant.parse("2026-11-17T00:00:00Z").plusSeconds(i * 60L).toString(),
                    java.time.Instant.parse("2026-11-17T00:00:30Z").plusSeconds(i * 60L).toString(), 1, 10));
        }
        assertThat(h.past(LINE, null, NOW).all()).hasSize(LineHistory.MAX_PER_LINE)
                .extracting(LineHistory.Occurrence::incidentId).doesNotContain("i0", "i49").contains("i50", "i249");
    }

    @Test
    void rebuiltFromTheSameEventsAfterARestartItIsTheSame() {
        LineHistory before = new LineHistory();
        threeIncidents().forEach(before::accept);
        LineHistory after = new LineHistory();                                // a restart re-reads the events
        threeIncidents().forEach(after::accept);
        assertThat(after.past(LINE, "i3", NOW)).isEqualTo(before.past(LINE, "i3", NOW));
    }

    @Test
    void lastTimeIsBeforeTheOpenedIncidentEvenWhenNewerOnesExist() {
        LineHistory h = new LineHistory();
        threeIncidents().forEach(h::accept);
        // Opening the middle one (i2) from the closed list: last time before it is i1, not the newer i3
        assertThat(h.past(LINE, "i2", NOW).last().incidentId()).isEqualTo("i1");
        assertThat(h.past(LINE, "i1", NOW).last()).as("nothing before the first").isNull();
    }
}
