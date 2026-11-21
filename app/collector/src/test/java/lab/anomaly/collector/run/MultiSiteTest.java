package lab.anomaly.collector.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lab.anomaly.collector.CollectorSettings;
import lab.anomaly.collector.CollectorSettings.ClassificationRule;
import lab.anomaly.collector.DatadogCredentials;
import lab.anomaly.collector.emit.MessageSink;
import lab.anomaly.collector.plan.PollPlan;
import lab.anomaly.collector.plan.PollUnit;
import lab.anomaly.collector.source.Dashboard;
import lab.anomaly.collector.source.MetricMetadata;
import lab.anomaly.collector.source.MetricSource;
import lab.anomaly.collector.source.SourceException;
import lab.anomaly.collector.source.TimeseriesAnswer;
import lab.anomaly.collector.source.TimeseriesRequest;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.mock.env.MockEnvironment;

/**
 * add-multi-site tasks 2.2-2.8: several sources at once, each in its own lane. Timing tests use the real clock with
 * a 300 ms poll interval, so a "15 s" behaviour runs in under a second; tolerances are generous to stay stable.
 */
class MultiSiteTest {

    private static final long INTERVAL_MS = 300;
    private static final String FAKE_KEY = "0123456789abcdef0123456789abcdef";

    /** A site for tests: its dashboards, behaviours on demand, every call's time recorded per question. */
    static class StubSite implements MetricSource {
        final String site;
        final int panels;
        volatile long dashboardBlockMs;
        volatile CountDownLatch dashboardGate;
        volatile SourceException.Kind failWith;
        volatile int retryAfterSeconds;
        volatile long answerDelayMs;
        volatile TimeseriesAnswer.RateLimitStatus reportedLimit;
        final AtomicInteger dashboardReads = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final Map<String, ConcurrentLinkedQueue<Long>> callTimes = new ConcurrentHashMap<>();

        StubSite(String site, int panels) {
            this.site = site;
            this.panels = panels;
        }

