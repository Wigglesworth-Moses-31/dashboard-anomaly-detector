package lab.anomaly.contracts;

import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;

/**
 * Kafka topic names and the key of each message (design C8), so no service types a topic name by hand.
 * Kafka sends every message with the same key to the same partition, which keeps their order.
 */
public final class Topics {

    public static final String METRIC_SERIES = "metric-series";
    public static final String METRIC_POINTS = "metric-points";
    public static final String INCIDENT_EVENTS = "incident-events";
    /** Requests to close an incident with a reason (add-incident-resolution); read only by the Detector. */
    public static final String INCIDENT_COMMANDS = "incident-commands";

    public static String key(SeriesDescriptor message) {
        return message.getSeriesId();
    }

    public static String key(MetricPoints message) {
        return message.getSeriesId();
    }

    public static String key(IncidentEvent message) {
        return message.getIncidentId();
    }

    public static String key(lab.anomaly.contracts.v1.ResolveIncident message) {
        return message.getIncidentId();
    }

    private Topics() {
    }
}
