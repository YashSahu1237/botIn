package in.yesmadam.botin.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE DEMO, AS A TEST.
 *
 * =========================================================================
 * WHY THIS MATTERS MORE THAN IT LOOKS
 * =========================================================================
 *
 * The seventeen cases are the POC's evidence. Until now they only ran when somebody
 * remembered to open two terminals, which means the honest description of them was
 * "they passed the last time anyone tried". A demo nobody runs rots exactly like code
 * nobody runs, and it rots silently, because the person who finds out is an audience.
 *
 * Here they run on every `mvn test`. If a change breaks the cap, the kill switch, the
 * duplicate guard or the agent handover, the build says so on the day it happens rather
 * than in a room.
 *
 * THIS NEEDS A REAL SERVER, not MockMvc, and that is deliberate. DemoScenarios drives the
 * service over HTTP on purpose — the claim being supported is "every case is reachable
 * THROUGH THE API", and a runner that reached past the controllers could pass while the
 * endpoint a real client uses was broken. That is not hypothetical: plan step 86 was built,
 * tested, documented as done, and unreachable through the API, because every test drove it
 * from somewhere a client never goes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"demo", "test"})
class DemoScenarioRunTest {

    @Autowired DemoScenarios scenarios;

    @Test
    @DisplayName("Every case in the acceptance run passes")
    void theWholeRunIsGreen() {
        Map<String, Object> report = scenarios.runAll();

        @SuppressWarnings("unchecked")
        List<DemoScenarios.Result> results = (List<DemoScenarios.Result>) report.get("results");

        // The failure message is the whole value of this test. "expected true, got false"
        // would send somebody to run the demo by hand to find out what broke; this names
        // the case, the assertion, what it wanted and what it got.
        List<String> failures = new ArrayList<>();
        for (DemoScenarios.Result result : results) {
            if (result.passed()) continue;
            StringBuilder detail = new StringBuilder("\n  " + result.title());
            if (result.error() != null) detail.append("\n      threw: ").append(result.error());
            for (DemoScenarios.Check check : result.checks()) {
                if (check.passed()) continue;
                detail.append("\n      ").append(check.label())
                      .append(": expected '").append(check.expected())
                      .append("', got '").append(check.actual()).append('\'');
            }
            failures.add(detail.toString());
        }

        assertTrue(failures.isEmpty(),
                "cases failed in the acceptance run:" + String.join("", failures));
    }

    @Test
    @DisplayName("The run covers every case the list advertises, and each one asserts something")
    void nothingIsListedWithoutBeingChecked() {
        Map<String, Object> report = scenarios.runAll();

        @SuppressWarnings("unchecked")
        List<DemoScenarios.Result> results = (List<DemoScenarios.Result>) report.get("results");

        assertEquals(scenarios.list().size(), results.size(),
                "a case is advertised that the run does not execute");

        // A CASE WITH NO CHECKS IS A CASE THAT CANNOT FAIL, and a case that cannot fail is
        // decoration. It would sit on the console showing a green badge forever while
        // demonstrating nothing, which is worse than not being there.
        for (DemoScenarios.Result result : results) {
            assertFalse(result.checks().isEmpty(),
                    result.title() + " ran without asserting anything");
        }
    }

    @Test
    @DisplayName("A case can be run on its own, and twice, and says the same thing")
    void oneCaseIsIndependentAndRepeatable() {
        // A case that only passes after the ones above it is not a case, it is a
        // coincidence — and on the console a reviewer clicks whichever one they asked
        // about, in whatever order they ask.
        String id = scenarios.list().get(0).id();

        DemoScenarios.Result first = scenarios.run(id).orElseThrow();
        DemoScenarios.Result again = scenarios.run(id).orElseThrow();

        assertTrue(first.passed(), first.title() + " failed when run on its own");
        assertEquals(first.passed(), again.passed(),
                first.title() + " gave a different answer the second time — the reseed is not "
              + "putting the world back");
    }

    @Test
    @DisplayName("An unknown case is absent rather than silently empty")
    void anUnknownIdIsNotFound() {
        assertTrue(scenarios.run("no-such-case").isEmpty());
    }
}
