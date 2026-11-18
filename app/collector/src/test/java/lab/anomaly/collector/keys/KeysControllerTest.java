package lab.anomaly.collector.keys;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** add-settings-page task 2.1: keys checked with a fake Datadog, written to .env, other lines kept. */
class KeysControllerTest {

    static final String GOOD_API = "a".repeat(32), GOOD_APP = "b".repeat(40);

    @TempDir Path dir;
    HttpServer datadog;
    final AtomicInteger changed = new AtomicInteger();
    KeysController controller;
    Path env;

    @BeforeEach
    void setUp() throws Exception {
        datadog = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        datadog.createContext("/api/v1/validate", x -> {
            int s = GOOD_API.equals(x.getRequestHeaders().getFirst("DD-API-KEY")) ? 200 : 403;
            byte[] b = (s == 200 ? "{\"valid\":true}" : "{\"errors\":[\"Forbidden: " + x.getRequestHeaders().getFirst("DD-API-KEY") + "\"]}").getBytes();
            x.sendResponseHeaders(s, b.length); x.getResponseBody().write(b); x.close();
        });
        datadog.createContext("/api/v1/query", x -> {
            int s = GOOD_APP.equals(x.getRequestHeaders().getFirst("DD-APPLICATION-KEY")) ? 200 : 403;
            byte[] b = "{}".getBytes();
            x.sendResponseHeaders(s, b.length); x.getResponseBody().write(b); x.close();
        });
        datadog.start();
        String base = "http://127.0.0.1:" + datadog.getAddress().getPort();
        env = dir.resolve(".env");
        Files.writeString(env, "# my lab\nPLATFORM_DB_PASSWORD=secret-db\nDD_US_API_KEY=old\nDD_US_APP_KEY=old\n");
        controller = new KeysController(Optional.of(new EnvFile(env)), new KeyChecker(site -> base), changed::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        datadog.stop(0);
    }

    @Test
    void validKeysAreSavedAndOtherLinesKept() throws Exception {
        var r = controller.save("eu", Map.of("api_key", GOOD_API, "app_key", " " + GOOD_APP + " "));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(Files.readString(env)).contains("# my lab", "PLATFORM_DB_PASSWORD=secret-db", "DD_US_API_KEY=old",
                "DD_EU_API_KEY=" + GOOD_API, "DD_EU_APP_KEY=" + GOOD_APP);
        assertThat(changed).hasValue(1);
        assertThat(controller.list()).filteredOn(m -> m.get("region").equals("eu")).singleElement()
                .satisfies(m -> assertThat(m.get("present")).isEqualTo(true));
        assertThat(controller.list().toString()).doesNotContain(GOOD_API).doesNotContain("old");
    }

    @Test
    void aWrongApiKeyIsRefusedWithoutEchoingIt() throws Exception {
        String before = Files.readString(env);
        var r = controller.save("eu", Map.of("api_key", "wrong-api-key-value", "app_key", GOOD_APP));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().get("problem")).isEqualTo("Datadog (datadoghq.eu) refused the API key");
        assertThat(r.getBody().toString()).doesNotContain("wrong-api-key-value");
        assertThat(Files.readString(env)).isEqualTo(before);
        assertThat(changed).hasValue(0);
    }

    @Test
    void aWrongApplicationKeyIsRefused() {
        var r = controller.check(Map.of("region", "us", "api_key", GOOD_API, "app_key", "nope"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat((String) r.getBody().get("problem")).startsWith("Datadog (datadoghq.com) refused the application key");
    }

    @Test
    void onlyUsAndEuAndBothKeys() {
        assertThat(controller.check(Map.of("region", "ap", "api_key", GOOD_API, "app_key", GOOD_APP)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.save("eu", Map.of("api_key", GOOD_API)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.save("eu", Map.of("api_key", GOOD_API, "app_key", "two\nlines")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void removingTakesOnlyThatRegionsLines() throws Exception {
        assertThat(controller.remove("us").getStatusCode().value()).isEqualTo(200);
        assertThat(Files.readString(env)).contains("PLATFORM_DB_PASSWORD=secret-db").doesNotContain("DD_US_");
        assertThat(changed).hasValue(1);
    }

    @Test
    void withoutAnEnvFileNothingIsWritten() {
        KeysController none = new KeysController(Optional.empty(), new KeyChecker(), () -> { });
        assertThat(none.save("eu", Map.of("api_key", GOOD_API, "app_key", GOOD_APP)).getStatusCode().value()).isEqualTo(503);
    }
}
