package in.yesmadam.botin.classifier;

import in.yesmadam.botin.catalogue.ConcernCatalogue;
import in.yesmadam.botin.catalogue.ConcernCatalogueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * THE BOUNDARY. Everything the model says passes through here, and nothing that fails a
 * check gets past it.
 *
 * WHY A SEPARATE CLASS FROM THE CLIENT. The client's job is to talk to a service; this
 * one's job is to distrust the answer. Spike 5 fed the boundary a hallucinated category,
 * a confidence of 1.7, prose where a field should be, and a missing field — all four had
 * to stop here, and they only stop reliably if there is a single place responsible for
 * stopping them.
 *
 * THE HALLUCINATION GUARD IS READ FROM THE CATALOGUE, NOT HARD-CODED. A category is
 * valid only if it names a concern that is ACTIVE right now. That makes the closed set
 * correct by construction as concerns are switched on and off, and it means a model
 * trained on last quarter's taxonomy cannot route a partner into a flow that no longer
 * exists.
 *
 * WHAT THIS CLASS NEVER DOES: throw, and threshold. It never throws because every
 * failure means the same thing — we do not know — and the caller has one safe answer for
 * that. It never thresholds because the 0.7 floor belongs in the decision tables, where
 * it can be tuned without a deployment (plan step 51).
 */
@Component
public class ClassifierGateway {

    private static final Logger log = LoggerFactory.getLogger(ClassifierGateway.class);

    private static final Set<String> SENTIMENTS = Set.of("NEUTRAL", "NEGATIVE", "ABUSIVE");

    private final ClassifierClient client;
    private final ConcernCatalogueRepository catalogue;

    public ClassifierGateway(ClassifierClient client, ConcernCatalogueRepository catalogue) {
        this.client = client;
        this.catalogue = catalogue;
    }

    public Classification classifyIntent(String text) {
        if (isEmpty(text)) return Classification.NO_MATCH;
        return check(safely(() -> client.classifyIntent(text)), text);
    }

    public Classification classifyReason(String l2Concern, String text) {
        if (isEmpty(text)) return Classification.NO_MATCH;
        return check(safely(() -> client.classifyReason(l2Concern, text)), text);
    }

    public String describe() { return client.describe(); }

    /**
     * Every failure mode of a remote call, collapsed into one answer.
     *
     * The circuit breaker opening, a timeout, a connection refused, a 500, malformed
     * JSON — to a partner these are the same event, and the right response to all of
     * them is a person. Catching broadly here is deliberate, not laziness: a narrower
     * catch would let some unanticipated exception reach the flow and turn "the
     * classifier is down" into an error the partner sees.
     */
    private Classification safely(java.util.function.Supplier<Classification> call) {
        try {
            Classification result = call.get();
            return result == null ? Classification.NO_MATCH : result;
        } catch (Exception e) {
            log.warn("classifier unavailable ({}) — treating as no confident match",
                    e.getClass().getSimpleName());
            return Classification.NO_MATCH;
        }
    }

    /** The four checks spike 5 proved are needed, in the order they can fail. */
    private Classification check(Classification raw, String text) {
        if (raw == null || !raw.matched()) return normaliseSentiment(raw);

        // 1. HALLUCINATED CATEGORY. The single most dangerous failure: a plausible code
        //    that is not ours routes the partner into the wrong flow with high confidence.
        if (!activeConcernCodes().contains(raw.category())) {
            log.warn("classifier returned category '{}', which is not an active concern — "
                   + "discarding the match for text of {} chars", raw.category(), text.length());
            return Classification.NO_MATCH;
        }

        // 2. CONFIDENCE OUT OF BOUNDS. 1.7 is not "very confident", it is a broken
        //    response, and treating it as a number would beat every threshold forever.
        if (raw.confidence() < 0.0 || raw.confidence() > 1.0 || Double.isNaN(raw.confidence())) {
            log.warn("classifier returned confidence {} for '{}' — out of bounds, discarding",
                    raw.confidence(), raw.category());
            return Classification.NO_MATCH;
        }

        return normaliseSentiment(raw);
    }

    /**
     * An unrecognised sentiment is NOT treated as neutral by omission.
     *
     * Falling back to NEUTRAL silently would mean a model that starts returning
     * something unexpected quietly disables trigger E — the flag that exists to catch a
     * partner in distress. So an unknown value is logged and forced to NEUTRAL
     * deliberately, and the flags are preserved untouched.
     */
    private Classification normaliseSentiment(Classification raw) {
        if (raw == null) return Classification.NO_MATCH;
        if (raw.sentiment() != null && SENTIMENTS.contains(raw.sentiment())) return raw;

        log.warn("classifier returned sentiment '{}', which is not one of {} — forcing NEUTRAL",
                raw.sentiment(), SENTIMENTS);
        return new Classification(raw.category(), raw.confidence(), raw.extractedReason(),
                "NEUTRAL", raw.abuseFlag(), raw.riskFlag());
    }

    private Set<String> activeConcernCodes() {
        return catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().stream()
                .map(ConcernCatalogue::getL2Code)
                .collect(Collectors.toSet());
    }

    private static boolean isEmpty(String text) {
        return text == null || text.isBlank();
    }
}
