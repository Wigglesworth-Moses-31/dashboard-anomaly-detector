package lab.anomaly.notifier;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lab.anomaly.contracts.ContractJson;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.IncidentState;

/** Test helpers: incident events as the Detector sends them. */
public final class Events {

    public static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    public static long at(String localDateTime, ZoneId zone) {
        return LocalDateTime.parse(localDateTime).atZone(zone).toInstant().toEpochMilli();
    }

    public static IncidentEvent event(String id, long seq, boolean closed, long startedMs, long endedMs) {
        Incident i = Incident.newBuilder().setIncidentId(id).setTitle(id + ": latency up")
                .setState(closed ? IncidentState.INCIDENT_STATE_CLOSED : IncidentState.INCIDENT_STATE_OPEN)
                .setStartedMs(startedMs).setEndedMs(closed ? endedMs : 0).setGroupKey("api=" + id).build();
        return IncidentEvent.newBuilder().setEventId(id + "-" + seq).setIncidentId(id).setSequence(seq)
                .setType(closed ? EventType.EVENT_TYPE_CLOSED : seq == 1 ? EventType.EVENT_TYPE_OPENED : EventType.EVENT_TYPE_UPDATED)
                .setOccurredMs(startedMs).setIncident(i).build();
    }

    public static String json(IncidentEvent e) {
        return ContractJson.toJson(e);
    }

    private Events() {
    }
}
