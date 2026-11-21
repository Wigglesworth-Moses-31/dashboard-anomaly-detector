package lab.anomaly.notifier.dash;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.contracts.v1.HistoryBatchRequest;
import lab.anomaly.contracts.v1.HistoryChunk;
import lab.anomaly.contracts.v1.ListSeriesRequest;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.Resolution;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.StoreServiceGrpc;

/** The Notifier's questions to the Store (add-dashboard-view D1): every line, and many lines' history at once. */
public class StoreClient implements AutoCloseable {

    /** What the dashboard pages need from the Store (tests give a fake). */
    public interface Store {
        List<SeriesDescriptor> listSeries();

        Map<String, List<Point>> history(List<String> ids, long fromMs, long toMs, boolean raw);

        /** Rolled up in the Store to {@code intervalMs} with {@code method} (add-datadog-look K8). */
        default Map<String, List<Point>> history(List<String> ids, long fromMs, long toMs, long intervalMs, lab.anomaly.contracts.v1.Rollup method) {
            return history(ids, fromMs, toMs, true);
        }
    }

    private ManagedChannel channel;
    private String address;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong slowestMs = new AtomicLong();

    public synchronized StoreServiceGrpc.StoreServiceBlockingStub stub(String storeAddress) {
        if (channel == null || !storeAddress.equals(address)) {
            if (channel != null) {
                channel.shutdownNow();
            }
            int colon = storeAddress.lastIndexOf(':');
            channel = ManagedChannelBuilder.forAddress(storeAddress.substring(0, colon), Integer.parseInt(storeAddress.substring(colon + 1)))
                    .usePlaintext().build();
            address = storeAddress;
        }
        return StoreServiceGrpc.newBlockingStub(channel);
    }

    public Store store(java.util.function.Supplier<String> storeAddress) {
        return new Store() {
            @Override
            public List<SeriesDescriptor> listSeries() {
                List<SeriesDescriptor> out = new ArrayList<>();
                stub(storeAddress.get()).withDeadlineAfter(30, TimeUnit.SECONDS).listSeries(ListSeriesRequest.getDefaultInstance())
                        .forEachRemaining(out::add);
                return out;
            }

            @Override
            public Map<String, List<Point>> history(List<String> ids, long fromMs, long toMs, long intervalMs, lab.anomaly.contracts.v1.Rollup method) {
                long start = System.nanoTime();
                Map<String, List<Point>> out = new HashMap<>();
                for (int i = 0; i < ids.size(); i += 1000) {
                    Iterator<HistoryChunk> it = stub(storeAddress.get()).withDeadlineAfter(30, TimeUnit.SECONDS)
                            .getHistoryBatch(HistoryBatchRequest.newBuilder().addAllSeriesIds(ids.subList(i, Math.min(ids.size(), i + 1000)))
                                    .setFromMs(fromMs).setToMs(toMs).setResolution(Resolution.RESOLUTION_RAW)
                                    .setIntervalMs(intervalMs).setMethod(method).build());
                    it.forEachRemaining(c -> out.computeIfAbsent(c.getSeriesId(), k -> new ArrayList<>()).addAll(c.getPointsList()));
                }
                calls.incrementAndGet();
                slowestMs.accumulateAndGet((System.nanoTime() - start) / 1_000_000, Math::max);
                return out;
            }

            @Override
            public Map<String, List<Point>> history(List<String> ids, long fromMs, long toMs, boolean raw) {
                long start = System.nanoTime();
                Map<String, List<Point>> out = new HashMap<>();
                for (int i = 0; i < ids.size(); i += 1000) {             // the Store answers at most 1000 lines per question
                    Iterator<HistoryChunk> it = stub(storeAddress.get()).withDeadlineAfter(30, TimeUnit.SECONDS)
                            .getHistoryBatch(HistoryBatchRequest.newBuilder().addAllSeriesIds(ids.subList(i, Math.min(ids.size(), i + 1000)))
                                    .setFromMs(fromMs).setToMs(toMs)
                                    .setResolution(raw ? Resolution.RESOLUTION_RAW : Resolution.RESOLUTION_MINUTE).build());
                    it.forEachRemaining(c -> out.computeIfAbsent(c.getSeriesId(), k -> new ArrayList<>()).addAll(c.getPointsList()));
                }
                calls.incrementAndGet();
                slowestMs.accumulateAndGet((System.nanoTime() - start) / 1_000_000, Math::max);
                return out;
            }
        };
    }

    public long calls() {
        return calls.get();
    }

    public long slowestMs() {
        return slowestMs.get();
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