        @Override
        public Dashboard dashboard(String id) throws SourceException {
            dashboardReads.incrementAndGet();
            try {
                if (dashboardGate != null) {
                    dashboardGate.await(dashboardBlockMs, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<Dashboard.Panel> list = new ArrayList<>();
            for (int i = 0; i < panels; i++) {
                list.add(new Dashboard.Panel("Panel " + i, "", List.of(new Dashboard.Query("query1", "sum:lab.requests{panel:" + i + "}")),
                        List.of("query1")));
            }
            return new Dashboard(id, "Dashboard " + id, List.of(), list, 0);
        }

        @Override
        public TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException {
            calls.incrementAndGet();
            callTimes.computeIfAbsent(request.canonicalQuestion(), k -> new ConcurrentLinkedQueue<>()).add(System.currentTimeMillis());
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                if (answerDelayMs > 0) {
                    Thread.sleep(answerDelayMs);
                }
                SourceException.Kind kind = failWith;
                if (kind != null) {
                    throw new SourceException(kind, site + " says " + kind, retryAfterSeconds, null);
                }
                long t = (request.toMs() / 10_000) * 10_000 - 10_000;
                return new TimeseriesAnswer(List.of(t), List.of(new TimeseriesAnswer.Series(0, Map.of(), List.of(1.0))),
                        System.currentTimeMillis(), reportedLimit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException(SourceException.Kind.NETWORK, "interrupted");
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public Optional<MetricMetadata> metadata(String metric) {
            return Optional.empty();
        }

        /** Largest gap between two calls of the same question, over every question, from the second call on. */
        long largestGapMs(long fromMs, long toMs) {
            long worst = 0;
            for (ConcurrentLinkedQueue<Long> times : callTimes.values()) {
                Long previous = null;
                for (long t : times) {
                    if (t < fromMs || t > toMs) {
                        continue;
                    }
                    if (previous != null) {
                        worst = Math.max(worst, t - previous);
                    }
                    previous = t;
                }
            }
            return worst;
        }

        int callsBetween(long fromMs, long toMs) {
            return (int) callTimes.values().stream().flatMap(ConcurrentLinkedQueue::stream).filter(t -> t >= fromMs && t <= toMs).count();
        }
    }

    static CollectorSettings.Source source(String name, String site, String... dashboards) {
        String upper = name.toUpperCase();
        return new CollectorSettings.Source(name, site, "DD_" + upper + "_API_KEY", "DD_" + upper + "_APP_KEY",
                java.util.Arrays.stream(dashboards).map(CollectorSettings.DashboardRef::valueOf).toList(), Map.of(), 2, 1.0);
    }

    static CollectorSettings settings(CollectorSettings.Source... sources) {
        return new CollectorSettings("datadog", null, List.of(), Duration.ofMillis(INTERVAL_MS), Duration.ofSeconds(10),
                Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMinutes(60), Map.of(),
                List.<ClassificationRule>of(), 8, 1.0, 0, Duration.ofMillis(10), Duration.ofMillis(20), 2,
                Duration.ofMinutes(1), Duration.ofHours(24), 1000, new CollectorSettings.Recording(false, "recordings"), null,
                List.of(sources));
    }

    private final Map<String, StubSite> sites = new ConcurrentHashMap<>();
    private final AtomicReference<CollectorSettings> current = new AtomicReference<>();
    private final AtomicLong version = new AtomicLong(1);
    private final AtomicInteger messages = new AtomicInteger();
    private MockEnvironment env = new MockEnvironment()
            .withProperty("DD_US_API_KEY", FAKE_KEY).withProperty("DD_US_APP_KEY", FAKE_KEY)
            .withProperty("DD_EU_API_KEY", FAKE_KEY).withProperty("DD_EU_APP_KEY", FAKE_KEY);
    private CollectorEngine engine;

    private CollectorEngine start(CollectorSettings s) {
        current.set(s);
        engine = new CollectorEngine(current::get, version::get,
                c -> DatadogCredentials.fromEnvironment(env, c.apiKeyEnv(), c.appKeyEnv(), c.name()),
                (c, credentials, clock) -> sites.computeIfAbsent(c.site(), site -> new StubSite(site, 3)),
                null, Clock.systemUTC());
        engine.startLive(new MessageSink() {
            @Override public void send(SeriesDescriptor d) { messages.incrementAndGet(); }
            @Override public void send(MetricPoints p) { messages.incrementAndGet(); }
        });
        return engine;
    }

    private void change(CollectorSettings s) {
        current.set(s);
        version.incrementAndGet();
    }

    private StubSite site(String site) {
        return sites.computeIfAbsent(site, s -> new StubSite(s, 3));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as("condition within " + maxMs + " ms").isTrue();
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.stop();
        }
    }

    // ---- 2.2 units per lane ------------------------------------------------------------------------------------

    @Test
    void sameQuestionIsOneUnitInASourceAndTwoAcrossSources() {
        StubSite stub = new StubSite("x", 1);
        Dashboard a = new Dashboard("a", "A", List.of(), List.of(new Dashboard.Panel("P", "",
                List.of(new Dashboard.Query("query1", "sum:lab.requests{*}")), List.of("query1"))), 0);
        Dashboard b = new Dashboard("b", "B", List.of(), a.panels(), 0);
        List<PollUnit> us = PollPlan.build("us", List.of(a, b), Map.of(), Duration.ofSeconds(10));
        List<PollUnit> eu = PollPlan.build("eu", List.of(a), Map.of(), Duration.ofSeconds(10));

        assertThat(us).singleElement().satisfies(u -> assertThat(u.views()).hasSize(2));
        assertThat(us.get(0).key()).isEqualTo(eu.get(0).key());                // the line ids stay as before
        assertThat(us.get(0).id()).isEqualTo("us|" + us.get(0).key()).isNotEqualTo(eu.get(0).id());
        assertThat(stub.site).isEqualTo("x");
    }

    // ---- 2.3 discovery off the scheduler thread ------------------------------------------------------------------

    @Test
    void aBlockedDashboardReadInOneSourceDoesNotDelayTheOther() throws Exception {
        site("datadoghq.eu").dashboardGate = new CountDownLatch(1);
        site("datadoghq.eu").dashboardBlockMs = 60_000;
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));

        waitUntil(() -> site("datadoghq.com").calls.get() >= 6, 3000);
        long from = System.currentTimeMillis();
        Thread.sleep(2000);
        long to = System.currentTimeMillis();

        assertThat(engine.lane("eu").unitCount()).as("EU still reading its dashboards").isZero();
        assertThat(site("datadoghq.com").largestGapMs(from, to)).as("US gap between polls of one question")
                .isLessThanOrEqualTo(INTERVAL_MS + 100);
        site("datadoghq.eu").dashboardGate.countDown();
        waitUntil(() -> site("datadoghq.eu").calls.get() > 0, 3000);
    }

    // ---- 2.4 reconciler ---------------------------------------------------------------------------------------

    @Test
    void settingsChangesRebuildOnlyTheLanesThatChanged() throws Exception {
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));
        waitUntil(() -> engine.lane("us") != null && engine.lane("us").unitCount() == 3 && engine.lane("eu").unitCount() == 3, 3000);
        Lane us = engine.lane("us");
        Fetcher usFetcher = us.fetcher();
        Lane eu = engine.lane("eu");
        int euReads = site("datadoghq.eu").dashboardReads.get();

