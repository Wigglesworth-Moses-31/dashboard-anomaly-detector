package lab.anomaly.reporter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lab.anomaly.reporter.run.ReportScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** On request (design R1): {@code POST /reports/2026-11-15} reports that day's ended windows (or returns the existing ones). */
@RestController
class ReportController {

    private final ReportScheduler scheduler;

    ReportController(ReportScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/reports/{date}")
    ResponseEntity<Map<String, Object>> report(@PathVariable String date) {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "date must be like 2026-11-15"));
        }
        List<String> files = scheduler.runDay(day, System.currentTimeMillis());
        return ResponseEntity.ok(Map.of("day", date, "files", files, "folder", scheduler.dir().toAbsolutePath().toString(),
                "lastError", scheduler.lastError));
    }
}
