package in.yesmadam.botin.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * ADR-005 — the most load-bearing class in the service.
 *
 * A Flowable Service Task runs inside the engine's transaction. If a LATER node
 * throws, the engine rolls back — and by default that takes this record with it,
 * while the external system it describes does NOT roll back. Money moved, no
 * evidence, and reconciliation has nothing to detect.
 *
 * REQUIRES_NEW suspends the engine's transaction, commits this row in its own,
 * and returns. Only then does the caller make the external call.
 *
 * PROVEN in the R&D ladder, in both directions: with the annotation the row
 * survives a rollback, without it the row is lost.
 *
 * ---------------------------------------------------------------------------
 * TRAP: Spring applies @Transactional through a proxy. A call from INSIDE this
 * class to another of its own methods silently ignores the annotation — no
 * error, no warning, and the money record quietly stops being protected. Always
 * call these from another bean.
 *
 * TODO(before Phase 4): re-run the rollback test once the UAT datasource is
 * wired. A second datasource changes which transaction manager Spring selects,
 * and a wrongly wired one breaks REQUIRES_NEW with no error at all.
 * ---------------------------------------------------------------------------
 */
@Service
public class TicketActionRecorder {

    private static final Logger log = LoggerFactory.getLogger(TicketActionRecorder.class);

    private final TicketActionRepository repository;

    public TicketActionRecorder(TicketActionRepository repository) {
        this.repository = repository;
    }

    /**
     * Commits an ATTEMPTED row in its own transaction, BEFORE the external call.
     *
     * @return empty when this exact action was already attempted — the caller
     *         must then short-circuit rather than act again. That is the
     *         idempotency guarantee, enforced by a unique constraint rather
     *         than by a check-then-act race.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TicketAction> recordAttempt(UUID ticketId, String actionType,
                                                Long amountPaise, String requestPayload) {
        return recordAttempt(ticketId, actionType, null, amountPaise, requestPayload);
    }

    /**
     * @param externalReference the THIRD PARTY's id for the thing being acted on — the
     *        gateway's order reference, not ours. Supply it wherever one exists.
     *
     *        Without it this guard protects retries of THIS ticket and nothing else,
     *        and the case that actually happens is the other one: a partner re-raising
     *        the same complaint next week gets a new session, a new ticket, a different
     *        key, and a second payment. See TicketAction.idempotencyKey.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TicketAction> recordAttempt(UUID ticketId, String actionType, String externalReference,
                                                Long amountPaise, String requestPayload) {
        String key = TicketAction.idempotencyKey(ticketId, actionType, externalReference);

        Optional<TicketAction> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("action already attempted, short-circuiting key={} status={}",
                     key, existing.get().getStatus());
            return Optional.empty();
        }

        TicketAction attempt = repository.saveAndFlush(
                TicketAction.attempt(ticketId, actionType, externalReference, amountPaise, requestPayload));
        log.info("recorded ATTEMPTED in its own transaction key={}", key);
        return Optional.of(attempt);
    }

    /** Outcome write. Also REQUIRES_NEW: it must not be lost to a later rollback either. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    /**
     * WHAT ACTUALLY MOVED, recorded with the outcome.
     *
     * `amount_paise` has existed on this row since the first migration and NOTHING EVER WROTE
     * IT on a real attempt — the delegate passed null. Every ledger row said that a credit
     * succeeded and none said how much.
     *
     * That is invisible until somebody tries to reconcile, which is exactly what step 61 does,
     * and then it is total: you cannot sum a column of nulls. A payment log that records the
     * fact of a payment but not its size is not an audit trail, it is a receipt with the
     * number torn off.
     *
     * Written at the OUTCOME rather than the attempt, deliberately: before the call we only
     * know what we intend to pay, and if the process dies mid-call an amount on the row would
     * assert a movement nobody can confirm. Null on an ATTEMPTED row is the honest state.
     */
    public void recordOutcome(UUID actionId, boolean success, String responsePayload, Long amountPaise) {
        recordOutcomeInternal(actionId, success, responsePayload, amountPaise);
    }

    public void recordOutcome(UUID actionId, boolean success, String responsePayload) {
        recordOutcomeInternal(actionId, success, responsePayload, null);
    }

    private void recordOutcomeInternal(UUID actionId, boolean success,
                                       String responsePayload, Long amountPaise) {
        repository.findById(actionId).ifPresent(a -> {
            if (success) a.succeeded(responsePayload); else a.failed(responsePayload);
            if (amountPaise != null) a.recordAmount(amountPaise);
            repository.saveAndFlush(a);
        });
    }
}
