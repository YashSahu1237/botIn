package in.yesmadam.botin.shared.ledger;
import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PLAN STEP 44. Pure arithmetic over ledger rows. No Spring, no database. */
class DuplicateCreditGuardTest {

    private final DuplicateCreditGuard guard = new DuplicateCreditGuard();

    private static LedgerEntry credit(long rupees) { return new LedgerEntry("CREDIT", "TRANSPORT", Rupees.of(rupees)); }
    private static LedgerEntry debit(long rupees)  { return new LedgerEntry("DEBIT",  "TRANSPORT", Rupees.of(rupees)); }

    @Test
    @DisplayName("a plain credit counts as paid")
    void creditIsPaid() {
        assertTrue(guard.alreadyCredited(List.of(credit(200)), "TRANSPORT"));
    }

    @Test
    @DisplayName("THE REASON THIS EXISTS — a credit that was fully clawed back is NOT paid")
    void fullyReversedIsNotPaid() {
        // UAT holds both CREDIT/TRANSPORT and DEBIT/TRANSPORT, and the live source
        // carries the string "Unused transport for ". An EXISTS(CREDIT) check would
        // tell this partner "already paid" for money that is no longer theirs.
        List<LedgerEntry> reversed = List.of(credit(200), debit(200));
        assertEquals(0, Rupees.ZERO.compareTo(guard.netRupees(reversed, "TRANSPORT")));
        assertFalse(guard.alreadyCredited(reversed, "TRANSPORT"));
    }

    @Test
    @DisplayName("a partial claw-back leaves them still paid")
    void partialReversalIsStillPaid() {
        List<LedgerEntry> partial = List.of(credit(200), debit(50));
        assertEquals(0, Rupees.of(150).compareTo(guard.netRupees(partial, "TRANSPORT")));
        assertTrue(guard.alreadyCredited(partial, "TRANSPORT"));
    }

    @Test
    @DisplayName("other sub-actions are ignored entirely")
    void otherSubactionsDoNotCount() {
        List<LedgerEntry> mixed = List.of(
                new LedgerEntry("CREDIT", "INCENTIVE", Rupees.of(500)),
                new LedgerEntry("CREDIT", "TRANSPORT", Rupees.of(100)));
        assertEquals(0, Rupees.of(100).compareTo(guard.netRupees(mixed, "TRANSPORT")));
        assertFalse(guard.alreadyCredited(mixed, "SOMETHING_ELSE"));
    }

    @Test
    @DisplayName("an empty or null ledger is not paid, and does not throw")
    void emptyLedgerIsSafe() {
        assertFalse(guard.alreadyCredited(List.of(), "TRANSPORT"));
        assertFalse(guard.alreadyCredited(null, "TRANSPORT"));
        assertEquals(0, Rupees.ZERO.compareTo(guard.netRupees(null, null)));
    }

    @Test
    @DisplayName("an action value we do not recognise contributes nothing either way")
    void unknownActionIsIgnored() {
        // Better to under-count and re-examine than to guess a direction and pay twice.
        List<LedgerEntry> odd = List.of(new LedgerEntry("ADJUSTMENT", "TRANSPORT", Rupees.of(99)));
        assertEquals(0, Rupees.ZERO.compareTo(guard.netRupees(odd, "TRANSPORT")));
    }
}
