package lab.anomaly.reporter.source;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.RemoveRawReply;
import lab.anomaly.contracts.v1.RemoveRawRequest;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.contracts.v1.StoreServiceGrpc;
import lab.anomaly.contracts.v1.SummaryRequest;
import lab.anomaly.kit.settings.SettingsHolder;
import lab.anomaly.reporter.ReporterSettings;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The real sources (design R3, R4): Kafka topics read from the beginning up to their end (no group, nothing
 * committed), and the Store over gRPC. Each report reads afresh: a few thousand events, about a second.
 */
@Component
public class LiveSources implements Sources {

    private final String bootstrap;
    private final SettingsHolder<ReporterSettings> settings;
    private ManagedChannel channel;
    private String channelAddress = "";

    public LiveSources(Environment environment, SettingsHolder<ReporterSettings> settings) {
        this.bootstrap = environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092");
        this.settings = settings;
    }

    @Override
    public Collection<IncidentEvent> incidents() {
        Map<String, IncidentEvent> latest = new HashMap<>();
        readAll(Topics.INCIDENT_EVENTS, value -> {
            try {
                IncidentEvent e = IncidentEvent.parseFrom(value);
                if (ContractChecks.problems(e).isEmpty()) {
                    latest.merge(e.getIncidentId(), e, (a, b) -> b.getSequence() > a.getSequence() ? b : a);
                }
            } catch (Exception ignored) {
                // a broken event is not part of any report
            }
        });
        return latest.values();
    }

    @Override
    public Collection<SeriesDescriptor> lines() {
        Map<String, SeriesDescriptor> latest = new HashMap<>();
        readAll(Topics.METRIC_SERIES, value -> {
            try {
                SeriesDescriptor d = SeriesDescriptor.parseFrom(value);
                latest.merge(d.getSeriesId(), d, (a, b) -> b.getUpdatedMs() >= a.getUpdatedMs() ? b : a);
            } catch (Exception ignored) {
                // not readable: skipped
            }
        });
        return latest.values();
    }

    @Override
    public List<SeriesSummary> summaries(long fromMs, long toMs) {
        List<SeriesSummary> out = new ArrayList<>();
        stub().withDeadlineAfter(60, TimeUnit.SECONDS)
                .getSummary(SummaryRequest.newBuilder().setFromMs(fromMs).setToMs(toMs).build())
                .forEachRemaining(out::add);
        return out;
    }

    @Override
    public RemoveRawReply removeRawBefore(long beforeMs) {
        return stub().withDeadlineAfter(10, TimeUnit.MINUTES).removeRawBefore(RemoveRawRequest.newBuilder().setBeforeMs(beforeMs).build());
    }

    private synchronized StoreServiceGrpc.StoreServiceBlockingStub stub() {
        String address = settings.get().storeAddress();
        if (channel == null || !address.equals(channelAddress)) {
            if (channel != null) {
                channel.shutdownNow();
            }
            int colon = address.lastIndexOf(':');
            channel = ManagedChannelBuilder.forAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)))
                    .usePlaintext().build();
            channelAddress = address;
        }
        return StoreServiceGrpc.newBlockingStub(channel);
    }

    private void readAll(String topic, Consumer<byte[]> each) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "reporter");
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> parts = c.partitionsFor(topic).stream().map(i -> new TopicPartition(i.topic(), i.partition())).toList();
            c.assign(parts);
            c.seekToBeginning(parts);
            Map<TopicPartition, Long> end = c.endOffsets(parts);
            long until = System.currentTimeMillis() + 60_000;
            while (parts.stream().anyMatch(tp -> c.position(tp) < end.get(tp)) && System.currentTimeMillis() < until) {
                for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(500))) {
                    if (r.offset() < end.get(new TopicPartition(r.topic(), r.partition()))) {
                        each.accept(r.value());
                    }
                }
            }
        }
    }

    @PreDestroy
    void close() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
