package in.yesmadam.botin.decision;

import in.yesmadam.botin.console.DecisionTrace;
import org.flowable.dmn.api.DecisionExecutionAuditContainer;
import org.flowable.dmn.api.DmnDecisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The only way to evaluate a decision table.
 *
 * Everything about how the tables behave was established by spike 2, and two findings
 * are enforced here rather than left to good intentions:
 *
 *   1. Hit policy is FIRST, so ROW ORDER IS LOGIC. A row moved is a behaviour change.
 *   2. A case matching NO row returns null — not a default, not an error. Every table
 *      ends in a catch-all for that reason, and this class refuses a null result loudly
 *      instead of handing a null tier to a gateway that cannot branch on it.
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private final DmnDecisionService dmn;

    public DecisionService(DmnDecisionService dmn) {
        this.dmn = dmn;
    }

    public Decision decide(String decisionKey, Map<String, Object> facts) {
        Map<String, Object> result = dmn.createExecuteDecisionBuilder()
                .decisionKey(decisionKey)
                .variables(facts)
                .executeWithSingleResult();

        if (result == null) {
            // Only reachable if a table lost its catch-all. Failing here is the whole
            // point: the alternative is a null tier reaching a gateway, which in spike 2
            // produced no error anywhere.
            throw new NoMatchingRuleException(decisionKey, facts);
        }

        Decision decision = new Decision(
                str(result.get("tier")),
                str(result.get("action")),
                str(result.get("outcomeType")),
                result);

        log.debug("{} -> tier={} action={} type={}",
                decisionKey, decision.tier(), decision.action(), decision.outcomeType());
        return decision;
    }

    /**
     * THE SAME DECISION, PLUS WHICH ROW PRODUCED IT.
     *
     * `decide` answers "what". This answers "why", and the difference is the whole reason
     * anybody trusts a rules engine rather than a method full of if statements: the table is
     * readable, and a reader can be SHOWN the row that fired instead of told about it.
     *
     * Flowable keeps a per-rule audit of every evaluation. We ask for it rather than working
     * out which row must have matched, because working it out means re-implementing the
     * expression language beside the engine — a second source of truth for the one thing that
     * must never have two.
     *
     * Under FIRST hit policy exactly one row produces the answer: the first that matched.
     * Everything above it is a row that did NOT match, and seeing which is usually the
     * explanation somebody is actually after.
     */
    public Explained decideExplained(String decisionKey, Map<String, Object> facts) {
        DecisionExecutionAuditContainer audit = dmn.createExecuteDecisionBuilder()
                .decisionKey(decisionKey)
                .variables(facts)
                .executeWithAuditTrail();

        List<Map<String, Object>> results = audit.getDecisionResult();
        Map<String, Object> result = results == null || results.isEmpty() ? null : results.get(0);
        if (result == null) throw new NoMatchingRuleException(decisionKey, facts);

        // =====================================================================
        // SORTED, AND RE-INDEXED FROM ZERO. Both halves were bugs.
        // =====================================================================
        //
        // FLOWABLE NUMBERS ITS RULES FROM 1. The reader that parses the .dmn file numbers
        // them from 0, and the console joins the two by index — so every outcome landed on
        // the row BELOW the one it belonged to, and the last row's outcome matched nothing
        // at all. With the catch-all firing, that showed as "no row fired". With the cap
        // firing, it would have shown as the cap being fine and the row beneath it paying:
        // A CONFIDENTLY WRONG EXPLANATION, which is worse than none, because somebody
        // watching a demo has no way to know.
        //
        // AND THE ORDER MATTERS SEPARATELY. "The first match wins" is only true if the
        // entries are walked in rule order, and a Map makes no such promise. It happened to
        // work. Sorting makes it true rather than lucky.
        List<DecisionTrace.RuleOutcome> rules = new ArrayList<>();
        if (audit.getRuleExecutions() != null) {
            List<Integer> inOrder = new ArrayList<>(audit.getRuleExecutions().keySet());
            Collections.sort(inOrder);

            boolean firedFound = false;
            for (int position = 0; position < inOrder.size(); position++) {
                boolean matched = isValid(audit.getRuleExecutions().get(inOrder.get(position)));
                boolean fired = matched && !firedFound;      // FIRST: the first match wins
                if (fired) firedFound = true;
                rules.add(new DecisionTrace.RuleOutcome(position, matched, fired));
            }
        }

        return new Explained(new Decision(str(result.get("tier")), str(result.get("action")),
                str(result.get("outcomeType")), result), rules);
    }

    /** Reflective, so an audit-container shape change degrades the EXPLANATION, not the flow. */
    private static boolean isValid(Object ruleExecution) {
        try {
            return (boolean) ruleExecution.getClass().getMethod("isValid").invoke(ruleExecution);
        } catch (Exception e) {
            return false;
        }
    }

    public record Explained(Decision decision, List<DecisionTrace.RuleOutcome> rules) { }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    public static class NoMatchingRuleException extends RuntimeException {
        public NoMatchingRuleException(String decisionKey, Map<String, Object> facts) {
            super("Decision table '" + decisionKey + "' matched no rule and has no catch-all. Facts: " + facts);
        }
    }
}
