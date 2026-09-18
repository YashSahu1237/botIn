package in.yesmadam.botin.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * GATE 1, AND IT COMMITS ON ITS OWN.
 *
 * =========================================================================
 * WHY REQUIRES_NEW HERE, WHICH IS NOT OBVIOUS AND WAS NOT THE FIRST DESIGN
 * =========================================================================
 *
 * The ticket used to be saved inside the surrounding transaction — flushed, not
 * committed. That is fine right up until something else needs to see it from a DIFFERENT
 * transaction, and exactly one thing does: `TicketActionRecorder`, which is REQUIRES_NEW
 * precisely so an attempt row survives a rollback (ADR-005).
 *
 * A separate transaction cannot see uncommitted rows. So the attempt insert failed on the
 * foreign key to a ticket that, as far as its transaction was concerned, did not exist:
 *
 *     Referential integrity constraint violation:
 *     PUBLIC.TICKET_ACTION FOREIGN KEY(TICKET_ID) REFERENCES PUBLIC.TICKET(ID)
 *
 * The spike that established ADR-005 never met this, because its test created and
 * committed a ticket first and then exercised the recorder. In the real flow Gate 1 and
 * the action happen in the same request, and that is the case that matters.
 *
 * THE PRINCIPLE, WHICH IS THE PART WORTH REMEMBERING: if we are about to call an external
 * system on behalf of a ticket, THAT TICKET MUST BE AS DURABLE AS THE CALL. An attempt
 * row referencing a ticket that was rolled away is a dangling audit record — the FK
 * turned that into a loud failure here, but in a schema without one it would have been a
 * quiet corruption of the only evidence we keep.
 *
 * WHAT THIS COSTS. If the surrounding transaction rolls back after this commits, a ticket
 * exists for work that was undone, and the partner's retry opens a second one. That is
 * visible, countable and correctable. The alternative — losing the record that we
 * contacted a payment gateway on someone's behalf — is none of those things. And the
 * money guard is unaffected either way: it is keyed on the gateway's reference, not on
 * the ticket, so a duplicate ticket still cannot produce a duplicate payment.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository tickets;

    public TicketService(TicketRepository tickets) {
        this.tickets = tickets;
    }

    /**
     * Open the ticket for this session, or escalate the one it already has.
     *
     * REUSED, NOT REPLACED, when one exists. Trigger A runs a second process over a
     * conversation that already crossed Gate 1 — that is the same complaint escalated,
     * not a new one, and a second row would inflate every volume number.
     *
     * @return the ticket id, committed and visible to any transaction
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID openOrEscalate(UUID sessionId, String spId, String l1Concern, String l2Concern,
                               String tier, String action,
                               boolean agentRequired, String triggerReason, String csatResult) {

        // NOTHING IS READ FROM THE SESSION HERE, and that is the hard-won part. This
        // transaction cannot see what the caller has only flushed, so loading the
        // session returned it as it was BEFORE this request — no concern selected —
        // and every ticket was filed with a null concern. The NOT NULL constraint
        // caught it; nullable columns would have hidden it completely.
        Ticket ticket = tickets.findByHelpSessionId(sessionId).stream().findFirst()
                .orElseGet(() -> Ticket.openAtGate1(sessionId, spId, l1Concern, l2Concern));

        ticket.recordDecision(tier, action);
        if (agentRequired) {
            // Sets tier T3, the trigger and AWAITING_AGENT together — the ticket is not
            // "open and also escalated", it is waiting for a person.
            ticket.escalate(triggerReason);
        }

        // WRITTEN HERE, not by the caller afterwards. A second write from the outer
        // transaction would flush a copy of this row loaded BEFORE this commit, and
        // Hibernate's first-level cache makes that copy the one it believes in — so the
        // trigger reason set two lines above would be silently reverted. One writer per
        // row per request.
        if (csatResult != null) ticket.recordCsat(csatResult);

        tickets.saveAndFlush(ticket);
        log.info("Gate 1 crossed: ticket {} for session {} tier={} status={}",
                ticket.getId(), sessionId, ticket.getTier(), ticket.getStatus());

        return ticket.getId();
    }

    /**
     * The action failed, so the ticket is no longer what it said it was.
     *
     * WHY THIS IS NOT COSMETIC. Gate 1 wrote this row as a T2 before the external call —
     * correctly, because that is what had been decided. The call then failed and the case
     * went to a person, and a row left saying T2 would be counted as automation that
     * worked. The failures would be invisible in exactly the metric meant to show whether
     * automating this concern was a good idea.
     *
     * It is a separate committing transaction for the same reason the ticket was: this
     * runs inside a process whose surrounding transaction may still roll back, and the
     * fact that we called a gateway and it failed must outlive that.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void escalateAfterFailedAction(UUID ticketId, String action, String triggerReason) {
        tickets.findById(ticketId).ifPresent(ticket -> {
            ticket.recordDecision("T3", action);
            ticket.escalate(triggerReason);
            tickets.saveAndFlush(ticket);
            log.warn("ticket {} moved to T3 after a failed action — it is no longer a T2", ticketId);
        });
    }
}
