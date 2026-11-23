package lab.anomaly.detector.line;

import lab.anomaly.detector.rules.Direction;
import lab.anomaly.detector.rules.LineFacts;
import lab.anomaly.detector.rules.Rule;

/**
 * One metric line being watched (design D3, D5): its recent values, its presence, and its state machine
 * (State pattern, kept as an enum plus transitions in {@link #accept}). Every time is the value's own time.
 */
public final class LineWatch {

    public enum State { NORMAL, SUSPECT, SPIKING, CALMING, SILENT }

    /** What a value or a silence check did to the line, for the incident book. */
    public enum Event { SKIPPED, WARMING, NONE, SPIKE_STARTED, PEAK, CALMING, RESPIKED, CALMED, SILENT }

    /** Limits from the settings, in milliseconds. */
    public record Limits(long historyMs, long minHistoryMs, long calmMs, long slowHistoryMs, long slowMinHistoryMs) {

        public Limits(long historyMs, long minHistoryMs, long calmMs) {
            this(historyMs, minHistoryMs, calmMs, 7_200_000, 1_800_000);
        }

        /** calmer-anomalies D3: a line judged on averages; calm ends at the first average within its rule. */
        Limits slow() {
            return new Limits(slowHistoryMs, slowMinHistoryMs, 0, slowHistoryMs, slowMinHistoryMs);
        }
    }

    private LineFacts facts;
    private Window window;
    private final Presence presence;

    private Rule rule;
    private long ruleVersion = -1;

    private State state = State.NORMAL;
    private long lastTs = Long.MIN_VALUE;
    private long knownSince = Long.MIN_VALUE;
    private boolean warmed;

    private int streak;              // beyond steps in a row while NORMAL/SUSPECT
    private long streakStart;        // time of the first of them
    private long spikeStart;
    private long calmSince;
    private double normalAtSpike = Double.NaN;
    private double peak = Double.NaN;
    private long peakTs;
    private Direction direction;
    private boolean silent;          // the current spike is a silence

    private double madCache = Double.NaN;
    private long madTs = Long.MIN_VALUE;

    // calmer-anomalies D1: a rule judged on averages; 0 = every step
    private long periodMs;
    private long periodStart = Long.MIN_VALUE;   // start of the open (unfinished) period
    private double periodSum;
    private int periodCount;
    private boolean refill;                      // the window was cleared by a rule change: next warm start refills it

    public LineWatch(LineFacts facts, long historyMs) {
        this.facts = facts;
        int steps = (int) Math.max(1, historyMs / facts.stepMs());
        this.window = new Window(steps + 2);
        this.presence = new Presence(steps);
    }

    // ---- facts and rule ---------------------------------------------------------------------------------------

    public LineFacts facts() {
        return facts;
    }

    public void describe(LineFacts newFacts) {
        this.facts = newFacts;
        this.ruleVersion = -1;           // the description changed: match the rules again
    }

    public Rule rule() {
        return rule;
    }

    public boolean needsRule(long version) {
        return ruleVersion != version;
    }

    public void useRule(Rule r, long version) {
        long p = r.periodFor(facts.stepMs());
        if (rule != null && p != periodMs) {
            resetJudging();              // fast <-> slow (D4): a new window, refilled from the Store
        }
        this.periodMs = p;
        this.rule = r;
        this.ruleVersion = version;
    }

    /** Judged on averages of this many milliseconds; 0 when judged every step. */
    public long periodMs() {
        return periodMs;
    }

    private void resetJudging() {
        window = new Window(8);
        periodStart = Long.MIN_VALUE;
        periodSum = 0;
        periodCount = 0;
        knownSince = Long.MIN_VALUE;
        madTs = Long.MIN_VALUE;
        if (state == State.NORMAL || state == State.SUSPECT) {
            state = State.NORMAL;
            streak = 0;
        }
        warmed = false;
        refill = true;
    }

    // ---- warm start -------------------------------------------------------------------------------------------

    public boolean warmed() {
        return warmed;
    }

    /** Fills the history with values from the Store (all before the first live value). */
    public void warm(long[] times, double[] values, long historyMs) {
        warm(times, values, new Limits(historyMs, 0, 0));
    }

