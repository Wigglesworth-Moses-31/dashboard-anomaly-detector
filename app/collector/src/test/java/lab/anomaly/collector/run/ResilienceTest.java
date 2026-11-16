package lab.anomaly.collector.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.Dashboard.Query;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.SourceException.Kind;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import lab.anomaly.collector.support.MutableClock;
import org.junit.jupiter.api.Test;

/** Tasks 5.1 to 5.3: scheduler, token bucket, 429 gate, bulkhead, retries, circuit breaker. */
class ResilienceTest {

    private static final long T0 = 1_763_182_800_000L;

    // ---- 5.1 scheduler ----------------------------------------------------------------------------

    @Test
    void eachUnitRunsOncePerIntervalAndStartsAreSpread() {
        Scheduler scheduler = new Scheduler(15_000);
        List<String> keys = List.of("unit-a", "unit-b", "unit-c", "unit-d", "unit-e");
        scheduler.setUnits(keys, T0);
        Map<String, List<Long>> runs = new HashMap<>();
        for (long now = T0; now < T0 + 60_000; now += 100) {
            for (String key : scheduler.takeDue(now)) {
                runs.computeIfAbsent(key, k -> new ArrayList<>()).add(now);
                scheduler.finished(key);
            }
        }
        assertThat(runs.keySet()).containsExactlyInAnyOrderElementsOf(keys);
        runs.values().forEach(times -> {
            assertThat(times).hasSize(4);                                         // 60 s / 15 s
            for (int i = 1; i < times.size(); i++) {
                assertThat(times.get(i) - times.get(i - 1)).isBetween(14_900L, 15_100L);
            }
        });
        // Keys differing only in their last letter must still start at clearly different times
        List<Long> firsts = runs.values().stream().map(t -> t.get(0)).sorted().toList();
        assertThat(firsts).as("first starts spread").doesNotHaveDuplicates();
        assertThat(firsts.get(firsts.size() - 1) - firsts.get(0)).as("spread across the interval").isGreaterThan(5_000);
    }

    @Test
    void aStillRunningUnitSkipsItsTurn() {
        Scheduler scheduler = new Scheduler(15_000);
        scheduler.setUnits(List.of("slow"), T0);
        int started = 0;
        for (long now = T0; now < T0 + 60_000; now += 100) {
            started += scheduler.takeDue(now).size();                             // never finished
        }
        assertThat(started).isEqualTo(1);
        assertThat(scheduler.skippedTurns()).isEqualTo(3);
    }

    // ---- 5.2 token bucket, gate, bulkhead -----------------------------------------------------------

    @Test
    void tokenBucketMakesTheFourthCallWait() {
        AtomicLong nanos = new AtomicLong();
        TokenBucket bucket = new TokenBucket(3, 1, nanos::get);
        assertThat(bucket.reserve()).isZero();
        assertThat(bucket.reserve()).isZero();
        assertThat(bucket.reserve()).isZero();
        assertThat(TimeUnit.NANOSECONDS.toMillis(bucket.reserve())).isEqualTo(1_000);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertThat(bucket.reserve()).isZero();
    }

    /** A source whose answers are scripted per call. */
    private static class ScriptedSource implements MetricSource {
        final List<Object> script = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public Dashboard dashboard(String id) { throw new UnsupportedOperationException(); }
        @Override public Optional<MetricMetadata> metadata(String m) { return Optional.empty(); }
        @Override public TimeseriesAnswer timeseries(TimeseriesRequest r) throws SourceException {
            Object next = script.get(Math.min(calls.getAndIncrement(), script.size() - 1));
            if (next instanceof SourceException e) {
                throw e;
            }
            return (TimeseriesAnswer) next;
        }
    }

    private static final TimeseriesRequest REQUEST = new TimeseriesRequest(List.of(new Query("query1", "sum:x{*}")), List.of("query1"), 0, 1, 10_000);
    private static final TimeseriesAnswer OK = new TimeseriesAnswer(List.of(), List.of(), T0, null);

    private static Fetcher fetcher(MetricSource source, MutableClock clock, List<Long> slept, int retries) {
        return new Fetcher(source, clock, ms -> { slept.add(ms); clock.advance(Duration.ofMillis(ms)); }, 8, 0.8, retries,
                new Backoff(Duration.ofMillis(500), Duration.ofSeconds(8), new Random(1)));
    }

    @Test
    void a429PausesEveryCallerUntilTheReset() throws Exception {
        ScriptedSource source = new ScriptedSource();
        source.script.add(new SourceException(Kind.THROTTLED, "HTTP 429", 5, null));
        source.script.add(OK);
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(T0));
        List<Long> slept = new ArrayList<>();
        Fetcher fetcher = fetcher(source, clock, slept, 3);

