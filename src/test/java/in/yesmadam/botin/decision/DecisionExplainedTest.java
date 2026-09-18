package in.yesmadam.botin.decision;

import in.yesmadam.botin.console.DecisionTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXPLAINING A DECISION MUST NOT CHANGE IT.
 *
 * `DecideDelegate` now calls `decideExplained` rather than `decide`, so every real decision in
 * the system — including every one that moves money — goes through Flowable's audit-trail API
 * instead of the plain one. That was done to make the console able to show which row fired.
 *
 * It is a change to the money path made for the benefit of a demo, which is exactly the kind of
 * change that deserves suspicion. So this asserts the thing that matters: for the same facts,
 * the two paths return the SAME answer. If they ever diverge, the explanation is not an
 * explanation — it is a second opinion, and the partner got the other one.
 */
@SpringBootTest
@ActiveProfiles("test")
class DecisionExplainedTest {

    private static final String TRANSPORT = "transport-not-received-decision";

    @Autowired DecisionService decisions;

    @Test
    @DisplayName("decide() and decideExplained() return the same answer, row by row")
    void explainingChangesNothing() {
        // One fact set per outcome the table can reach from its live rows.
        List<Map<String, Object>> cases = List.of(
                transport(false, 25_000L, "PATH_1", true, "NONE", false, 0),      // pays
                transport(true,  25_000L, "PATH_1", true, "NONE", false, 0),      // already paid
                transport(false, 45_000L, "PATH_1", true, "NONE", false, 0),      // over the cap
                transport(false, null,    "PATH_2", false, "CANCELLED_BY_AGENT", false, 0),
                transport(false, null,    "PATH_3", true,  "NONE", false, 0));    // inside the hub

        for (Map<String, Object> facts : cases) {
            Decision plain = decisions.decide(TRANSPORT, facts);
            Decision explained = decisions.decideExplained(TRANSPORT, facts).decision();

            assertEquals(plain.tier(), explained.tier(), "tier differs for " + facts);
            assertEquals(plain.action(), explained.action(), "action differs for " + facts);
            assertEquals(plain.outcomeType(), explained.outcomeType(), "outcome differs for " + facts);
        }
    }

    @Test
    @DisplayName("Exactly one row fires, and it is the FIRST row that matched")
    void firstHitPolicyIsWhatTheTraceReports() {
        // ₹450 is over the cap. Row 1 (already paid) does not match; row 2 (the cap) does.
        // Rows below it can also match — and must be reported as matched but NOT fired, because
        // under FIRST they were never reached. That distinction is the whole explanation.
        DecisionService.Explained explained =
                decisions.decideExplained(TRANSPORT, transport(false, 45_000L, "PATH_1", true, "NONE", false, 0));

        List<DecisionTrace.RuleOutcome> rules = explained.rules();
        assertFalse(rules.isEmpty(), "no per-rule audit came back — the console would show an empty grid");

        long fired = rules.stream().filter(DecisionTrace.RuleOutcome::fired).count();
        assertEquals(1, fired, "under FIRST exactly one row produces the answer");

        DecisionTrace.RuleOutcome firedRule = rules.stream()
                .filter(DecisionTrace.RuleOutcome::fired).findFirst().orElseThrow();

        rules.stream().filter(r -> r.index() < firedRule.index()).forEach(earlier ->
                assertFalse(earlier.matched(),
                        "row " + earlier.index() + " matched but a LATER row is marked as fired — "
                      + "that is not FIRST hit policy, and the explanation would be wrong"));

        assertEquals("TICKET_EXCEEDS_CAP", explained.decision().action(),
                "precondition: this fact set is the cap case");
    }

    @Test
    @DisplayName("THE FIRED INDEX POINTS AT THE RIGHT ROW — 0-based, matching the file")
    void theIndexIsTheOneTheFileUses() {
        // THE BUG THIS EXISTS FOR. Flowable numbers rules from 1; the reader that parses the
        // .dmn numbers them from 0; the console joins the two by index. Every outcome landed
        // one row low and the last row's outcome matched nothing — which showed up as "no row
        // fired" for the catch-all, and WOULD HAVE SHOWN THE WRONG ROW HIGHLIGHTED for every
        // other case. A confidently wrong explanation is worse than no explanation: nobody
        // watching a demo can tell.
        //
        // Rs450 is over the cap, which is the SECOND row of the transport table — index 1.
        DecisionService.Explained explained =
                decisions.decideExplained(TRANSPORT, transport(false, 45_000L, "PATH_1", true, "NONE", false, 0));

        DecisionTrace.RuleOutcome fired = explained.rules().stream()
                .filter(DecisionTrace.RuleOutcome::fired).findFirst().orElseThrow();

        assertEquals(1, fired.index(),
                "the cap is row 2 of the file, which is index 1. Any other number means the "
              + "console highlights a row that did not fire");

        // Contiguous and zero-based, because that is what the file reader produces.
        for (int i = 0; i < explained.rules().size(); i++) {
            assertEquals(i, explained.rules().get(i).index(),
                    "rule outcomes must be 0-based and in rule order, or the join is a guess");
        }
    }

    @Test
    @DisplayName("KNOWING NOTHING still explains itself — the catch-all is a row like any other")
    void everyFactNullStillNamesTheRowThatFired() {
        // THIS IS TODAY'S NORMAL CASE, not an edge one. With no read grant every provider
        // returns its full key set with null values, every row fails to match, and the
        // catch-all sends the partner to a person. That is the design working — but if the
        // panel cannot name the row that fired, the most common decision in the system is the
        // one it cannot explain, and a reviewer sees a grid with nothing highlighted.
        Map<String, Object> nothing = new LinkedHashMap<>();
        for (String key : List.of("alreadyCredited", "computedAmountPaise", "transportPath",
                                  "arrivedAt300metre", "cancellationStatus",
                                  "lastMinCashbackCredited", "distanceBeyondRadiusKm")) {
            nothing.put(key, null);          // PRESENT and null — absent would be an error
        }

        DecisionService.Explained explained = decisions.decideExplained(TRANSPORT, nothing);

        assertEquals("T3", explained.decision().tier(), "knowing nothing must reach a person");
        assertFalse(explained.rules().isEmpty(),
                "no per-rule audit came back at all — the console draws an empty grid and "
              + "nobody can tell it is broken");
        assertEquals(1, explained.rules().stream().filter(DecisionTrace.RuleOutcome::fired).count(),
                "the catch-all fired, so exactly one row must be reported as having fired");
    }

    @Test
    @DisplayName("A table that matches nothing still throws, exactly as the plain path does")
    void noMatchBehavesIdentically() {
        Map<String, Object> nothing = new LinkedHashMap<>();
        // Absent variables, not null ones — strict mode treats these as evaluation errors, and
        // both paths must fail the same way. A trace that swallowed this would hide the one
        // condition the catch-all exists for.
        assertThrows(Exception.class, () -> decisions.decide(TRANSPORT, nothing));
        assertThrows(Exception.class, () -> decisions.decideExplained(TRANSPORT, nothing));
    }

    private static Map<String, Object> transport(boolean credited, Long amount, String path,
                                                 boolean arrived, String cancellation,
                                                 boolean cashback, double beyondKm) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("alreadyCredited", credited);
        f.put("computedAmountPaise", amount);
        f.put("transportPath", path);
        f.put("arrivedAt300metre", arrived);
        f.put("cancellationStatus", cancellation);
        f.put("lastMinCashbackCredited", cashback);
        f.put("distanceBeyondRadiusKm", beyondKm);
        return f;
    }
}
