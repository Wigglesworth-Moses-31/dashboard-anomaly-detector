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
    public record Limits(long historyMs, long minHistoryMs, long calmMs) {
    }

    private LineFacts facts;
    private final Window window;
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
        this.rule = r;
        this.ruleVersion = version;
    }

    // ---- warm start -------------------------------------------------------------------------------------------

    public boolean warmed() {
        return warmed;
    }

    /** Fills the history with values from the Store (all before the first live value). */
    public void warm(long[] times, double[] values, long historyMs) {
        for (int i = 0; i < times.length; i++) {
            if (times[i] > lastTs) {
                window.add(times[i], values[i], historyMs);
                presence.mark(Math.floorDiv(times[i], facts.stepMs()));
                lastTs = times[i];
                if (knownSince == Long.MIN_VALUE) {
                    knownSince = times[i];
                }
            }
        }
        warmed = true;
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

    /** Judges one finished value. Values at or before the last judged time are repeats and are skipped. */
    public Event accept(long ts, double value, Limits limits) {
        if (ts <= lastTs) {
            return Event.SKIPPED;
        }
        lastTs = ts;
        presence.mark(Math.floorDiv(ts, facts.stepMs()));
        if (knownSince == Long.MIN_VALUE) {
            knownSince = ts;
        }
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
                state = State.CALMING;
                calmSince = ts;
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
