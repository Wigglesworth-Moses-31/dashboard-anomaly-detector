package lab.anomaly.collector.emit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;

/**
 * Sends only what is new (add-collector design K7).
 * <ul>
 *   <li>A line's description is sent first when the line is new, and again when it changes.</li>
 *   <li>A partial point is always sent (it is still changing); a finished point is sent once, and again only if
 *       its value changed.</li>
 *   <li>Per line, the finished steps already sent live in a LinkedHashMap that drops its eldest entry beyond
 *       {@code maxSteps}: O(1) per point and fixed memory, however long the Collector runs.</li>
 * </ul>
 */
public final class Emitter {

    private volatile MessageSink sink;
    private final int maxSteps;
    private final Map<String, LineState> lines = new ConcurrentHashMap<>();
    private final Map<String, LateChanges> late = new ConcurrentHashMap<>();       // by source site

    /**
     * Finished steps that came back with another value, per source (fix-false-drops design F2): how many, and how long
     * after the step's end the latest and the slowest arrived. O(1) per change.
     */
    public static final class LateChanges {
        private final java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();
        private volatile long latestAfterMs;
        private volatile long maxAfterMs;

        synchronized void add(long afterMs) {
            count.incrementAndGet();
            latestAfterMs = afterMs;
            maxAfterMs = Math.max(maxAfterMs, afterMs);
        }

        public long count() {
            return count.get();
        }

        public long latestAfterMs() {
            return latestAfterMs;
        }

        public long maxAfterMs() {
            return maxAfterMs;
        }
    }

    /** Late changes of one source (by site), or null when there were none. */
    public LateChanges lateChanges(String site) {
        return late.get(site);
    }

    public Emitter(MessageSink sink, int maxSteps) {
        this.sink = sink;
        this.maxSteps = maxSteps;
    }

    private final class LineState {
        long firstSeenMs;
        int descriptionHash;
        final LinkedHashMap<Long, Double> sentFinished = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Double> eldest) {
                return size() > maxSteps;
            }
        };
    }

    /** Sends the description (if new or changed) and the new points of one line. */
    public void emit(SeriesDescriptor.Builder description, List<Point> points, long collectedMs) {
        String id = description.getSeriesId();
        LineState state = lines.computeIfAbsent(id, k -> new LineState());
        synchronized (state) {
            int hash = description.clone().clearFirstSeenMs().clearUpdatedMs().build().hashCode();
            if (state.firstSeenMs == 0 || state.descriptionHash != hash) {
                if (state.firstSeenMs == 0) {
                    state.firstSeenMs = collectedMs;
                }
                state.descriptionHash = hash;
                sink.send(description.setFirstSeenMs(state.firstSeenMs).setUpdatedMs(collectedMs).build());
            }
            MetricPoints.Builder fresh = MetricPoints.newBuilder().setSeriesId(id).setCollectedMs(collectedMs);
            for (Point point : points) {
                if (point.getPartial()) {
                    fresh.addPoints(point);
                    continue;
                }
                Double sent = state.sentFinished.get(point.getTsMs());
                if (sent != null && Double.compare(sent, point.getValue()) != 0) {
                    long stepMs = description.getStepSeconds() > 0 ? description.getStepSeconds() * 1000L : 10_000L;
                    late.computeIfAbsent(description.getSource().getInstance(), k -> new LateChanges())
                            .add(collectedMs - (point.getTsMs() + stepMs));
                }
                if (sent == null || Double.compare(sent, point.getValue()) != 0) {
                    state.sentFinished.put(point.getTsMs(), point.getValue());
                    fresh.addPoints(point);
                }
            }
            if (fresh.getPointsCount() > 0) {
                sink.send(fresh.build());
            }
        }
    }

    /**
     * Sends from now on to another sink, keeping the memory of what was already sent. Used when settings change
     * (for example recording switched on), so finished steps are not sent twice and recording sees new messages.
     */
    public void setSink(MessageSink newSink) {
        this.sink = newSink;
    }

    /** Number of lines seen so far. */
    public int lineCount() {
        return lines.size();
    }

    /** Remembered finished steps of one line (for tests and the health page). */
    int remembered(String seriesId) {
        LineState state = lines.get(seriesId);
        return state == null ? 0 : state.sentFinished.size();
    }
}
