package lab.anomaly.notifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Notifier: streams open incidents live to the web page. */
@SpringBootApplication
public class NotifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotifierApplication.class, args);
    }
}
