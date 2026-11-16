package lab.anomaly.detector.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.detector.incident.IncidentBook.Change;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tasks 4.2 and 4.4 against the platform database (-Dlive.db=true, PLATFORM_DB_PASSWORD in the environment).
 * Uses incident ids starting with "test-" and removes them afterwards.
 */
@EnabledIfSystemProperty(named = "live.db", matches = "true")
class IncidentRepositoryLiveTest {

    private JdbcTemplate jdbc;
    private IncidentRepository repo;

    @BeforeEach
    void connect() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:postgresql://localhost:55432/anomaly", "anomaly",
                System.getenv("PLATFORM_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("incidents").createSchemas(false).load().migrate();
        jdbc = new JdbcTemplate(ds);
        repo = new IncidentRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM incidents.outbox WHERE incident_id LIKE 'test-%'");
        jdbc.update("DELETE FROM incidents.incident WHERE incident_id LIKE 'test-%'");
    }

    static Incident incident(String id, String group, IncidentState state, long ended) {
        return Incident.newBuilder().setIncidentId(id).setState(state).setTitle(group + ": latency up").setStartedMs(1_763_200_000_000L)
                .setEndedMs(ended).setGroupKey(group).build();
    }

    static Change change(Incident i, EventType type, long seq) {
        return new Change(type, i, seq, i.getStartedMs(), 0);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void aChangeIsSavedWithItsEventInOneTransaction() throws Exception {
        Incident i = incident("test-1", "api=/test", IncidentState.INCIDENT_STATE_OPEN, 0);
        repo.save(List.of(change(i, EventType.EVENT_TYPE_OPENED, 1)));
        assertThat(count("SELECT count(*) FROM incidents.incident WHERE incident_id = 'test-1' AND state = 'OPEN'")).isEqualTo(1);
        List<IncidentRepository.Pending> pending = repo.unpublished(1000).stream().filter(p -> p.incidentId().equals("test-1")).toList();
        assertThat(pending).hasSize(1);
        IncidentEvent event = IncidentEvent.parseFrom(pending.get(0).payload());
        assertThat(event.getSequence()).isEqualTo(1);
        assertThat(event.getIncident().getGroupKey()).isEqualTo("api=/test");

        // A batch with a broken change saves nothing at all: incident rows and events stay together.
        Incident ok = incident("test-2", "api=/test2", IncidentState.INCIDENT_STATE_OPEN, 0);
        Incident broken = Incident.newBuilder(ok).setIncidentId("test-3").setState(IncidentState.INCIDENT_STATE_UNSPECIFIED).build();
        assertThatThrownBy(() -> repo.save(List.of(change(ok, EventType.EVENT_TYPE_OPENED, 1), change(broken, EventType.EVENT_TYPE_OPENED, 1))))
                .hasMessageContaining("contract");
        assertThat(count("SELECT count(*) FROM incidents.incident WHERE incident_id IN ('test-2', 'test-3')")).isZero();
        assertThat(count("SELECT count(*) FROM incidents.outbox WHERE incident_id IN ('test-2', 'test-3')")).isZero();
    }

    @Test
    void twoOpenIncidentsForOneGroupAreRefused() {
        repo.save(List.of(change(incident("test-1", "api=/test", IncidentState.INCIDENT_STATE_OPEN, 0), EventType.EVENT_TYPE_OPENED, 1)));
        assertThatThrownBy(() -> repo.save(List.of(change(incident("test-2", "api=/test", IncidentState.INCIDENT_STATE_OPEN, 0),
                EventType.EVENT_TYPE_OPENED, 1)))).hasMessageContaining("one_open_per_group");
        // Closing the first and opening a new one for the same group in one batch is fine.
        repo.save(List.of(change(incident("test-1", "api=/test", IncidentState.INCIDENT_STATE_CLOSED, 1_763_200_100_000L), EventType.EVENT_TYPE_CLOSED, 2),
                change(incident("test-2", "api=/test", IncidentState.INCIDENT_STATE_OPEN, 0), EventType.EVENT_TYPE_OPENED, 1)));
        assertThat(repo.openIncidents()).extracting(r -> r.incident().getIncidentId()).contains("test-2").doesNotContain("test-1");
    }

    @Test
    void anOlderEventNeverOverwritesANewerState() {
        Incident open = incident("test-1", "api=/test", IncidentState.INCIDENT_STATE_OPEN, 0);
        Incident closed = incident("test-1", "api=/test", IncidentState.INCIDENT_STATE_CLOSED, 1_763_200_100_000L);
        repo.save(List.of(change(closed, EventType.EVENT_TYPE_CLOSED, 3)));
        repo.save(List.of(change(open, EventType.EVENT_TYPE_UPDATED, 2)));
        assertThat(jdbc.queryForObject("SELECT state FROM incidents.incident WHERE incident_id = 'test-1'", String.class)).isEqualTo("CLOSED");
    }

    @Test
    void anUnsentEventStaysWaitingUntilMarkedSent() {
        repo.save(List.of(change(incident("test-1", "api=/test", IncidentState.INCIDENT_STATE_OPEN, 0), EventType.EVENT_TYPE_OPENED, 1)));
        long id = repo.unpublished(1000).stream().filter(p -> p.incidentId().equals("test-1")).findFirst().orElseThrow().id();
        // "Restart": a new repository object sees the same waiting event.
        IncidentRepository afterRestart = new IncidentRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
        assertThat(afterRestart.unpublished(1000)).extracting(IncidentRepository.Pending::id).contains(id);
        afterRestart.markPublished(id);
        assertThat(afterRestart.unpublished(1000)).extracting(IncidentRepository.Pending::id).doesNotContain(id);
    }

    @Test
    void closedIncidentsOlderThanTheRetentionAreRemoved() {
        repo.save(List.of(
                change(incident("test-old", "api=/old", IncidentState.INCIDENT_STATE_CLOSED, 1), EventType.EVENT_TYPE_CLOSED, 1),
                change(incident("test-yesterday", "api=/new", IncidentState.INCIDENT_STATE_CLOSED, 1), EventType.EVENT_TYPE_CLOSED, 1),
                change(incident("test-open", "api=/open", IncidentState.INCIDENT_STATE_OPEN, 0), EventType.EVENT_TYPE_OPENED, 1)));
        jdbc.update("UPDATE incidents.incident SET ended = now() - interval '3 days' WHERE incident_id = 'test-old'");
        jdbc.update("UPDATE incidents.incident SET ended = now() - interval '1 day' WHERE incident_id = 'test-yesterday'");
        jdbc.update("UPDATE incidents.incident SET started = now() - interval '5 days' WHERE incident_id = 'test-open'");
        repo.removeOld(Duration.ofDays(2));
        assertThat(jdbc.queryForList("SELECT incident_id FROM incidents.incident WHERE incident_id LIKE 'test-%' ORDER BY 1", String.class))
                .containsExactly("test-open", "test-yesterday");
    }
}
