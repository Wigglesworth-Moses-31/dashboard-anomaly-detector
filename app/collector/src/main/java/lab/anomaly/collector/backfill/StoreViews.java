package lab.anomaly.collector.backfill;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.HashSet;
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
    }

    private final Supplier<String> address;
    private ManagedChannel channel;
    private String channelAddress;
    private Set<String> cached;
    private long cachedAtMs;

    public StoreViews(Supplier<String> address) {
        this.address = address;
    }

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
            stub().withDeadlineAfter(30, TimeUnit.SECONDS).listSeries(ListSeriesRequest.getDefaultInstance())
                    .forEachRemaining(d -> d.getViewsList().forEach(v -> out.add(key(d.getSource().getInstance(), v.getViewId()))));
            cached = out;
            cachedAtMs = now;
            return Optional.of(out);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
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