        fetcher.fetch(REQUEST);                                                    // throttled, waits 5 s, succeeds
        assertThat(slept).contains(5_000L);
        assertThat(fetcher.throttledCount()).isEqualTo(1);

        // another caller right after a fresh 429 waits for the same gate
        fetcher.gate().closeFor(5, clock.millis());
        slept.clear();
        fetcher.fetch(REQUEST);
        assertThat(slept).contains(5_000L);
    }

    @Test
    void bulkheadLimitsCallsInFlight() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        MetricSource slow = new ScriptedSource() {
            @Override public TimeseriesAnswer timeseries(TimeseriesRequest r) {
                maxSeen.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                return OK;
            }
        };
        Fetcher fetcher = new Fetcher(slow, java.time.Clock.systemUTC(), Thread::sleep, 3, 1.0, 0,
                new Backoff(Duration.ofMillis(1), Duration.ofMillis(1), new Random(1)));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> fetcher.fetch(REQUEST));
            }
            Thread.sleep(300);
            assertThat(fetcher.inFlight(3)).isEqualTo(3);
            release.countDown();
        }
        assertThat(maxSeen.get()).isEqualTo(3);
    }

    // ---- 5.3 retries and the circuit breaker ----------------------------------------------------------

    @Test
    void serverErrorsAreRetriedWithGrowingRandomWaits() {
        ScriptedSource source = new ScriptedSource();
        source.script.add(new SourceException(Kind.SERVER, "HTTP 503"));
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(T0));
        List<Long> slept = new ArrayList<>();

        assertThatThrownBy(() -> fetcher(source, clock, slept, 3).fetch(REQUEST)).isInstanceOf(SourceException.class);
        assertThat(source.calls.get()).isEqualTo(4);                                // 1 try + 3 retries
        assertThat(slept).hasSize(3).allMatch(ms -> ms >= 0 && ms <= 8_000);
        assertThat(slept.get(0)).isLessThanOrEqualTo(500);                          // caps 0.5 s, 1 s, 2 s
        assertThat(slept.get(1)).isLessThanOrEqualTo(1_000);
        assertThat(slept.get(2)).isLessThanOrEqualTo(2_000);
    }

    @Test
    void aBadQuestionIsNotRetried() {
        ScriptedSource source = new ScriptedSource();
        source.script.add(new SourceException(Kind.BAD_REQUEST, "HTTP 400"));
        assertThatThrownBy(() -> fetcher(source, new MutableClock(Instant.ofEpochMilli(T0)), new ArrayList<>(), 3).fetch(REQUEST))
                .isInstanceOf(SourceException.class);
        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    void breakerOpensAfterFiveFailuresThenTriesOnce() {
        CircuitBreaker breaker = new CircuitBreaker(5, 300_000);
        for (int i = 0; i < 5; i++) {
            assertThat(breaker.allow(T0)).isTrue();
            breaker.onFailure(T0, "HTTP 400");
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allow(T0 + 60_000)).isFalse();
        assertThat(breaker.allow(T0 + 300_000)).isTrue();                          // half-open: one try
        breaker.onFailure(T0 + 300_000, "HTTP 400");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allow(T0 + 600_001)).isTrue();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aBrokenUnitOpensItsBreakerWhileTheOtherStaysOnTime() {
        // Two units on one scheduler, as the engine runs them; unit "broken" always gets a 400
        Scheduler scheduler = new Scheduler(15_000);
        scheduler.setUnits(List.of("good", "broken"), T0);
        Map<String, CircuitBreaker> breakers = Map.of("good", new CircuitBreaker(5, 300_000), "broken", new CircuitBreaker(5, 300_000));
        Map<String, List<Long>> calls = new HashMap<>();
        for (long now = T0; now < T0 + 180_000; now += 100) {
            for (String key : scheduler.takeDue(now)) {
                CircuitBreaker breaker = breakers.get(key);
                if (breaker.allow(now)) {
                    calls.computeIfAbsent(key, k -> new ArrayList<>()).add(now);
                    if (key.equals("broken")) {
                        breaker.onFailure(now, "HTTP 400 bad query");
                    } else {
                        breaker.onSuccess();
                    }
                }
                scheduler.finished(key);
            }
        }
        assertThat(calls.get("good")).hasSize(12);                                  // every 15 s for 3 minutes
        assertThat(calls.get("broken")).hasSize(5);                                 // then its breaker is open
        assertThat(breakers.get("broken").state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
