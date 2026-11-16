package lab.anomaly.notifier;

import lab.anomaly.notifier.live.NotifierEngine;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RSocket routes for the web page (add-contracts C8, add-notifier N3). {@code incidents.live}: every incident of the
 * day as one-line IncidentEvent JSON, then {@code {"snapshot_complete":true,"day":...,"time_zone":...}}, then each
 * change. The stream ends normally at midnight or when the time zone changes; the page reconnects.
 */
@Controller
class LiveController {

    private final NotifierEngine engine;

    LiveController(NotifierEngine engine) {
        this.engine = engine;
    }

    /** Request-response: lets a client check that the connection works. */
    @MessageMapping("ping")
    Mono<String> ping() {
        return Mono.just("pong");
    }

    @MessageMapping("incidents.live")
    Flux<String> incidentsLive() {
        return engine.hub().connect();
    }
}
