package lab.anomaly.notifier.board;

import static lab.anomaly.notifier.Events.KOLKATA;
import static lab.anomaly.notifier.Events.at;
import static lab.anomaly.notifier.Events.event;
import static lab.anomaly.notifier.Events.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import lab.anomaly.contracts.v1.IncidentEvent;
import org.junit.jupiter.api.Test;

/** Task 2.1: one state per incident, the day in the shared time zone, midnight, zone change, day by arrival. */
class BoardTest {

    static final long NOW = at("2026-11-16T17:10:00", KOLKATA);

    private static void put(Board b, IncidentEvent e, long received) {
        b.accept(e, json(e), received);
    }

    @Test
    void aRepeatAndAnOlderLateEventChangeNothing() {
        Board b = new Board(KOLKATA, false, NOW);
        long start = at("2026-11-16T17:04:00", KOLKATA);
        put(b, event("inv", 7, true, start, start + 180_000), NOW);
        assertThat(b.accept(event("inv", 7, true, start, start + 180_000), "x", NOW)).isEqualTo(Board.Outcome.REPEAT_OR_LATE);
        assertThat(b.accept(event("inv", 5, false, start, 0), "y", NOW)).isEqualTo(Board.Outcome.REPEAT_OR_LATE);
        assertThat(b.get("inv").open()).as("stays closed").isFalse();
        assertThat(b.get("inv").event().getSequence()).isEqualTo(7);
    }

    @Test
    void theWholeDayStaysOpenFirstNewestFirst() {
        Board b = new Board(KOLKATA, false, NOW);
        put(b, event("a", 3, true, at("2026-11-16T09:00:00", KOLKATA), at("2026-11-16T09:03:00", KOLKATA)), NOW);
        put(b, event("b", 3, true, at("2026-11-16T16:56:00", KOLKATA), at("2026-11-16T16:59:00", KOLKATA)), NOW);
        put(b, event("c", 1, false, at("2026-11-16T17:04:00", KOLKATA), 0), NOW);
        put(b, event("d", 1, false, at("2026-11-16T17:08:00", KOLKATA), 0), NOW);
        put(b, event("old", 3, true, at("2026-11-15T23:00:00", KOLKATA), at("2026-11-15T23:05:00", KOLKATA)), NOW);
        assertThat(b.snapshot()).extracting(Board.Entry::id).containsExactly("d", "c", "b", "a");
    }

    @Test
    void atMidnightClosedIncidentsOfYesterdayLeaveAndAnOpenOneStaysUntilItCloses() {
        long evening = at("2026-11-16T23:59:00", KOLKATA);
        Board b = new Board(KOLKATA, false, evening);
        put(b, event("closed", 3, true, at("2026-11-16T22:00:00", KOLKATA), at("2026-11-16T22:03:00", KOLKATA)), evening);
        long lateStart = at("2026-11-16T23:58:00", KOLKATA);
        put(b, event("late", 1, false, lateStart, 0), evening);

        assertThat(b.roll(at("2026-11-17T00:00:30", KOLKATA))).isTrue();
        assertThat(b.today()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(b.snapshot()).extracting(Board.Entry::id).containsExactly("late");

        put(b, event("late", 2, true, lateStart, at("2026-11-17T00:03:00", KOLKATA)), at("2026-11-17T00:03:10", KOLKATA));
        assertThat(b.snapshot()).as("yesterday's incident leaves once closed").isEmpty();
    }

    @Test
    void switchingToNewYorkMovesTheDay() {
        // 16 Nov 08:00 in Kolkata is 15 Nov 21:30 in New York.
        Board b = new Board(KOLKATA, false, NOW);
        put(b, event("early", 3, true, at("2026-11-16T08:00:00", KOLKATA), at("2026-11-16T08:05:00", KOLKATA)), NOW);
        assertThat(b.size()).isEqualTo(1);
        b.reconfigure(ZoneId.of("America/New_York"), false, NOW);
        assertThat(b.today()).isEqualTo(LocalDate.parse("2026-11-16"));
        assertThat(b.size()).as("its New York day is the 15th: not today in New York").isZero();
    }

    @Test
    void dayByReceivedShowsAnOldRecordingAsToday() {
        Board b = new Board(KOLKATA, true, NOW);
        put(b, event("rec", 3, true, at("2026-11-15T13:40:00", KOLKATA), at("2026-11-15T13:43:00", KOLKATA)), NOW);
        assertThat(b.snapshot()).extracting(Board.Entry::id).containsExactly("rec");
        Board byStart = new Board(KOLKATA, false, NOW);
        put(byStart, event("rec", 3, true, at("2026-11-15T13:40:00", KOLKATA), at("2026-11-15T13:43:00", KOLKATA)), NOW);
        assertThat(byStart.size()).isZero();
    }
}
