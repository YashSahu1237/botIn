package in.yesmadam.botin.surface.demo;

import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
import in.yesmadam.botin.concern.amount.transport.TransportFactProvider;
import in.yesmadam.botin.concern.product.deliverydelay.ProductDeliveryFactProvider;
import in.yesmadam.botin.integration.payu.MockPayUGateway;
import in.yesmadam.botin.platform.decision.Decision;
import in.yesmadam.botin.platform.decision.DecisionService;
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
 * PLAN STEP 96 — the fixture set, and the claim that makes it worth having.
 *
 * THE CLAIM: the demo differs from a real run in where a row came from, and in nothing else.
 * If the fixtures fed conclusions straight into the decision tables, the demo would prove that
 * the fixture file is internally consistent — an expensive way to learn nothing. So the tests
 * below drive the REAL derivations: the duplicate-credit guard, the path selector and the TAT
 * service each run against fixture rows exactly as they run against UAT rows.
 *
 * The cases are chosen to be the ones where a plausible-looking shortcut gives the wrong
 * answer, because those are the ones a demo has to be able to survive being asked about.
 */
@SpringBootTest
@ActiveProfiles({"demo", "test"})
class DemoFixturesTest {

    @Autowired DemoFixtures fixtures;
    @Autowired TransportFactProvider transport;
    @Autowired ProductDeliveryFactProvider delivery;
    @Autowired DecisionService decisions;
    @Autowired MockPayUGateway gateway;

    @Test
    @DisplayName("The synthetic world loads, and every fixture says what it is for")
    void everyFixtureExplainsItself() {
        assertFalse(fixtures.transportOrders().isEmpty());
        assertFalse(fixtures.productOrders().isEmpty());
        assertFalse(fixtures.recharges().isEmpty());

        // A FIXTURE NOBODY CAN EXPLAIN IS A FIXTURE NOBODY SHOULD TRUST. Six months from now
        // the question about any of these rows will be "why is this one here, and can I
        // change it" — and an unanswerable version of that question is how a fixture set
        // rots into a set of magic numbers that everybody is afraid to touch.
        fixtures.transportOrders().forEach(o -> assertNotNull(blank(o.demonstrates()),
                "transport order " + o.orderId() + " does not say what it demonstrates"));
        fixtures.productOrders().forEach(o -> assertNotNull(blank(o.demonstrates()),
                "product order for " + o.spId() + " does not say what it demonstrates"));
        fixtures.recharges().forEach(r -> assertNotNull(blank(r.demonstrates()),
                "recharge " + r.orderId() + " does not say what it demonstrates"));
    }

    @Test
    @DisplayName("The gateway is seeded from the SAME file — one description of the world")
    void theGatewayAgreesWithTheFixtureFile() {
        assertEquals(MockPayUGateway.SUCCESS, gateway.statusOf("DEMO-RCH-PAID"));
        assertEquals(0, Rupees.of(250).compareTo(gateway.amountFor("DEMO-RCH-PAID")));
        assertEquals(MockPayUGateway.FAILURE, gateway.statusOf("DEMO-RCH-FAILED"));

        // Three fixture sources describing the same synthetic world would drift, and the
        // drift would show up as a demo behaving differently from its own script.
        assertNull(gateway.statusOf("DEMO-RCH-UNKNOWN"),
                "the 26% state — begun and never confirmed — must survive seeding as null");
    }

    @Test
    @DisplayName("THE REVERSAL CASE — a credit that was clawed back is NOT 'already paid'")
    void theDuplicateGuardRunsForRealAgainstFixtureRows() {
        Map<String, Object> facts = transportFactsFor("7008");

        // The fixture has CREDIT 150 and DEBIT 150 against the same order. EXISTS(CREDIT) —
        // the obvious implementation — reads that as paid and refuses this partner money they
        // never kept. Net position reads it correctly. This is the guard running against a
        // fixture row exactly as it would against a UAT row.
        assertEquals(false, facts.get("alreadyCredited"),
                "a reversed credit must not read as payment, or this partner is refused twice");
    }

    @Test
    @DisplayName("THE PATH SELECTOR runs for real — the fixture supplies rows, not conclusions")
    void thePathIsDerivedAndNotDeclared() {
        // Customer charged, nothing credited -> PATH_1. Nothing in the fixture says "PATH_1".
        assertEquals("PATH_1", transportFactsFor("7003").get("transportPath"));

        // Not charged, unassigned by the business -> PATH_2.
        assertEquals("PATH_2", transportFactsFor("7005").get("transportPath"));

        // Not charged, no cancellation -> PATH_3, decided on distance.
        assertEquals("PATH_3", transportFactsFor("7006").get("transportPath"));
    }

    @Test
    @DisplayName("CAP ESCALATION, end to end — Rs450 becomes a ticket, not a Rs300 payment")
    void theCapEscalatesRatherThanClamping() {
        Decision decision = decisions.decide("transport-not-received-decision", transportFactsFor("7002"));

        assertEquals("T3", decision.tier());
        assertEquals("TICKET_EXCEEDS_CAP", decision.action());
        assertEquals("TICKET", decision.outcomeType(),
                "a cap is a handover to a person. Clamping to Rs300 would be a silent "
              + "underpayment that nobody ever sees");
    }

    @Test
    @DisplayName("Already paid beats everything, including the cap above it")
    void ruleOneOutranksTheCap() {
        assertEquals("INFORM_ALREADY_PAID",
                decisions.decide("transport-not-received-decision", transportFactsFor("7001")).action());
    }

    @Test
    @DisplayName("THE TAT SERVICE runs for real, and fixture times are RELATIVE")
    void latenessIsComputedAndNotDeclared() {
        // Placed one day ago: inside the standard TAT, so the bot answers.
        assertEquals(false, deliveryFactsFor("SP-DEMO-20").get("pastElevenPmDeadline"));

        // Placed six days ago: genuinely late, so a person looks.
        assertEquals(true, deliveryFactsFor("SP-DEMO-21").get("pastElevenPmDeadline"));

        // These two assertions are the reason the fixture says "placedDaysAgo" and not a
        // date. With a fixed timestamp this test passes today and starts failing — or worse,
        // passes for the wrong reason — as the calendar moves under it.
    }

    @Test
    @DisplayName("An order the fixtures do not know about reaches a human, correctly")
    void anUnknownReferenceIsNotAnError() {
        Map<String, Object> facts = transportFactsFor("does-not-exist");

        assertNull(facts.get("transportPath"), "nothing may be inferred about an unknown order");
        assertEquals("T3", decisions.decide("transport-not-received-decision", facts).tier(),
                "knowing nothing must reach a person — never a default, and never an error");
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> transportFactsFor(String orderId) {
        return transport.fetchFacts(new FactRequest(
                UUID.randomUUID(), "SP-DEMO", "TRANSPORT_NOT_RECEIVED", orderId, null));
    }

    private Map<String, Object> deliveryFactsFor(String spId) {
        return delivery.fetchFacts(new FactRequest(
                UUID.randomUUID(), spId, "PROD_DELIVERY_DELAY", null, null));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
