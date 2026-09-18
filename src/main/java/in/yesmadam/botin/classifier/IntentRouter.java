package in.yesmadam.botin.classifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.api.dto.NextStep;
import in.yesmadam.botin.decision.Decision;
import in.yesmadam.botin.decision.DecisionService;
import in.yesmadam.botin.facts.FactProviderRegistry;
import in.yesmadam.botin.facts.FactRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PLAN STEPS 85 AND 87 — the partner typed instead of tapping.
 *
 * THIS CLASS ANSWERS EXACTLY ONE QUESTION: ask again, or go? It does not route, it does
 * not pick a concern, and it does not know what 0.7 means.
 *
 * That narrowness is deliberate and it was not the first design. The obvious version
 * sends a confident match straight into the matched concern — and that quietly creates a
 * SECOND routing mechanism beside `RerouteDelegate`, which does the same job for text
 * typed inside a concern. Two mechanisms for one behaviour means two places to fix a
 * routing bug, and the one that gets fixed is the one that gets demoed. So the entry
 * point hands the partner to the TRIAGE CONCERN and lets its process, its decision table
 * and `RerouteDelegate` do the routing — the same path in-concern text takes.
 *
 * THE CONFIDENCE FLOOR IS NOT HERE EITHER. This asks the triage table what it makes of
 * the text and reads the answer; "confident enough to act on" stays one number in one
 * file, tunable without a deployment.
 *
 * WHERE THE LOOP LIVES, AND WHY NOT IN BPMN. The Engineering Design puts the
 * clarification loop in the process. It cannot go there for the ENTRY point: no process
 * exists yet, because no concern has been chosen — which is the whole situation the loop
 * exists to resolve. The in-concern loop, when it is built, still belongs in BPMN.
 *
 * AND THE LOOP IS A RATE CONTROL, NOT A COURTESY. It is the only mechanism in the design
 * that pushes the agent-connect rate DOWN: every low-confidence session recovered by a
 * re-prompt is a ticket that never reaches a person.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /** The concern that exists to catch text nothing else claims. */
    public static final String TRIAGE_CONCERN = "OTHER_FREETEXT_TRIAGE";
    private static final String TRIAGE_DECISION = "other-freetext-triage-decision";

    /** Trigger E. Risk skips the loop entirely — see below. */
    private static final String RISK_ACTION = "AGENT_RISK_FLAGGED";

    private final FactProviderRegistry providers;
    private final DecisionService decisions;
    private final ObjectMapper json;
    private final int maxClarifications;

    public IntentRouter(FactProviderRegistry providers, DecisionService decisions, ObjectMapper json,
                        @Value("${botin.classifier.clarification-attempts:2}") int maxClarifications) {
        this.providers = providers;
        this.decisions = decisions;
        this.json = json;
        this.maxClarifications = maxClarifications;
    }

    /**
     * @param clarification the step to show when we are asking again, else null
     * @param factsJson     the classification already made, handed to the process so a
     *                      real model is called ONCE per turn rather than twice
     */
    public record Outcome(NextStep clarification, String factsJson) {
        public boolean isClarification() { return clarification != null; }
    }

    public Outcome route(String spId, String text, int attemptsSoFar) {
        if (text == null || text.isBlank()) {
            return askAgain(attemptsSoFar, "empty text", null);
        }

        Map<String, Object> facts = providers.require(TRIAGE_CONCERN).fetchFacts(
                new FactRequest(null, spId, TRIAGE_CONCERN, null, text));
        String factsJson = serialise(facts);

        Decision decision = decisions.decide(TRIAGE_DECISION, facts);

        // TRIGGER E SKIPS THE LOOP. Asking someone who has just described an accident to
        // "please say a bit more" is the wrong response to the one case where speed
        // matters most.
        if (RISK_ACTION.equals(decision.action())) {
            log.info("entry text flagged as risk — going to a person now, no clarification");
            return new Outcome(null, factsJson);
        }

        // Placeable. Hand it on and let the triage process route it.
        if (decision.isReroute()) {
            log.info("entry text is placeable — handing to {} to route", TRIAGE_CONCERN);
            return new Outcome(null, factsJson);
        }

        return askAgain(attemptsSoFar, "no confident match", factsJson);
    }

    /**
     * Ask again, or stop asking.
     *
     * STOPPING IS A REAL OUTCOME, not a failure. A partner who has rephrased twice and
     * still cannot be placed is telling us something the taxonomy does not cover, and
     * the useful response is a person holding the whole transcript — not a third prompt
     * that makes them feel unheard.
     */
    private Outcome askAgain(int attemptsSoFar, String why, String factsJson) {
        if (attemptsSoFar >= maxClarifications) {
            log.info("giving up after {} clarifications ({}) — handing to a person",
                    attemptsSoFar, why);
            return new Outcome(null, factsJson);
        }

        // Not word-for-word the same twice. Repeating a prompt verbatim reads as the bot
        // not listening, which is exactly what the partner already suspects by now.
        String prompt = attemptsSoFar == 0
                ? "Thoda detail mein bataiye — kya dikkat hui?"
                : "Maaf kijiye, samajh nahi paaye. Ek baar aur bataiye, thoda saaf shabdon mein.";

        return new Outcome(NextStep.text("FREE_TEXT_CLARIFY", prompt), factsJson);
    }

    private String serialise(Map<String, Object> facts) {
        try {
            return json.writeValueAsString(facts);
        } catch (Exception e) {
            // Not fatal: the process re-fetches when this is absent. One extra model
            // call is a far better outcome than losing the turn.
            log.warn("could not carry the classification into the process — it will re-classify", e);
            return null;
        }
    }
}
