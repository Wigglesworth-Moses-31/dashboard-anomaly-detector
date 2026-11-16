package lab.anomaly.detector.incident;

import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.detector.incident.IncidentBook.Change;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQL for incidents and the outbox (design D7, Repository pattern). {@link #save} writes every change of a batch
 * with its event in one transaction: the incident row and its announcement exist together or not at all.
 */
@Repository
public class IncidentRepository {

    /** An incident open before a restart, with the sequence of its last event. */
    public record Restored(Incident incident, long sequence) {
    }

    /** An event waiting to be sent. */
    public record Pending(long id, String incidentId, byte[] payload, long arrivedMs) {
    }

    private static final String UPSERT_INCIDENT = """
            INSERT INTO incidents.incident (incident_id, group_key, state, title, started, ended, sequence, body, updated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (incident_id) DO UPDATE SET state = EXCLUDED.state, title = EXCLUDED.title,
                ended = EXCLUDED.ended, sequence = EXCLUDED.sequence, body = EXCLUDED.body, updated = now()
            WHERE incidents.incident.sequence < EXCLUDED.sequence
            """;
    private static final String INSERT_OUTBOX = """
            INSERT INTO incidents.outbox (event_id, incident_id, payload, arrived_ms, created) VALUES (?, ?, ?, ?, now())
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public IncidentRepository(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /** Saves the changes and their events in one transaction. A change that fails the contract checks is a bug: it throws. */
    public void save(List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            for (Change c : changes) {
                IncidentEvent event = IncidentEvent.newBuilder().setEventId(UUID.randomUUID().toString())
                        .setIncidentId(c.incident().getIncidentId()).setSequence(c.sequence()).setType(c.type())
                        .setOccurredMs(c.occurredMs()).setIncident(c.incident()).build();
                List<String> problems = ContractChecks.problems(event);
                if (!problems.isEmpty()) {
                    throw new IllegalStateException("Incident event breaks the contract: " + problems);
                }
                Incident i = c.incident();
                boolean closed = i.getState() == IncidentState.INCIDENT_STATE_CLOSED;
                jdbc.update(UPSERT_INCIDENT, i.getIncidentId(), i.getGroupKey(), closed ? "CLOSED" : "OPEN", i.getTitle(),
                        ts(i.getStartedMs()), closed ? ts(i.getEndedMs()) : null, c.sequence(), i.toByteArray());
                jdbc.update(INSERT_OUTBOX, UUID.fromString(event.getEventId()), i.getIncidentId(), event.toByteArray(), c.arrivedMs());
            }
        });
    }

    public List<Restored> openIncidents() {
        return jdbc.query("SELECT body, sequence FROM incidents.incident WHERE state = 'OPEN' ORDER BY started",
                (rs, n) -> {
                    try {
                        return new Restored(Incident.parseFrom(rs.getBytes(1)), rs.getLong(2));
                    } catch (InvalidProtocolBufferException e) {
                        throw new IllegalStateException("Stored incident is not readable", e);
                    }
                });
    }

    public List<Pending> unpublished(int limit) {
        return jdbc.query("SELECT id, incident_id, payload, arrived_ms FROM incidents.outbox WHERE published IS NULL ORDER BY id LIMIT ?",
                (rs, n) -> new Pending(rs.getLong(1), rs.getString(2), rs.getBytes(3), rs.getLong(4)), limit);
    }

    public void markPublished(long id) {
        jdbc.update("UPDATE incidents.outbox SET published = now() WHERE id = ?", id);
    }

    public void markPublished(List<Long> ids) {
        jdbc.batchUpdate("UPDATE incidents.outbox SET published = now() WHERE id = ?", ids.stream().map(id -> new Object[] {id}).toList());
    }

    /** Seconds the oldest unsent event has waited; 0 when none. */
    public double oldestUnpublishedSeconds() {
        Double s = jdbc.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created)), 0) FROM incidents.outbox WHERE published IS NULL",
                Double.class);
        return s == null ? 0 : s;
    }

    public long unpublishedCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM incidents.outbox WHERE published IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    /** Design D9: closed incidents older than the retention, and sent events older than a day. */
    public int[] removeOld(Duration incidentRetention) {
        int incidents = jdbc.update("DELETE FROM incidents.incident WHERE state = 'CLOSED' AND ended < now() - ?::interval",
                incidentRetention.toSeconds() + " seconds");
        int events = jdbc.update("DELETE FROM incidents.outbox WHERE published IS NOT NULL AND published < now() - interval '1 day'");
        return new int[] {incidents, events};
    }

    private static Timestamp ts(long ms) {
        return Timestamp.from(Instant.ofEpochMilli(ms));
    }
}