        // EU gets another dashboard: EU re-reads, keeps its lane; US is untouched
        change(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2", "d3")));
        waitUntil(() -> site("datadoghq.eu").dashboardReads.get() >= euReads + 2, 3000);
        assertThat(engine.lane("eu")).isSameAs(eu);
        assertThat(engine.lane("us")).isSameAs(us);
        assertThat(engine.lane("us").fetcher()).as("US keeps its token bucket and gate").isSameAs(usFetcher);

        // EU moves to another site: a new lane, old EU units gone
        change(settings(source("us", "datadoghq.com", "d1"), source("eu", "us5.datadoghq.com", "d2")));
        waitUntil(() -> engine.lane("eu") != eu && engine.lane("eu").unitCount() == 3, 3000);
        assertThat(engine.lane("us")).isSameAs(us);
        assertThat(engine.units().keySet()).allMatch(id -> id.startsWith("us|") || id.startsWith("eu|"));

        // EU removed: its units leave; a new source "ap" is added
        change(settings(source("us", "datadoghq.com", "d1"), source("ap", "ap1.datadoghq.com", "d4")));
        env.withProperty("DD_AP_API_KEY", FAKE_KEY).withProperty("DD_AP_APP_KEY", FAKE_KEY);
        waitUntil(() -> engine.lane("eu") == null && engine.lane("ap") != null && engine.lane("ap").unitCount() == 3, 3000);
        assertThat(engine.units().keySet()).noneMatch(id -> id.startsWith("eu|"));
        assertThat(engine.lane("us")).isSameAs(us);
    }

    // ---- 2.5 budget ------------------------------------------------------------------------------------------