    /**
     * Fills the history with values from the Store (all before the first live value). A line judged on averages sums
     * them into periods: finished periods enter the window, the last one stays open for the live values (D4).
     * After a rule change ({@code refill}) values already seen live are used again, for the window only.
     */
    public void warm(long[] times, double[] values, Limits limits) {
        long historyMs = periodMs > 0 ? limits.slowHistoryMs() : limits.historyMs();
        for (int i = 0; i < times.length; i++) {
            long ts = times[i];
            boolean fresh = ts > lastTs;
            if (!fresh && !refill) {
                continue;
            }
            if (fresh) {
                presence.mark(Math.floorDiv(ts, facts.stepMs()));
                lastTs = ts;
            }
            if (knownSince == Long.MIN_VALUE) {
                knownSince = ts;
            }
            if (periodMs > 0) {
                long p = Math.floorDiv(ts, periodMs) * periodMs;
                if (periodStart != Long.MIN_VALUE && p != periodStart && periodCount > 0) {
                    window.add(periodStart, periodSum / periodCount, historyMs);
                    periodCount = 0;
                    periodSum = 0;
                }
                if (p != periodStart) {
                    periodStart = p;
                }
                periodSum += values[i];
                periodCount++;
            } else {
                window.add(ts, values[i], historyMs);
            }
        }
        refill = false;
        warmed = true;
    }

    /** How far back a warm start should reach for this line. */
    public long warmSpanMs(Limits limits) {
        return periodMs > 0 ? limits.slowHistoryMs() : limits.historyMs();
    }

    public void markWarmed() {
        warmed = true;
    }

    /** After a restart, a line that is part of an open incident continues in CALMING (design D7). */
    public void resumeInIncident(double normal, double peakValue, long peakAt, Direction dir, boolean wasSilent) {
        state = State.CALMING;
        calmSince = Long.MIN_VALUE;      // set by the first value
        normalAtSpike = normal;
        peak = peakValue;
        peakTs = peakAt;
        direction = dir;
        silent = wasSilent;
    }

    // ---- judging ----------------------------------------------------------------------------------------------

    /**
     * Judges one finished value. Values at or before the last judged time are repeats and are skipped. A line judged
     * on averages (D1) adds the value to its open period; the first value of the next period hands the finished
     * period's average, timed at the period's start, to the same judging.
     */
    public Event accept(long ts, double value, Limits limits) {
        if (ts <= lastTs) {
            return Event.SKIPPED;
        }
        lastTs = ts;
        presence.mark(Math.floorDiv(ts, facts.stepMs()));
        if (knownSince == Long.MIN_VALUE) {
            knownSince = ts;
        }
        if (periodMs <= 0) {
            return judge(ts, value, limits);
        }
        long p = Math.floorDiv(ts, periodMs) * periodMs;
        if (periodStart == p) {
            periodSum += value;
            periodCount++;
            return Event.NONE;
        }
        Event event = Event.NONE;
        if (periodStart != Long.MIN_VALUE && periodStart < p && periodCount > 0) {
            event = judge(periodStart, periodSum / periodCount, limits.slow());
        }
        periodStart = p;
        periodSum = value;
        periodCount = 1;
        return event;
    }

