package in.yesmadam.botin.platform.process;

import in.yesmadam.botin.platform.catalogue.CatalogueService;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import in.yesmadam.botin.platform.session.TicketService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * GATE 1, and the only place it is crossed.
 *
 * A T0 leaves here having created nothing. That is not an optimisation — it is the BRD's
 * central claim made literal: deflected volume does not appear in ticket counts at all,
 * because there is nothing to count. Every tier test from here on asserts it.
 *
 * WHY THE GATE SITS AFTER THE DECISION. Whether a case needs a ticket is a property of
 * the ANSWER, not of the question: the same concern can deflect one partner and require
 * an action for the next. So the ticket cannot be opened when the concern is picked —
 * only once the table has spoken.
 *
 * THE WRITE ITSELF IS IN TicketService, AND IT COMMITS ON ITS OWN. See that class: the
 * action recorder runs in a separate transaction by design, and a separate transaction
 * cannot see a row this one has only flushed.
 */
@Component("openTicketDelegate")
public class OpenTicketDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(OpenTicketDelegate.class);

    private final TicketService ticketService;
    private final CatalogueService catalogue;

    public OpenTicketDelegate(TicketService ticketService, CatalogueService catalogue) {
        this.ticketService = ticketService;
        this.catalogue = catalogue;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String tier = (String) execution.getVariable(ProcessVariables.TIER);

        if ("T0".equals(tier)) {
            log.debug("T0 — Gate 1 not crossed, no ticket");
            return;
        }

        // A REROUTE IS NOT AN OUTCOME, SO IT MUST NOT FILE ANYTHING.
        //
        // This step runs before the gateway, and a reroute carries tier "-" rather than
        // "T0" — so without this check the triage concern opened a ticket and THEN sent
        // the partner somewhere else, where the real concern decided for itself. A
        // partner whose text was correctly recognised and deflected would have finished
        // with a ticket that no tier ever asked for, and every rerouted case would have
        // been counted twice: once as triage, once as whatever it turned out to be.
        if (Boolean.TRUE.equals(execution.getVariable(ProcessVariables.REROUTE_REQUIRED))) {
            log.debug("reroute — Gate 1 is the target concern's decision, not this one's");
            return;
        }

        UUID sessionId = UUID.fromString((String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));
        String l2Concern = (String) execution.getVariable(ProcessVariables.L2_CONCERN);

        // The L1 path comes from the CATALOGUE rather than the session row, for the same
        // reason the rest of these fields are passed in: the committing transaction
        // cannot see a session change this one has not committed. The catalogue is
        // reference data and was committed long ago.
        String l1Concern = catalogue.find(l2Concern).map(ConcernCatalogue::getL1Code).orElse(null);

        UUID ticketId = ticketService.openOrEscalate(
                sessionId,
                (String) execution.getVariable(ProcessVariables.SP_ID),
                l1Concern,
                l2Concern,
                tier,
                (String) execution.getVariable(ProcessVariables.ACTION),
                Boolean.TRUE.equals(execution.getVariable(ProcessVariables.AGENT_REQUIRED)),
                (String) execution.getVariable(ProcessVariables.TRIGGER_REASON),
                (String) execution.getVariable(ProcessVariables.CSAT_RESULT));

        execution.setVariable(ProcessVariables.TICKET_ID, ticketId.toString());
    }
}
