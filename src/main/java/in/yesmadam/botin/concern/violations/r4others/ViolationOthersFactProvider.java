package in.yesmadam.botin.concern.violations.r4others;

import in.yesmadam.botin.platform.classifier.ClassifierGateway;
import in.yesmadam.botin.platform.facts.ConcernFactProvider;
import in.yesmadam.botin.platform.facts.FactRequest;
import in.yesmadam.botin.shared.classification.ClassifierFacts;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * VIOL_R4_OTHERS — the partner picked "other" on an appeal and typed a reason.
 *
 * Thin by design: the four facts are computed in ClassifierFacts, which OTHER_FREETEXT_TRIAGE
 * reads too. What lives HERE is only what names this concern.
 */
@Component
public class ViolationOthersFactProvider implements ConcernFactProvider {

    private final ClassifierGateway classifier;

    public ViolationOthersFactProvider(ClassifierGateway classifier) { this.classifier = classifier; }

    @Override public String concernCode() { return "VIOL_R4_OTHERS"; }
    @Override public Set<String> factKeys() { return ClassifierFacts.classifierKeys(); }

    @Override
    public Map<String, Object> fetchFacts(FactRequest r) {
        // The concern is passed so the model can rule it OUT. "It is this concern
        // after all" is not a reroute — sending the partner back into the flow they
        // are already in reads as the bot ignoring them.
        return ClassifierFacts.factsFrom(this, classifier.classifyReason(concernCode(), r.freeText()));
    }
}
