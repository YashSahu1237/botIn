package in.yesmadam.botin.platform.classifier;
/**
 * What the model said, after it has been checked. Never the raw response.
 *
 * @param category         an L2 concern code, or null when nothing matched
 * @param confidence       0.0 to 1.0, bounded here and NOT thresholded here — the 0.7
 *                         floor lives in the decision tables, so it can be tuned without
 *                         a deployment (plan step 51)
 * @param extractedReason  the structured summary an agent reads. Deliberately not prose
 *                         written by a second model call: this one already exists
 * @param sentiment        NEUTRAL / NEGATIVE / ABUSIVE — rides along on the same call
 * @param abuseFlag        the partner is being abusive
 * @param riskFlag         the text describes harm, threat or an emergency
 */
public record Classification(
        String category,
        double confidence,
        String extractedReason,
        String sentiment,
        boolean abuseFlag,
        boolean riskFlag) {

    /**
     * THE ONLY VALUE THIS SYSTEM EVER FALLS BACK TO. An explicit "no confident match",
     * never an absent or null answer.
     *
     * The difference is the whole safety argument. A null would reach the same catch-all
     * and produce the same ticket — by accident. This says it on purpose, so the day a
     * classifier IS wired in and starts failing, the behaviour does not change shape.
     */
    public static final Classification NO_MATCH =
            new Classification(null, 0.0, null, "NEUTRAL", false, false);

    public boolean matched() { return category != null && !category.isBlank(); }

    /** Trigger E: sentiment and risk force a human immediately, whatever the category. */
    public boolean forcesHuman() { return abuseFlag || riskFlag; }
}
