package lab.anomaly.notifier.live;

import static lab.anomaly.notifier.Events.KOLKATA;
import static lab.anomaly.notifier.Events.at;
import static lab.anomaly.notifier.Events.event;
import static lab.anomaly.notifier.Events.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.notifier.board.Board;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;

/** Tasks 2.2 and 2.3: snapshot then changes without a gap; a stalled page gets the newest state once. */
class HubTest {

    static final long NOW = at("2026-11-16T17:10:00", KOLKATA);
    static final long START = at("2026-11-16T17:04:00", KOLKATA);

    /** A page that asks for nothing until told to. */
    static final class Page extends BaseSubscriber<String> {
        final List<String> got = new CopyOnWriteArrayList<>();
        volatile boolean completed;

        @Override
        protected void hookOnSubscribe(Subscription s) {
        }

        @Override
        protected void hookOnNext(String value) {
            got.add(value);
        }

        @Override
        protected void hookOnComplete() {
            completed = true;
        }
    }

    private final Hub hub = new Hub(new Board(KOLKATA, false, NOW), 2000);

    private void send(IncidentEvent e) {
        hub.apply(e, json(e), NOW);
    }

    @Test
    void snapshotThenTheEndLineThenChanges() {
        send(event("a", 1, false, START, 0));
        Page page = new Page();
        hub.connect().subscribe(page);
        page.request(100);
        send(event("b", 1, false, START + 60_000, 0));
        assertThat(page.got).hasSize(3);
        assertThat(page.got.get(0)).contains("\"incident_id\":\"a\"");
        assertThat(page.got.get(1)).isEqualTo("{\"snapshot_complete\":true,\"day\":\"2026-11-16\",\"time_zone\":\"Asia/Kolkata\",\"day_by\":\"started\","
                + "\"display_zones\":[\"UTC\",\"Asia/Kolkata\",\"America/New_York\"]}");
        assertThat(page.got.get(2)).contains("\"incident_id\":\"b\"");
    }

    @Test
    void twentyUpdatesWhileAPageIsStalledBecomeOneInTheNewestStateAndOthersAreNotDelayed() {
        Page stalled = new Page();
        Page reading = new Page();
        hub.connect().subscribe(stalled);
        hub.connect().subscribe(reading);
        reading.request(Long.MAX_VALUE);
        stalled.request(1);                                        // takes only the end-of-snapshot line
        long t0 = System.nanoTime();
        for (int seq = 1; seq <= 20; seq++) {
            send(event("inv", seq, seq == 20, START, START + 180_000));
        }
        Duration took = Duration.ofNanos(System.nanoTime() - t0);
        assertThat(reading.got).as("the reading page got all 20 at once").hasSize(1 + 20);
        assertThat(took).as("a stalled page never makes the sender wait").isLessThan(Duration.ofMillis(200));

        stalled.request(100);
        assertThat(stalled.got).hasSize(2);
        assertThat(stalled.got.get(1)).contains("\"sequence\":\"20\"").contains("INCIDENT_STATE_CLOSED");
        assertThat(hub.merged.get()).isEqualTo(19);
    }

    @Test
    void aChangeDuringTheSnapshotIsNeitherLostNorShownAsOlder() {
        send(event("a", 1, false, START, 0));
        Page page = new Page();
        hub.connect().subscribe(page);                              // snapshot holds a@1, not yet sent
        send(event("a", 2, false, START, 0));                       // replaces it in the tray
        page.request(100);
        assertThat(page.got).hasSize(2);
        assertThat(page.got.get(0)).contains("\"sequence\":\"2\"");
    }

    @Test
    void midnightEndsEveryStreamSoPagesReconnect() {
        Page page = new Page();
        hub.connect().subscribe(page);
        page.request(10);
        assertThat(hub.roll(at("2026-11-17T00:00:05", KOLKATA))).isTrue();
        assertThat(page.completed).isTrue();
        assertThat(hub.viewers()).isZero();
    }

    @Test
    void theTrayStaysBoundedOnAHugeDay() {
        Hub small = new Hub(new Board(KOLKATA, false, NOW), 10);
        Page stalled = new Page();
        small.connect().subscribe(stalled);
        for (int i = 0; i < 50; i++) {
            IncidentEvent e = event("i" + i, 2, true, START + i, START + i + 1000);
            small.apply(e, json(e), NOW);
        }
        stalled.request(1000);
        assertThat(stalled.got.size()).isLessThanOrEqualTo(11);
    }

    @Test
    void theDisplayZonesTravelOnTheEndLine() {
        Hub hub = new Hub(new Board(java.time.ZoneId.of("UTC"), false, 0), 100);
        hub.reconfigure(java.time.ZoneId.of("UTC"), false, 0, java.util.List.of("America/New_York", "UTC"));
        assertThat(hub.snapshotLineForTest()).endsWith("\"display_zones\":[\"America/New_York\",\"UTC\"]}");
    }
}
