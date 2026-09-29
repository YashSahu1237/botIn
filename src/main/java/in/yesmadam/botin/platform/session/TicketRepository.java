package in.yesmadam.botin.platform.session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    /** Agent-connect trigger D: same SP, same sub-concern, inside a window. */
    long countBySpIdAndL2ConcernAndCreatedAtAfter(String spId, String l2Concern, Instant since);

    /**
     * Every ticket ever opened against one partner.
     *
     * Exists for the Gate 1 checkpoints. "This partner's journey filed nothing" is the
     * claim a deflection actually makes, and it is provable per partner without
     * depending on the rest of the database being empty — which, in a suite whose money
     * tests commit on purpose, it never is.
     */
    long countBySpId(String spId);

    /** Gate 1, per session. A T0 session must never have one of these. */
    long countByHelpSessionId(UUID helpSessionId);

    /**
     * This partner's recent history, for the escalation context an agent opens first.
     *
     * Bounded at ten on purpose. An agent reads the last few contacts; a partner with
     * four hundred tickets would otherwise produce a snapshot too large for the column
     * and too long for anyone to read.
     */
    List<Ticket> findTop10BySpIdOrderByCreatedAtDesc(String spId);

    /**
     * The ticket already open for this conversation, if any.
     *
     * Trigger A reuses it rather than opening a second. This is the same complaint,
     * now escalated — counting it twice would inflate every volume number and hide the
     * fact that the bot's answer was rejected, which is the one thing trigger A exists
     * to surface.
     */
    List<Ticket> findByHelpSessionId(UUID helpSessionId);
}
