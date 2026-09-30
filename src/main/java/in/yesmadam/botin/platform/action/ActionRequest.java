package in.yesmadam.botin.platform.action;
import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
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

    /**
     * AN AMOUNT the decision table produced, in RUPEES. Zero when it produced none.
     *
     * Widened from long to BigDecimal with the move off paise: the table can now emit
     * 68.50, and reading that as a long would silently truncate it to 68 — a partner
     * short-changed by half a rupee per claim, with nothing reporting it.
     */
    public BigDecimal amountFact(String key) {
        Object value = facts == null ? null : facts.get(key);
        if (value instanceof BigDecimal b) return Rupees.scaled(b);
        if (value instanceof Number n)     return Rupees.scaled(new BigDecimal(n.toString()));
        return Rupees.ZERO;
    }
}
