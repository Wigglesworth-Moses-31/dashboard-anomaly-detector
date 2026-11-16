package lab.anomaly.detector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Detector: spots spikes as numbers arrive and groups them into incidents. */
@SpringBootApplication
public class DetectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectorApplication.class, args);
    }
}
