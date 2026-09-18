package in.yesmadam.botin.action;

import java.util.Map;
import java.util.UUID;

/**
 * Everything an action needs, and nothing it does not.
 *
 * @param ticketId  the ticket this is being done against — for the audit trail, never
 *                  for idempotency (see ActionService.externalReferenceFor)
 * @param spId      whose wallet, whose fine, whose leave
 * @param reference what the partner selected — the order, the booking, the violation
 * @param facts     the fact map the decision was made on, so an action can read a value
 *                  the table computed rather than looking it up a second time and
 *                  possibly getting a different answer
 */
public record ActionRequest(
        UUID ticketId,
        String spId,
        String l2Concern,
        String reference,
        Map<String, Object> facts) {

    /** A number the decision table produced, in paise. Zero when it produced none. */
    public long numericFact(String key) {
        Object value = facts == null ? null : facts.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
