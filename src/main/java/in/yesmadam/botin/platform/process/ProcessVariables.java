package in.yesmadam.botin.platform.process;
/**
 * The variables every concern process is started with, and everything the generic
 * process writes as it runs.
 *
 * Kept as constants because a typo in a variable name is not a compile error and
 * not a runtime error either — the delegate simply reads null and behaves as if the
 * partner had given nothing. Same failure shape as the Togglz hole in spike 4.
 */
public final class ProcessVariables {

    /** The help_session id. Every delegate needs it to write its ending. */
    public static final String HELP_SESSION_ID = "helpSessionId";

    public static final String SP_ID = "spId";
    public static final String L2_CONCERN = "l2Concern";

    /** Whatever the partner selected from a DROPDOWN — a booking, an order, a violation. */
    public static final String SELECTED_REFERENCE = "selectedReference";

    // ---------------------------------------------------------- written as the process runs

    /**
     * The fact map, serialised as JSON rather than stored as a Map.
     *
     * A Map process variable becomes one opaque BLOB in ACT_RU_VARIABLE that nobody can
     * read when a case goes wrong. JSON is legible in the database, and the escalation
     * context needs exactly this text for its facts_snapshot — so one representation
     * serves both the decision and the agent who has to pick the case up.
     */
    public static final String FACTS_JSON = "factsJson";

    public static final String TIER = "tier";
    public static final String ACTION = "action";
    public static final String OUTCOME_TYPE = "outcomeType";

    /**
     * What the gateway branches on — NOT tier.
     *
     * Tier comes from the concern's own decision table (ADR-008). Whether a human takes
     * the case is a broader question: the pre-flight gate can force it before any table
     * runs, and an unbuilt action forces it after. One variable holds the answer so the
     * gateway has exactly one thing to read.
     */
    public static final String AGENT_REQUIRED = "agentRequired";

    /** The gateway's second branch: the partner is in the wrong concern. */
    public static final String REROUTE_REQUIRED = "rerouteRequired";

    /** Which concern to move them to. Verified against the catalogue before it is used. */
    public static final String REROUTE_TARGET = "rerouteTarget";

    /** A..E for the five defined triggers, or CONCERN for a concern table's own T3. */
    public static final String TRIGGER_REASON = "triggerReason";

    /** Set by the pre-flight gate. Trigger B is an authoritative override, not a fallback. */
    public static final String MANDATORY_HUMAN = "mandatoryHuman";

    /** Gate 1. Stays null for a T0 — and a test asserts exactly that. */
    public static final String TICKET_ID = "ticketId";

    /**
     * The satisfaction answer, carried into a trigger-A escalation.
     *
     * It travels with the process rather than being written to the ticket afterwards,
     * because "afterwards" means a SECOND write from the outer transaction to a row the
     * escalation has already committed — and Hibernate's first-level cache serves the
     * copy loaded BEFORE that commit, so the flush silently reverts it.
     */
    public static final String CSAT_RESULT = "csatResult";

    /** The third party's reference for what an action actually did. The receipt. */
    public static final String ACTION_REFERENCE = "actionReference";

    /** Which agent queue the User Task is offered to. */
    public static final String AGENT_QUEUE = "agentQueue";

    private ProcessVariables() { }
}
