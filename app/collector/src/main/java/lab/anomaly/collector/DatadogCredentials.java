package lab.anomaly.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * The Datadog API and application keys, read only from this process's environment (spec: no secrets in
 * configuration files). The values never leave this class in readable form: {@link #toString()} and the
 * startup log only say whether each key is present.
 */
public final class DatadogCredentials {

    private static final Logger log = LoggerFactory.getLogger(DatadogCredentials.class);

    private final String apiKey;
    private final String applicationKey;

    private DatadogCredentials(String apiKey, String applicationKey) {
        this.apiKey = blankToNull(apiKey);
        this.applicationKey = blankToNull(applicationKey);
    }

    /** Reads DD_API_KEY and DD_APP_KEY from the environment and logs which are present. */
    public static DatadogCredentials fromEnvironment(Environment environment) {
        return fromEnvironment(environment, "DD_API_KEY", "DD_APP_KEY", "default");
    }

    /**
     * Reads the two keys named by a source's settings (add-multi-site design M6). The names were checked against
     * {@code CollectorSettings.KEY_NAME} before; only presence is logged.
     */
    public static DatadogCredentials fromEnvironment(Environment environment, String apiKeyName, String appKeyName, String source) {
        DatadogCredentials credentials =
                new DatadogCredentials(environment.getProperty(apiKeyName), environment.getProperty(appKeyName));
        log.info("Datadog keys of source '{}' ({}, {}): {}", source, apiKeyName, appKeyName, credentials);
        return credentials;
    }

    /** What is missing, for health: "" when complete, else "API key", "application key" or "both". */
    public String missing() {
        if (apiKey == null && applicationKey == null) {
            return "both";
        }
        return apiKey == null ? "API key" : applicationKey == null ? "application key" : "";
    }

    public boolean complete() {
        return apiKey != null && applicationKey != null;
    }

    /** Only for building request headers; never log the result. */
    public String apiKey() {
        return apiKey;
    }

    /** Only for building request headers; never log the result. */
    public String applicationKey() {
        return applicationKey;
    }

    @Override
    public String toString() {
        return "API key " + (apiKey != null ? "present" : "MISSING")
                + ", application key " + (applicationKey != null ? "present" : "MISSING");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
