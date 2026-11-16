package lab.anomaly.collector.emit;

import lab.anomaly.contracts.v1.MetricPoints;
import lab.anomaly.contracts.v1.SeriesDescriptor;

/** Where the Collector's messages go: Kafka in production, a list in tests. */
public interface MessageSink {

    void send(SeriesDescriptor description);

    void send(MetricPoints points);
}
