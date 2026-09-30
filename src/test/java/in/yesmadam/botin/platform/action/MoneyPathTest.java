package in.yesmadam.botin.platform.action;

import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
import in.yesmadam.botin.integration.payu.MockPayUGateway;
import in.yesmadam.botin.platform.safety.BotinFeature;
import in.yesmadam.botin.platform.safety.TicketAction;
import in.yesmadam.botin.platform.safety.TicketActionRepository;
import in.yesmadam.botin.platform.session.Ticket;
import in.yesmadam.botin.platform.session.TicketRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.repository.FeatureState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PHASE 12 — plan steps 89, 90 and the step 92 checkpoint.
 *
 * THE FIRST TESTS IN THIS PROJECT WHERE SOMETHING ACTUALLY MOVES. Everything before this
 * could be wrong and cost a partner time; from here it can be wrong and cost them money,
 * or pay them twice. So these assert the ledger, not just the reply.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MoneyPathTest {

    /** Rs250. Was 25_000 paise until money moved to rupees — the same amount. */
    private static final BigDecimal AMOUNT = Rupees.of(250);

    /**
     * A FRESH ORDER ID PER TEST, and the reason is the thing under test.
     *
     * The duplicate guard is global and DURABLE — the attempt row commits in its own
     * transaction and outlives everything, which is exactly what makes a retry safe.
     * Sharing one order id across tests therefore does not share a fixture, it shares a
     * paid recharge: the first test to credit it takes the key, and every later test
     * correctly refuses to pay again and fails for the right reason.
     *
     * The re-raise test deliberately reuses ITS id twice. That is the only place a
     * repeat should happen, and it is the assertion.
     */
    private String ORDER;

    @BeforeEach
    void freshOrderId(org.junit.jupiter.api.TestInfo info) {
        ORDER = "ORD-" + info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("X");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MockPayUGateway gateway;
    @Autowired TicketRepository tickets;
    @Autowired TicketActionRepository actions;
    @Autowired FeatureManager features;

    @BeforeEach
    void resetGatewayAndFlag() {
        gateway.reset();
        setFlag(BotinFeature.RECHARGE_AUTO_CREDIT, true);
    }

    // ------------------------------------------------- 89: the gateway states

    @Test
    @DisplayName("A successful recharge that never reached the wallet is credited")
    void successfulRechargeIsCredited() throws Exception {
        gateway.setRecharge(ORDER, MockPayUGateway.SUCCESS, AMOUNT);

        JsonNode view = raise("SP-MP-01", ORDER);

        assertEquals("CLOSED_RESOLVED", view.get("status").asText());
        assertEquals(0, AMOUNT.compareTo(gateway.creditedFor(ORDER)), "the money moved, once");

        TicketAction action = onlyActionFor("SP-MP-01");
        assertEquals("AUTO_CREDIT_WALLET", action.getActionType());
        assertEquals(TicketAction.SUCCEEDED, action.getStatus());
        assertEquals(ORDER, action.getExternalReference(),
                "the gateway's own id is on the row — without it a dispute has nowhere to go");
    }

    @Test
    @DisplayName("The state that is 26% of real rows — no gateway response — never pays")
    void noGatewayResponseNeverPays() throws Exception {
        gateway.setRecharge(ORDER, null, AMOUNT);   // begun, never confirmed

        JsonNode view = raise("SP-MP-02", ORDER);

        assertEquals("AGENT_CONNECTING", view.at("/nextStep/code").asText());
        assertEquals(0, Rupees.ZERO.compareTo(gateway.creditedFor(ORDER)),
                "nothing may move on a state we cannot read");
        assertEquals(0, actions.count() - actionsBefore, "and nothing should even be attempted");
    }

    @Test
    @DisplayName("A failed recharge is not a credit — the gateway refunds on its own timeline")
    void failedRechargeIsNotCredited() throws Exception {
        gateway.setRecharge(ORDER, MockPayUGateway.FAILURE, AMOUNT);

        JsonNode view = raise("SP-MP-03", ORDER);

        assertEquals("ASK_RECHARGE_AGAIN", view.at("/nextStep/code").asText());
        assertEquals(0, Rupees.ZERO.compareTo(gateway.creditedFor(ORDER)));
    }

    // ------------------------------------------- 90: idempotency on the third-party id

    @Test
    @DisplayName("THE STEP 90 CASE — the same order raised in a NEW session pays once")
    void reRaisingTheSameOrderDoesNotPayTwice() throws Exception {
        gateway.setRecharge(ORDER, MockPayUGateway.SUCCESS, AMOUNT);

        raise("SP-MP-04", ORDER);
        assertEquals(0, AMOUNT.compareTo(gateway.creditedFor(ORDER)));

        // A week later, the same partner raises the same recharge again — because the
        // balance confused them, or somebody told them to. NEW session, NEW ticket.
        // Keyed on our ticket id this would be a fresh key and a second payment.
        JsonNode second = raise("SP-MP-04", ORDER);

        assertEquals(0, AMOUNT.compareTo(gateway.creditedFor(ORDER)),
                "one payment for one payment — this is the whole point of step 90");
        assertEquals("INFORM_ALREADY_CREDITED", second.at("/nextStep/code").asText(),
                "and the partner is told plainly, not silently ignored");

        assertEquals(2, tickets.countBySpId("SP-MP-04"), "two contacts, two tickets — correctly");
        assertEquals(1, actions.findAll().stream()
                        .filter(a -> ORDER.equals(a.getExternalReference())).count(),
                "but ONE attempt row, because the key is the gateway's id, not ours");
    }

    // ----------------------------------------- the failure path: record, then escalate

    @Test
    @DisplayName("A gateway failure escalates and is NEVER retried automatically")
    void aFailedCreditGoesToAPersonNotToARetry() throws Exception {
        gateway.setRecharge(ORDER, MockPayUGateway.SUCCESS, AMOUNT);
        gateway.failNextCalls(1);

        JsonNode view = raise("SP-MP-05", ORDER);

        assertEquals("AGENT_CONNECTING", view.at("/nextStep/code").asText());
        assertEquals(0, Rupees.ZERO.compareTo(gateway.creditedFor(ORDER)));

        // THE ATTEMPT SURVIVED THE FAILURE. That is ADR-005 and REQUIRES_NEW: the row was
        // committed before the call, so it is still here even though the call threw —
        // and it is what stops a retry from paying.
        TicketAction action = onlyActionFor("SP-MP-05");
        assertEquals(TicketAction.FAILED, action.getStatus());
        assertEquals(ORDER, action.getExternalReference());

        Ticket ticket = tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-MP-05").get(0);
        assertEquals("T3", ticket.getTier());
        assertEquals("AGENT_ACTION_FAILED", ticket.getDmnAction());

        // The gateway may have done the thing and failed to say so. A retry on that is a
        // second payment, so a person looks.
        gateway.failNextCalls(0);
        JsonNode retry = raise("SP-MP-05", ORDER);
        assertEquals(0, Rupees.ZERO.compareTo(gateway.creditedFor(ORDER)),
                "a re-raise after a FAILED attempt must not quietly pay — the key is taken");
        assertEquals("INFORM_ALREADY_CREDITED", retry.at("/nextStep/code").asText());
    }

    // --------------------------------------- 92: flip the switch with nothing restarted

    @Test
    @DisplayName("THE CHECKPOINT — flipping the kill switch reroutes the NEXT request to T3")
    void theKillSwitchTakesEffectWithNoRestart() throws Exception {
        gateway.setRecharge("ORD-ON", MockPayUGateway.SUCCESS, AMOUNT);
        gateway.setRecharge("ORD-OFF", MockPayUGateway.SUCCESS, AMOUNT);

        // ON: the bot pays.
        raise("SP-MP-06", "ORD-ON");
        assertEquals(0, AMOUNT.compareTo(gateway.creditedFor("ORD-ON")));

        // Flip it. No restart, no redeploy, nothing in flight disturbed.
        setFlag(BotinFeature.RECHARGE_AUTO_CREDIT, false);

        JsonNode afterFlip = raise("SP-MP-07", "ORD-OFF");

        // OFF DOES NOT MEAN FAIL. The partner is still served — by a person.
        assertEquals("AGENT_CONNECTING", afterFlip.at("/nextStep/code").asText());
        assertEquals(0, Rupees.ZERO.compareTo(gateway.creditedFor("ORD-OFF")), "automation stopped, money stopped");

        Ticket ticket = tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-MP-07").get(0);
        assertEquals("T3", ticket.getTier());
        assertEquals("AGENT_AUTOMATION_DISABLED", ticket.getDmnAction(),
                "the reason is on the ticket — 'why did this go to an agent' must be answerable");
    }

    // ------------------------------------------------------------------ helpers

    private long actionsBefore = 0;

    /** Menu, menu with the order id, and out the other side. */
    private JsonNode raise(String spId, String orderId) throws Exception {
        actionsBefore = actions.count();

        String sessionId = json.readTree(mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spId\":\"" + spId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("sessionId").asText();

        send(sessionId, "{\"selection\":\"AMOUNT_RELATED\"}");
        return send(sessionId, "{\"selection\":\"RECHARGE_DEBIT_NO_CREDIT\",\"reference\":\""
                + orderId + "\"}");
    }

    private JsonNode send(String sessionId, String body) throws Exception {
        return json.readTree(mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private TicketAction onlyActionFor(String spId) {
        List<Ticket> spTickets = tickets.findTop10BySpIdOrderByCreatedAtDesc(spId);
        List<TicketAction> found = actions.findByTicketIdIn(
                spTickets.stream().map(Ticket::getId).toList());
        assertEquals(1, found.size(), "expected exactly one action row for " + spId);
        return found.get(0);
    }

    /**
     * THE INJECTED MANAGER, not FeatureContext.getFeatureManager().
     *
     * The static lookup resolves through the servlet context that TogglzFilter sets up,
     * and under MockMvc there is no filter chain — so it throws, and the message sends
     * you looking at Togglz configuration rather than at the call. The application code
     * never had this problem: KillSwitch takes FeatureManager as a dependency, which is
     * the same reason it is testable.
     */
    private void setFlag(BotinFeature feature, boolean enabled) {
        features.setFeatureState(new FeatureState(feature, enabled));
    }
}
