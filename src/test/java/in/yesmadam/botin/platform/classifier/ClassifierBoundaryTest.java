package in.yesmadam.botin.platform.classifier;

import in.yesmadam.botin.platform.catalogue.ConcernCatalogueRepository;
import in.yesmadam.botin.platform.decision.Decision;
import in.yesmadam.botin.platform.decision.DecisionService;
import in.yesmadam.botin.platform.facts.FactProviderRegistry;
import in.yesmadam.botin.platform.facts.FactRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 11 — plan steps 83, 84 and the step 88 checkpoint.
 *
 * WHAT THIS IS REALLY TESTING: that nothing a model says is believed without checking,
 * and that every way the model can fail produces the same safe answer.
 *
 * The gateway is built by hand here rather than autowired, with deliberately broken
 * clients standing in for the service. That is the only way to exercise the four
 * failure modes spike 5 found — a real client cannot be persuaded to return a
 * confidence of 1.7 on demand.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClassifierBoundaryTest {

    @Autowired ConcernCatalogueRepository catalogue;
    @Autowired ClassifierGateway realGateway;
    @Autowired FactProviderRegistry providers;
    @Autowired DecisionService decisions;

    /** A client that answers with whatever we hand it. */
    private ClassifierGateway gatewayReturning(Classification canned) {
        return new ClassifierGateway(new ClassifierClient() {
            @Override public Classification classifyIntent(String text) { return canned; }
            @Override public Classification classifyReason(String c, String t) { return canned; }
            @Override public String describe() { return "canned"; }
        }, catalogue);
    }

    // ------------------------------------------------- the four things spike 5 found

    @Test
    @DisplayName("A HALLUCINATED CATEGORY is discarded, however confident the model was")
    void aCategoryThatIsNotOursIsNotAMatch() {
        // The most dangerous failure in the system: a plausible-looking code that is not
        // ours, returned at 0.99, would route the partner into a flow with total
        // confidence and no error anywhere.
        Classification result = gatewayReturning(
                new Classification("REFUND_REQUEST", 0.99, "sounds real", "NEUTRAL", false, false))
                .classifyIntent("kuch to hua hai");

        assertFalse(result.matched(), "a category outside the live taxonomy must never match");
        assertEquals(0.0, result.confidence());
    }

    @Test
    @DisplayName("An INACTIVE concern is not a valid target either")
    void anInactiveConcernIsNotAMatch() {
        // ARW_TO_BANK is seeded and real and not built. A model trained on the full
        // taxonomy will happily name it, and routing a partner into an unbuilt concern
        // is how they meet "we cannot help with that" after being told we could.
        Classification result = gatewayReturning(
                new Classification("ARW_TO_BANK", 0.98, null, "NEUTRAL", false, false))
                .classifyIntent("bank ka paisa");

        assertFalse(result.matched(), "the closed set is ACTIVE concerns, not all concerns");
    }

    @Test
    @DisplayName("CONFIDENCE OUT OF BOUNDS is a broken response, not a very sure one")
    void confidenceAboveOneIsDiscarded() {
        // 1.7 beats every threshold that will ever be written. Treating it as a number
        // means the floor silently stops existing.
        assertFalse(gatewayReturning(
                new Classification("FORGET_MPIN", 1.7, null, "NEUTRAL", false, false))
                .classifyIntent("mpin").matched());

        assertFalse(gatewayReturning(
                new Classification("FORGET_MPIN", -0.5, null, "NEUTRAL", false, false))
                .classifyIntent("mpin").matched());
    }

    @Test
    @DisplayName("An UNKNOWN SENTIMENT is forced to NEUTRAL loudly, and the flags survive")
    void unknownSentimentDoesNotDisableTheRiskFlags() {
        // Falling back silently would mean a model that starts returning something
        // unexpected quietly disables trigger E — the flag that exists to catch a
        // partner in distress. The category and the flags must come through untouched.
        Classification result = gatewayReturning(
                new Classification("FORGET_MPIN", 0.9, null, "FURIOUS", true, true))
                .classifyIntent("mpin");

        assertEquals("NEUTRAL", result.sentiment());
        assertTrue(result.abuseFlag(), "the flags are not collateral damage");
        assertTrue(result.riskFlag());
        assertTrue(result.forcesHuman(), "trigger E still fires");
    }

    // ------------------------------------------- step 88: kill the service mid-session

    @Test
    @DisplayName("THE CHECKPOINT — a dead classifier degrades to a human, and throws nothing")
    void aDeadClassifierNeverReachesThePartner() {
        ClassifierGateway dead = new ClassifierGateway(new ClassifierClient() {
            @Override public Classification classifyIntent(String t) {
                throw new IllegalStateException("connection refused");
            }
            @Override public Classification classifyReason(String c, String t) {
                throw new RuntimeException("read timed out");
            }
            @Override public String describe() { return "dead"; }
        }, catalogue);

        // Not an exception, not a null, not a partial answer. The same explicit "no
        // confident match" an honest model returns for text it does not recognise — so
        // there is no second code path that could behave differently under load.
        Classification intent = assertDoesNotThrow(() -> dead.classifyIntent("mpin bhool gaya"));
        Classification reason = assertDoesNotThrow(() -> dead.classifyReason("VIOL_R4_OTHERS", "kuch aur"));

        assertFalse(intent.matched());
        assertFalse(reason.matched());
        assertEquals(0.0, intent.confidence());
        assertFalse(intent.forcesHuman(), "a dead classifier is not a risk signal — it is silence");
    }

    @Test
    @DisplayName("...and a null response is treated the same way")
    void aNullResponseIsNotATrustedAnswer() {
        assertFalse(gatewayReturning(null).classifyIntent("anything").matched());
    }

    // ------------------------------------------------ step 83: the stub is real, and honest

    @Test
    @DisplayName("The stub matches fixture phrases and admits ignorance about everything else")
    void theStubIsDeterministicAndMostlySaysNo() {
        assertEquals("FORGET_MPIN", realGateway.classifyIntent("mera mpin bhool gaya").category());
        assertEquals("FORGET_MPIN", realGateway.classifyIntent("MERA MPIN BHOOL GAYA").category(),
                "case must not change the answer");

        // The common case, and correctly so. A stub that pretended to understand real
        // partner text would make every demo a lie.
        assertFalse(realGateway.classifyIntent("bhai kal wala kaam ka kya hua").matched());
    }

    @Test
    @DisplayName("Being already in the concern is not a reroute")
    void theConcernYouAreInIsNeverTheAnswer() {
        // Sending a partner back into the flow they are standing in reads as the bot
        // ignoring them, and to the caller it is a loop.
        assertFalse(realGateway.classifyReason("FORGET_MPIN", "mpin bhool gaya").matched());
        assertTrue(realGateway.classifyReason("VIOL_R4_OTHERS", "mpin bhool gaya").matched());
    }

    // --------------------------------------------- the facts the tables actually read

    @Test
    @DisplayName("The free-text concerns still decide safely when the classifier says nothing")
    void noMatchStillReachesAPerson() {
        for (String concern : new String[]{"VIOL_R4_OTHERS", "OTHER_FREETEXT_TRIAGE"}) {
            Map<String, Object> facts = providers.require(concern).fetchFacts(
                    new FactRequest(null, "SP-CB", concern, null, "bilkul samajh nahi aaya"));

            assertEquals(false, facts.get("classificationMatched"), concern);
            assertEquals(false, facts.get("riskFlagged"), concern);
            assertNull(facts.get("rerouteTarget"), concern);

            Decision d = decisions.decide(concern.equals("VIOL_R4_OTHERS")
                    ? "viol-r4-others-decision" : "other-freetext-triage-decision", facts);
            assertFalse(d.isReroute(), concern);
            assertEquals("T3", d.tier(), concern);
        }
    }

    @Test
    @DisplayName("A risk word reaches a person even when the text also names a concern")
    void riskOutranksAConfidentMatch() {
        Map<String, Object> facts = providers.require("OTHER_FREETEXT_TRIAGE").fetchFacts(
                new FactRequest(null, "SP-CB", "OTHER_FREETEXT_TRIAGE", null,
                        "accident ho gaya, mpin bhi bhool gaya"));

        assertEquals(true, facts.get("classificationMatched"), "the text does name a concern");
        assertEquals(true, facts.get("riskFlagged"), "and it also describes an emergency");

        Decision d = decisions.decide("other-freetext-triage-decision", facts);
        assertFalse(d.isReroute(), "someone describing an accident must not be sent to self-serve");
        assertEquals("AGENT_RISK_FLAGGED", d.action());
    }

    @Test
    @DisplayName("Which classifier is wired in is visible, not guessed at")
    void theWiredClassifierIsNamed() {
        assertTrue(realGateway.describe().startsWith("stub"),
                "no CLASSIFIER_URL is set in tests, so the stub must be the one in play: "
                + realGateway.describe());
    }
}
