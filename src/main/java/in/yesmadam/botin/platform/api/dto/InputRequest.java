package in.yesmadam.botin.platform.api.dto;
/**
 * One turn from the partner. Exactly one of these is expected to be set, matching
 * the StepType that was last returned.
 *
 * @param selection the {@code code} of the chosen Option, for MENU and DROPDOWN
 * @param freeText  typed text, for TEXT
 * @param reference WHAT the partner is talking about — the order, the booking, the
 *                  violation. Supplied alongside the concern selection.
 *
 *                  PROVISIONAL, AND IT MATTERS MORE THAN IT LOOKS. Plan step 54 replaces
 *                  this with a DROPDOWN that offers the partner their own recent orders,
 *                  which is better in two ways: they cannot mistype it, and we cannot be
 *                  handed a reference belonging to somebody else. Until then the client
 *                  supplies it, and a MISSING one weakens the duplicate guard rather
 *                  than blocking the flow — see TicketAction.idempotencyKey, where that
 *                  weakening is recorded on the row instead of assumed.
 */
public record InputRequest(String selection, String freeText, String reference) {

    /** For callers with no reference to give. */
    public InputRequest(String selection, String freeText) {
        this(selection, freeText, null);
    }
}
