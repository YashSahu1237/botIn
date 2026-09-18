package in.yesmadam.botin.process;

import in.yesmadam.botin.api.dto.NextStep;
import in.yesmadam.botin.session.Ticket;
import in.yesmadam.botin.session.TicketRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs when the agent completes the task — which may be hours and a restart later.
 *
 * THIS IS THE STEP THAT PROVES ADR-001. Everything it needs — which session, which
 * ticket, what the partner asked — it reads from variables the engine persisted before
 * it went to sleep. Nothing was held in memory across the wait, so the wait can be as
 * long as it likes and can span a deployment.
 */
@Component("agentCompletionDelegate")
public class AgentCompletionDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(AgentCompletionDelegate.class);

    /** Set by the agent API when the task is completed. */
    public static final String RESOLUTION_NOTE = "resolutionNote";
    public static final String RESOLVED_BY = "resolvedBy";

    private final SessionStepWriter steps;
    private final TicketRepository tickets;

    public AgentCompletionDelegate(SessionStepWriter steps, TicketRepository tickets) {
        this.steps = steps;
        this.tickets = tickets;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID sessionId = UUID.fromString((String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));
        String ticketId = (String) execution.getVariable(ProcessVariables.TICKET_ID);
        String note = (String) execution.getVariable(RESOLUTION_NOTE);

        if (ticketId != null) {
            tickets.findById(UUID.fromString(ticketId)).ifPresent(Ticket::close);
        }

        // The agent's own words reach the partner. Nothing here rewrites or summarises
        // them: a note written by a person and then reworded by the system is a message
        // nobody actually sent.
        String prompt = (note == null || note.isBlank())
                ? "Aapki query support agent ne dekh li hai aur close kar di gayi hai."
                : note;

        steps.writeAndClose(sessionId, NextStep.message("AGENT_RESOLVED", prompt),
                "CLOSED_AGENT_RESOLVED");

        log.info("agent {} completed ticket {} for session {}",
                execution.getVariable(RESOLVED_BY), ticketId, sessionId);
    }
}
