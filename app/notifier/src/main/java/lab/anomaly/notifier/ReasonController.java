package lab.anomaly.notifier;

import java.util.Map;
import lab.anomaly.contracts.ContractChecks;
import lab.anomaly.notifier.live.CommandPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The page's "Close with reason" (add-incident-resolution S3): POST /incidents/{id}/reason {"reason": "..."} → 202 with
 * the command id; the incident closes when the Detector's closing event arrives on the page. 400 for an empty or too
 * long reason, 503 when Kafka did not take the command.
 */
@RestController
class ReasonController {

    private static final Logger log = LoggerFactory.getLogger(ReasonController.class);

    private final CommandPublisher commands;

    @org.springframework.beans.factory.annotation.Autowired
    ReasonController(Environment environment) {
        this(new CommandPublisher(environment.getProperty("spring.kafka.bootstrap-servers", "localhost:59092")));
    }

    ReasonController(CommandPublisher commands) {
        this.commands = commands;
    }

    @PostMapping("/incidents/{id}/reason")
    ResponseEntity<Map<String, String>> reason(@PathVariable String id, @RequestBody Map<String, String> body) {
        String reason = body == null || body.get("reason") == null ? "" : body.get("reason").strip();
        if (reason.isEmpty() || reason.length() > ContractChecks.MAX_REASON) {
            return ResponseEntity.badRequest().body(Map.of("problem", "a reason of 1 to " + ContractChecks.MAX_REASON + " characters is needed"));
        }
        try {
            String commandId = commands.resolve(id, reason, System.currentTimeMillis());
            log.info("Close command {} for incident {} sent", commandId, id);
            return ResponseEntity.accepted().body(Map.of("command_id", commandId));
        } catch (Exception e) {
            log.warn("Close command for incident {} not sent: {}", id, e.toString());
            return ResponseEntity.status(503).body(Map.of("problem", "not sent: " + e.getMessage()));
        }
    }
}
