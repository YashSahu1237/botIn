package in.yesmadam.botin.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.payu.MockPayUGateway;
import in.yesmadam.botin.safety.BotinFeature;
import in.yesmadam.botin.safety.TicketAction;
import in.yesmadam.botin.safety.TicketActionRecorder;
import in.yesmadam.botin.safety.TicketActionRepository;
import in.yesmadam.botin.session.SessionOpener;
import in.yesmadam.botin.session.Ticket;
import in.yesmadam.botin.session.TicketRepository;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PLAN STEP 61 — the last safety check, and the only one that looks BACKWARDS.
 *
 * Everything else asks whether a payment was decided correctly at the moment of paying.
 * This asks whether our record of what we paid still matches what actually moved. Two
 * systems agreeing during a transaction is not the same as two systems agreeing afterwards,
 * and the gap between them is where money quietly disappears.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconciliationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ReconciliationService reconciliation;
    @Autowired MockPayUGateway gateway;
    @Autowired TicketActionRecorder recorder;
    @Autowired TicketActionRepository actions;
    @Autowired TicketRepository tickets;
    @Autowired FeatureManager features;
    @Autowired SessionOpener sessions;

    @BeforeEach
    void freshWorld() {
        clear();
        features.setFeatureState(new FeatureState(BotinFeature.RECHARGE_AUTO_CREDIT, true));
    }

    /**
     * CLEAN UP AFTERWARDS TOO, not only before.
     *
     * Reconciliation reads the WHOLE action table by design — that is what reconciling is —
     * so this class both needs an empty world and leaves a dirty one. Cleaning only on the
     * way in fixes this class's tests and hands the mess to whoever runs next.
     */
    @org.junit.jupiter.api.AfterEach
    void leaveItAsWeFoundIt() {
        clear();
    }

    private void clear() {
        actions.deleteAllInBatch();
        gateway.reset();
    }

    @Test
    @DisplayName("A real credit reconciles — and the ROW KNOWS HOW MUCH, which it did not before")
    void aRealPaymentAgreesWithTheGateway() throws Exception {
        String order = "ORD-RECON-1";
        gateway.setRecharge(order, MockPayUGateway.SUCCESS, 25_000L);

        raiseRecharge("SP-RECON-1", order);

        // THE BUG THIS STEP FOUND. `amount_paise` existed on this row from the first
        // migration and nothing ever wrote it — every ledger row said a credit succeeded and
        // none said how much. Invisible until something tried to add them up, and then total:
        // you cannot sum a column of nulls.
        TicketAction credit = actions.findAll().stream()
                .filter(a -> "AUTO_CREDIT_WALLET".equals(a.getActionType()))
                .filter(a -> TicketAction.SUCCEEDED.equals(a.getStatus()))
                .findFirst().orElseThrow(() -> new AssertionError("no successful credit was recorded"));

        assertEquals(25_000L, credit.getAmountPaise(),
                "a payment log that records the fact of a payment but not its size is a "
              + "receipt with the number torn off");

        ReconciliationService.Report report = reconciliation.reconcile();
        assertTrue(report.clean(), "a straightforward credit must reconcile: " + report);
        assertEquals(1, report.creditsChecked());
        assertEquals(25_000L, report.believedPaise());
    }

    @Test
    @DisplayName("THE SILENT DIRECTION — the gateway moved money we have no row for")
    void moneyThatMovedWithNoRecordIsFound() {
        // Nobody complains about money they received. There is no partner to raise it, no
        // ticket to look at, and no amount of reading our own tables would ever show it —
        // the evidence is entirely on the other side. This is the case the job exists for.
        gateway.creditWallet("SP-GHOST", "ORD-GHOST", 40_000L);

        ReconciliationService.Report report = reconciliation.reconcile();

        assertFalse(report.clean());
        assertEquals(1, report.unrecorded().size());
        assertEquals("ORD-GHOST", report.unrecorded().get(0).externalReference());
        assertEquals(40_000L, report.unrecorded().get(0).gatewaySaysPaise());
        assertEquals(0L, report.unrecorded().get(0).weBelievePaise(), "we believe nothing — that is the point");
    }

    @Test
    @DisplayName("We say we paid, the gateway disagrees on the amount")
    void amountMismatchIsFound() {
        UUID ticketId = aTicketFor("SP-RECON-2");

        TicketAction attempt = recorder.recordAttempt(
                ticketId, "AUTO_CREDIT_WALLET", "ORD-SHORT", null, "{}").orElseThrow();
        recorder.recordOutcome(attempt.getId(), true, "{}", 25_000L);

        gateway.creditWallet("SP-RECON-2", "ORD-SHORT", 10_000L);   // short-paid

        ReconciliationService.Report report = reconciliation.reconcile();

        assertFalse(report.clean());
        assertEquals(1, report.mismatched().size());
        assertEquals(25_000L, report.mismatched().get(0).weBelievePaise());
        assertEquals(10_000L, report.mismatched().get(0).gatewaySaysPaise());
    }

    @Test
    @DisplayName("A succeeded credit with no amount is itself a finding, not a clean row")
    void aCreditWithNoAmountIsNotSilentlySkipped() {
        UUID ticketId = aTicketFor("SP-RECON-3");

        TicketAction attempt = recorder.recordAttempt(
                ticketId, "AUTO_CREDIT_WALLET", "ORD-NOAMOUNT", null, "{}").orElseThrow();
        recorder.recordOutcome(attempt.getId(), true, "{}");     // the old 3-arg call: no amount

        ReconciliationService.Report report = reconciliation.reconcile();

        assertFalse(report.clean(),
                "skipping it would make the report look clean precisely because the row is "
              + "unreadable — the worst possible way to pass");
        assertEquals(1, report.creditsWithNoAmount());
    }

    @Test
    @DisplayName("It REPORTS and corrects nothing")
    void itNeverFixesWhatItFinds() {
        gateway.creditWallet("SP-GHOST", "ORD-GHOST-2", 40_000L);

        reconciliation.reconcile();
        reconciliation.reconcile();

        // A reconciliation job that corrects what it finds is a second, unsupervised payment
        // path — running without anybody watching, on exactly the cases nobody understood the
        // first time. Twice through must change nothing.
        assertEquals(40_000L, gateway.creditedFor("ORD-GHOST-2"));
        assertEquals(0, actions.count(), "it must not write rows to make its own output tidy");
    }

    /**
     * A ticket on a REAL session, because the schema insists — and it is right to.
     *
     * The first version of these two tests invented a random UUID for `help_session_id`, and
     * the foreign key refused it. That is the same constraint, doing the same job, as in
     * Phase 12a: an audit row pointing at something that does not exist is a dangling record,
     * and here it would have been a reconciliation test asserting things about a payment that
     * could never have happened.
     *
     * Ironic, for a test about whether our records match reality.
     */
    private UUID aTicketFor(String spId) {
        UUID sessionId = sessions.openCommitted(spId, null);
        return tickets.saveAndFlush(
                Ticket.openAtGate1(sessionId, spId, "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT")).getId();
    }

    private void raiseRecharge(String spId, String orderId) throws Exception {
        String sessionId = json.readTree(mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spId\":\"" + spId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("sessionId").asText();

        send(sessionId, "{\"selection\":\"AMOUNT_RELATED\"}");
        send(sessionId, "{\"selection\":\"RECHARGE_DEBIT_NO_CREDIT\",\"reference\":\"" + orderId + "\"}");
    }

    private JsonNode send(String sessionId, String body) throws Exception {
        return json.readTree(mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