    /** The time of the newest judged value: the value's own time, or the start of the newest finished period. */
    private Event judge(long ts, double value, Limits limits) {
        if (state == State.CALMING && calmSince == Long.MIN_VALUE) {
            calmSince = ts;              // resumed after a restart
        }
        boolean judgeable = ts - knownSince >= limits.minHistoryMs() && window.size() >= 3;
        if (!judgeable && state != State.CALMING && state != State.SPIKING) {
            window.add(ts, value, limits.historyMs());
            return Event.WARMING;
        }
        double normal = state == State.SPIKING || state == State.CALMING ? normalAtSpike : window.median();
        Direction beyond = rule.measure().beyond(value, normal, rule.needsMad() ? mad(ts) : Double.NaN);

        switch (state) {
            case NORMAL, SUSPECT -> {
                window.add(ts, value, limits.historyMs());
                if (beyond == null) {
                    state = State.NORMAL;
                    streak = 0;
                    return Event.NONE;
                }
                if (streak == 0 || beyond != direction) {
                    streak = 0;
                    streakStart = ts;
                    direction = beyond;
                    peak = value;
                    peakTs = ts;
                    normalAtSpike = normal;
                }
                streak++;
                updatePeak(ts, value);
                if (streak >= rule.steps()) {
                    state = State.SPIKING;
                    spikeStart = streakStart;
                    silent = false;
                    streak = 0;
                    return Event.SPIKE_STARTED;
                }
                state = State.SUSPECT;
                return Event.NONE;
            }
            case SPIKING -> {
                if (ts - spikeStart > limits.historyMs() / 2) {
                    window.add(ts, value, limits.historyMs());       // a lasting change becomes the new normal
                    normalAtSpike = window.median();
                }
                if (beyond != null) {
                    return updatePeak(ts, value) ? Event.PEAK : Event.NONE;
                }
                calmSince = ts;
                if (limits.calmMs() <= 0) {                   // averages (D3): one average within the rule is calm
                    state = State.NORMAL;
                    window.add(ts, value, limits.historyMs());
                    return Event.CALMED;
                }
                state = State.CALMING;
                return Event.CALMING;
            }
            case CALMING -> {
                if (beyond != null) {
                    state = State.SPIKING;
                    silent = false;
                    if (direction == null) {
                        direction = beyond;
                    }
                    updatePeak(ts, value);
                    return Event.RESPIKED;
                }
                if (ts - calmSince >= limits.calmMs()) {
                    state = State.NORMAL;
                    window.add(ts, value, limits.historyMs());
                    return Event.CALMED;
                }
                return Event.NONE;
            }
            case SILENT -> {
                state = State.CALMING;       // values again: wait out the calm period
                calmSince = ts;
                if (beyond != null) {
                    state = State.SPIKING;
                    silent = false;
                    direction = beyond;
                    updatePeak(ts, value);
                    return Event.RESPIKED;
                }
                return Event.CALMING;
            }
            default -> throw new IllegalStateException(state.name());
        }
    }

    /**
     * Called when the line's dashboard has moved on {@code silenceMs} past this line's last value (design D2).
     * Only lines that usually have a value at every step, and that are judged, can go silent.
     */
    public Event checkSilence(long watermark, long silenceMs, double minPresence, long minHistoryMs) {
        if (lastTs == Long.MIN_VALUE || state == State.SILENT || state == State.SPIKING) {
            return Event.NONE;
        }
        if (watermark - lastTs < silenceMs || presence.share() < minPresence || lastTs - knownSince < minHistoryMs) {
            return Event.NONE;
        }
        boolean wasCalming = state == State.CALMING;
        state = State.SILENT;
        silent = true;
        streak = 0;
        if (!wasCalming) {
            spikeStart = lastTs + facts.stepMs();
            normalAtSpike = window.median();
            peak = Double.NaN;
            peakTs = spikeStart;
            direction = null;
        }
        return Event.SILENT;
    }

    private boolean updatePeak(long ts, double value) {
        boolean worse = Double.isNaN(peak) || (direction == Direction.DOWN ? value < peak : value > peak);
        if (worse) {
            peak = value;
            peakTs = ts;
        }
        return worse;
    }

    private double mad(long ts) {
        if (madTs == Long.MIN_VALUE || ts - madTs >= 60_000) {
            madCache = window.wobble();
            madTs = ts;
        }
        return madCache;
    }

    // ---- read-only view ---------------------------------------------------------------------------------------

    public State state() {
        return state;
    }

    public long lastTs() {
        return lastTs;
    }

    public boolean judgeable(long minHistoryMs) {
        return knownSince != Long.MIN_VALUE && lastTs - knownSince >= minHistoryMs && window.size() >= 3;
    }

    /** As above with the limits of the line's own speed (calmer-anomalies). */
    public boolean judgeable(Limits limits) {
        return judgeable(periodMs > 0 ? limits.slowMinHistoryMs() : limits.minHistoryMs());
    }

    public long spikeStart() {
        return spikeStart;
    }

    public long calmSince() {
        return calmSince;
    }

    public double normalAtSpike() {
        return normalAtSpike;
    }

    public double peak() {
        return peak;
    }

    public long peakTs() {
        return peakTs;
    }

    /** "up", "down" or "silent". */
    public String reason() {
        if (silent || direction == null) {
            return "silent";
        }
        return direction.word();
    }

    public double presence() {
        return presence.share();
    }

    public double normalNow() {
        return window.median();
    }
}