    @Test
    void budgetIsPlannedRateOverAllowedRate() {
        assertThat(Lane.budget(120, 15, 300 * 0.8 / 10)).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Lane.budget(360, 15, 100 * 0.8 / 10)).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Lane.budget(0, 15, 24)).isZero();
    }

    @Test
    void overBudgetSourceIsNamedOnHealth() throws Exception {
        site("datadoghq.eu").reportedLimit = new TimeseriesAnswer.RateLimitStatus(1, 10, 1, 5);   // 0.1 per second
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));
        waitUntil(() -> site("datadoghq.eu").calls.get() > 0 && engine.lane("eu").fetcher().lastLimit() != null, 3000);

        Health health = new CollectorHealthIndicator(engine).health();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> sources = (Map<String, Map<String, Object>>) health.getDetails().get("sources");
        assertThat(sources).containsOnlyKeys("us", "eu");
        assertThat((long) sources.get("eu").get("budgetPercent")).isGreaterThan(90);
        assertThat((long) sources.get("us").get("budgetPercent")).isLessThan(90);
        assertThat(health.getDetails().get("warnings").toString()).contains("eu: the plan uses").doesNotContain("us: the plan");
    }

    // ---- 2.6 isolation ---------------------------------------------------------------------------------------

    @Test
    void euThrottledWaitsAloneWhileUsKeepsItsTiming() throws Exception {
        site("datadoghq.eu").failWith = SourceException.Kind.THROTTLED;
        site("datadoghq.eu").retryAfterSeconds = 4;                     // the window below ends well before the reset
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));
        waitUntil(() -> site("datadoghq.eu").calls.get() >= 1 && site("datadoghq.com").calls.get() >= 6, 3000);
        Thread.sleep(50);
        int euBefore = site("datadoghq.eu").calls.get();
        long from = System.currentTimeMillis();
        Thread.sleep(1500);
        long to = System.currentTimeMillis();

        assertThat(site("datadoghq.eu").calls.get()).as("no EU call while its gate is closed").isEqualTo(euBefore);
        assertThat(engine.lane("eu").fetcher().gate().waitMs(System.currentTimeMillis())).isPositive();
        assertThat(engine.lane("us").fetcher().gate().waitMs(System.currentTimeMillis())).isZero();
        assertThat(site("datadoghq.com").largestGapMs(from, to)).isLessThanOrEqualTo(INTERVAL_MS + 100);
    }

    @Test
    void euUnreachableOpensOnlyEuBreakers() throws Exception {
        site("datadoghq.eu").failWith = SourceException.Kind.NETWORK;
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));
        waitUntil(() -> engine.lane("eu") != null && engine.openBreakers(engine.lane("eu")).size() == 3, 4000);
        long from = System.currentTimeMillis();
        Thread.sleep(1000);
        long to = System.currentTimeMillis();

        assertThat(engine.openBreakers(engine.lane("us"))).isEmpty();
        assertThat(site("datadoghq.com").largestGapMs(from, to)).isLessThanOrEqualTo(INTERVAL_MS + 100);
        assertThat(new CollectorHealthIndicator(engine).health().getDetails().get("warnings").toString())
                .contains("eu: 3 panel(s) paused by their breaker").doesNotContain("us:");
    }

    @Test
    void euSlowUsesOnlyItsOwnSlots() throws Exception {
        StubSite eu = new StubSite("datadoghq.eu", 6);
        eu.answerDelayMs = 1500;
        sites.put("datadoghq.eu", eu);
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));
        waitUntil(() -> site("datadoghq.com").calls.get() >= 6 && eu.calls.get() >= 2, 3000);
        long from = System.currentTimeMillis();
        Thread.sleep(2000);
        long to = System.currentTimeMillis();

        assertThat(eu.maxInFlight.get()).as("EU in flight never above its own bulkhead of 2").isLessThanOrEqualTo(2);
        assertThat(site("datadoghq.com").largestGapMs(from, to)).isLessThanOrEqualTo(INTERVAL_MS + 100);
    }

    // ---- 2.7 spread ------------------------------------------------------------------------------------------

    @Test
    void twoSourcesDoNotBurstTogether() {
        long interval = 15_000;
        int[] perSecond = new int[15];
        for (String lane : List.of("us", "eu")) {
            for (int i = 0; i < 100; i++) {
                String id = lane + "|{\"queries\":[\"sum:lab.requests{panel:" + i + "}.rollup(max, 10)\"]}";
                perSecond[(int) (Math.floorMod(Scheduler.spread(id), interval) / 1000)]++;
            }
        }
        assertThat(java.util.Arrays.stream(perSecond).max().orElse(0)).isLessThanOrEqualTo(50);
    }

    // ---- 2.8 keys missing ------------------------------------------------------------------------------------

    @Test
    void missingApplicationKeyStopsOnlyThatSource() throws Exception {
        env = new MockEnvironment().withProperty("DD_US_API_KEY", FAKE_KEY).withProperty("DD_US_APP_KEY", FAKE_KEY)
                .withProperty("DD_EU_API_KEY", FAKE_KEY);
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));
        waitUntil(() -> site("datadoghq.com").calls.get() >= 3, 3000);
        Thread.sleep(300);

        assertThat(site("datadoghq.eu").dashboardReads.get() + site("datadoghq.eu").calls.get()).as("nothing sent to EU").isZero();
        Health health = new CollectorHealthIndicator(engine).health();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> sources = (Map<String, Map<String, Object>>) health.getDetails().get("sources");
        assertThat(sources.get("eu").get("keys")).isEqualTo("missing: application key");
        assertThat(sources.get("us").get("keys")).isEqualTo("present");
        assertThat(health.toString()).doesNotContain(FAKE_KEY);
        assertThat(messages.get()).isPositive();
    }

    // ---- settings binding (design M5) ------------------------------------------------------------------------

    @Test
    void sourcesBindFromYaml(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path yml = dir.resolve("collector.yml");
        Files.writeString(yml, """
                anomaly:
                  collector:
                    source: datadog
                    sources:
                      - name: us
                        site: datadoghq.com
                        api-key-env: DD_US_API_KEY
                        app-key-env: DD_US_APP_KEY
                        dashboards:
                          - file: dashboards/us/shop.json
                      - name: eu
                        site: datadoghq.eu
                        api-key-env: DD_EU_API_KEY
                        app-key-env: DD_EU_APP_KEY
                        dashboards:
                          - file: dashboards/eu/service-overview.json
                            id: stu-vwx-yz1
                            filters: { env: prod }
                            presets: all
                        max-concurrent-requests: 4
                    poll-interval: 15s
                    step: 10s
                    backfill: 10m
                    recent-window: 2m
                    dashboard-refresh: 60m
                    max-concurrent-requests: 8
                    rate-limit-share: 0.8
                    retries: 3
                    retry-base: 500ms
                    retry-cap: 8s
                    breaker-failures: 5
                    breaker-open: 5m
                    metadata-ttl: 24h
                    metadata-cache-size: 10000
                """);
        CollectorSettings s = ReplayComparison.settings(yml);
        assertThat(CollectorSettings.validSources(s)).isEmpty();
        assertThat(s.lanes()).extracting(CollectorSettings.LaneConfig::name).containsExactly("us", "eu");
        CollectorSettings.DashboardRef eu = s.lanes().get(1).dashboards().get(0);
        assertThat(eu.file()).isEqualTo("dashboards/eu/service-overview.json");
        assertThat(eu.id()).isEqualTo("stu-vwx-yz1");
        assertThat(eu.filters()).containsEntry("env", "prod");
        assertThat(eu.allPresets()).isTrue();
        assertThat(s.lanes().get(1).maxConcurrentRequests()).isEqualTo(4);
        assertThat(s.lanes().get(0).maxConcurrentRequests()).isEqualTo(8);
    }

    // ---- 6.1 the settings template, as setup.sh fills it, is valid ------------------------------------------

    @Test
    void theTemplateFilledBySetupIsValid(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String template = Files.readString(Path.of("../../config-template/regions.yml"));
        String block = """
                      - name: us
                        site: datadoghq.com
                        api-key-env: DD_US_API_KEY
                        app-key-env: DD_US_APP_KEY
                        dashboards:
                          - file: dashboards/us/shop.json
                      - name: eu
                        site: datadoghq.eu
                        api-key-env: DD_EU_API_KEY
                        app-key-env: DD_EU_APP_KEY
                        dashboards:
                          - file: dashboards/eu/shop.json
                            presets: all""";
        Files.writeString(dir.resolve("regions.yml"), template.replace("__SOURCES__", block.indent(6).stripTrailing()));   // as setup.sh writes it
        Files.copy(Path.of("../../config-template/collector.yml"), dir.resolve("collector.yml"));
        CollectorSettings s = lab.anomaly.collector.check.CheckDashboards.read(dir);
        java.lang.reflect.Method validators = CollectorSettings.class.getDeclaredMethod("validators");
        validators.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<lab.anomaly.kit.settings.SettingsValidator<? super CollectorSettings>> all =
                (List<lab.anomaly.kit.settings.SettingsValidator<? super CollectorSettings>>) validators.invoke(null);
        assertThat(all.stream().map(v -> v.check(s)).flatMap(Optional::stream).toList()).isEmpty();
        assertThat(s.lanes()).extracting(CollectorSettings.LaneConfig::site).containsExactly("datadoghq.com", "datadoghq.eu");
        assertThat(s.classification()).isNotEmpty();
    }

    // ---- found live in 7.3: a source whose dashboards cannot be read must not look healthy ---------------

    @Test
    void unreadableDashboardsAreNamedAndRetriedSoon() throws Exception {
        StubSite eu = new StubSite("datadoghq.eu", 3) {
            @Override
            public Dashboard dashboard(String id) throws SourceException {
                dashboardReads.incrementAndGet();
                throw new SourceException(SourceException.Kind.NETWORK, "network: ConnectException");
            }
        };
        sites.put("datadoghq.eu", eu);
        start(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2", "d3")));
        waitUntil(() -> eu.dashboardReads.get() >= 2 && engine.lane("eu").lastDiscoveryMs > 0, 3000);

        Health health = new CollectorHealthIndicator(engine).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails().get("warnings").toString())
                .contains("eu: 2 of 2 dashboard(s) cannot be read").doesNotContain("us:");
        assertThat(engine.lane("eu").nextDiscoveryMs - engine.lane("eu").lastDiscoveryMs)
                .isLessThanOrEqualTo(CollectorEngine.UNREADABLE_RETRY_MS);
        assertThat(engine.lane("us").nextDiscoveryMs - engine.lane("us").lastDiscoveryMs).isGreaterThan(CollectorEngine.UNREADABLE_RETRY_MS);
    }

    // ---- add-settings-page 1.1: regions.yml is read with collector.yml and wins ----------------------------------

    @Test
    void regionsYmlWinsAndCollectorYmlAloneStillLoads(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.copy(Path.of("../../config-template/collector.yml"), dir.resolve("collector.yml"));
        CollectorSettings alone = lab.anomaly.collector.check.CheckDashboards.read(dir);
        assertThat(alone.sources()).isEmpty();
        assertThat(alone.pollInterval()).isEqualTo(java.time.Duration.ofSeconds(15));
        Files.writeString(dir.resolve("collector.yml"), Files.readString(dir.resolve("collector.yml")).replace(
                "    poll-interval: 15s", """
                    sources:
                      - { name: us, site: datadoghq.com, api-key-env: DD_US_API_KEY, app-key-env: DD_US_APP_KEY }
                    poll-interval: 15s""".indent(4).stripTrailing()));
        Files.writeString(dir.resolve("regions.yml"), """
                anomaly:
                  collector:
                    sources:
                      - { name: eu, site: datadoghq.eu, api-key-env: DD_EU_API_KEY, app-key-env: DD_EU_APP_KEY }
                """);
        CollectorSettings both = lab.anomaly.collector.check.CheckDashboards.read(dir);
        assertThat(both.sources()).extracting(CollectorSettings.Source::name).containsExactly("eu");
        assertThat(both.pollInterval()).isEqualTo(java.time.Duration.ofSeconds(15));
    }

    // ---- add-settings-page 1.2: keys from .env on every reload ---------------------------------------------------

    @Test
    void newKeysInEnvFileRestartOnlyThatLaneAndAreNeverLogged(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String usKey = "us0000000000000000000000000000aa", euOld = "eu0000000000000000000000000000bb", euNew = "eu1111111111111111111111111111cc";
        Path file = dir.resolve(".env");
        Files.writeString(file, "# lab\nPLATFORM_DB_PASSWORD=not-read\n");
        lab.anomaly.collector.keys.EnvFile envFile = new lab.anomaly.collector.keys.EnvFile(file);
        envFile.write(Map.of("DD_US_API_KEY", usKey, "DD_US_APP_KEY", usKey, "DD_EU_API_KEY", euOld, "DD_EU_APP_KEY", euOld));
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            MockEnvironment empty = new MockEnvironment();                    // nothing in the environment: .env only
            current.set(settings(source("us", "datadoghq.com", "d1"), source("eu", "datadoghq.eu", "d2")));
            engine = new CollectorEngine(current::get, version::get,
                    c -> DatadogCredentials.from(java.util.Optional.of(envFile), empty, c.apiKeyEnv(), c.appKeyEnv(), c.name()),
                    (c, credentials, clock) -> sites.computeIfAbsent(c.site(), site -> new StubSite(site, 3)),
                    null, Clock.systemUTC());
            engine.startLive(new MessageSink() {
                @Override public void send(SeriesDescriptor d) { }
                @Override public void send(MetricPoints p) { }
            });
            waitUntil(() -> engine.lane("us") != null && engine.lane("eu") != null && engine.lane("eu").ready(), 3000);
            Lane us = engine.lane("us"), eu = engine.lane("eu");

            engine.keysChanged();                                             // nothing changed in the file: both kept
            Thread.sleep(3 * INTERVAL_MS);
            assertThat(engine.lane("eu")).isSameAs(eu);

            envFile.write(Map.of("DD_EU_API_KEY", euNew, "DD_EU_APP_KEY", euNew));
            engine.keysChanged();
            waitUntil(() -> engine.lane("eu") != eu && engine.lane("eu") != null, 3000);
            assertThat(engine.lane("us")).as("US keeps its lane").isSameAs(us);
            assertThat(Files.readString(file)).contains("# lab", "PLATFORM_DB_PASSWORD=not-read", "DD_EU_APP_KEY=" + euNew)
                    .doesNotContain(euOld);
            assertThat(java.nio.file.Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        } finally {
            root.detachAppender(logs);
        }
        assertThat(logs.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain(usKey).doesNotContain(euOld).doesNotContain(euNew));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> envFile.get("PLATFORM_DB_PASSWORD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- add-backfill: never-collected views get their last days first -------------------------------------------

    /** Each dashboard has its own questions; every answer has a finished value at the start of the asked window. */
    static final class BackfillSite extends StubSite {
        BackfillSite(String site) {
            super(site, 3);
        }

        @Override
        public Dashboard dashboard(String id) throws SourceException {
            Dashboard d = super.dashboard(id);
            List<Dashboard.Panel> own = d.panels().stream().map(p -> new Dashboard.Panel(p.title(), p.path(),
                    List.of(new Dashboard.Query("query1", p.queries().get(0).text().replace("}", ",dash:" + id + "}"))), p.formulas())).toList();
            return new Dashboard(id, d.title(), List.of(), own, 0);
        }

        @Override
        public TimeseriesAnswer timeseries(TimeseriesRequest request) throws SourceException {
            calls.incrementAndGet();
            long t = (request.fromMs() / 10_000) * 10_000;
            List<TimeseriesAnswer.Series> series = new ArrayList<>();
            for (int i = 0; i < request.formulas().size(); i++) {
                series.add(new TimeseriesAnswer.Series(i, Map.of(), List.of(1.0 + i)));
            }
            return new TimeseriesAnswer(List.of(t), series, System.currentTimeMillis(), null);
        }
    }

    @Test
    void onlyNewViewsAreBackfilledOldestFirstThenPolledLive() throws Exception {
        java.util.List<MetricPoints> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        current.set(settings(source("us", "datadoghq.com", "d1", "d2")));
        engine = new CollectorEngine(current::get, version::get,
                c -> DatadogCredentials.fromEnvironment(env, c.apiKeyEnv(), c.appKeyEnv(), c.name()),
                (c, credentials, clock) -> new BackfillSite(c.site()), null, Clock.systemUTC());
        engine.setCollectedViews(() -> Optional.of(java.util.Set.of("datadoghq.com|d1")), Duration.ofHours(2), Duration.ofHours(1), 0);
        engine.startLive(new MessageSink() {
            @Override public void send(SeriesDescriptor d) { }
            @Override public void send(MetricPoints p) { sent.add(p); }
        });
        waitUntil(() -> engine.lane("us") != null && engine.lane("us").unitCount() == 6, 3000);
        waitUntil(() -> sent.stream().filter(MetricPoints::getBackfill).count() >= 6, 5000);     // 3 panels × 2 windows
        waitUntil(() -> engine.held().isEmpty(), 3000);
        java.util.List<MetricPoints> backfill = sent.stream().filter(MetricPoints::getBackfill).toList();
        assertThat(backfill).hasSize(6);
        // oldest first: the first window's points come before the second's
        assertThat(backfill.get(0).getPoints(0).getTsMs()).isLessThan(backfill.get(5).getPoints(0).getTsMs());
        assertThat(engine.backfills()).isEmpty();
        int liveBefore = (int) sent.stream().filter(m -> !m.getBackfill()).count();
        waitUntil(() -> sent.stream().filter(m -> !m.getBackfill()).count() >= liveBefore + 6, 3000);   // all 6 polled live now
        engine.stop();

        java.util.List<MetricPoints> again = java.util.Collections.synchronizedList(new ArrayList<>());
        engine = new CollectorEngine(current::get, version::get,
                c -> DatadogCredentials.fromEnvironment(env, c.apiKeyEnv(), c.appKeyEnv(), c.name()),
                (c, credentials, clock) -> new BackfillSite(c.site()), null, Clock.systemUTC());
        engine.setCollectedViews(() -> Optional.of(java.util.Set.of("datadoghq.com|d1", "datadoghq.com|d2")), Duration.ofHours(2), Duration.ofHours(1), 0);
        engine.startLive(new MessageSink() {
            @Override public void send(SeriesDescriptor d) { }
            @Override public void send(MetricPoints p) { again.add(p); }
        });
        waitUntil(() -> again.size() >= 6, 3000);
        Thread.sleep(3 * INTERVAL_MS);
        assertThat(again).as("a restart backfills nothing").noneMatch(MetricPoints::getBackfill);
    }

    @Test
    void whileTheStoreCannotBeAskedNewUnitsWait() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean storeUp = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.List<MetricPoints> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        current.set(settings(source("us", "datadoghq.com", "d1")));
        engine = new CollectorEngine(current::get, version::get,
                c -> DatadogCredentials.fromEnvironment(env, c.apiKeyEnv(), c.appKeyEnv(), c.name()),
                (c, credentials, clock) -> new BackfillSite(c.site()), null, Clock.systemUTC());
        engine.setCollectedViews(() -> storeUp.get() ? Optional.of(java.util.Set.of()) : Optional.empty(), Duration.ofHours(1), Duration.ofHours(1), 0);
        engine.startLive(new MessageSink() {
            @Override public void send(SeriesDescriptor d) { }
            @Override public void send(MetricPoints p) { sent.add(p); }
        });
        waitUntil(() -> engine.lane("us") != null && engine.lane("us").unitCount() == 3, 3000);
        Thread.sleep(3 * INTERVAL_MS);
        assertThat(sent).as("nothing polled while the Store's answer is awaited").isEmpty();
        assertThat(engine.held()).hasSize(3);
        storeUp.set(true);
        waitUntil(() -> sent.stream().anyMatch(MetricPoints::getBackfill), 15_000);
        waitUntil(() -> sent.stream().anyMatch(m -> !m.getBackfill()), 5_000);
    }

    /** redesign-dashboard R1: a collected view whose newest value is {@code ageMs} old; what the catch-up sends. */
    private java.util.List<MetricPoints> catchUp(long ageMs) throws Exception {
        java.util.List<MetricPoints> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        current.set(settings(source("us", "datadoghq.com", "d1")));
        engine = new CollectorEngine(current::get, version::get,
                c -> DatadogCredentials.fromEnvironment(env, c.apiKeyEnv(), c.appKeyEnv(), c.name()),
                (c, credentials, clock) -> new BackfillSite(c.site()), null, Clock.systemUTC());
        long newest = System.currentTimeMillis() - ageMs;
        engine.setCollectedViews(new lab.anomaly.collector.backfill.StoreViews.Collected() {
            @Override public Optional<java.util.Set<String>> views() { return Optional.of(java.util.Set.of("datadoghq.com|d1")); }
            @Override public Map<String, Long> newest() {
                return Map.of("datadoghq.com|d1", newest, lab.anomaly.collector.backfill.StoreViews.ANSWERED, 0L);
            }
        }, Duration.ofHours(24), Duration.ofHours(4), 0);
        engine.startLive(new MessageSink() {
            @Override public void send(SeriesDescriptor d) { }
            @Override public void send(MetricPoints p) { sent.add(p); }
        });
        waitUntil(() -> engine.lane("us") != null && engine.lane("us").unitCount() == 3, 3000);
        waitUntil(() -> sent.stream().anyMatch(m -> !m.getBackfill()), 10_000);              // live polling has started
        waitUntil(() -> engine.held().isEmpty(), 3000);
        engine.stop();
        return sent.stream().filter(MetricPoints::getBackfill).toList();
    }

    @Test
    void aNineHourGapIsFilledFromTheNewestValue() throws Exception {
        long start = System.currentTimeMillis() - Duration.ofHours(9).toMillis();
        java.util.List<MetricPoints> filled = catchUp(Duration.ofHours(9).toMillis());
        assertThat(filled).as("3 panels × 3 windows of 4 hours").hasSize(9);
        assertThat(filled.get(0).getPoints(0).getTsMs()).isBetween(start - 20_000, start + 20_000);
    }

    @Test
    void aThreeDayGapFillsOnlyTheLastDay() throws Exception {
        long dayAgo = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        java.util.List<MetricPoints> filled = catchUp(Duration.ofDays(3).toMillis());
        assertThat(filled).as("3 panels × 6 windows of 4 hours").hasSize(18);
        assertThat(filled.get(0).getPoints(0).getTsMs()).isBetween(dayAgo - 20_000, dayAgo + 20_000);
    }

    @Test
    void aShortRestartCatchesUpNothing() throws Exception {
        assertThat(catchUp(30_000)).isEmpty();
    }
}
