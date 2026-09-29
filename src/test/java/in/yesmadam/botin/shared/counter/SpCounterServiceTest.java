package in.yesmadam.botin.shared.counter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 45. Needs the database, because the whole point is durability.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpCounterServiceTest {

    @Autowired SpCounterService counters;
    @Autowired TransactionTemplate transactions;

    private static final String SEPT = "2026-09";
    private static final String OCT  = "2026-10";

    @Test
    @DisplayName("a partner who has never taken one reads as zero, with no row needed")
    void missingRowIsZero() {
        assertEquals(0, counters.count("SP-NEVER", SpCounterService.PERIOD_LEAVE, SEPT));
    }

    @Test
    @DisplayName("grants accumulate within a period")
    void grantsAccumulate() {
        String sp = "SP-C1";
        assertEquals(1, counters.grant(sp, SpCounterService.PERIOD_LEAVE, SEPT));
        assertEquals(2, counters.grant(sp, SpCounterService.PERIOD_LEAVE, SEPT));
        assertEquals(2, counters.count(sp, SpCounterService.PERIOD_LEAVE, SEPT));
    }

    @Test
    @DisplayName("THE RESET — a new month starts at zero, and last month is still readable")
    void theResetIsFreeAndNonDestructive() {
        // Nothing is zeroed and nothing is deleted. October is a different key, so it
        // begins at zero by simply not existing yet — and September is still there to
        // answer a dispute. There is no scheduled job here that can fail to run.
        String sp = "SP-C2";
        counters.grant(sp, SpCounterService.PERIOD_LEAVE, SEPT);
        counters.grant(sp, SpCounterService.PERIOD_LEAVE, SEPT);

        assertEquals(0, counters.count(sp, SpCounterService.PERIOD_LEAVE, OCT), "October is fresh");
        assertEquals(2, counters.count(sp, SpCounterService.PERIOD_LEAVE, SEPT), "September survives");
    }

    @Test
    @DisplayName("counters are per partner, per key, and per period — no leakage")
    void countersAreFullyScoped() {
        counters.grant("SP-C3", SpCounterService.PERIOD_LEAVE, SEPT);

        assertEquals(0, counters.count("SP-C4", SpCounterService.PERIOD_LEAVE, SEPT),
                "another partner is unaffected");
        assertEquals(0, counters.count("SP-C3", SpCounterService.POOLED_EMERGENCY, SEPT),
                "another counter on the same partner is unaffected");
    }

    @Test
    @DisplayName("the cap holds — one period leave a month, the second is refused")
    void theCapHolds() {
        String sp = "SP-C5";
        assertTrue(counters.grantIfWithinCap(sp, SpCounterService.PERIOD_LEAVE, SEPT, 1),
                "the first this month is allowed");
        assertFalse(counters.grantIfWithinCap(sp, SpCounterService.PERIOD_LEAVE, SEPT, 1),
                "the second is refused");
        assertEquals(1, counters.count(sp, SpCounterService.PERIOD_LEAVE, SEPT),
                "a refused grant must not increment anything");
    }

    @Test
    @DisplayName("a refused grant in one period does not refuse the next")
    void theCapResetsWithThePeriod() {
        String sp = "SP-C6";
        assertTrue(counters.grantIfWithinCap(sp, SpCounterService.PERIOD_LEAVE, SEPT, 1));
        assertFalse(counters.grantIfWithinCap(sp, SpCounterService.PERIOD_LEAVE, SEPT, 1));
        assertTrue(counters.grantIfWithinCap(sp, SpCounterService.PERIOD_LEAVE, OCT, 1),
                "next month, the allowance is available again");
    }

    @Test
    @DisplayName("a grant SURVIVES the caller rolling back")
    void grantSurvivesCallerRollback() {
        // Same reasoning as TicketActionRecorder. An allowance handed to a partner and
        // then rolled back because something later failed is a benefit they keep and a
        // counter that says they never took it — so the next request grants it again.
        String sp = "SP-C7";
        try {
            transactions.executeWithoutResult(status -> {
                counters.grant(sp, SpCounterService.CYCLE_REMOVAL, "cycle:41");
                throw new IllegalStateException("something later in the flow blew up");
            });
            fail("the caller's transaction should have rolled back");
        } catch (IllegalStateException expected) {
            // as designed
        }

        assertEquals(1, counters.count(sp, SpCounterService.CYCLE_REMOVAL, "cycle:41"),
                "the tally was lost with the caller's rollback — REQUIRES_NEW is not in effect");
    }

    // ------------------------------------------------------------- period keys

    @Test
    @DisplayName("the month boundary is the calendar month, not a rolling 30 days")
    void monthKeyIsCalendarMonth() {
        assertEquals("2026-09", counters.monthKey(LocalDate.of(2026, 9, 1)));
        assertEquals("2026-09", counters.monthKey(LocalDate.of(2026, 9, 30)));
        assertEquals("2026-10", counters.monthKey(LocalDate.of(2026, 10, 1)),
                "the 1st is a new period, whatever happened the day before");
    }

    @Test
    @DisplayName("the cycle key follows the live cycle row, so the two cannot drift")
    void cycleKeyFollowsTheLiveCycle() {
        // The 25-job cycle is not ours — it is a row in tbl_sp_job_completion_cycle.
        // Keying off its id means a new cycle there starts a fresh allowance here with
        // nothing to synchronise.
        assertEquals("cycle:41", counters.cycleKey(41L));
        assertNotEquals(counters.cycleKey(41L), counters.cycleKey(42L));
    }
}
