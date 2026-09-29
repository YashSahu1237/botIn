package in.yesmadam.botin.shared.classification;

import in.yesmadam.botin.platform.classifier.Classification;
import in.yesmadam.botin.platform.facts.ConcernFactProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * The facts a reroute question is decided from, shared by the two concerns that ask it.
 *
 * WHY THIS IS IN shared/ RATHER THAN BESIDE EITHER CONCERN. VIOL_R4_OTHERS sits in the
 * VIOLATIONS L1 and OTHER_FREETEXT_TRIAGE in OTHER_ISSUES. A helper used by two concerns in
 * DIFFERENT L1s rises to the global shared/ — the same rule that put tat, geo and ledger here.
 * It has no single concern folder to live in, and that is the structure telling the truth.
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
public final class ClassifierFacts {

    private static final Logger log = LoggerFactory.getLogger(ClassifierFacts.class);

    private ClassifierFacts() { }

    /** Exactly the inputs both reroute tables read. Restated in the tests, on purpose. */
    public static Set<String> classifierKeys() {
        return Set.of("classificationMatched", "classifierConfidence", "rerouteTarget", "riskFlagged");
    }

    public static Map<String, Object> factsFrom(ConcernFactProvider self, Classification result) {
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
}
