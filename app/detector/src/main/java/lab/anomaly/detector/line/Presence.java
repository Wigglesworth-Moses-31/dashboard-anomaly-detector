package lab.anomaly.detector.line;

/**
 * Was there a value at each of the last {@code w} steps? A ring of bits (design D3). Lets the Detector tell a line
 * that always has values (it can "go silent") from one that is often empty (a rare error code).
 * Costs: mark O(1) amortised (skipped steps are cleared once), share O(w / 64).
 */
public final class Presence {

    private final long[] bits;
    private final int w;
    private long firstStep = Long.MIN_VALUE;
    private long lastStep = Long.MIN_VALUE;

    public Presence(int steps) {
        this.w = Math.max(1, steps);
        this.bits = new long[(w + 63) / 64];
    }

    /** Marks the step with index {@code step} (time / step length) as present. Older steps beyond w are ignored. */
    public void mark(long step) {
        if (firstStep == Long.MIN_VALUE) {
            firstStep = step;
            lastStep = step;
            set(step, true);
            return;
        }
        if (step > lastStep) {
            long gap = Math.min(step - lastStep - 1, w);
            for (long s = lastStep + 1; s <= lastStep + gap; s++) {
                set(s, false);
            }
            lastStep = step;
            set(step, true);
        } else if (step > lastStep - w) {
            set(step, true);                   // a late value inside the window
        }
    }

    /** Share of steps with a value, over the steps seen so far, at most the last w. 0 when nothing seen. */
    public double share() {
        if (firstStep == Long.MIN_VALUE) {
            return 0;
        }
        long seen = Math.min(w, lastStep - firstStep + 1);
        int count = 0;
        for (long word : bits) {
            count += Long.bitCount(word);
        }
        return Math.min(1.0, count / (double) seen);
    }

    private void set(long step, boolean on) {
        int i = (int) Math.floorMod(step, (long) w);
        if (on) {
            bits[i >> 6] |= 1L << (i & 63);
        } else {
            bits[i >> 6] &= ~(1L << (i & 63));
        }
    }
}
