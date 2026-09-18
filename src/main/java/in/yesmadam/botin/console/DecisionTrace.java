package in.yesmadam.botin.console;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * WHY THE BOT DID THAT — recorded at the moment it decided.
 *
 * =========================================================================
 * THE PROBLEM THIS SOLVES
 * =========================================================================
 *
 * Everything this system does is defensible and almost none of it is VISIBLE. A partner sees
 * one sentence. A ticket row shows a tier and an action. In between — which facts were read,
 * what each one turned out to be, which row of which table matched and which rows did not —
 * there is nothing to look at, and that is the entire interesting part.
 *
 * A reviewer watching a demo cannot tell a good decision from a lucky one without it. Neither
 * can an agent asking "why did this reach me", nor an engineer three months from now asking
 * why one partner was paid and another refused. All three are the same question.
 *
 * =========================================================================
 * WHY IN MEMORY, AND WHY BOUNDED
 * =========================================================================
 *
 * This is a demonstration and debugging surface, not an audit log. The audit log already
 * exists and is durable: `ticket`, `ticket_action`, and Flowable's own history. Those are the
 * record. This is the explanation, and an explanation that outlives the conversation it
 * explains is just a table nobody prunes.
 *
 * Bounded to the most recent conversations, oldest evicted. It cannot grow, it cannot fail a
 * request, and losing it costs nothing that matters.
 *
 * NOTHING HERE IS ON THE DECIDING PATH. Recording happens after the decision is made, inside a
 * try/catch that swallows everything. An explanation that could break a resolution would be a
 * remarkably bad trade.
 */
@Component
public class DecisionTrace {

    /** Enough for a demo and any plausible debugging session. */
    private static final int KEEP = 200;

    /**
     * NOT NAMED `Entry`, and that is not cosmetic. Inside a `LinkedHashMap` subclass the name
     * `Entry` resolves to `Map.Entry`, so `removeEldestEntry(Map.Entry<String, Entry>)` quietly
     * became a DIFFERENT method that overrides nothing — the compiler caught it here, but the
     * same shadowing in a place without an override check would simply never be called.
     */
    private final Map<String, Recorded> bySession =
            Collections.synchronizedMap(new LinkedHashMap<String, Recorded>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Recorded> eldest) {
                    return size() > KEEP;
                }
            });

    public void record(String sessionId, String concern, String dmnKey,
                       Map<String, Object> facts, String tier, String action, String outcomeType,
                       List<RuleOutcome> rules) {
        if (sessionId == null) return;
        bySession.put(sessionId, new Recorded(concern, dmnKey, new LinkedHashMap<>(facts),
                tier, action, outcomeType, rules == null ? List.of() : rules));
    }

    public Optional<Recorded> forSession(String sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    /**
     * @param facts       every fact the table was given — INCLUDING the nulls, because a null
     *                    is why most rules do not fire and hiding it hides the answer
     * @param rules       one entry per row of the table, in order, saying whether it matched
     */
    public record Recorded(String concern, String dmnKey, Map<String, Object> facts,
                           String tier, String action, String outcomeType, List<RuleOutcome> rules) { }

    /** @param fired the row that actually produced the answer. Under FIRST there is one. */
    public record RuleOutcome(int index, boolean matched, boolean fired) { }
}
