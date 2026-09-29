package in.yesmadam.botin.platform.process;

import in.yesmadam.botin.platform.action.ActionRegistry;
import in.yesmadam.botin.platform.catalogue.CatalogueService;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import in.yesmadam.botin.platform.decision.Decision;
import in.yesmadam.botin.platform.decision.DecisionService;
import in.yesmadam.botin.platform.safety.KillSwitch;
import in.yesmadam.botin.surface.console.DecisionTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * STAGE 3 — the concern's own decision table. The only step that decides anything.
 *
 * WHY THE DECISION IS CALLED FROM A DELEGATE AND NOT A BPMN BUSINESS RULE TASK. A
 * businessRuleTask names its decision key in the process file. That would put the same
 * pointer in two places — concern_catalogue.dmn_key and the BPMN — and the two would be
 * free to disagree, which is the one thing ADR-006 exists to prevent. It would also
 * force a BPMN file per concern where one generic file does. The decision is still
 * configuration; only the lookup of WHICH table moved.
 *
 * EVERY PATH OUT OF HERE IS SAFE. The table can return a tier this build cannot act on,
 * the facts can have failed, a rule can match nothing. All three end with a human,
 * which is the only default that cannot hurt a partner.
 */
@Component("decideDelegate")
public class DecideDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(DecideDelegate.class);

    /** Not one of the five cross-cutting triggers — see EscalationContext.triggerReason. */
    public static final String TRIGGER_CONCERN_DECISION = "CONCERN";

    private final CatalogueService catalogue;
    private final DecisionService decisions;
    private final ActionRegistry actions;
    private final KillSwitch killSwitch;
    private final DecisionTrace trace;
    private final ObjectMapper json;

    public DecideDelegate(CatalogueService catalogue, DecisionService decisions,
                          ActionRegistry actions, KillSwitch killSwitch, ObjectMapper json,
                          DecisionTrace trace) {
        this.catalogue = catalogue;
        this.decisions = decisions;
        this.actions = actions;
        this.killSwitch = killSwitch;
        this.json = json;
        this.trace = trace;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String l2Concern = (String) execution.getVariable(ProcessVariables.L2_CONCERN);

        // Trigger B already answered the question. Running the table anyway could only
        // produce a tier we are contractually required to ignore.
        if (Boolean.TRUE.equals(execution.getVariable(ProcessVariables.MANDATORY_HUMAN))) {
            escalate(execution, "AGENT_MANDATORY_HUMAN", PreFlightDelegate.TRIGGER_MANDATORY_HUMAN);
            return;
        }

        if (Boolean.TRUE.equals(execution.getVariable(FetchFactsDelegate.FACTS_FAILED))) {
            escalate(execution, "AGENT_FACTS_UNAVAILABLE", TRIGGER_CONCERN_DECISION);
            return;
        }

        try {
            ConcernCatalogue concern = catalogue.find(l2Concern).orElseThrow();
            Map<String, Object> facts = readFacts(execution);

            DecisionService.Explained explained = decisions.decideExplained(concern.getDmnKey(), facts);
            Decision decision = explained.decision();

            // WHY, recorded beside WHAT. After the decision, never before it, and inside a
            // catch-everything: an explanation that could break a resolution would be a
            // remarkably bad trade. See DecisionTrace.
            try {
                trace.record((String) execution.getVariable(ProcessVariables.HELP_SESSION_ID),
                        l2Concern, concern.getDmnKey(), facts,
                        decision.tier(), decision.action(), decision.outcomeType(), explained.rules());
            } catch (Exception traceFailure) {
                log.debug("could not record a decision trace — the decision is unaffected", traceFailure);
            }

            // ---------------------------------------------------------------------
            // A T2 MOVES MONEY OR CHANGES STATE. TWO THINGS MUST BOTH BE TRUE BEFORE
            // ONE IS ALLOWED TO RUN, and either being false means the same thing: do
            // not automate, send this to a person. The partner is still served; only
            // the automation stops.
            //
            //   1. SOMETHING CAN ACTUALLY PERFORM IT. A table may name an action whose
            //      plumbing is not built — rules get written before services, which is
            //      the right order. Telling a partner their wallet has been credited
            //      when nothing happened is worse than any delay.
            //   2. THE KILL SWITCH IS ON. ADR-007. This is the check step 92 flips at
            //      runtime: the next request routes to T3, with no restart and nothing
            //      in flight disturbed.
            //
            // The flag is read HERE rather than at a BPMN gateway because it decides
            // the TIER, and tier has to be settled before Gate 1 opens a ticket that
            // says T2. A gateway downstream would be reading a decision already made.
            // ---------------------------------------------------------------------
            if (decision.movesMoney()) {
                if (!actions.canPerform(decision.action())) {
                    log.warn("{} decided {} (T2) but nothing performs it — escalating",
                            l2Concern, decision.action());
                    escalate(execution, "AGENT_ACTION_NOT_BUILT", TRIGGER_CONCERN_DECISION);
                    return;
                }
                if (!killSwitch.isAutomationAllowed(concern)) {
                    log.warn("{} decided {} (T2) but its kill switch is OFF — escalating",
                            l2Concern, decision.action());
                    escalate(execution, "AGENT_AUTOMATION_DISABLED", TRIGGER_CONCERN_DECISION);
                    return;
                }
            }

            // REROUTE: the partner is in the wrong concern and the table named the right
            // one. The target travels as a FACT rather than an output, because a DMN
            // output can only be a literal — the table decides THAT a reroute should
            // happen; it cannot know where to.
            if (decision.isReroute()) {
                String target = String.valueOf(facts.get("rerouteTarget"));
                execution.setVariable(ProcessVariables.TIER, "-");
                execution.setVariable(ProcessVariables.ACTION, decision.action());
                execution.setVariable(ProcessVariables.OUTCOME_TYPE, "REROUTE");
                execution.setVariable(ProcessVariables.REROUTE_TARGET, target);
                execution.setVariable(ProcessVariables.REROUTE_REQUIRED, true);
                execution.setVariable(ProcessVariables.AGENT_REQUIRED, false);
                execution.setVariable(ProcessVariables.TRIGGER_REASON, null);
                log.info("{} decided REROUTE -> {}", l2Concern, target);
                return;
            }

            execution.setVariable(ProcessVariables.REROUTE_REQUIRED, false);
            execution.setVariable(ProcessVariables.TIER, decision.tier());
            execution.setVariable(ProcessVariables.ACTION, decision.action());
            execution.setVariable(ProcessVariables.OUTCOME_TYPE, decision.outcomeType());

            boolean agentRequired = "T3".equals(decision.tier());
            execution.setVariable(ProcessVariables.AGENT_REQUIRED, agentRequired);
            execution.setVariable(ProcessVariables.TRIGGER_REASON,
                    agentRequired ? TRIGGER_CONCERN_DECISION : null);

            log.info("{} decided tier={} action={} type={}",
                    l2Concern, decision.tier(), decision.action(), decision.outcomeType());

        } catch (Exception e) {
            // A table that matched nothing, a table that failed to evaluate, a dmn_key
            // pointing at nothing. The partner gets a person either way.
            log.error("decision failed for {} — escalating", l2Concern, e);
            escalate(execution, "AGENT_DECISION_FAILED", TRIGGER_CONCERN_DECISION);
        }
    }

    private void escalate(DelegateExecution execution, String action, String triggerReason) {
        // Set on EVERY path, because the gateway reads it. An absent variable in a
        // condition expression is an evaluation error, not a false — the same
        // absent-versus-null distinction that governs the decision tables themselves.
        execution.setVariable(ProcessVariables.REROUTE_REQUIRED, false);
        execution.setVariable(ProcessVariables.TIER, "T3");
        execution.setVariable(ProcessVariables.ACTION, action);
        execution.setVariable(ProcessVariables.OUTCOME_TYPE, "TICKET");
        execution.setVariable(ProcessVariables.AGENT_REQUIRED, true);
        execution.setVariable(ProcessVariables.TRIGGER_REASON, triggerReason);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFacts(DelegateExecution execution) throws Exception {
        String factsJson = (String) execution.getVariable(ProcessVariables.FACTS_JSON);
        return json.readValue(factsJson == null ? "{}" : factsJson, Map.class);
    }
}
