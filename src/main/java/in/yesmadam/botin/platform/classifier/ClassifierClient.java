package in.yesmadam.botin.platform.classifier;
/**
 * The two questions a model is ever asked here, and no others.
 *
 * NARROW ON PURPOSE. Both are closed-set classification of a short Hinglish string.
 * Neither generates prose, neither is given a database, and neither is asked to decide
 * anything. Naming the task this precisely is what stops the conversation drifting
 * toward a general-purpose assistant, which is a different and far more expensive
 * purchase — and it is why a failure here can safely degrade to "ask a human" rather
 * than taking a flow down with it.
 *
 * TWO IMPLEMENTATIONS, ONE INTERFACE: a deterministic stub, and an HTTP client. The
 * stub is not a mock in a test — it is a real bean, chosen at startup when no classifier
 * URL is configured, so the whole system can be built, demonstrated and reasoned about
 * before the procurement question is answered.
 */
public interface ClassifierClient {

    /**
     * The partner typed something before picking anything. Which concern is this?
     *
     * @param text what they wrote, in Hinglish, usually under twenty words
     * @return a classification, never null
     */
    Classification classifyIntent(String text);

    /**
     * The partner is inside a concern and typed a reason. Is it actually this concern?
     *
     * @param l2Concern where they currently are, so the model can rule it in or out
     */
    Classification classifyReason(String l2Concern, String text);

    /** For logging and the health endpoint: which one is actually wired in. */
    String describe();
}
