package lab.anomaly.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatadogCredentialsTest {

    private static final String FAKE_API_KEY = "0123456789abcdef0123456789abcdef";
    private static final String FAKE_APP_KEY = "fedcba9876543210fedcba9876543210fedcba98";

    @Test
    void keysAreReadButNeverShown() {
        DatadogCredentials credentials = DatadogCredentials.fromEnvironment(new MockEnvironment()
                .withProperty("DD_API_KEY", FAKE_API_KEY)
                .withProperty("DD_APP_KEY", FAKE_APP_KEY));

        assertThat(credentials.complete()).isTrue();
        assertThat(credentials.toString())
                .isEqualTo("API key present, application key present")
                .doesNotContain(FAKE_API_KEY)
                .doesNotContain(FAKE_APP_KEY);
    }

    @Test
    void missingKeysAreReportedNotHidden() {
        DatadogCredentials credentials = DatadogCredentials.fromEnvironment(new MockEnvironment());

        assertThat(credentials.complete()).isFalse();
        assertThat(credentials.toString()).isEqualTo("API key MISSING, application key MISSING");
    }

    @Test
    void keysAreReadByTheirSettingsNames() {
        DatadogCredentials credentials = DatadogCredentials.fromEnvironment(new MockEnvironment()
                .withProperty("DD_EU_API_KEY", FAKE_API_KEY)
                .withProperty("DD_EU_APP_KEY", FAKE_APP_KEY), "DD_EU_API_KEY", "DD_EU_APP_KEY", "eu");
        assertThat(credentials.complete()).isTrue();
        assertThat(credentials.missing()).isEmpty();
    }

    @Test
    void apiKeyWithoutApplicationKeyIsNamed() {
        DatadogCredentials credentials = DatadogCredentials.fromEnvironment(new MockEnvironment()
                .withProperty("DD_EU_API_KEY", FAKE_API_KEY), "DD_EU_API_KEY", "DD_EU_APP_KEY", "eu");
        assertThat(credentials.complete()).isFalse();
        assertThat(credentials.missing()).isEqualTo("application key");
        assertThat(credentials.toString()).doesNotContain(FAKE_API_KEY);
        assertThat(DatadogCredentials.fromEnvironment(new MockEnvironment(), "DD_EU_API_KEY", "DD_EU_APP_KEY", "eu").missing())
                .isEqualTo("both");
    }
}
