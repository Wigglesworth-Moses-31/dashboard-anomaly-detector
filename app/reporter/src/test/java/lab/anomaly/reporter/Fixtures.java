package lab.anomaly.reporter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lab.anomaly.contracts.v1.AffectedSeries;
import lab.anomaly.contracts.v1.EventType;
import lab.anomaly.contracts.v1.Incident;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.IncidentState;
import lab.anomaly.contracts.v1.Kind;
import lab.anomaly.contracts.v1.Link;
import lab.anomaly.contracts.v1.RemoveRawReply;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.SeriesSummary;
import lab.anomaly.contracts.v1.View;
import lab.anomaly.reporter.source.Sources;

/** Test helpers: incidents, lines and a fake set of sources that records clean-up calls. */
public final class Fixtures {

    public static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    public static long at(String local) {
        return LocalDateTime.parse(local).atZone(KOLKATA).toInstant().toEpochMilli();
    }

    public static Incident incident(String id, String title, String start, String end, AffectedSeries... lines) {
        Incident.Builder b = Incident.newBuilder().setIncidentId(id).setTitle(title).setStartedMs(at(start))
                .setGroupKey("api=" + id).addAllSeries(List.of(lines))
                .addLinks(Link.newBuilder().setLabel("Lab: API platform").setUrl("https://app.datadoghq.com/dashboard/w2f-pqs-ghd?from_ts=1&to_ts=2"));
        if (end == null) {
            b.setState(IncidentState.INCIDENT_STATE_OPEN);
        } else {
            b.setState(IncidentState.INCIDENT_STATE_CLOSED).setEndedMs(at(end));
        }
        return b.build();
    }

    public static AffectedSeries line(String signal, String reason, double peak, double normal, String unit, String panel) {
        return AffectedSeries.newBuilder().setSeriesId("s-" + signal).setMetric("lab.api." + signal).setSignal(signal).setReason(reason)
                .setPeakValue(peak).setNormalValue(normal).setUnit(unit).setPanelTitle(panel).putGroupTags("api", "/v1/invoices").build();
    }

    public static SeriesDescriptor descriptor(String id, Kind kind, String panel) {
        return SeriesDescriptor.newBuilder().setSeriesId(id).setMetric("m." + id).setKind(kind).setUnit("ms")
                .addViews(View.newBuilder().setViewId("d").setViewTitle("Swagstore").setPanelTitle(panel)).build();
    }

    public static SeriesSummary summary(String id, long count, double max, double mean) {
        return SeriesSummary.newBuilder().setSeriesId(id).setCount(count).setMin(0).setMax(max).setMean(mean).setP95(mean).build();
    }

    /** Fake sources: fixed incidents, lines and summaries; records every clean-up request; can be made to fail. */
    public static final class FakeSources implements Sources {
        public final List<IncidentEvent> events = new ArrayList<>();
        public final List<SeriesDescriptor> descriptors = new ArrayList<>();
        public final List<SeriesSummary> sums = new ArrayList<>();
        public final List<Long> removeCalls = new ArrayList<>();
        public RuntimeException failRemove;

        public FakeSources add(Incident i) {
            events.add(IncidentEvent.newBuilder().setEventId("e-" + i.getIncidentId()).setIncidentId(i.getIncidentId()).setSequence(1)
                    .setType(EventType.EVENT_TYPE_OPENED).setIncident(i).build());
            return this;
        }

        @Override
        public Collection<IncidentEvent> incidents() {
            return events;
        }

        @Override
        public Collection<SeriesDescriptor> lines() {
            return descriptors;
        }

        @Override
        public List<SeriesSummary> summaries(long fromMs, long toMs) {
            return sums;
        }

        @Override
        public RemoveRawReply removeRawBefore(long beforeMs) {
            if (failRemove != null) {
                throw failRemove;
            }
            removeCalls.add(beforeMs);
            return RemoveRawReply.newBuilder().setRemovedPoints(1000).setEffectiveBeforeMs(beforeMs).build();
        }
    }

    private Fixtures() {
    }

    public static Map<String, String> none() {
        return Map.of();
    }
}
