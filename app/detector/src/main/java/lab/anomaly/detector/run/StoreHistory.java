package lab.anomaly.detector.run;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import lab.anomaly.contracts.v1.HistoryRequest;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.Resolution;
import lab.anomaly.contracts.v1.StoreServiceGrpc;

/**
 * Asks the Store for one line's raw values before a time (warm start, design D3). One channel, reused.
 * A failure returns nothing: the line then warms from the values that arrive.
 */
final class StoreHistory implements AutoCloseable {

    record Values(long[] times, double[] values) {
        static final Values NONE = new Values(new long[0], new double[0]);
    }

    private final ManagedChannel channel;
    private final StoreServiceGrpc.StoreServiceBlockingStub stub;

    StoreHistory(String address) {
        int colon = address.lastIndexOf(':');
        channel = ManagedChannelBuilder.forAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)))
                .usePlaintext().build();
        stub = StoreServiceGrpc.newBlockingStub(channel);
    }

    /** Finished raw values in [from, to), oldest first. */
    Values history(String seriesId, long fromMs, long toMs) {
        var chunks = stub.withDeadlineAfter(5, TimeUnit.SECONDS).getHistory(HistoryRequest.newBuilder().setSeriesId(seriesId)
                .setFromMs(fromMs).setToMs(toMs).setResolution(Resolution.RESOLUTION_RAW).build());
        long[] t = new long[64];
        double[] v = new double[64];
        int n = 0;
        while (chunks.hasNext()) {
            for (Point p : chunks.next().getPointsList()) {
                if (p.getPartial()) {
                    continue;
                }
                if (n == t.length) {
                    t = java.util.Arrays.copyOf(t, n * 2);
                    v = java.util.Arrays.copyOf(v, n * 2);
                }
                t[n] = p.getTsMs();
                v[n] = p.getValue();
                n++;
            }
        }
        return new Values(java.util.Arrays.copyOf(t, n), java.util.Arrays.copyOf(v, n));
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
