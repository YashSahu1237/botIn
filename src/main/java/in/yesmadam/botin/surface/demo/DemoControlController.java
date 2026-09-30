package in.yesmadam.botin.surface.demo;

import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
import in.yesmadam.botin.integration.payu.MockPayUGateway;
import in.yesmadam.botin.platform.escalation.EscalationContextRepository;
import in.yesmadam.botin.platform.safety.TicketActionRepository;
import in.yesmadam.botin.platform.session.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.repository.FeatureState;
import org.togglz.core.util.NamedFeature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PLAN STEP 95 — the handles the demo script needs, and nothing else.
 *
 * =========================================================================
 * WHY THIS EXISTS
 * =========================================================================
 *
 * Three rows of the coverage matrix are about what happens when the world MISBEHAVES:
 * the payment gateway dies mid-session, an operator pulls the kill switch, a partner
 * raises the same claim twice. None of them can be demonstrated by asking nicely — the
 * misbehaviour has to be arranged.
 *
 * Until now it was arranged from inside test code, which means the most important
 * behaviour in the system — what it does when something breaks — was the one part
 * nobody could watch. A demo that only shows the happy path is an advertisement.
 *
 * =========================================================================
 * WHY IT IS SAFE, WHICH IS A FAIR QUESTION
 * =========================================================================
 *
 * These endpoints flip kill switches and change what a payment gateway reports. In a real
 * deployment that is an attack surface with money on the other side of it. Three things
 * stand between here and there, and they compound:
 *
 *   1. `@Profile("demo")` — the controller DOES NOT EXIST otherwise. Not disabled, not
 *      secured: absent. The URLs 404 because nothing is mapped to them.
 *   2. The demo profile cannot start beside a real UAT datasource. `DemoFixtures` fails
 *      the application at startup rather than serve a mixture.
 *   3. The only gateway it can reach is `MockPayUGateway`. There is no real PayU
 *      credential anywhere in this codebase, so "the gateway" is a bean in memory.
 *
 * A test asserts point 1 by looking for a 404 in an ordinary run, because "we remembered
 * the annotation" is not a guarantee, and the failure mode is silent.
 */
@RestController
@RequestMapping("/demo")
@Profile("demo")
public class DemoControlController {

    private static final Logger log = LoggerFactory.getLogger(DemoControlController.class);

    private final MockPayUGateway gateway;
    private final FeatureManager features;
    private final DemoFixtures fixtures;
    private final TicketRepository tickets;
    private final TicketActionRepository actions;
    private final EscalationContextRepository escalations;

    public DemoControlController(MockPayUGateway gateway, FeatureManager features,
                                 DemoFixtures fixtures, TicketRepository tickets,
                                 TicketActionRepository actions,
                                 EscalationContextRepository escalations) {
        this.gateway = gateway;
        this.features = features;
        this.fixtures = fixtures;
        this.tickets = tickets;
        this.actions = actions;
        this.escalations = escalations;
    }

    /**
     * HOW MANY TICKETS THIS PARTNER HAS. The single most important number in the POC.
     *
     * The BRD's central claim is that a deflection creates NO TICKET — not a closed one,
     * not a zero-cost one, none. That claim is asserted in the test suite at the database
     * level, but a demo where nobody can SEE the count is a demo where the claim is taken
     * on trust, and it is the one claim the whole business case rests on.
     */
    /**
     * What the synthetic world contains — the script and the console both read this, so the
     * three cannot drift apart.
     *
     * A demo script or a console with its own hard-coded order ids is a SECOND description of
     * the fixture set, and the two disagree the first time somebody edits one of them.
     */
    @GetMapping("/fixtures")
    public Map<String, Object> whatIsInThisWorld() {
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("warning", "EVERY VALUE HERE IS SYNTHETIC. Nothing is read from a live system.");
        world.put("recharges", fixtures.recharges());
        world.put("transportOrders", fixtures.transportOrders());
        world.put("productOrders", fixtures.productOrders());
        return world;
    }

    /** Set what PayU reports for an order — success, failure, or nothing at all. */
    @PostMapping("/gateway/{orderId}")
    public Map<String, Object> setGatewayStatus(@PathVariable String orderId,
                                                @RequestBody GatewayState state) {
        gateway.setRecharge(orderId, state.status(),
                state.amountRupees() == null ? Rupees.ZERO : Rupees.scaled(state.amountRupees()));
        log.warn("DEMO CONTROL — gateway for {} now reports {}", orderId, state.status());
        return Map.of("orderId", orderId, "status", String.valueOf(gateway.statusOf(orderId)));
    }

    /**
     * KILL THE GATEWAY FOR THE NEXT N CALLS.
     *
     * The interesting number is 1: one call fails, the attempt row survives because it was
     * committed BEFORE the call, and the retry is refused rather than paying twice. That is
     * ADR-005, and it is only convincing when somebody watches it happen.
     */
    @PostMapping("/gateway/fail-next/{calls}")
    public Map<String, Object> failNextCalls(@PathVariable int calls) {
        gateway.failNextCalls(calls);
        log.warn("DEMO CONTROL — the next {} gateway call(s) will fail", calls);
        return Map.of("failNextCalls", calls);
    }

