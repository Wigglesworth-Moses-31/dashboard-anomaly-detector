package lab.anomaly.detector.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Task 2.1: the window's median and MAD equal a brute-force sort of the same values, while values leave it. */
class WindowTest {

    static double bruteMedian(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    static double bruteMad(double[] v) {
        double m = bruteMedian(v);
        return bruteMedian(Arrays.stream(v).map(x -> Math.abs(x - m)).toArray());
    }

    @Test
    void oneSpikeDoesNotChangeNormal() {
        Window w = new Window(8);
        long t = 0;
        for (double v : new double[] {140, 135, 138, 1500, 142}) {
            w.add(t += 10_000, v, 1_800_000);
        }
        assertThat(w.median()).isEqualTo(140.0);
    }

    @Test
    void matchesBruteForceWithRepeatsGapsAndValuesLeaving() {
        Random r = new Random(42);
        long history = 1_800_000;                         // 30 minutes of 10-second steps
        Window w = new Window(4);                         // small on purpose: it must grow
        Deque<long[]> kept = new ArrayDeque<>();
        Deque<Double> keptValues = new ArrayDeque<>();
        long t = 0;
        for (int i = 0; i < 5_000; i++) {
            t += r.nextInt(10) == 0 ? 60_000 : 10_000;    // sometimes a gap
            double v = r.nextInt(5) == 0 ? 100 : Math.round(r.nextGaussian() * 20 + 100);   // many repeats of 100
            if (r.nextInt(200) == 0) {
                v = 5_000;                               // spikes
            }
            w.add(t, v, history);
            kept.addLast(new long[] {t});
            keptValues.addLast(v);
            while (kept.peekFirst()[0] <= t - history) {
                kept.removeFirst();
                keptValues.removeFirst();
            }
            double[] expected = keptValues.stream().mapToDouble(Double::doubleValue).toArray();
            assertThat(w.size()).isEqualTo(expected.length);
            assertThat(w.median()).as("step %d", i).isEqualTo(bruteMedian(expected));
            if (i % 97 == 0) {
                assertThat(w.mad()).isCloseTo(bruteMad(expected), offset(1e-9));
            }
        }
    }

    @Test
    void wobbleIgnoresEarlierProblemsInTheWindow() {
        Window w = new Window(64);
        for (int i = 0; i < 80; i++) {
            boolean problem = i % 8 < 3;                       // broken 3 of every 8 steps, like the generator
            w.add(i * 10_000L, problem ? 70 : 94 + (i % 3) - 1, 1_800_000);
        }
        double meanDistance = java.util.Arrays.stream(w.snapshot()).map(x -> Math.abs(x - w.median())).average().orElseThrow();
        assertThat(meanDistance).as("the average distance is pulled up by the problems").isGreaterThan(9);
        assertThat(w.wobble()).as("MAD stays at the normal values' size").isLessThanOrEqualTo(2.0);
    }

    @Test
    void wobbleIsNotZeroWhenMostValuesAreEqual() {
        Window w = new Window(16);
        double[] v = {100, 100, 85.7, 100, 100, 71.4, 100, 100, 100, 85.7};
        for (int i = 0; i < v.length; i++) {
            w.add(i * 10_000L, v[i], 1_800_000);
        }
        assertThat(w.mad()).isZero();
        assertThat(w.wobble()).isCloseTo((14.3 + 28.6 + 14.3) / 10, offset(1e-9));
    }

    @Test
    void quickselectFindsEveryRank() {
        Random r = new Random(1);
        double[] v = r.doubles(301, -50, 50).toArray();
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        for (int k = 0; k < v.length; k++) {
            assertThat(Window.select(v.clone(), k)).isEqualTo(sorted[k]);
        }
    }

    @Test
    void presenceTellsAlwaysThereFromRare() {
        Presence always = new Presence(180);
        Presence rare = new Presence(180);
        for (long step = 0; step < 300; step++) {
            always.mark(step);
            if (step % 10 == 0) {
                rare.mark(step);
            }
        }
        assertThat(always.share()).isEqualTo(1.0);
        assertThat(rare.share()).isCloseTo(0.1, offset(0.01));
    }

    /** fix-backfill-faults F2: a day of values keeps the heaps near the window's size, with the same medians. */
    @Test
    void aDayOfValuesStaysWithinTheWindow() {
        long step = 10_000;
        long history = 1_800_000;                          // 180 values
        Random r = new Random(7);
        for (int shape = 0; shape < 4; shape++) {
            Window w = new Window(16);
            Deque<double[]> live = new ArrayDeque<>();
            for (int i = 0; i < 8_640; i++) {             // 24 hours
                double v = switch (shape) {
                    case 0 -> i;                           // rising trend: old values sink deep into a heap
                    case 1 -> -i;
                    case 2 -> r.nextGaussian() * 100;
                    default -> 99.5 + r.nextGaussian() * 0.3;
                };
                long ts = i * step;
                w.add(ts, v, history);
                live.addLast(new double[] {ts, v});
                while (live.peekFirst()[0] <= ts - history) {
                    live.removeFirst();
                }
                assertThat(w.heapEntries()).isLessThanOrEqualTo(2 * w.size() + 16);
                if (i % 97 == 0) {
                    double[] values = live.stream().mapToDouble(p -> p[1]).toArray();
                    assertThat(w.median()).isEqualTo(bruteMedian(values));
                }
            }
            assertThat(w.size()).isEqualTo(180);
        }
    }
}
