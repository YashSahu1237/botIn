package in.yesmadam.botin.surface.demo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * THE SEVENTEEN CASES, AS DATA, IN ONE PLACE.
 *
 * =========================================================================
 * WHY THIS EXISTS, WHEN tools/demo-run.sh ALREADY DID IT
 * =========================================================================
 *
 * It did, and that was the problem: the cases lived only in a shell script, so the only
 * way to see them was to have a terminal open beside the console. A reviewer watching the
 * console saw a chat box and a rule trace, and had to take somebody's word that seventeen
 * assertions existed somewhere else.
 *
 * So the cases move HERE and the script becomes a client of this. ONE DEFINITION. Two
 * surfaces read it: the console renders it with a Run button, and demo-run.sh calls
 * /demo/scenarios/run-all and still exits non-zero on a failure, which is what makes it
 * usable in CI. If the two had separate definitions they would drift, and the first anyone
 * would know is a demo that passes in the terminal and fails on screen.
 *
 * =========================================================================
 * WHY SELF-HTTP RATHER THAN CALLING THE SERVICES DIRECTLY
 * =========================================================================
 *
 * The claim these cases exist to support is "every case in the coverage matrix is reachable
 * THROUGH THE API". A runner that reached past the controllers into HelpSessionService
 * would be a weaker test that looked like a stronger one — it could pass while the endpoint
 * that a real client uses was broken, which is exactly the failure that plan step 86 was:
 * built, tested, documented as done, and unreachable through the API.
 *
 * So every step here goes over HTTP to this same service, on the port the web server
 * actually bound to. It costs a socket per call and buys the only claim worth making.
 *
 * DEMO PROFILE ONLY. These scenarios flip kill switches, force gateway failures and reset
 * the ticket table. None of that may exist anywhere a real partner can reach.
 */
