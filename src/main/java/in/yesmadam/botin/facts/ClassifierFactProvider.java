package in.yesmadam.botin.facts;

import in.yesmadam.botin.classifier.Classification;
import in.yesmadam.botin.classifier.ClassifierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * The two concerns whose question is "is this actually a different concern?" —
 * VIOL_R4_OTHERS and OTHER_FREETEXT_TRIAGE. Both read the same facts, so one class
 * serves both rather than two that have to be kept in step.
 *
 * REPORTS WHAT THE CLASSIFIER SAID. DECIDES NOTHING. Whether 0.9 is confident enough to
 * act on, and whether a risk flag outranks a match, are both questions for the decision
 * table — business calls that will be tuned, and tuning them must not need a deployment.
 * So this class hands over four facts and stops.
 *
 * WITH NO CLASSIFIER CONFIGURED the stub answers, and it answers NO_MATCH for anything
 * that is not a fixture phrase — which is most real text. Every one of those reaches a
 * human, which is correct rather than a placeholder: a wrong reroute costs the partner
 * an entire second journey through the wrong flow.
 */
@Component
public class ClassifierFactProvider {

    private static final Logger log = LoggerFactory.getLogger(ClassifierFactProvider.class);

    /** Exactly the inputs both reroute tables read. Restated in the tests, on purpose. */
    static Set<String> classifierKeys() {
        return Set.of("classificationMatched", "classifierConfidence", "rerouteTarget", "riskFlagged");
    }

    static Map<String, Object> factsFrom(ConcernFactProvider self, Classification result) {
        Map<String, Object> facts = self.emptyFacts();

        // SET EXPLICITLY, NEVER LEFT NULL. A null would reach the same catch-all, but by
        // accident rather than by decision — and the day a real classifier is wired in
        // badly, "no opinion" and "no confident match" would behave identically right up
        // until they did not.
        facts.put("classificationMatched", result.matched());
        facts.put("classifierConfidence", result.confidence());
        facts.put("rerouteTarget", result.category());
        facts.put("riskFlagged", result.forcesHuman());

        log.debug("{} -> matched={} confidence={} target={} risk={}", self.concernCode(),
                result.matched(), result.confidence(), result.category(), result.forcesHuman());
        return facts;
    }

    /** VIOL_R4_OTHERS — the partner picked "other" on an appeal and typed a reason. */
    @Component
    public static class ViolationOthers implements ConcernFactProvider {

        private final ClassifierGateway classifier;

        public ViolationOthers(ClassifierGateway classifier) { this.classifier = classifier; }

        @Override public String concernCode() { return "VIOL_R4_OTHERS"; }
        @Override public Set<String> factKeys() { return classifierKeys(); }

        @Override
        public Map<String, Object> fetchFacts(FactRequest r) {
            // The concern is passed so the model can rule it OUT. "It is this concern
            // after all" is not a reroute — sending the partner back into the flow they
            // are already in reads as the bot ignoring them.
            return factsFrom(this, classifier.classifyReason(concernCode(), r.freeText()));
        }
    }

    /** OTHER_FREETEXT_TRIAGE — the partner typed something before picking anything at all. */
    @Component
    public static class FreeTextTriage implements ConcernFactProvider {

        private final ClassifierGateway classifier;

        public FreeTextTriage(ClassifierGateway classifier) { this.classifier = classifier; }

        @Override public String concernCode() { return "OTHER_FREETEXT_TRIAGE"; }
        @Override public Set<String> factKeys() { return classifierKeys(); }

        @Override
        public Map<String, Object> fetchFacts(FactRequest r) {
            return factsFrom(this, classifier.classifyIntent(r.freeText()));
        }
    }
}
