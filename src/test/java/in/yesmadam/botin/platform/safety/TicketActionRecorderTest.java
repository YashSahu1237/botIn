package in.yesmadam.botin.platform.safety;

import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
import in.yesmadam.botin.platform.session.HelpSession;
import in.yesmadam.botin.platform.session.HelpSessionRepository;
import in.yesmadam.botin.platform.session.Ticket;
import in.yesmadam.botin.platform.session.TicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Promoted from the R&D spike into the real suite. This is the test that stops
 * ADR-005 quietly regressing.
 *
 * The rollback case matters more than the happy one: a test that only proves the
 * good path cannot tell you whether it would catch the annotation being deleted.
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketActionRecorderTest {

    @Autowired TicketActionRecorder recorder;
    @Autowired TicketActionRepository actions;
    @Autowired TicketRepository tickets;
    @Autowired HelpSessionRepository sessions;
    @Autowired TransactionTemplate txTemplate;

    private UUID aTicket() {
        HelpSession s = HelpSession.start("SP-TEST", null);
        s.selectConcern("AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED");
        sessions.saveAndFlush(s);
        return tickets.saveAndFlush(Ticket.openAtGate1(s)).getId();
    }

    @Test
    @DisplayName("the ATTEMPTED row survives a rollback of the calling transaction")
    void recordSurvivesCallerRollback() {
        UUID ticketId = aTicket();

        assertThrows(RuntimeException.class, () -> txTemplate.execute(status -> {
            recorder.recordAttempt(ticketId, "TRANSPORT_CREDIT", Rupees.of(250), "{}");
            throw new IllegalStateException("simulated failure AFTER the external call");
        }));

        // Asked through the contract rather than a hard-coded key shape. What this test
        // is about is that the row OUTLIVES THE ROLLBACK; the shape of the key is a
        // different property with its own test (MoneyPathTest, the re-raise case), and
        // spelling it literally here meant step 90's change to that shape broke a test
        // that has nothing to do with it.
        assertTrue(actions.existsByIdempotencyKey(
                TicketAction.idempotencyKey(ticketId, "TRANSPORT_CREDIT")),
            "THE GATE: the row must outlive the rollback. It is the only evidence "
          + "that an external system was called.");
    }

    @Test
    @DisplayName("a repeat attempt short-circuits instead of acting twice")
    void secondAttemptShortCircuits() {
        UUID ticketId = aTicket();

        Optional<TicketAction> first  = recorder.recordAttempt(ticketId, "TRANSPORT_CREDIT", Rupees.of(250), "{}");
        Optional<TicketAction> second = recorder.recordAttempt(ticketId, "TRANSPORT_CREDIT", Rupees.of(250), "{}");

        assertTrue(first.isPresent(), "first attempt proceeds");
        assertTrue(second.isEmpty(),
            "second returns empty so the caller must NOT act again — one credit, not two");

        // THIS TICKET'S ROWS, NOT THE WHOLE TABLE. It used to count every row in the
        // database, which was only ever correct because this was the only class writing
        // any. The moment a second one existed — ReconciliationTest — the count became
        // "how many payments has the entire suite made", and this assertion started
        // reporting the suite's history rather than this test's behaviour.
        //
        // A global count in a test is a shared fixture wearing a different hat.
        assertEquals(1, actions.findByTicketIdIn(java.util.List.of(ticketId)).size(),
            "exactly one attempt row for this ticket");
    }

    @Test
    @DisplayName("the outcome write lands on the same row")
    void outcomeUpdatesTheAttempt() {
        UUID ticketId = aTicket();
        TicketAction a = recorder.recordAttempt(ticketId, "TRANSPORT_CREDIT", Rupees.of(250), "{}").orElseThrow();

        recorder.recordOutcome(a.getId(), true, "{\"ref\":\"TXN-1\"}");

        assertEquals(TicketAction.SUCCEEDED,
            actions.findById(a.getId()).orElseThrow().getStatus());
    }
}
