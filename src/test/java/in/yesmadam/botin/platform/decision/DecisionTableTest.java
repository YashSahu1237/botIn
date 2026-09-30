package in.yesmadam.botin.platform.decision;

import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogueRepository;
import org.flowable.dmn.api.DmnRepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PHASE 5 CHECKPOINT — one test per rule, fed straight to the decision engine.
 *
 * No database, no fact provider, no HTTP. Facts go in as a map, an outcome comes out.
 * That is deliberate: a rule that is wrong should fail here, in milliseconds, naming the
 * rule — not three layers up where the symptom is "the partner got the wrong answer".
 *
 * It is also why this phase was taken ahead of the fact providers. The rules can be
 * proven while the UAT schema is still an open question.
 */
@SpringBootTest
@ActiveProfiles("test")
class DecisionTableTest {

    @Autowired DecisionService decisions;
    @Autowired DmnRepositoryService dmnRepositoryService;
    @Autowired ConcernCatalogueRepository catalogue;

    // ---------------------------------------------------------------- structure

    @Test
    @DisplayName("every active concern's dmn_key resolves to a deployed decision")
    void noDanglingDecisionKeys() {
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            // A NULL dmn_key is legitimate for a concern that DECIDES NOTHING. FORGET_MPIN is
            // one: its process is startEvent -> finaliseStep -> endEvent, with no decide step,
            // so a table there would be deployed, pointed at, and never evaluated — which it
            // was, until V8. The invariant that matters is narrower than "everything has one":
            //
            //   concern-generic ALWAYS reaches DecideDelegate, so it MUST have a dmn_key.
            //   A concern with its own process may legitimately have none.
            //
            // tools/preflight.py check_catalogue_pointers asserts the same thing without a JVM.
            if (c.getDmnKey() == null) {
                assertNotEquals("concern-generic", c.getProcessKey(),
                        c.getL2Code() + " runs concern-generic, which always decides, but has "
                        + "no dmn_key — DecideDelegate would dereference null on a real session");
                return;
            }
            long deployed = dmnRepositoryService.createDecisionQuery()
                    .decisionKey(c.getDmnKey()).count();
            assertEquals(1, deployed,
                    "catalogue points at decision '" + c.getDmnKey() + "' which is not deployed");
        });
    }

    @Test
    @DisplayName("a table with nothing known never pays — it decides safely or fails loudly")
    void nothingKnownNeverPays() {
        // An EMPTY fact map is not the same as a map of nulls. The engine runs in strict
        // mode, so an input naming a variable that is absent entirely is an evaluation
        // error, and an error takes the WHOLE decision down — catch-all included. That is
        // the right default: a fact provider that forgot a key should stop the flow, not
        // quietly match some other row.
        //
        // So the requirement this test encodes is not "always returns a row". It is the
        // one that actually matters: NOTHING KNOWN NEVER MOVES MONEY.
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            // A concern that decides nothing has no table to sweep. See noDanglingDecisionKeys
            // for the invariant that still holds: concern-generic MUST have a dmn_key.
            if (c.getDmnKey() == null) return;
            try {
                Decision d = decisions.decide(c.getDmnKey(), new HashMap<>());
                assertNotNull(d.outcomeType(), "no outcomeType from " + c.getDmnKey());
                assertFalse(d.movesMoney(),
                        c.getDmnKey() + " moved money knowing nothing about the case");
            } catch (DecisionService.NoMatchingRuleException failedLoudly) {
                // Acceptable, and preferable to a guess.
            }
        });
    }

    /**
     * THE FACT CONTRACT. Every input each table reads, by name.
     *
     * Written out rather than discovered, because this list is the thing that has to
     * match what the fact providers put in the map — and a name that does not match
     * behaves exactly like a value that is false. The table read arrivedAt300m while the
     * provider supplied arrivedAt300metre, and every Path 2 claim would have fallen
     * through to an agent with nothing anywhere reporting it.
     *
     * When a provider is written in Phase 4, this is the list it must satisfy.
     */
    private static final Map<String, List<String>> TABLE_INPUTS = Map.of(
        "transport-not-received-decision", List.of(
            "alreadyCredited", "computedAmountRupees", "transportPath", "arrivedAt300metre",
            "cancellationStatus", "lastMinCashbackCredited", "distanceBeyondRadiusKm"),
        "recharge-debit-no-credit-decision", List.of(
            "payuStatus", "alreadyCredited", "amountRupees"),
        "viol-r4-others-decision", List.of("classificationMatched", "classifierConfidence",
                                           "rerouteTarget", "riskFlagged"),
        "viol-r5-periods-decision", List.of("priorPeriodLeavesThisMonth"),
        "viol-r9-no-product-decision", List.of(
            "orderPlaced", "pastDeliveryTat", "priorRemovalsThisCycle"),
        "prod-delivery-delay-decision", List.of("pastElevenPmDeadline"),
        "other-freetext-triage-decision", List.of("classificationMatched", "classifierConfidence",
                                                  "rerouteTarget", "riskFlagged")
    );

    @Test
    @DisplayName("every table's catch-all is reachable when the facts are present but unknown")
    void everyTableHasAReachableCatchAll() {
        // What a real fact provider produces: every key PRESENT, values null where the
        // lookup found nothing. This is the case the catch-all exists for.
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            // A concern that decides nothing has no table to sweep. See noDanglingDecisionKeys
            // for the invariant that still holds: concern-generic MUST have a dmn_key.
            if (c.getDmnKey() == null) return;
            List<String> inputs = TABLE_INPUTS.get(c.getDmnKey());
            assertNotNull(inputs, "no declared fact contract for " + c.getDmnKey());

            Map<String, Object> nulls = new HashMap<>();
            inputs.forEach(name -> nulls.put(name, null));

            Decision d = decisions.decide(c.getDmnKey(), nulls);
            assertNotNull(d.tier(), "no tier from " + c.getDmnKey() + " on all-null facts");
            assertFalse(d.movesMoney(), c.getDmnKey() + " paid out on all-null facts");
        });
    }

    @Test
    @DisplayName("the fact contract covers every active concern, with no table left out")
    void everyActiveConcernHasADeclaredFactContract() {
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            if (c.getDmnKey() == null) return;      // decides nothing, so reads no facts
            assertTrue(TABLE_INPUTS.containsKey(c.getDmnKey()),
                    "a table was added without declaring what facts it reads: " + c.getDmnKey());
        });
    }

    // ------------------------------------------------------- TRANSPORT, 10 rules

    @Nested
    @DisplayName("TRANSPORT_NOT_RECEIVED")
    class Transport {

        private static final String KEY = "transport-not-received-decision";

        private Map<String, Object> facts() {
            Map<String, Object> f = new HashMap<>();
            f.put("alreadyCredited", false);
            f.put("computedAmountRupees", Rupees.of(100));   // comfortably under the cap
            f.put("transportPath", "PATH_3");
            f.put("arrivedAt300metre", true);
            f.put("cancellationStatus", null);
            f.put("lastMinCashbackCredited", false);
            f.put("distanceBeyondRadiusKm", 0);
            return f;
        }

        @Test @DisplayName("rule 1 — already credited beats everything")
        void alreadyCredited() {
            Map<String, Object> f = facts();
            f.put("alreadyCredited", true);
            f.put("transportPath", "PATH_1");          // would otherwise pay
            Decision d = decisions.decide(KEY, f);
            assertEquals("INFORM_ALREADY_PAID", d.action());
            assertFalse(d.movesMoney());
        }

        @Test @DisplayName("rule 2 — THE CAP. Above Rs300 goes to a human, whatever the path")
        void aboveTheCapNeverPays() {
            for (String path : new String[]{"PATH_1", "PATH_2", "PATH_3"}) {
                Map<String, Object> f = facts();
                f.put("transportPath", path);
                f.put("distanceBeyondRadiusKm", 9);
                f.put("computedAmountRupees", Rupees.of(450));
                Decision d = decisions.decide(KEY, f);
                assertEquals("TICKET_EXCEEDS_CAP", d.action(),
                        "the cap did not hold on " + path);
                assertFalse(d.movesMoney(), "money moved above the cap on " + path);
            }
        }

        @Test @DisplayName("the cap row must sit ABOVE the paying rows, or it never fires")
        void theCapOutranksThePayingRows() {
            // This is the row-order finding. The mapping lists the cap last; under FIRST
            // that ordering pays Rs450 and never reaches the cap. If someone 'restores'
            // the mapping's order, this test is what catches it.
            Map<String, Object> f = facts();
            f.put("transportPath", "PATH_3");
            f.put("distanceBeyondRadiusKm", 9);
            f.put("computedAmountRupees", Rupees.of(450));
            assertEquals("TICKET_EXCEEDS_CAP", decisions.decide(KEY, f).action());
        }

        @Test @DisplayName("rule 3 — Path 1 auto-credits")
        void pathOnePays() {
            Map<String, Object> f = facts();
            f.put("transportPath", "PATH_1");
            Decision d = decisions.decide(KEY, f);
            assertEquals("AUTO_CREDIT_TRANSPORT", d.action());
            assertEquals("T2", d.tier());
            assertTrue(d.movesMoney());
        }

        @Test @DisplayName("rule 4 — Path 2, never arrived, denied")
        void pathTwoDidNotTravel() {
            Map<String, Object> f = facts();
            f.put("transportPath", "PATH_2");
            f.put("arrivedAt300metre", false);
            Decision d = decisions.decide(KEY, f);
            assertEquals("DENY_DID_NOT_TRAVEL", d.action());
            assertEquals("T1", d.tier());
        }

        @Test @DisplayName("rule 5 — Path 2, arrived, cancelled NR or by agent, pays")
        void pathTwoNotTheCustomersDoing() {
            for (String status : new String[]{"CANCELLED_NR", "CANCELLED_BY_AGENT"}) {
                Map<String, Object> f = facts();
                f.put("transportPath", "PATH_2");
                f.put("cancellationStatus", status);
                Decision d = decisions.decide(KEY, f);
                assertEquals("AUTO_CREDIT_TRANSPORT", d.action(), "failed on " + status);
                assertTrue(d.movesMoney());
            }
        }

        @Test @DisplayName("rule 6 — Path 2, customer cancelled, cashback already paid, denied")
        void cashbackCompensates() {
            Map<String, Object> f = facts();
            f.put("transportPath", "PATH_2");
            f.put("cancellationStatus", "CANCELLED_CR");
            f.put("lastMinCashbackCredited", true);
            assertEquals("DENY_CASHBACK_COMPENSATES", decisions.decide(KEY, f).action());
        }

        @Test @DisplayName("rule 7 — Path 2, customer cancelled, NO cashback: anomaly, human")
        void missingCashbackIsAnomaly() {
            Map<String, Object> f = facts();
            f.put("transportPath", "PATH_2");
            f.put("cancellationStatus", "CANCELLED_CR");
            f.put("lastMinCashbackCredited", false);
            Decision d = decisions.decide(KEY, f);
            assertEquals("TICKET_CASHBACK_ANOMALY", d.action());
            assertTrue(d.needsTicket());
        }

        @Test @DisplayName("rule 8 — Path 3, inside the hub radius, denied")
        void withinHubRadius() {
            Map<String, Object> f = facts();
            f.put("distanceBeyondRadiusKm", 0);
            assertEquals("DENY_WITHIN_HUB", decisions.decide(KEY, f).action());
        }

        @Test @DisplayName("rule 9 — Path 3, beyond the radius, pays at Rs50/km from the TABLE")
        void beyondHubRadiusPaysPerKm() {
            Map<String, Object> f = facts();
            f.put("distanceBeyondRadiusKm", 4);
            Decision d = decisions.decide(KEY, f);
            assertEquals("AUTO_CREDIT_DISTANCE", d.action());
            assertTrue(d.movesMoney());
            assertEquals(50L, d.numeric("ratePerKmRupees"),
                    "Rs50/km must come from the decision table, not from Java");
        }

        @Test @DisplayName("rule 10 — an unrecognised path reaches a human, not a guess")
        void unknownPathFallsToTheCatchAll() {
            Map<String, Object> f = facts();
            f.put("transportPath", "PATH_SOMETHING_NEW");
            f.put("distanceBeyondRadiusKm", null);
            Decision d = decisions.decide(KEY, f);
            assertEquals("AGENT_UNMATCHED", d.action());
            assertFalse(d.movesMoney());
        }
    }

    // -------------------------------------------------------- RECHARGE, 6 rules

    @Nested
    @DisplayName("RECHARGE_DEBIT_NO_CREDIT")
    class Recharge {

        private static final String KEY = "recharge-debit-no-credit-decision";

        private Map<String, Object> facts(String status, Boolean credited) {
            Map<String, Object> f = new HashMap<>();
            f.put("payuStatus", status);
            f.put("alreadyCredited", credited);
            // Declared by the table but branched on by no rule: what to pay is a fact
            // the gateway reports, not a decision this table makes. Present because
            // strict mode treats an ABSENT variable as an evaluation error — which
            // would take the whole table down, catch-all included.
            f.put("amountRupees", Rupees.of(250));
            return f;
        }

        @Test @DisplayName("rule 1 — NO gateway response at all. 26% of real rows land here.")
        void noGatewayResponseGoesToAHuman() {
            // The concern mapping calls this "pending". The data calls it NULL, on a
            // quarter of every row in the table. PayuStatusNormaliser turns it into
            // NO_RESPONSE so the table never has to test for null.
            Decision d = decisions.decide(KEY, facts("NO_RESPONSE", false));
            assertEquals("TICKET_PAYU_NO_RESPONSE", d.action());
            assertTrue(d.needsTicket());
            assertFalse(d.movesMoney());
        }

        @Test @DisplayName("rule 2 — the gateway has no record of the transaction")
        void notFoundGoesToAHuman() {
            Decision d = decisions.decide(KEY, facts("NOT_FOUND", false));
            assertEquals("TICKET_PAYU_NOT_FOUND", d.action());
            assertFalse(d.movesMoney());
        }

        @Test @DisplayName("A failed recharge is answered by the bot — dissatisfaction is trigger A's job")
        void failedRechargeIsAnsweredNotTicketed() {
            // The row that sat here read `FAILED and not satisfied` and produced a ticket.
            // Deleted: the system already does that through CSAT and trigger A, and the
            // table is evaluated BEFORE satisfaction is ever asked — so the row could only
            // ever have read null. See the comment in the .dmn file.
            Decision d = decisions.decide(KEY, facts("FAILED", false));
            assertEquals("ASK_RECHARGE_AGAIN", d.action());
            assertFalse(d.movesMoney());
            assertFalse(d.needsTicket(),
                    "the bot answers; a person is involved only if the partner says it did not help");
        }

        @Test @DisplayName("rule 4 — ADDED BEYOND THE MAPPING: success but already credited")
        void duplicateCreditIsRefused() {
            Decision d = decisions.decide(KEY, facts("SUCCESS", true));
            assertEquals("INFORM_ALREADY_CREDITED", d.action());
            assertFalse(d.movesMoney(), "a re-raise must never credit twice");
        }

        @Test @DisplayName("rule 5 — success, not credited: the one row that moves money")
        void successCredits() {
            Decision d = decisions.decide(KEY, facts("SUCCESS", false));
            assertEquals("AUTO_CREDIT_WALLET", d.action());
            assertEquals("T2", d.tier());
            assertTrue(d.movesMoney());
        }

        @Test @DisplayName("rule 6 — failed and no complaint: explain the refund window")
        void failedIsExplained() {
            Decision d = decisions.decide(KEY, facts("FAILED", false));
            assertEquals("ASK_RECHARGE_AGAIN", d.action());
            assertEquals("T1", d.tier());
        }

        @Test @DisplayName("rule 7 — a status string nobody has seen before reaches a human")
        void unrecognisedStatusFallsThrough() {
            assertEquals("AGENT_UNMATCHED",
                    decisions.decide(KEY, facts("UNRECOGNISED", false)).action());
        }

        @Test @DisplayName("the raw column values from UAT normalise to what the table expects")
        void realColumnValuesNormaliseCorrectly() {
            // Counted in UAT: success 74,796 | NULL 26,374 | failure 36 | Not Found 11.
            // If this mapping drifts from the column, the money row goes dead silently.
            var n = new in.yesmadam.botin.concern.amount.recharge.PayuStatusNormaliser();
            assertEquals("SUCCESS",     n.normalise("success"));
            assertEquals("FAILED",      n.normalise("failure"));
            assertEquals("NOT_FOUND",   n.normalise("Not Found"));
            assertEquals("NO_RESPONSE", n.normalise(null));
            assertEquals("NO_RESPONSE", n.normalise("   "));
            assertEquals("UNRECOGNISED", n.normalise("something new"));

            // And the normalised value must actually credit, end to end.
            assertTrue(decisions.decide(KEY, facts(n.normalise("success"), false)).movesMoney());
        }
    }

    // ----------------------------------------------------------- FORGET_MPIN, 0

    // THE TABLE IS GONE, AND SO IS THE TEST THAT READ IT (V8).
    //
    // This asserted T0 / SHOW_RESET_DEEPLINK / SELF-SERVE out of forget-mpin-decision. All
    // three values were real, and none of them reached a partner: the concern's process is
    // startEvent -> finaliseStep -> endEvent, with no decide step, so the table was never
    // evaluated in production. The test passed by calling a table nothing else called —
    // which made an inert file look load-bearing and kept it alive.
    //
    // FORGET_MPIN's actual behaviour is asserted where it actually happens:
    //   ForgetMpinT0Test — the process shape contains no ticket, action or escalation step
    //   DemoScenarios "T0" — a real session ends CLOSED_DEFLECTED and creates no ticket

    // ------------------------------------------------------ VIOL_R5_PERIODS, 3

    @Nested
    @DisplayName("VIOL_R5_PERIODS")
    class PeriodLeave {

        private static final String KEY = "viol-r5-periods-decision";

        private Map<String, Object> priorLeaves(Integer n) {
            Map<String, Object> f = new HashMap<>();
            f.put("priorPeriodLeavesThisMonth", n);
            return f;
        }

        @Test @DisplayName("rule 1 — the first this month is removed")
        void firstIsRemoved() {
            Decision d = decisions.decide(KEY, priorLeaves(0));
            assertEquals("REMOVE_VIOLATION", d.action());
            assertEquals("BOT", d.outcomeType());
        }

        @Test @DisplayName("rule 2 — the second is upheld, and UPHOLD is a real outcome")
        void secondIsUpheld() {
            Decision d = decisions.decide(KEY, priorLeaves(1));
            assertEquals("UPHOLD_MONTHLY_LIMIT", d.action());
            assertEquals("UPHOLD", d.outcomeType());
            assertFalse(d.needsTicket(), "upholding is an answer, not an escalation");
        }

        @Test @DisplayName("rule 3 — no counter available at all reaches a human")
        void missingCounterFallsThrough() {
            assertEquals("AGENT_UNMATCHED", decisions.decide(KEY, priorLeaves(null)).action());
        }
    }

    // --------------------------------------------------- VIOL_R9_NO_PRODUCT, 5

    @Nested
    @DisplayName("VIOL_R9_NO_PRODUCT")
    class NoProduct {

        private static final String KEY = "viol-r9-no-product-decision";

        private Map<String, Object> facts(Boolean ordered, Boolean pastTat, Integer priorRemovals) {
            Map<String, Object> f = new HashMap<>();
            f.put("orderPlaced", ordered);
            f.put("pastDeliveryTat", pastTat);
            f.put("priorRemovalsThisCycle", priorRemovals);
            return f;
        }

        @Test @DisplayName("rule 1 — never ordered: upheld with a reminder")
        void neverOrdered() {
            Decision d = decisions.decide(KEY, facts(false, false, 0));
            assertEquals("UPHOLD_REMIND_ORDER", d.action());
            assertEquals("UPHOLD", d.outcomeType());
        }

        @Test @DisplayName("rule 2 — ordered but logistics missed the TAT: our failure, human fixes it")
        void logisticsFailure() {
            Decision d = decisions.decide(KEY, facts(true, true, 0));
            assertEquals("TICKET_LOGISTICS_FAILURE", d.action());
            assertTrue(d.needsTicket());
        }

        @Test @DisplayName("rule 3 — ordered, within TAT, allowance available: removed")
        void allowanceAvailable() {
            Decision d = decisions.decide(KEY, facts(true, false, 0));
            assertEquals("REMOVE_VIOLATION", d.action());
            assertEquals("T2", d.tier());
        }

        @Test @DisplayName("rule 4 — the cycle allowance is spent: upheld")
        void allowanceSpent() {
            Decision d = decisions.decide(KEY, facts(true, false, 1));
            assertEquals("UPHOLD_ALLOWANCE_USED", d.action());
            assertEquals("UPHOLD", d.outcomeType());
        }

        @Test @DisplayName("rule 5 — nothing known: human")
        void nothingKnown() {
            assertEquals("AGENT_UNMATCHED", decisions.decide(KEY, facts(null, null, null)).action());
        }
    }

    // ------------------------------------------------- PROD_DELIVERY_DELAY, 3

    @Nested
    @DisplayName("PROD_DELIVERY_DELAY")
    class DeliveryDelay {

        private static final String KEY = "prod-delivery-delay-decision";

        private Map<String, Object> pastDeadline(Boolean v) {
            Map<String, Object> f = new HashMap<>();
            f.put("pastElevenPmDeadline", v);
            return f;
        }

        @Test @DisplayName("rule 1 — past the 11 PM deadline: logistics escalation")
        void lateIsEscalated() {
            Decision d = decisions.decide(KEY, pastDeadline(true));
            assertEquals("TICKET_LOGISTICS_ESCALATION", d.action());
            assertTrue(d.needsTicket());
        }

        @Test @DisplayName("rule 2 — inside TAT: show the date and ask them to wait")
        void onTimeIsExplained() {
            Decision d = decisions.decide(KEY, pastDeadline(false));
            assertEquals("SHOW_EXPECTED_DELIVERY", d.action());
            assertEquals("BOT", d.outcomeType());
        }

        @Test @DisplayName("rule 3 — TAT unknown: human")
        void unknownTat() {
            assertEquals("AGENT_UNMATCHED", decisions.decide(KEY, pastDeadline(null)).action());
        }
    }

    // ------------------------------------------------- the two REROUTE tables

    @Nested
    @DisplayName("REROUTE tables — VIOL_R4_OTHERS and OTHER_FREETEXT_TRIAGE")
    class Reroute {

        private Map<String, Object> facts(Boolean matched, Double confidence) {
            return facts(matched, confidence, false);
        }

        private Map<String, Object> facts(Boolean matched, Double confidence, Boolean risk) {
            Map<String, Object> f = new HashMap<>();
            f.put("classificationMatched", matched);
            f.put("classifierConfidence", confidence);
            f.put("rerouteTarget", matched == Boolean.TRUE ? "FORGET_MPIN" : null);
            f.put("riskFlagged", risk);
            return f;
        }

        @Test @DisplayName("a confident match reroutes, and REROUTE carries no tier")
        void confidentMatchReroutes() {
            for (String key : new String[]{"viol-r4-others-decision", "other-freetext-triage-decision"}) {
                Decision d = decisions.decide(key, facts(true, 0.9));
                assertEquals("REROUTE_TO_CONCERN", d.action(), "failed on " + key);
                assertTrue(d.isReroute());
                assertEquals("-", d.tier(), "REROUTE has no tier — the mapping writes it as '-'");
            }
        }

        @Test @DisplayName("below the confidence floor it goes to a human, in BOTH tables")
        void lowConfidenceNeverReroutes() {
            // The floor is the same number in both tables on purpose. A wrong reroute
            // costs the partner an entire second journey.
            for (String key : new String[]{"viol-r4-others-decision", "other-freetext-triage-decision"}) {
                Decision d = decisions.decide(key, facts(true, 0.69));
                assertFalse(d.isReroute(), "rerouted below the confidence floor in " + key);
                assertEquals("T3", d.tier());
            }
        }

        @Test @DisplayName("no match at all goes to a human with the transcript")
        void noMatchGoesToAHuman() {
            assertEquals("CREATE_TICKET_OTHER",
                    decisions.decide("viol-r4-others-decision", facts(false, 0.95)).action());
            assertEquals("TICKET_WITH_TRANSCRIPT",
                    decisions.decide("other-freetext-triage-decision", facts(false, 0.95)).action());
        }

        @Test @DisplayName("TRIGGER E — a risk flag outranks even a confident match")
        void riskFlaggedAlwaysReachesAPerson() {
            // The row order is the entire rule here. Under FIRST, a confident match sits
            // below this one, so without it someone typing about an accident would be
            // routed into a self-serve flow by a model that was sure where it belonged.
            for (String key : new String[]{"viol-r4-others-decision", "other-freetext-triage-decision"}) {
                Decision d = decisions.decide(key, facts(true, 0.99, true));
                assertFalse(d.isReroute(), "rerouted a risk-flagged message in " + key);
                assertEquals("T3", d.tier(), "failed on " + key);
                assertEquals("AGENT_RISK_FLAGGED", d.action(), "failed on " + key);
            }
        }
    }
}
