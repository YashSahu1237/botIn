package in.yesmadam.botin.platform.process;

import in.yesmadam.botin.platform.api.dto.NextStep;
import in.yesmadam.botin.platform.session.Ticket;
import in.yesmadam.botin.platform.session.TicketRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The bot resolved it. No agent, no handover — an answer and an ending.
 *
 * This is the branch the whole system exists to widen. Every case that leaves here is
 * one that did not reach a person.
 */
@Component("resolveDelegate")
public class ResolveDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ResolveDelegate.class);

    private final SessionStepWriter steps;
    private final ResponseTemplates templates;
    private final TicketRepository tickets;

    public ResolveDelegate(SessionStepWriter steps, ResponseTemplates templates, TicketRepository tickets) {
        this.steps = steps;
        this.templates = templates;
        this.tickets = tickets;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID sessionId = UUID.fromString((String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));
        String action = (String) execution.getVariable(ProcessVariables.ACTION);
        String tier = (String) execution.getVariable(ProcessVariables.TIER);

        String prompt = templates.promptFor(action);

        if (prompt == null) {
            // A decision nobody wrote words for. The honest failure is to say so — a
            // generic "your request has been processed" would read as a resolution for
            // an outcome that was never designed, which is how a partner ends up
            // believing they have been paid.
            log.error("no response template for action '{}' — the partner gets the unprepared "
                    + "ending. Add it to that concern's "
                    + "resources/concern/<l1>/<l2>/templates.properties.", action);
            prompt = "Is baare mein hum aapko abhi jawab nahi de paa rahe. "
                   + "Support team se baat karein.";
        }

        // A T1 or T2 ticket closes here. A T0 never created one.
        String ticketId = (String) execution.getVariable(ProcessVariables.TICKET_ID);
        if (ticketId != null) {
            tickets.findById(UUID.fromString(ticketId)).ifPresent(Ticket::close);
        }

        steps.writeAndClose(sessionId, NextStep.message(action, prompt),
                "T0".equals(tier) ? "CLOSED_DEFLECTED" : "CLOSED_RESOLVED");
    }
}
