package lab.anomaly.notifier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import lab.anomaly.contracts.Topics;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.notifier.Events;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.reactivestreams.Subscription;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.BaseSubscriber;

/**
 * Task 3.1 over the network, against the running Notifier (-Dlive.notifier=true): events published to Kafka reach an
 * RSocket client within 5 s; a client that asks for nothing does not delay another, and later gets the newest state.
 * Publishes incidents with ids "test-..." to incident-events (they close at the end).
 */
@EnabledIfSystemProperty(named = "live.notifier", matches = "true")
class NotifierLiveTest {

    static final class Client extends BaseSubscriber<String> {
        final List<String> got = new CopyOnWriteArrayList<>();
        final List<Long> at = new CopyOnWriteArrayList<>();
        final long initial;
        volatile boolean caughtUp;

        Client(long initial) {
            this.initial = initial;
        }

        @Override
        protected void hookOnSubscribe(Subscription s) {
            if (initial > 0) {
                s.request(initial);
            }
        }

        @Override
        protected void hookOnNext(String line) {
            if (line.contains("\"snapshot_complete\"")) {
                caughtUp = true;
            }
            got.add(line);
            at.add(System.currentTimeMillis());
        }
    }

    static RSocketRequester connect() {
        return RSocketRequester.builder().dataMimeType(MimeTypeUtils.APPLICATION_JSON)
                .rsocketStrategies(RSocketStrategies.create()).websocket(URI.create("ws://localhost:7104"));
    }

    @Test
    void changesArriveWithinFiveSecondsAndAStalledClientDoesNotDelayOthers() throws Exception {
        RSocketRequester r1 = connect();
        RSocketRequester r2 = connect();
        Client reading = new Client(Long.MAX_VALUE);
        Client stalled = new Client(0);
        r1.route("incidents.live").retrieveFlux(String.class).subscribe(reading);
        r2.route("incidents.live").retrieveFlux(String.class).subscribe(stalled);
        long until = System.currentTimeMillis() + 15_000;
        while (!reading.caughtUp && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(reading.caughtUp).as("snapshot then snapshot_complete").isTrue();
        int before = reading.got.size();

        Properties p = new Properties();
        p.put("bootstrap.servers", "localhost:59092");
        String id = "test-" + System.currentTimeMillis();
        long start = System.currentTimeMillis();
        try (var producer = new KafkaProducer<String, byte[]>(p, new StringSerializer(), new ByteArraySerializer())) {
            long[] sent = new long[21];
            for (int seq = 1; seq <= 20; seq++) {
                IncidentEvent e = Events.event(id, seq, seq == 20, start, start + 60_000);
                producer.send(new ProducerRecord<>(Topics.INCIDENT_EVENTS, id, e.toByteArray())).get(5, TimeUnit.SECONDS);
                sent[seq] = System.currentTimeMillis();
            }
            until = System.currentTimeMillis() + 10_000;
            while (reading.got.size() < before + 20 && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            assertThat(reading.got.size() - before).as("the reading client got every change").isEqualTo(20);
            long worst = 0;
            for (int k = 0; k < 20; k++) {
                worst = Math.max(worst, reading.at.get(before + k) - sent[k + 1]);
            }
            System.out.println("LIVE worst Kafka-to-client delay ms = " + worst);
            assertThat(worst).isLessThan(5_000);
        }

        stalled.request(Long.MAX_VALUE);
        Thread.sleep(1_000);
        List<String> mine = stalled.got.stream().filter(l -> l.contains(id)).toList();
        System.out.println("LIVE stalled client: " + mine.size() + " line(s) for the test incident; newest sequence 20: "
                + (mine.size() == 1 && mine.get(0).contains("\"sequence\":\"20\"")));
        assertThat(mine).singleElement().asString().contains("\"sequence\":\"20\"").contains("INCIDENT_STATE_CLOSED");
        r1.dispose();
        r2.dispose();
    }
}