@Component
@Profile("demo")
public class DemoScenarios implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DemoScenarios.class);

    /** What one assertion in one case came out as. */
    public record Check(String label, String expected, String actual, boolean passed) { }

    /** What one case came out as. `error` is non-null only when the case threw. */
    public record Result(String id, String title, String proves, boolean passed,
                         int passedChecks, int failedChecks,
                         List<Check> checks, List<String> sessionIds, String error) { }

    /** The list, with no result — what the console renders before anything has been run. */
    public record Listing(String id, String title, String proves) { }

    @FunctionalInterface
    interface Body { void run(Run run); }

    private record Scenario(String id, String title, String proves, Body body) { }

    private final ObjectMapper json;
    private final RestTemplate http = new RestTemplate();
    private volatile int port = 8080;

    public DemoScenarios(ObjectMapper json) {
        this.json = json;
        // A 4xx must be a readable failure in the report, not an exception that loses the
        // rest of the run. The status code is itself an assertion in case 17.
        this.http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
        log.info("demo scenarios will drive this service over HTTP on port {}", this.port);
    }

    // =====================================================================
    //  THE RUN
    // =====================================================================

    private final List<Scenario> scenarios = List.of(

        new Scenario("t0-no-ticket",
            "T0 — a deflection that creates NO TICKET",
            "The BRD's central claim, made literal: deflected volume cannot appear in ticket "
          + "counts, because there is nothing to count.",
            r -> {
                String sid = r.raise("SP-DEMO-T0", "AMOUNT_RELATED", "FORGET_MPIN", null);
                JsonNode view = r.view(sid);
                r.expect("outcome", text(view, "nextStep", "type"), "MESSAGE");
                r.expect("status", text(view, "status"), "CLOSED_DEFLECTED");
                r.expect("TICKET ROWS", r.ticketCount("SP-DEMO-T0"), "0");
            }),

        new Scenario("t1-reasoned-refusal",
            "T1 — the bot answers from data, and says no",
            "The partner never reached the job, so there is nothing to pay. A correct refusal "
          + "is as valuable as a payment.",
            r -> {
                String sid = r.raise("SP-DEMO-13", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7004");
                r.expect("action", text(r.view(sid), "nextStep", "code"), "DENY_DID_NOT_TRAVEL");
            }),

        new Scenario("t2-money-moves",
            "T2 — MONEY MOVES",
            "PayU took the money, the wallet never saw it. The bot credits it and tells the "
          + "partner in words they can check against their balance.",
            r -> {
                String sid = r.raise("SP-DEMO-01", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-PAID");
                r.expect("status", text(r.view(sid), "status"), "CLOSED_RESOLVED");
            }),

        new Scenario("t3-unreadable-state",
            "T3 — the state we cannot read goes to a person",
            "26% of real rows have NO gateway response. Nothing may move on a state we cannot "
          + "read, so nobody is paid and nobody is refused.",
            r -> {
                String sid = r.raise("SP-DEMO-02", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-UNKNOWN");
                r.expect("action", text(r.view(sid), "nextStep", "code"), "AGENT_CONNECTING");
            }),

        new Scenario("idempotency",
            "IDEMPOTENCY — the same claim raised twice pays once",
            "Two conversations a week apart are two complaints and correctly two tickets. The "
          + "guard is keyed on PayU's order id, not ours, so it collides anyway.",
            r -> {
                r.raise("SP-DEMO-04", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-TWICE");
                String sid = r.raise("SP-DEMO-04", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-TWICE");
                r.expect("second attempt", text(r.view(sid), "nextStep", "code"), "INFORM_ALREADY_CREDITED");
            }),

        new Scenario("gateway-failure",
            "EXTERNAL FAILURE — the gateway dies mid-session",
            "The call may have succeeded and failed to tell us. So: record, escalate, and NEVER "
          + "retry. A retry on an unknown outcome is a second payment.",
            r -> {
                r.post("/demo/gateway/DEMO-RCH-FAILOVER", "{\"status\":\"success\",\"amountPaise\":25000}");
                r.post("/demo/gateway/fail-next/1", "{}");
                String first = r.raise("SP-DEMO-06", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-FAILOVER");
                r.expect("goes to a person", text(r.view(first), "nextStep", "code"), "AGENT_CONNECTING");
                String second = r.raise("SP-DEMO-06", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-FAILOVER");
                r.expect("retry refused", text(r.view(second), "nextStep", "code"), "INFORM_ALREADY_CREDITED");
            }),

        new Scenario("kill-switch",
            "THE KILL SWITCH — flipped live, no restart",
            "Off does not mean fail. It means do not automate: the partner is still served, by "
          + "a person. That is the difference between a kill switch and an outage.",
            r -> {
                r.post("/demo/flags/RECHARGE_AUTO_CREDIT", "{\"enabled\":false}");
                try {
                    String sid = r.raise("SP-DEMO-05", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-KILLSWITCH");
                    r.expect("routed to a human", text(r.view(sid), "nextStep", "code"), "AGENT_CONNECTING");
                } finally {
                    // Restored even if the assertion blew up. A demo that leaves a kill switch
                    // off poisons every case that runs after it, and the failure appears
                    // somewhere else entirely.
                    r.post("/demo/flags/RECHARGE_AUTO_CREDIT", "{\"enabled\":true}");
                }
            }),

        new Scenario("cap-escalation",
            "CAP ESCALATION — Rs450 becomes a ticket, not a Rs300 payment",
            "A cap is a handover to a person. Clamping would be a silent underpayment that "
          + "nobody ever sees.",
            r -> {
                String sid = r.raise("SP-DEMO-11", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7002");
                // The partner is told an agent will call — the same sentence every T3 ends
                // with, because a partner has no use for our vocabulary. The CAUSE is on the
                // ticket, and it has to be, or "why did this go to a human" is unanswerable.
                r.expect("partner is handed over", text(r.view(sid), "nextStep", "code"), "AGENT_CONNECTING");
                JsonNode latest = r.ticketLatest("SP-DEMO-11");
                r.expect("the ticket knows why", text(latest, "action"), "TICKET_EXCEEDS_CAP");
                r.expect("and it is a T3", text(latest, "tier"), "T3");
            }),

        new Scenario("reversal",
            "THE REVERSAL CASE — a clawed-back credit is NOT payment",
            "EXISTS(CREDIT) is the obvious implementation and it refuses this partner money "
          + "they never kept. Net position reads it correctly.",
            r -> {
                String sid = r.raise("SP-DEMO-17", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7008");
                String code = text(r.view(sid), "nextStep", "code");
                r.expect("not treated as paid",
                         "INFORM_ALREADY_PAID".equals(code) ? "WRONG — read as paid" : "ok",
                         "ok");
            }),

        new Scenario("already-paid-beats-cap",
            "ALREADY PAID beats everything, including the cap above it",
            "Row order is logic. This row sits above the cap deliberately.",
            r -> {
                String sid = r.raise("SP-DEMO-10", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7001");
                r.expect("action", text(r.view(sid), "nextStep", "code"), "INFORM_ALREADY_PAID");
            }),

        new Scenario("shared-tat",
            "SHARED SERVICE REUSE — one TAT function, inside and past the deadline",
            "PROD_DELIVERY_DELAY and the violation concern read the SAME service. If they ever "
          + "differed, one partner would be told their order is on time AND that it is late.",
            r -> {
                String inside = r.raise("SP-DEMO-20", "PRODUCT_ISSUES", "PROD_DELIVERY_DELAY", null);
                r.expect("inside TAT", text(r.view(inside), "nextStep", "code"), "SHOW_EXPECTED_DELIVERY");
                String past = r.raise("SP-DEMO-21", "PRODUCT_ISSUES", "PROD_DELIVERY_DELAY", null);
                r.expect("past TAT -> a person", text(r.view(past), "nextStep", "type"), "MESSAGE");
            }),

        new Scenario("free-text-entry",
            "FREE TEXT AT THE ENTRY POINT — no L1, no L2, just words",
            "The partner is handed to the TRIAGE concern and ITS table routes them — the same "
          + "path in-concern text takes. Two routing mechanisms would mean two places to fix a "
          + "routing bug.",
            r -> {
                String sid = r.start("SP-DEMO-30", "mpin bhool gaya hun");
                String status = text(r.view(sid), "status");
                r.expect("resolved without a menu",
                         "OPEN".equals(status) ? "still-open" : "routed", "routed");
            }),

        new Scenario("free-text-reroute",
            "FREE TEXT INSIDE A CONCERN — reroute",
            "The partner picked one thing and described another. The session MOVES rather than "
          + "forking: one conversation, one row, and the concern on it is the one being handled.",
            r -> {
                String sid = r.start("SP-DEMO-31", null);
                r.send(sid, "{\"selection\":\"VIOLATIONS\"}");
                r.send(sid, "{\"selection\":\"VIOL_R4_OTHERS\",\"freeText\":\"transport ka paisa nahi mila\"}");
                String landed = Optional.ofNullable(text(r.view(sid), "l2Concern")).orElse("none");
                // TWO assertions, because "not the original concern" is also true of a session
                // that never got anywhere. It must have MOVED and have LANDED somewhere.
                r.expect("left the triage concern",
                         "VIOL_R4_OTHERS".equals(landed) ? "stuck" : "moved", "moved");
                r.expect("and landed on a real one",
                         "none".equals(landed) ? "nowhere" : landed, landed);
            }),

        new Scenario("csat-asked",
            "CSAT IS ASKED on every bot resolution",
            "The only number that tells us whether automating a concern was a good idea, rather "
          + "than merely a busy one.",
            r -> {
                String sid = r.raise("SP-DEMO-40", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-FAILED");
                // csatExpected, not a CSAT step. Nothing is held open waiting for a rating —
                // the process has completed.
                r.expect("csat expected", text(r.view(sid), "csatExpected"), "true");
            }),

        new Scenario("trigger-a",
            "TRIGGER A — a rejected deflection escalates to a person",
            "The bot answered, the partner said it did not help. That is not a new complaint; it "
          + "is the same ticket, marked as one automation got wrong.",
            r -> {
                String sid = r.raise("SP-DEMO-40", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-FAILED");

                // MEASURED BEFORE AND AFTER, not against an absolute count.
                //
                // This assertion used to read `ticketCount == 1`, and it passed only because
                // the case before it happened to leave exactly one ticket on this partner and
                // this case reused that same session. Make the case self-contained — which it
                // must be, because a reviewer clicks whichever row they asked about — and the
                // count is 2, and a correct system reports a failure.
                //
                // The count was never the property. The property is that rejecting a
                // deflection REUSES the ticket rather than opening another one, and a
                // before/after comparison says that no matter what else has run. The id
                // comparison says it more strongly still: same number is not same row.
                String countBefore = r.ticketCount("SP-DEMO-40");
                String ticketBefore = text(r.ticketLatest("SP-DEMO-40"), "ticketId");

                JsonNode after = r.post("/help/sessions/" + sid + "/csat", "{\"satisfied\":false}");
                r.expect("agent offered", text(after, "agentOffered"), "true");
                r.expect("no new ticket was opened", r.ticketCount("SP-DEMO-40"), countBefore);
                r.expect("and it is the SAME ticket",
                         text(r.ticketLatest("SP-DEMO-40"), "ticketId"), ticketBefore);
            }),

        new Scenario("bounding-rule",
            "THE BOUNDING RULE — a satisfied partner is NOT offered an agent",
            "This is the rule that protects the whole target. Offering an agent to somebody the "
          + "bot already helped is how a deflection rate quietly becomes a handover rate.",
            r -> {
                // SEEDED FIRST. A rule about resolved cases needs a resolved case — an unknown
                // order reaches a human, and a satisfaction answer on an unresolved case is
                // refused, which reads as null rather than false.
                r.post("/demo/gateway/DEMO-RCH-BOUNDING", "{\"status\":\"failure\",\"amountPaise\":25000}");
                String sid = r.raise("SP-DEMO-41", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-BOUNDING");
                r.expect("resolved by the bot", text(r.view(sid), "csatExpected"), "true");
                JsonNode after = r.post("/help/sessions/" + sid + "/csat", "{\"satisfied\":true}");
                // The rule lives on this field, decided in closure logic rather than in the UI —
                // a suppression rule the client owns is one an old app version can ignore.
                r.expect("agentOffered", text(after, "agentOffered"), "false");
            }),

        new Scenario("agent-connect",
            "AGENT CONNECT — claim the task, read the context, complete it",
            "The agent opens the COMPUTED FACTS, not the transcript: what we already know, so "
          + "the partner is not asked to repeat themselves.",
            r -> {
                // Guarantee there is something in the queue rather than depending on an earlier
                // case having left one. A case that only passes in a particular order is not a
                // case, it is a coincidence.
                //
                // RAISING ONE WAS NOT ENOUGH. This took queue.get(0), which is whatever task is
                // oldest — usually one left by an earlier case, or by an earlier TEST CLASS in
                // the same context. /demo/reset clears tickets and escalation contexts but NOT
                // Flowable's task list, so those stale tasks point at rows that no longer exist,
                // and "read the context" then reads nothing. It passed only while reset was
                // silently failing and leaving the old rows in place.
                //
                // So: find the task belonging to the session THIS case just opened. Identity,
                // not position.
                String sid = r.raise("SP-DEMO-50", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT", "DEMO-RCH-UNKNOWN");
                JsonNode queue = r.get("/agent/tasks");
                JsonNode mine = null;
                if (queue != null && queue.isArray()) {
                    for (JsonNode task : queue) {
                        if (sid != null && sid.equals(text(task, "helpSessionId"))) { mine = task; break; }
                    }
                }
                if (mine == null) {
                    r.expect("a task is waiting for the session this case opened",
                             queue == null || !queue.isArray() ? "no queue" : "not in a queue of " + queue.size(),
                             "one task");
                    return;
                }
                String taskId = text(mine, "taskId");
                String ticketId = text(mine, "ticketId");
                JsonNode ctx = r.get("/tickets/" + ticketId + "/escalation-context");
                r.expect("context present",
                         ctx != null && ctx.hasNonNull("ticketId") ? "yes" : "no", "yes");
                r.post("/agent/tasks/" + taskId + "/claim", "{\"agentId\":\"demo-agent\"}");
                int status = r.postStatus("/agent/tasks/" + taskId + "/complete",
                        "{\"agentId\":\"demo-agent\",\"resolutionNote\":\"Aapka amount credit kar diya gaya hai.\"}");
                r.expect("completed", String.valueOf(status), "204");
            })
    );

    // =====================================================================
    //  RUNNING
    // =====================================================================

    public List<Listing> list() {
        List<Listing> out = new ArrayList<>();
        for (Scenario s : scenarios) out.add(new Listing(s.id(), s.title(), s.proves()));
        return out;
    }

    public Optional<Result> run(String id) {
        for (Scenario s : scenarios) {
            if (s.id().equals(id)) {
                reseed();
                return Optional.of(execute(s));
            }
        }
        return Optional.empty();
    }

    /**
     * EVERY CASE, EVERY TIME. The whole point of a fixed run is that it cannot be edited by
     * whoever is presenting it — the cases somebody would skip when a room is watching are
     * exactly the ones worth seeing.
     */
    public Map<String, Object> runAll() {
        reseed();
        List<Result> results = new ArrayList<>();
        int passed = 0;
        for (Scenario s : scenarios) {
            Result result = execute(s);
            results.add(result);
            if (result.passed()) passed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", results.size());
        out.put("passed", passed);
        out.put("failed", results.size() - passed);
        out.put("allPassed", passed == results.size());
        out.put("results", results);
        return out;
    }

    /**
     * Fixtures back to their starting state and the kill switches on, before anything runs.
     * Without it the second run of a durable-guard case finds the first run's ledger rows and
     * a case that genuinely passes reports a failure.
     */
    private void reseed() {
        try {
            post("/demo/reset", "{}");
            post("/demo/flags/RECHARGE_AUTO_CREDIT", "{\"enabled\":true}");
            post("/demo/flags/TRANSPORT_AUTO_CREDIT", "{\"enabled\":true}");
        } catch (Exception e) {
            log.warn("could not reseed the demo world before the run", e);
        }
    }

    private Result execute(Scenario scenario) {
        Run run = new Run();
        String error = null;
        try {
            scenario.body().run(run);
        } catch (Exception e) {
            // A case that throws is a failed case with a readable reason, never a run that
            // stops. The remaining sixteen still have something to say.
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("demo scenario {} threw", scenario.id(), e);
        }
        int passed = 0;
        for (Check c : run.checks) if (c.passed()) passed++;
        int failed = run.checks.size() - passed;
        return new Result(scenario.id(), scenario.title(), scenario.proves(),
                failed == 0 && error == null, passed, failed,
                List.copyOf(run.checks), List.copyOf(run.sessions), error);
    }

    // =====================================================================
    //  THE STEPS A CASE CAN TAKE — all of them over HTTP, on purpose
    // =====================================================================

    /** Accumulates what one case did and what it found. */
    final class Run {
        private final List<Check> checks = new ArrayList<>();
        private final List<String> sessions = new ArrayList<>();

        void expect(String label, String actual, String expected) {
            boolean ok = expected == null ? actual == null : expected.equals(actual);
            checks.add(new Check(label, expected, actual, ok));
        }

        /** Opens a session. `freeText` may be null. Returns the session id. */
        String start(String spId, String freeText) {
            String body = freeText == null
                    ? "{\"spId\":" + quote(spId) + "}"
                    : "{\"spId\":" + quote(spId) + ",\"freeText\":" + quote(freeText) + "}";
            String sid = text(post("/help/sessions", body), "sessionId");
            if (sid != null) sessions.add(sid);
            return sid;
        }

        JsonNode send(String sessionId, String body) {
            return post("/help/sessions/" + sessionId + "/input", body);
        }

        /** L1, then L2 with an optional reference. Returns the session id — and only that. */
        String raise(String spId, String l1, String l2, String reference) {
            String sid = start(spId, null);
            send(sid, "{\"selection\":" + quote(l1) + "}");
            send(sid, reference == null
                    ? "{\"selection\":" + quote(l2) + "}"
                    : "{\"selection\":" + quote(l2) + ",\"reference\":" + quote(reference) + "}");
            return sid;
        }

        JsonNode view(String sessionId) { return get("/help/sessions/" + sessionId); }

        String ticketCount(String spId) {
            return text(get("/demo/tickets/" + spId + "/count"), "tickets");
        }

        JsonNode ticketLatest(String spId) { return get("/demo/tickets/" + spId + "/latest"); }

        JsonNode get(String path)  { return DemoScenarios.this.get(path); }
        JsonNode post(String path, String body) { return DemoScenarios.this.post(path, body); }
        int postStatus(String path, String body) { return DemoScenarios.this.postStatus(path, body); }
    }

    // =====================================================================
    //  HTTP AND JSON PLUMBING
    // =====================================================================

    private String base() { return "http://127.0.0.1:" + port; }

    private JsonNode get(String path) {
        ResponseEntity<String> response = http.getForEntity(base() + path, String.class);
        return parse(response.getBody());
    }

    private JsonNode post(String path, String body) {
        return parse(exchange(path, body).getBody());
    }

    private int postStatus(String path, String body) {
        return exchange(path, body).getStatusCode().value();
    }

    private ResponseEntity<String> exchange(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(base() + path, HttpMethod.POST,
                new HttpEntity<>(body == null ? "{}" : body, headers), String.class);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return json.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** A JSON string literal, escaped properly — Hinglish free text goes through here. */
    private String quote(String value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    /**
     * Reads a value down a path, as TEXT.
     *
     * Every assertion compares strings, including the boolean ones. A check that reports
     * `expected 'true', got 'null'` says what happened; a check that reports
     * `expected true, got false` when the field was absent does not — and an ABSENT field
     * and a FALSE field mean completely different things here. asText() on a missing node
     * would hand back "" and hide that, so a missing node returns null instead.
     */
    private static String text(JsonNode node, String... path) {
        JsonNode at = node;
        for (String step : path) {
            if (at == null) return null;
            at = at.get(step);
        }
        return at == null || at.isNull() ? null : at.asText();
    }
}
