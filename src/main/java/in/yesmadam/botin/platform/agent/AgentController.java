package in.yesmadam.botin.platform.agent;

import in.yesmadam.botin.platform.escalation.EscalationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The three calls an agent console needs, and no more.
 *
 * NO AUTHENTICATION YET — POC only, and the same decision as the Togglz console (D-7).
 * Wave 1 puts agent identity behind real auth; today agentId is whatever the caller
 * claims, which is fine for proving the handover and unacceptable for anything else.
 * It is written down here rather than left to be noticed.
 */
@RestController
public class AgentController {

    private final AgentTaskService agents;

    public AgentController(AgentTaskService agents) {
        this.agents = agents;
    }

    /** The queue, oldest first. */
    @GetMapping("/agent/tasks")
    public List<AgentTaskView> open() {
        return agents.open();
    }

    @PostMapping("/agent/tasks/{taskId}/claim")
    public AgentTaskView claim(@PathVariable String taskId, @RequestBody AgentAction request) {
        return agents.claim(taskId, request.agentId());
    }

    /** Completing wakes the process, which closes the ticket and ends the session. */
    @PostMapping("/agent/tasks/{taskId}/complete")
    public ResponseEntity<Void> complete(@PathVariable String taskId, @RequestBody AgentAction request) {
        agents.complete(taskId, request.agentId(), request.resolutionNote());
        return ResponseEntity.noContent().build();
    }

    /**
     * What the agent opens first: the computed facts, not the transcript.
     *
     * 404 when there is none, which means this ticket never went to a human — a T1
     * resolution, say. That is a real answer rather than an error.
     */
    @GetMapping("/tickets/{ticketId}/escalation-context")
    public ResponseEntity<EscalationContext> context(@PathVariable UUID ticketId) {
        return agents.contextFor(ticketId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** @param resolutionNote what the partner will read, in the agent's own words. */
    public record AgentAction(String agentId, String resolutionNote) { }
}
