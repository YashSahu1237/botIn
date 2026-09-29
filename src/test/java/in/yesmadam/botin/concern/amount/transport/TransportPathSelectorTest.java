package in.yesmadam.botin.concern.amount.transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 49. The assumption, pinned down.
 *
 * These tests do not prove the derivation is RIGHT — nothing can, until someone writes
 * down the real path-selector rule. They prove it is CONSISTENT and that it lives in one
 * place. When the real rule arrives, these are the tests that change, and the fact that
 * they are the only ones that change is the point of extracting the selector at all.
 *
 * No Spring context: this is arithmetic on three inputs and deserves to run instantly.
 */
class TransportPathSelectorTest {

    private final TransportPathSelector selector = new TransportPathSelector();

    @Test
    @DisplayName("a customer charge that never reached the partner is Path 1")
    void chargedButNotCredited() {
        assertEquals("PATH_1", selector.select(true, false, null));
    }

    @Test
    @DisplayName("already credited is never Path 1, whatever else is true")
    void creditedIsNotPathOne() {
        // The table's first row catches this case anyway, but the selector must not
        // disagree with it — two components describing the same claim differently is
        // how a contradiction gets shipped.
        assertNotEquals("PATH_1", selector.select(true, true, null));
    }

    @Test
    @DisplayName("a cancellation makes it Path 2")
    void cancelledIsPathTwo() {
        assertEquals("PATH_2", selector.select(false, false, "CANCELLED_CR"));
        assertEquals("PATH_2", selector.select(false, false, "CANCELLED_BY_AGENT"));
    }

    @Test
    @DisplayName("a blank cancellation status is not a cancellation")
    void blankStatusIsNotACancellation() {
        assertEquals("PATH_3", selector.select(false, false, ""));
        assertEquals("PATH_3", selector.select(false, false, "   "));
    }

    @Test
    @DisplayName("neither charged nor cancelled is ordinary travel — Path 3")
    void ordinaryTravelIsPathThree() {
        assertEquals("PATH_3", selector.select(false, false, null));
    }

    @Test
    @DisplayName("THE CONFLICT THIS EXISTS TO RESOLVE: charged AND cancelled picks one path")
    void overlappingConditionsResolveToExactlyOnePath() {
        // A claim that was cancelled by an agent AND has an uncredited customer charge
        // satisfies the conditions of both rule 4 and rule 8, which pay different
        // amounts. Without a selector, FIRST hit policy silently picks whichever row is
        // higher. With one, the claim belongs to exactly one path and the choice is
        // written down where it can be argued with.
        String path = selector.select(true, false, "CANCELLED_BY_AGENT");
        assertEquals("PATH_1", path,
                "an uncredited customer charge takes precedence over the cancellation");
    }

    @Test
    @DisplayName("nulls never throw — a fact provider with no data still gets a path")
    void nullsAreSafe() {
        assertDoesNotThrow(() -> selector.select(null, null, null));
        assertEquals("PATH_3", selector.select(null, null, null));
    }
}