    /**
     * Flip a kill switch, the way an operator would at 2am.
     *
     * Deliberately the same `FeatureManager` the application reads, and the same durable
     * repository behind it — so this is not a demo shortcut past the mechanism, it is the
     * mechanism. The flip is written to the database and the next request sees it.
     */
    @PostMapping("/flags/{flag}")
    public Map<String, Object> flip(@PathVariable String flag, @RequestBody FlagState state) {
        NamedFeature feature = new NamedFeature(flag);
        features.setFeatureState(new FeatureState(feature, state.enabled()));
        log.warn("DEMO CONTROL — kill switch {} is now {}", flag, state.enabled() ? "ON" : "OFF");
        return Map.of("flag", flag, "enabled", features.isActive(feature));
    }

    @GetMapping("/flags")
    public List<Map<String, Object>> flags() {
        return java.util.Arrays.stream(in.yesmadam.botin.platform.safety.BotinFeature.values())
                .map(f -> Map.<String, Object>of(
                        "flag", f.name(),
                        "enabled", features.isActive(new NamedFeature(f.name()))))
                .toList();
    }

    /**
     * Back to the fixture file's opening position, so the script can be run again.
     *
     * =====================================================================
     * IT CLEARS THE LEDGER TOO, AND THAT NEEDS JUSTIFYING
     * =====================================================================
     *
     * The duplicate-payment guard is DURABLE by design: an attempt row commits before the
     * external call and outlives everything, which is exactly what makes a retry safe. So
     * the second run of the demo script found the first run's rows and correctly refused to
     * pay again — the very first credit came back as "already credited", and a case meant to
     * show a gateway failure showed a duplicate instead.
     *
     * The guard was right. The demo was wrong to assume a clean world without asking for one.
     *
     * DELETING ROWS IS A SERIOUS CONTROL and it is confined accordingly: this endpoint only
     * exists under the `demo` profile, and that profile refuses to start beside a real UAT
     * datasource. What it erases is a synthetic ledger in a database that cannot contain
     * anything else. The alternative — a demo runnable exactly once per restart — is the
     * kind of friction that ends with nobody running it.
     *
     * CHILDREN BEFORE PARENTS, AND THERE ARE TWO CHILDREN. This comment used to read
     * "actions before tickets: the foreign key points that way" and named only one of them.
     * escalation_context carries the same foreign key to ticket, so a reset attempted after
     * anything had escalated threw on `delete from ticket`, reset NOTHING, and left every
     * scenario after it running against a dirty world.
     *
     * IT PASSED FOR A YEAR ON EXECUTION ORDER. Surefire happened to run the demo tests before
     * the two that escalate, so a reset never met a row that referenced a ticket. Renaming the
     * packages in the L1/L2 restructure reshuffled that order and the luck ran out. The demo
     * suite was never order-independent; it only looked that way.
     */
    @PostMapping("/reset")
    public Map<String, Object> reset() throws Exception {
        gateway.reset();
        long clearedActions = actions.count();
        long clearedTickets = tickets.count();
        long clearedEscalations = escalations.count();
        escalations.deleteAllInBatch();   // FK: escalation_context -> ticket
        actions.deleteAllInBatch();       // FK: ticket_action      -> ticket
        tickets.deleteAllInBatch();
        fixtures.load();
        log.warn("DEMO CONTROL — gateway reset, fixtures reseeded, {} escalation row(s), {} "
               + "action row(s) and {} ticket(s) cleared so the run starts from a known world",
                clearedEscalations, clearedActions, clearedTickets);
        return Map.of("reset", true, "clearedEscalations", clearedEscalations,
                      "clearedActions", clearedActions, "clearedTickets", clearedTickets);
    }

    /**
     * WHAT THE TICKET SAYS, WHICH IS NOT WHAT THE PARTNER WAS TOLD.
     *
     * Every T3 ends with the same sentence to the partner, by design — a partner has no use
     * for our vocabulary. So "why did this go to a human" is only answerable from the row,
     * and these two endpoints are how the demo asks.
     *
     * THEY WERE MISSING. tools/demo-run.sh has called them since it was written; the three
     * assertions that use them — the T0 ticket count, the cap escalation's cause and tier —
     * were reading a 404 body and comparing null against a string. A check that cannot pass
     * is worse than no check: it occupies the place where a real one would go.
     */
    @GetMapping("/tickets/{spId}/count")
    public Map<String, Object> ticketCount(@PathVariable String spId) {
        return Map.of("spId", spId, "tickets", tickets.countBySpId(spId));
    }

    @GetMapping("/tickets/{spId}/latest")
    public Map<String, Object> latestTicket(@PathVariable String spId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spId", spId);
        tickets.findTop10BySpIdOrderByCreatedAtDesc(spId).stream().findFirst().ifPresentOrElse(t -> {
            out.put("ticketId", String.valueOf(t.getId()));
            out.put("tier", String.valueOf(t.getTier()));
            out.put("action", String.valueOf(t.getDmnAction()));
            out.put("status", String.valueOf(t.getStatus()));
            out.put("triggerReason", String.valueOf(t.getTriggerReason()));
        }, () -> out.put("ticketId", null));
        return out;
    }

    public record GatewayState(String status, BigDecimal amountRupees) { }
    public record FlagState(boolean enabled) { }
}
