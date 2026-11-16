package lab.anomaly.contracts;

import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.Aggregation;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.Link;
import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.Point;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.Source;
import lab.anomaly.contracts.v1.View;

/** One realistic example of each message, shared by the tests and docs/contracts.md. */
final class Examples {

    static final String QUERY = "sum:lab.api.request.count{env:prod} by {api,status_code}.as_count()";

    static SeriesDescriptor descriptor() {
        String queryId = SeriesIds.queryId(QUERY);
        java.util.Map<String, String> tags = java.util.Map.of("api", "/v1/invoices", "status_code", "500");
        return SeriesDescriptor.newBuilder()
                .setSeriesId(SeriesIds.seriesId("datadog", "datadoghq.com", queryId, tags))
                .setSource(Source.newBuilder().setSystem("datadog").setInstance("datadoghq.com"))
                .setQueryId(queryId)
                .setQueryText(QUERY)
                .setMetric("lab.api.request.count")
                .putAllGroupTags(tags)
                .setSpaceAggregation(Aggregation.AGGREGATION_SUM)
                .setTimeAggregation(Aggregation.AGGREGATION_MAX)
                .setStepSeconds(10)
                .setKind(Kind.KIND_COUNT)
                .setUnit("requests")
                .addViews(View.newBuilder().setViewId("w2f-pqs-ghd")
                        .setViewTitle("Lab: API platform (skeleton structure)").setPanelTitle("Count"))
                .setClassifiedBy("rule:2")
                .setFirstSeenMs(1_763_182_800_000L)
                .setUpdatedMs(1_763_182_800_000L)
                .build();
    }

    static MetricPoints points() {
        return MetricPoints.newBuilder()
                .setSeriesId(descriptor().getSeriesId())
                .setCollectedMs(1_763_182_815_412L)
                .addPoints(Point.newBuilder().setTsMs(1_763_182_790_000L).setValue(3))
                .addPoints(Point.newBuilder().setTsMs(1_763_182_800_000L).setValue(42))
                .addPoints(Point.newBuilder().setTsMs(1_763_182_810_000L).setValue(17).setPartial(true))
                .build();
    }

    static IncidentEvent incidentEvent() {
        String incidentId = "inc-20261115-0905-7c2e";
        return IncidentEvent.newBuilder()
                .setEventId("5b6f1d0e-8a1c-4c55-9c7b-2f0d3e9a4b11")
                .setIncidentId(incidentId)
                .setSequence(2)
                .setType(EventType.EVENT_TYPE_UPDATED)
                .setOccurredMs(1_763_182_830_000L)
                .setIncident(Incident.newBuilder()
                        .setIncidentId(incidentId)
                        .setState(IncidentState.INCIDENT_STATE_OPEN)
                        .setTitle("/v1/invoices: errors up")
                        .setStartedMs(1_763_182_800_000L)
                        .addSeries(AffectedSeries.newBuilder()
                                .setSeriesId(descriptor().getSeriesId())
                                .setMetric("lab.api.request.count")
                                .putAllGroupTags(descriptor().getGroupTagsMap())
                                .setPeakValue(42)
                                .setPeakTsMs(1_763_182_800_000L)
                                .setNormalValue(3)
                                .setUnit("requests")
                                .setSignal("errors")
                                .setReason("up")
                                .setDashboardTitle("Lab: API platform (skeleton structure)")
                                .setPanelTitle("Count"))
                        .setGroupKey("api=/v1/invoices")
                        .addLinks(Link.newBuilder().setLabel("Lab: API platform (skeleton structure)")
                                .setUrl("https://app.datadoghq.com/dashboard/w2f-pqs-ghd?from_ts=1763182500000&to_ts=1763183100000")))
                .build();
    }

    private Examples() {
    }
}
