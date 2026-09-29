package in.yesmadam.botin.concern.other.freetext;

import in.yesmadam.botin.platform.classifier.ClassifierGateway;
import in.yesmadam.botin.platform.facts.ConcernFactProvider;
import in.yesmadam.botin.platform.facts.FactRequest;
import in.yesmadam.botin.shared.classification.ClassifierFacts;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * OTHER_FREETEXT_TRIAGE — the partner typed something before picking anything at all.
 *
 * Thin by design: the four facts are computed in ClassifierFacts, which VIOL_R4_OTHERS
 * reads too. What lives HERE is only what names this concern.
 */
@Component
public class FreeTextTriageFactProvider implements ConcernFactProvider {

    private final ClassifierGateway classifier;

    public FreeTextTriageFactProvider(ClassifierGateway classifier) { this.classifier = classifier; }

    @Override public String concernCode() { return "OTHER_FREETEXT_TRIAGE"; }
    @Override public Set<String> factKeys() { return ClassifierFacts.classifierKeys(); }

    @Override
    public Map<String, Object> fetchFacts(FactRequest r) {
        return ClassifierFacts.factsFrom(this, classifier.classifyIntent(r.freeText()));
    }
}
