package lab.anomaly.contracts;

import java.util.Locale;
import java.util.Map;

/**
 * Datadog sites as people say them (add-multi-site design M9). One place, so the Detector, Notifier and Reporter
 * always agree: a short label for titles ("EU", "US1"...) and the host that serves a site's web pages. Both are O(1)
 * lookups in a small fixed map; an unknown site is shown as it is.
 */
public final class Sites {

    private static final Map<String, String> LABELS = Map.of(
            "datadoghq.com", "US1",
            "us3.datadoghq.com", "US3",
            "us5.datadoghq.com", "US5",
            "datadoghq.eu", "EU",
            "ap1.datadoghq.com", "AP1",
            "ap2.datadoghq.com", "AP2",
            "ddog-gov.com", "GOV");

    /** Sites whose web pages are on "app." + site; every other known site serves its pages on its own name. */
    private static final Map<String, String> APP_HOSTS = Map.of(
            "datadoghq.com", "app.datadoghq.com",
            "datadoghq.eu", "app.datadoghq.eu",
            "ddog-gov.com", "app.ddog-gov.com");

    /** "datadoghq.eu" → "EU"; an unknown site → the site itself; blank → "". */
    public static String label(String site) {
        if (site == null || site.isBlank()) {
            return "";
        }
        String s = normal(site);
        return LABELS.getOrDefault(s, s);
    }

    /** "datadoghq.eu" → "app.datadoghq.eu", "us3.datadoghq.com" → "us3.datadoghq.com". */
    public static String appHost(String site) {
        String s = normal(site);
        return APP_HOSTS.getOrDefault(s, s);
    }

    private static String normal(String site) {
        return site.strip().toLowerCase(Locale.ROOT);
    }

    private Sites() {
    }
}
