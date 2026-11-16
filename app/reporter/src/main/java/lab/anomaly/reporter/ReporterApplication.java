package lab.anomaly.reporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Reporter: writes the daily Markdown report, then cleans old raw data. */
@SpringBootApplication
public class ReporterApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReporterApplication.class, args);
    }
}
