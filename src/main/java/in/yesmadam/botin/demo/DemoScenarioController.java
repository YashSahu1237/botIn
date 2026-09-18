package in.yesmadam.botin.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * THE SEVENTEEN CASES, OVER HTTP, SO THE CONSOLE CAN SHOW THEM.
 *
 * Three endpoints and no cleverness:
 *
 *   GET  /demo/scenarios              what the cases are, without running anything
 *   POST /demo/scenarios/{id}/run     one case — for a reviewer who asks "show me that one"
 *   POST /demo/scenarios/run-all      every case, every time
 *
 * The distinction between the last two matters. Running one is for a conversation; running
 * all is the acceptance run, and it is deliberately not selectable — the cases somebody
 * would skip with a room watching are the ones worth seeing.
 *
 * DEMO PROFILE ONLY, like everything else in this package. These cases flip kill switches
 * and reset the ticket table.
 */
@RestController
@RequestMapping("/demo/scenarios")
@Profile("demo")
public class DemoScenarioController {

    private final DemoScenarios scenarios;

    public DemoScenarioController(DemoScenarios scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping
    public List<DemoScenarios.Listing> list() {
        return scenarios.list();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> runOne(@PathVariable String id) {
        return scenarios.run(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "NO_SUCH_SCENARIO")));
    }

    /**
     * 200 when every case passed, 409 when any did not.
     *
     * The status code carries the verdict so a caller does not have to parse the body to
     * learn it — which is what lets demo-run.sh stay a two-line script that still exits
     * non-zero on a failure, and what makes this usable from CI.
     */
    @PostMapping("/run-all")
    public ResponseEntity<Map<String, Object>> runAll() {
        Map<String, Object> report = scenarios.runAll();
        boolean allPassed = Boolean.TRUE.equals(report.get("allPassed"));
        return ResponseEntity.status(allPassed ? 200 : 409).body(report);
    }
}
