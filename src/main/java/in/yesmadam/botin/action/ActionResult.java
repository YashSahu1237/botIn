package in.yesmadam.botin.action;

/**
 * What an action did, in the words the audit row needs.
 *
 * @param externalReference the third party's own reference for the movement — the
 *                          receipt. Without it "we credited them" is our claim and
 *                          nothing more, and a dispute has nowhere to go.
 * @param amountPaise       what moved, so reconciliation can sum it
 */
public record ActionResult(String externalReference, long amountPaise, String detail) {

    public static ActionResult of(String externalReference, long amountPaise) {
        return new ActionResult(externalReference, amountPaise, null);
    }

    /**
     * Nothing moved, and that is the correct outcome — the partner had already been
     * paid, the state was already changed.
     *
     * DISTINCT FROM A FAILURE ON PURPOSE. "Already done" is a success the partner should
     * be told about plainly; a failure is a person's problem. Collapsing the two would
     * either alarm somebody who has their money or hide a payment that never happened.
     */
    public static ActionResult alreadyDone(String detail) {
        return new ActionResult(null, 0L, detail);
    }
}
