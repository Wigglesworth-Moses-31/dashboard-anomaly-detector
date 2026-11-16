package lab.anomaly.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lab.anomaly.contracts.v1.HistoryChunk;
import lab.anomaly.contracts.v1.HistoryRequest;
import lab.anomaly.contracts.v1.PingRequest;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.Resolution;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.contracts.v1.StoreServiceGrpc;
import lab.anomaly.contracts.v1.SummaryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tasks 5.1 and 7.2 over the network against the running Store (-Dlive.grpc=true), as the Detector and Reporter
 * will call it. The /v1/invoices p95 latency line is the one where the Collector's live run found 6 incident spikes.
 */
@EnabledIfSystemProperty(named = "live.grpc", matches = "true")
class StoreGrpcClientLiveTest {

    static final String INVOICES_P95 = "341ca619230b22a2";
    static final long FROM = Instant.parse("2026-11-15T07:00:00Z").toEpochMilli();
    static final long TO = Instant.parse("2026-11-15T10:00:00Z").toEpochMilli();

    private ManagedChannel channel;
    private StoreServiceGrpc.StoreServiceBlockingStub store;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", 9102).usePlaintext().build();
        store = StoreServiceGrpc.newBlockingStub(channel).withDeadlineAfter(Duration.ofSeconds(20));
    }

    @AfterEach
    void close() {
        channel.shutdownNow();
    }

    private List<Point> history(Resolution resolution) {
        List<Point> out = new ArrayList<>();
        store.getHistory(HistoryRequest.newBuilder().setSeriesId(INVOICES_P95).setFromMs(FROM).setToMs(TO)
                .setResolution(resolution).build()).forEachRemaining((HistoryChunk c) -> {
                    assertThat(c.getPointsCount()).isLessThanOrEqualTo(1000);
                    out.addAll(c.getPointsList());
                });
        return out;
    }

    @Test
    void historyShowsTheInvoicesIncidentPeaks() {
        assertThat(store.ping(PingRequest.getDefaultInstance())).isNotNull();
        List<Point> raw = history(Resolution.RESOLUTION_RAW);
        List<Point> perMinute = history(Resolution.RESOLUTION_MINUTE);

        // Traffic generator: an incident every 8 minutes for 3 minutes (same rule as tools/analyze_collector_run.py).
        long every = 480_000, lasts = 180_000;
        java.util.function.LongPredicate inIncident = ts -> ts % every < lasts;
        double[] values = raw.stream().mapToDouble(Point::getValue).sorted().toArray();
        double[] calm = raw.stream().filter(p -> !inIncident.test(p.getTsMs())).mapToDouble(Point::getValue).sorted().toArray();
        double normal = calm[calm.length / 2];
        long lastTs = raw.get(raw.size() - 1).getTsMs();
        java.util.TreeMap<Long, Double> peakPerIncident = new java.util.TreeMap<>();
        for (Point p : raw) {
            long start = p.getTsMs() - p.getTsMs() % every;
            if (inIncident.test(p.getTsMs()) && start + lasts <= lastTs) {             // complete incidents only
                peakPerIncident.merge(start, p.getValue(), Math::max);
            }
        }
        List<String> peaks = new ArrayList<>();
        peakPerIncident.forEach((start, peak) -> peaks.add(Instant.ofEpochMilli(start) + String.format(" peak %.0f ms (%.1fx)%s",
                peak, peak / normal, peak >= 3 * normal ? "" : " MISSED")));
        SeriesSummary summary = new ArrayList<SeriesSummary>() {{
            store.getSummary(SummaryRequest.newBuilder().setFromMs(FROM).setToMs(TO).build()).forEachRemaining(this::add);
        }}.stream().filter(s -> s.getSeriesId().equals(INVOICES_P95)).findFirst().orElseThrow();

        System.out.printf("GRPC-HISTORY raw=%d perMinute=%d normal=%.0f ms peaks=%d%n", raw.size(), perMinute.size(), normal, peaks.size());
        peaks.forEach(p -> System.out.println("GRPC-PEAK " + p));
        System.out.printf("GRPC-SUMMARY count=%d min=%.0f max=%.0f mean=%.1f p95=%.0f%n", summary.getCount(), summary.getMin(),
                summary.getMax(), summary.getMean(), summary.getP95());

        assertThat(raw).extracting(Point::getTsMs).isSorted();
        assertThat(peaks).hasSize(6).noneMatch(x -> x.endsWith("MISSED"));
        assertThat(summary.getCount()).isEqualTo(raw.size());
        assertThat(summary.getMax()).isEqualTo(Arrays.stream(values).max().orElseThrow());
    }

    @Test
    void aFortyDayWindowIsRefusedOverTheNetwork() {
        assertThatThrownBy(() -> store.getSummary(SummaryRequest.newBuilder().setFromMs(FROM)
                .setToMs(FROM + Duration.ofDays(40).toMillis()).build()).hasNext())
                .hasMessageContaining("INVALID_ARGUMENT").hasMessageContaining("longer than 7 days");
    }
}
