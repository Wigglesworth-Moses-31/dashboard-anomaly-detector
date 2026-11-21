package lab.anomaly.collector.backfill;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lab.anomaly.contracts.v1.ListSeriesRequest;
import lab.anomaly.contracts.v1.StoreServiceGrpc;

/**
 * Which dashboard views already have lines in the Store (add-backfill B2): "site|view id" for every view of every line
 * the Store knows. The Store is the record, so a restart backfills nothing that was collected. Cached for a minute.
 */
public class StoreViews implements AutoCloseable {

    /** What the engine needs: the collected views, or empty when the Store cannot be asked right now. */
    @FunctionalInterface
    public interface Collected {
        Optional<Set<String>> views();

        /** redesign-dashboard R1: "site|view id" → newest saved value of its lines (UTC ms); empty when unknown. */
        default java.util.Map<String, Long> newest() {
            return java.util.Map.of();
        }
    }

    private final Supplier<String> address;
    private ManagedChannel channel;
    private String channelAddress;
    private Set<String> cached;
    private long cachedAtMs;
    private java.util.Map<String, Long> newestCached = java.util.Map.of();

    public StoreViews(Supplier<String> address) {
        this.address = address;
    }

    /** Key present in {@link #newest()} when the Store answered (an older Store has no such call). */
    public static final String ANSWERED = "";

    public static String key(String site, String viewId) {
        return site + "|" + viewId;
    }

    public synchronized Optional<Set<String>> views() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAtMs < 60_000) {
            return Optional.of(cached);
        }
        try {
            Set<String> out = new HashSet<>();
            java.util.Map<String, List<String>> viewsOfLine = new java.util.HashMap<>();
            stub().withDeadlineAfter(30, TimeUnit.SECONDS).listSeries(ListSeriesRequest.getDefaultInstance())
                    .forEachRemaining(d -> d.getViewsList().forEach(v -> {
                        String k = key(d.getSource().getInstance(), v.getViewId());
                        out.add(k);
                        viewsOfLine.computeIfAbsent(d.getSeriesId(), x -> new java.util.ArrayList<>()).add(k);
                    }));
            // A view's gap starts where its freshest line stopped (a line that left the dashboard stays old)
            java.util.Map<String, Long> newest = new java.util.HashMap<>();
            try {
                stub().withDeadlineAfter(60, TimeUnit.SECONDS).getNewest(lab.anomaly.contracts.v1.NewestRequest.getDefaultInstance())
                        .forEachRemaining(n -> viewsOfLine.getOrDefault(n.getSeriesId(), List.of())
                                .forEach(k -> newest.merge(k, n.getNewestMs(), Math::max)));
                newest.put(ANSWERED, 0L);                       // the Store answers: a view missing here has no recent value
            } catch (RuntimeException e) {
                newest.clear();                                 // an older Store: no catch-up, as before
            }
            newestCached = java.util.Map.copyOf(newest);
            cached = out;
            cachedAtMs = now;
            return Optional.of(out);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public synchronized java.util.Map<String, Long> newest() {
        return newestCached;
    }

    /** Forget the cache (a backfill just added views). */
    public synchronized void invalidate() {
        cached = null;
    }

    private StoreServiceGrpc.StoreServiceBlockingStub stub() {
        String a = address.get();
        if (channel == null || !a.equals(channelAddress)) {
            if (channel != null) {
                channel.shutdownNow();
            }
            int colon = a.lastIndexOf(':');
            channel = ManagedChannelBuilder.forAddress(a.substring(0, colon), Integer.parseInt(a.substring(colon + 1))).usePlaintext().build();
            channelAddress = a;
        }
        return StoreServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
