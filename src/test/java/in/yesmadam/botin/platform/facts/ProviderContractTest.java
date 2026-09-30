package in.yesmadam.botin.platform.facts;

import in.yesmadam.botin.platform.catalogue.ConcernCatalogueRepository;
import in.yesmadam.botin.platform.decision.Decision;
import in.yesmadam.botin.platform.decision.DecisionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 46 CHECKPOINT — the join between the fact providers and the decision tables.
 *
 * This is the test that would have caught `arrivedAt300m` vs `arrivedAt300metre`. A fact
 * name that differs by one character is not a compile error, not a runtime error, and not
 * a log line — the table reads null, never matches, and every claim quietly reaches an
 * agent. Four separate things have now bitten this project that way.
 *
 * It runs with UAT DISABLED, which is the whole trick: a provider must return its full
 * key set even when it can look nothing up, so the contract is testable without a
 * database and without a credential.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProviderContractTest {

    @Autowired FactProviderRegistry registry;
    @Autowired ConcernCatalogueRepository catalogue;
    @Autowired DecisionService decisions;
    @Autowired List<ConcernFactProvider> providers;

    /** Same list as TABLE_INPUTS in DecisionTableTest. Duplicated ON PURPOSE — see below. */
    private static final Map<String, Set<String>> TABLE_INPUTS = Map.of(
        "TRANSPORT_NOT_RECEIVED", Set.of(
            "alreadyCredited", "computedAmountRupees", "transportPath", "arrivedAt300metre",
            "cancellationStatus", "lastMinCashbackCredited", "distanceBeyondRadiusKm"),
        "RECHARGE_DEBIT_NO_CREDIT", Set.of("payuStatus", "alreadyCredited", "amountRupees"),
        "FORGET_MPIN", Set.of("l2Concern"),
        "VIOL_R4_OTHERS", Set.of("classificationMatched", "classifierConfidence",
                                 "rerouteTarget", "riskFlagged"),
        "PROD_DELIVERY_DELAY", Set.of("pastElevenPmDeadline"),
        "OTHER_FREETEXT_TRIAGE", Set.of("classificationMatched", "classifierConfidence",
                                        "rerouteTarget", "riskFlagged")
    );

    @Test
    @DisplayName("every active concern has a fact provider")
    void everyActiveConcernHasAProvider() {
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c ->
                assertTrue(registry.has(c.getFactProvider()),
                        "active concern with no fact provider: " + c.getL2Code()));
    }

    @Test
    @DisplayName("THE NAME CHECK — each provider declares exactly the facts its table reads")
    void providerKeysMatchTheDecisionTable() {
        // Deliberately restated here rather than shared with DecisionTableTest. If both
        // read one constant, renaming that constant renames "the contract" on both sides
        // at once and the test agrees with itself while the table disagrees with reality.
        // Two independent statements of the same fact is what makes the check real.
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            Set<String> expected = TABLE_INPUTS.get(c.getL2Code());
            assertNotNull(expected, "no declared table contract for " + c.getL2Code());

            Set<String> declared = registry.require(c.getFactProvider()).factKeys();
            assertEquals(expected, declared,
                    "fact names drifted for " + c.getL2Code()
                    + " — the table reads " + expected + ", the provider supplies " + declared);
        });
    }

    @Test
    @DisplayName("with UAT off, every provider still returns its COMPLETE key set")
    void providersReturnEveryKeyEvenKnowingNothing() {
        // Strict mode: an ABSENT variable is an evaluation error that takes the whole
        // decision down, catch-all included. A present-but-null one lands on the
        // catch-all, which is a human. So "I could not look anything up" must produce a
        // full map of nulls, never a smaller map.
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            ConcernFactProvider provider = registry.require(c.getFactProvider());
            Map<String, Object> facts = provider.fetchFacts(
                    new FactRequest(UUID.randomUUID(), "SP-CONTRACT", c.getL2Code(), null, null));

            assertEquals(provider.factKeys(), facts.keySet(),
                    c.getL2Code() + " returned a different key set than it declares");
        });
    }

    @Test
    @DisplayName("those all-null facts DECIDE, and never move money")
    void knowingNothingDecidesAndNeverPays() {
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            Map<String, Object> facts = registry.require(c.getFactProvider()).fetchFacts(
                    new FactRequest(UUID.randomUUID(), "SP-CONTRACT", c.getL2Code(), null, null));

            // A concern that decides nothing has no table to hand these facts to. It also
            // cannot move money, which is what this test is really protecting.
            if (c.getDmnKey() == null) return;

            Decision d = decisions.decide(c.getDmnKey(), facts);
            assertNotNull(d.tier(), c.getL2Code() + " produced no tier");
            assertFalse(d.movesMoney(),
                    c.getL2Code() + " moved money on facts it could not look up");
        });
    }

    @Test
    @DisplayName("the free-text concerns say 'no confident match', not 'no opinion'")
    void absentClassifierIsAnExplicitNo() {
        // A wrong reroute costs the partner an entire second journey through the wrong
        // flow. With no classifier yet, that has to read as a definite no — set, not left
        // null. Null would reach the same catch-all, but by accident rather than decision.
        for (String concern : List.of("VIOL_R4_OTHERS", "OTHER_FREETEXT_TRIAGE")) {
            Map<String, Object> facts = registry.require(concern).fetchFacts(
                    new FactRequest(UUID.randomUUID(), "SP-CONTRACT", concern, null, "kuch aur hua hai"));

            assertEquals(false, facts.get("classificationMatched"), concern);
            assertEquals(0.0, facts.get("classifierConfidence"), concern);
            assertFalse(decisions.decide(concern.equals("VIOL_R4_OTHERS")
                            ? "viol-r4-others-decision" : "other-freetext-triage-decision",
                    facts).isReroute(), "rerouted with no classifier — " + concern);
        }
    }

    @Test
    @DisplayName("every UAT column a provider names is declared, and unconfirmed ones are marked")
    void uatDependenciesAreDeclared() {
        // The probe can only check what is declared. A provider that queries a column it
        // never declared is exactly the case the probe cannot protect, so at least assert
        // the declarations are well formed and that guesses are labelled as guesses.
        int inferred = 0;
        for (ConcernFactProvider provider : providers) {
            for (UatColumn col : provider.requiredColumns()) {
                assertNotNull(col.catalog(), "column with no catalog on " + provider.concernCode());
                assertTrue(List.of("ysmdm_admin", "ysmdm_users", "ysmdm_employees").contains(col.catalog()),
                        "unknown catalog " + col.catalog() + " on " + provider.concernCode());
                assertFalse(col.column().isBlank());
                if (!col.confirmed()) inferred++;
            }
        }
        assertTrue(inferred > 0,
                "every column is marked confirmed — if that is genuinely true, say so deliberately");
    }
}
