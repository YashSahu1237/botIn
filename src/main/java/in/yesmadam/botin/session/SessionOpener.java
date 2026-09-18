package in.yesmadam.botin.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Creates a help session AND COMMITS IT, before anything else can need it.
 *
 * ===================================================================
 * THE CHAIN, WHICH IS THE THING TO UNDERSTAND
 * ===================================================================
 *
 * Three rows are written on one request, and each references the one above it:
 *
 *     help_session  <--  ticket  <--  ticket_action
 *
 * The bottom one, the attempt log, MUST be written in its own transaction — that is
 * ADR-005, and it is what makes an external call survivable: if the request rolls back
 * after we have called a payment gateway, the record that we called it has to remain, or
 * the retry pays a second time.
 *
 * A separate transaction sees only COMMITTED rows. So the ticket must be committed
 * before the action can reference it — and therefore the session must be committed
 * before the ticket can reference it. Durability propagates UP the chain, and stopping
 * halfway just moves the foreign-key failure one link along. That is exactly what
 * happened here: committing the ticket alone turned a missing-ticket error into a
 * missing-session one.
 *
 * ONLY THE FREE-TEXT ENTRY PATH NEEDS THIS. Through the menu, the session was created
 * and committed by an earlier request, and by the time a concern is chosen it has been
 * on disk for some time. Only a partner who types their problem straight into Help
 * creates a session and reaches an action in the same request.
 *
 * AND IT IS DEFENSIBLE ON ITS OWN TERMS: a help session is the record that a partner
 * contacted us. Losing that because a later step failed loses the contact itself, which
 * is the one thing this system is supposed to be counting.
 */
@Service
public class SessionOpener {

    private final HelpSessionRepository sessions;

    public SessionOpener(HelpSessionRepository sessions) {
        this.sessions = sessions;
    }

    /**
     * @return the id of a session that is now durable. The caller re-reads it in its own
     *         transaction to get a MANAGED instance — the one returned here belongs to a
     *         transaction that has ended, and writing through it would go nowhere.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID openCommitted(String spId, String entryFreeText) {
        return sessions.saveAndFlush(HelpSession.start(spId, entryFreeText)).getId();
    }
}
