package in.yesmadam.botin.action;

/**
 * A T2 — the tier that moves money or changes state. One implementation per action code.
 *
 * THE ACTION CODE IS THE CONTRACT. The decision table emits a code; exactly one service
 * claims it; nothing else in the system knows what any of them do. So a table can change
 * its mind about WHEN to credit a wallet without anyone touching the code that credits
 * it, and a new action is a new bean rather than a new branch.
 *
 * THE EXTERNAL REFERENCE IS THE IMPORTANT PART OF THE CONTRACT. Every implementation
 * must name the THIRD PARTY's identifier for the thing being acted on — the PayU
 * transaction, the order, the fine — because that, and not our ticket id, is what makes
 * a retry safe. See TicketAction.idempotencyKey.
 */
public interface ActionService {

    /** The DMN action code this service claims, e.g. AUTO_CREDIT_WALLET. */
    String actionCode();

    /**
     * The THIRD PARTY's identifier for what is being acted on.
     *
     * Not our ticket id, and the distinction is the whole of step 90. A partner who
     * re-raises the same failed recharge gets a NEW session and a NEW ticket, so a key
     * built from our ids is different every time and would happily pay them twice. The
     * gateway's transaction id is the same in both attempts, which is exactly the
     * property a duplicate guard needs.
     *
     * @return null when this action genuinely has no third-party reference, in which
     *         case the ticket id is used and the guard protects retries of ONE ticket
     *         only — a weaker guarantee that must be a deliberate choice, not a default.
     */
    String externalReferenceFor(ActionRequest request);

    /**
     * Do it. Throw on failure — the attempt is already recorded, and swallowing the
     * exception here would record a success that never happened.
     */
    ActionResult execute(ActionRequest request);
}
