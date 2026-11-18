package lab.anomaly.contracts;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The regions the Settings tab may add (add-settings-page): at most two, US and EU, each with a fixed site and fixed
 * key names in {@code .env}. One place, so the Collector (keys) and the Config Service (entries) always agree.
 */
public enum Regions {
    US("us", "datadoghq.com"),
    EU("eu", "datadoghq.eu");

    public static final int MAX = 2;

    private final String region;
    private final String site;

    Regions(String region, String site) {
        this.region = region;
        this.site = site;
    }

    public String region() {
        return region;
    }

    public String site() {
        return site;
    }

    public String apiKeyName() {
        return "DD_" + region.toUpperCase(Locale.ROOT) + "_API_KEY";
    }

    public String appKeyName() {
        return "DD_" + region.toUpperCase(Locale.ROOT) + "_APP_KEY";
    }

    /** "us" or "eu" (any case); anything else is empty. */
    public static Optional<Regions> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        return List.of(values()).stream().filter(r -> r.region.equals(n)).findFirst();
    }
}
