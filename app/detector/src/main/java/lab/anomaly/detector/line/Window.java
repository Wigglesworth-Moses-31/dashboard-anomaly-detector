package lab.anomaly.detector.line;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * One line's recent finished values (design D3): a ring buffer of (time, value) that forgets values older than the
 * history window, plus a sliding median kept by two heaps with lazy deletion, plus MAD on request.
 * Not thread-safe: owned by the Detector's single consumer thread.
 *
 * <p>Costs (w = values in the window): add O(log w) amortised, median O(1), MAD O(w) average (quickselect).
 */
public final class Window {

    // ---- ring buffer ------------------------------------------------------------------------------------------
    private long[] times;
    private double[] values;
    private int head;          // index of the oldest value
    private int size;

    // ---- two heaps: low holds the smaller half (max on top), high the larger half (min on top) -----------------
    private final PriorityQueue<Double> low = new PriorityQueue<>(Collections.reverseOrder());
    private final PriorityQueue<Double> high = new PriorityQueue<>();
    private final Map<Double, Integer> delayed = new HashMap<>();   // values left the window, still inside a heap
    private int lowSize;       // live values in low
    private int highSize;      // live values in high

    public Window(int initialCapacity) {
        int capacity = Math.max(8, initialCapacity);
        times = new long[capacity];
        values = new double[capacity];
    }

    public int size() {
        return size;
    }

    public long oldestTs() {
        return size == 0 ? Long.MIN_VALUE : times[head];
    }

    /** Adds a value at time {@code ts} and forgets values at or before {@code ts - historyMs}. */
    public void add(long ts, double value, long historyMs) {
        if (size == times.length) {
            grow();
        }
        int tail = (head + size) % times.length;
        times[tail] = ts;
        values[tail] = value;
        size++;
        insert(value);
        while (size > 0 && times[head] <= ts - historyMs) {
            double gone = values[head];
            head = (head + 1) % times.length;
            size--;
            remove(gone);
        }
    }

    /** The median of the values in the window; NaN when empty. */
    public double median() {
        if (lowSize + highSize == 0) {
            return Double.NaN;
        }
        if (lowSize > highSize) {
            return low.peek();
        }
        return (low.peek() + high.peek()) / 2;
    }

    /** Median absolute deviation from the median; NaN when empty. O(w) average by quickselect. */
    public double mad() {
        if (size == 0) {
            return Double.NaN;
        }
        double[] copy = snapshot();
        double median = medianOf(copy);
        for (int i = 0; i < copy.length; i++) {
            copy[i] = Math.abs(copy[i] - median);
        }
        return medianOf(copy);
    }

    /**
     * The line's usual wobble: MAD, which ignores up to half the values being unusual (earlier problems in the
     * window). When more than half the values are equal (a success rate sitting at 100%) MAD is 0, so the mean
     * absolute deviation from the median is used instead. O(w) average.
     */
    public double wobble() {
        if (size == 0) {
            return Double.NaN;
        }
        double mad = mad();
        if (mad > 0) {
            return mad;
        }
        double median = median();
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += Math.abs(values[(head + i) % values.length] - median);
        }
        return sum / size;
    }

    /** The window's values, oldest first (for tests and the brute-force check). */
    public double[] snapshot() {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = values[(head + i) % values.length];
        }
        return out;
    }

    private void grow() {
        long[] t = new long[times.length * 2];
        double[] v = new double[values.length * 2];
        for (int i = 0; i < size; i++) {
            t[i] = times[(head + i) % times.length];
            v[i] = values[(head + i) % values.length];
        }
        times = t;
        values = v;
        head = 0;
    }

    // ---- two heaps with lazy deletion (classic sliding-window median) ------------------------------------------

    private void insert(double v) {
        if (lowSize == 0 || v <= low.peek()) {
            low.add(v);
            lowSize++;
        } else {
            high.add(v);
            highSize++;
        }
        rebalance();
    }

    private void remove(double v) {
        delayed.merge(v, 1, Integer::sum);
        if (lowSize > 0 && v <= low.peek()) {
            lowSize--;
            if (v == low.peek()) {
                prune(low);
            }
        } else {
            highSize--;
            if (!high.isEmpty() && v == high.peek()) {
                prune(high);
            }
        }
        rebalance();
    }

    /** Low may hold one more live value than high, never fewer. */
    private void rebalance() {
        if (lowSize > highSize + 1) {
            high.add(low.poll());
            lowSize--;
            highSize++;
            prune(low);
        } else if (lowSize < highSize) {
            low.add(high.poll());
            highSize--;
            lowSize++;
            prune(high);
        }
    }

    /** Drops values from the top of a heap that already left the window. */
    private void prune(PriorityQueue<Double> heap) {
        while (!heap.isEmpty()) {
            Double top = heap.peek();
            Integer pending = delayed.get(top);
            if (pending == null) {
                return;
            }
            if (pending == 1) {
                delayed.remove(top);
            } else {
                delayed.put(top, pending - 1);
            }
            heap.poll();
        }
    }

    // ---- quickselect ------------------------------------------------------------------------------------------

    static double medianOf(double[] a) {
        int n = a.length;
        if (n % 2 == 1) {
            return select(a, n / 2);
        }
        double upper = select(a, n / 2);
        double lower = select(a, n / 2 - 1);
        return (lower + upper) / 2;
    }

    /** The k-th smallest value (0-based); reorders the array. O(n) average. */
    static double select(double[] a, int k) {
        int lo = 0;
        int hi = a.length - 1;
        while (lo < hi) {
            double pivot = a[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (a[i] < pivot) {
                    i++;
                }
                while (a[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    double t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                return a[k];
            }
        }
        return a[k];
    }

    @Override
    public String toString() {
        return "Window" + Arrays.toString(snapshot());
    }
}
