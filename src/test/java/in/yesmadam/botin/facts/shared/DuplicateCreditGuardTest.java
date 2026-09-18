package in.yesmadam.botin.facts.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PLAN STEP 44. Pure arithmetic over ledger rows. No Spring, no database. */
class DuplicateCreditGuardTest {

    private final DuplicateCreditGuard guard = new DuplicateCreditGuard();

    private static LedgerEntry credit(long paise) { return new LedgerEntry("CREDIT", "TRANSPORT", paise); }
    private static LedgerEntry debit(long paise)  { return new LedgerEntry("DEBIT",  "TRANSPORT", paise); }

    @Test
    @DisplayName("a plain credit counts as paid")
    void creditIsPaid() {
        assertTrue(guard.alreadyCredited(List.of(credit(20000)), "TRANSPORT"));
    }

    @Test
    @DisplayName("THE REASON THIS EXISTS — a credit that was fully clawed back is NOT paid")
    void fullyReversedIsNotPaid() {
        // UAT holds both CREDIT/TRANSPORT and DEBIT/TRANSPORT, and the live source
        // carries the string "Unused transport for ". An EXISTS(CREDIT) check would
        // tell this partner "already paid" for money that is no longer theirs.
        List<LedgerEntry> reversed = List.of(credit(20000), debit(20000));
        assertEquals(0L, guard.netPaise(reversed, "TRANSPORT"));
        assertFalse(guard.alreadyCredited(reversed, "TRANSPORT"));
    }

    @Test
    @DisplayName("a partial claw-back leaves them still paid")
    void partialReversalIsStillPaid() {
        List<LedgerEntry> partial = List.of(credit(20000), debit(5000));
        assertEquals(15000L, guard.netPaise(partial, "TRANSPORT"));
        assertTrue(guard.alreadyCredited(partial, "TRANSPORT"));
    }

    @Test
    @DisplayName("other sub-actions are ignored entirely")
    void otherSubactionsDoNotCount() {
        List<LedgerEntry> mixed = List.of(
                new LedgerEntry("CREDIT", "INCENTIVE", 50000),
                new LedgerEntry("CREDIT", "TRANSPORT", 10000));
        assertEquals(10000L, guard.netPaise(mixed, "TRANSPORT"));
        assertFalse(guard.alreadyCredited(mixed, "SOMETHING_ELSE"));
    }

    @Test
    @DisplayName("an empty or null ledger is not paid, and does not throw")
    void emptyLedgerIsSafe() {
        assertFalse(guard.alreadyCredited(List.of(), "TRANSPORT"));
        assertFalse(guard.alreadyCredited(null, "TRANSPORT"));
        assertEquals(0L, guard.netPaise(null, null));
    }

    @Test
    @DisplayName("an action value we do not recognise contributes nothing either way")
    void unknownActionIsIgnored() {
        // Better to under-count and re-examine than to guess a direction and pay twice.
        List<LedgerEntry> odd = List.of(new LedgerEntry("ADJUSTMENT", "TRANSPORT", 9999));
        assertEquals(0L, guard.netPaise(odd, "TRANSPORT"));
    }
}
