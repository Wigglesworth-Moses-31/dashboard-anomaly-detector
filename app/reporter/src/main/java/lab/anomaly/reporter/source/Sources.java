package lab.anomaly.reporter.source;

import java.util.Collection;
import java.util.List;
import lab.anomaly.contracts.v1.IncidentEvent;
import lab.anomaly.contracts.v1.RemoveRawReply;
import lab.anomaly.contracts.v1.SeriesDescriptor;
import lab.anomaly.contracts.v1.SeriesSummary;

/** Everything the Reporter reads from or asks of other services; an interface so tests can use fakes. */
public interface Sources {

    /** The latest event of every incident still in Kafka (highest sequence per incident). */
    Collection<IncidentEvent> incidents();

    /** Every line's description, by series id order. */
    Collection<SeriesDescriptor> lines();

    /** Every line's summary over [fromMs, toMs). */
    List<SeriesSummary> summaries(long fromMs, long toMs);

    /** Asks the Store to remove raw points older than {@code beforeMs} (the Store applies its 3-hour floor). */
    RemoveRawReply removeRawBefore(long beforeMs);
}
