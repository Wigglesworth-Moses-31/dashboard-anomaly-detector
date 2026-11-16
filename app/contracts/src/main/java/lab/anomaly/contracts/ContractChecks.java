package lab.anomaly.contracts;

import java.util.ArrayList;
import java.util.List;
import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;

/**
 * Checks a reader runs before using a received message (design C7). Each method returns the problems found;
 * an empty list means the message is usable. Protobuf has no "required" fields, so this is where
 * completeness is enforced.
 */
public final class ContractChecks {

    /** Numbers of one line: id, at least one point, times strictly increasing, finite values. One pass: O(n). */
    public static List<String> problems(MetricPoints message) {
        List<String> problems = new ArrayList<>();
        if (!SeriesIds.isId(message.getSeriesId())) {
            problems.add("series_id: missing or not 16 hex characters");
        }
        if (message.getCollectedMs() <= 0) {
            problems.add("collected_ms: missing");
        }
        if (message.getPointsCount() == 0) {
            problems.add("points: at least one point is required");
        }
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < message.getPointsCount(); i++) {
            Point point = message.getPoints(i);
            if (point.getTsMs() <= previous) {
                problems.add("points: not in time order at position " + i);
            }
            if (!Double.isFinite(point.getValue())) {
                problems.add("points: value at position " + i + " is not a finite number");
            }
            previous = point.getTsMs();
        }
        return problems;
    }

    /** Description of one line: ids, enums set, step positive, and the id matches its own fields. */
    public static List<String> problems(SeriesDescriptor message) {
        List<String> problems = new ArrayList<>();
        if (!SeriesIds.isId(message.getSeriesId())) {
            problems.add("series_id: missing or not 16 hex characters");
        } else if (!message.getSeriesId().equals(SeriesIds.seriesId(message))) {
            problems.add("series_id: does not match the id computed from source, query_id and group_tags");
        }
        if (!SeriesIds.isId(message.getQueryId())) {
            problems.add("query_id: missing or not 16 hex characters");
        }
        if (message.getSource().getSystem().isBlank()) {
            problems.add("source.system: missing");
        }
        if (message.getMetric().isBlank()) {
            problems.add("metric: missing");
        }
        if (message.getSpaceAggregation() == Aggregation.AGGREGATION_UNSPECIFIED) {
            problems.add("space_aggregation: not set");
        }
        if (message.getTimeAggregation() == Aggregation.AGGREGATION_UNSPECIFIED) {
            problems.add("time_aggregation: not set");
        }
        if (message.getKind() == Kind.KIND_UNSPECIFIED) {
            problems.add("kind: not set (use KIND_UNKNOWN when no setting describes the metric)");
        }
        if (message.getStepSeconds() <= 0) {
            problems.add("step_seconds: must be positive");
        }
        return problems;
    }

    /** Incident event: ids, type, sequence and a full incident whose id matches. */
    public static List<String> problems(IncidentEvent message) {
        List<String> problems = new ArrayList<>();
        if (message.getEventId().isBlank()) {
            problems.add("event_id: missing");
        }
        if (message.getIncidentId().isBlank()) {
            problems.add("incident_id: missing");
        }
        if (message.getSequence() < 1) {
            problems.add("sequence: must be 1 or more");
        }
        if (message.getType() == EventType.EVENT_TYPE_UNSPECIFIED) {
            problems.add("type: not set");
        }
        if (!message.hasIncident()) {
            problems.add("incident: missing (every event carries the full incident)");
        } else {
            if (!message.getIncident().getIncidentId().equals(message.getIncidentId())) {
                problems.add("incident.incident_id: does not match incident_id");
            }
            if (message.getIncident().getState() == IncidentState.INCIDENT_STATE_UNSPECIFIED) {
                problems.add("incident.state: not set");
            }
        }
        return problems;
    }

    private ContractChecks() {
    }
}
