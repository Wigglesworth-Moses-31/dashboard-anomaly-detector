package lab.anomaly.notifier;

import lab.anomaly.notifier.board.LineHistory;
import lab.anomaly.notifier.live.NotifierEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A line's last incidents for the page (add-incident-history design H4): GET /history?line=<id>&before=<incident>. */
@RestController
class HistoryController {

    private final NotifierEngine engine;

    HistoryController(NotifierEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/history")
    LineHistory.Past history(@RequestParam String line, @RequestParam(required = false) String before) {
        return engine.history().past(line, before, System.currentTimeMillis());
    }
}
