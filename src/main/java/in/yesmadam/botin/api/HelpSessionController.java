package in.yesmadam.botin.api;

import in.yesmadam.botin.api.dto.CsatRequest;
import in.yesmadam.botin.api.dto.InputRequest;
import in.yesmadam.botin.api.dto.SessionView;
import in.yesmadam.botin.api.dto.StartSessionRequest;
import in.yesmadam.botin.session.HelpSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The conversation. Three endpoints, and all three return the same SessionView, so a
 * client that can render a response can render a reconnect with no second code path.
 */
@RestController
@RequestMapping("/help/sessions")
public class HelpSessionController {

    private final HelpSessionService service;

    public HelpSessionController(HelpSessionService service) {
        this.service = service;
    }

    /** Help was tapped. Creates the session and returns the L1 menu. No ticket is created. */
    @PostMapping
    public ResponseEntity<SessionView> start(@RequestBody StartSessionRequest request) {
        if (request == null || request.spId() == null || request.spId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.start(request.spId(), request.freeText()));
    }

    /** One turn from the partner — a selection or typed text. */
    @PostMapping("/{sessionId}/input")
    public SessionView input(@PathVariable UUID sessionId, @RequestBody InputRequest request) {
        return service.input(sessionId, request == null ? new InputRequest(null, null) : request);
    }

    /** Reconnect. The app was closed, the network dropped, the partner came back. */
    @GetMapping("/{sessionId}")
    public SessionView view(@PathVariable UUID sessionId) {
        return service.view(sessionId);
    }

    /**
     * The satisfaction answer, given AFTER the session has closed.
     *
     * A separate endpoint rather than another /input turn, because it arrives on a
     * finished conversation and may arrive much later — a partner handed a deeplink has
     * to go and use it before they can say whether it helped. Nothing is held open for
     * it, and there is no timer, because there is no state waiting.
     *
     * "No" escalates to a human against the same ticket; "Yes" suppresses the agent
     * option entirely.
     */
    @PostMapping("/{sessionId}/csat")
    public ResponseEntity<SessionView> csat(@PathVariable UUID sessionId,
                                            @RequestBody CsatRequest request) {
        if (request == null || request.satisfied() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.recordCsat(sessionId, request.satisfied()));
    }
}
