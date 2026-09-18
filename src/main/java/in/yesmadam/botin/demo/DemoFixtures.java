package in.yesmadam.botin.demo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.facts.shared.LedgerEntry;
import in.yesmadam.botin.payu.MockPayUGateway;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * PLAN STEP 96 — THE SYNTHETIC WORLD THE DEMO RUNS IN.
 *
 * =========================================================================
 * WHY THIS EXISTS AT ALL
 * =========================================================================
 *
 * Six of the eight concerns decide by reading UAT, and there is no read grant yet. With UAT
 * off every fact provider returns nulls, every table lands on its catch-all, and the demo
 * shows eight different ways of saying "a person will look at this". The decision layer would
 * be completely built, completely tested, and completely invisible.
 *
 * So the demo gets a world: order rows, ledger rows and gateway states, shaped exactly like
 * the UAT rows they stand in for.
 *
 * =========================================================================
 * THE RULE THAT MATTERS MORE THAN THE FIXTURES
 * =========================================================================
 *
 * A synthetic fact must never be mistaken for a real one. Three things enforce that, and none
 * of them is a comment:
 *
 *   1. THIS BEAN ONLY EXISTS UNDER THE `demo` PROFILE. Not a flag, not a property that could
 *      be present-but-empty — a profile, which is on or off.
 *   2. IT REFUSES TO START ALONGSIDE UAT. If a real datasource is configured and this bean is
 *      loaded, the application FAILS AT STARTUP rather than serving a mixture. Half-real data
 *      is worse than either kind: nobody can tell afterwards which decisions were evidence.
 *   3. EVERY READ LOGS THAT IT IS A FIXTURE. Anyone watching a log during a demo can see which
 *      answers came from where, which is exactly what a sceptical reviewer should be asking.
 *
 * =========================================================================
 * WHAT THE FIXTURES DO AND DO NOT SUPPLY
 * =========================================================================
 *
 * They supply RAW ROWS, not conclusions. The path selector, the duplicate-credit guard and the
 * TAT service all run for real against them — those derivations are the thing worth showing,
 * and a fixture that handed over a finished `transportPath` would demonstrate nothing but the
 * fixture file.
 *
 * The one exception is `computedAmountPaise`, which the real provider leaves null because the
 * rate question is unanswered (DEFERRED D-B). The fixture supplies a figure DIRECTLY and it is
 * labelled as invented, because the cap and the rate are two different questions: showing that
 * an amount over the cap becomes a ticket rather than a clamped payment does not require the
 * amount to be right, it requires an amount to exist.
 *
 * TIMES ARE RELATIVE, NEVER ABSOLUTE. A product order is "placed five days ago", not placed on
 * a date. A fixture with a fixed timestamp demonstrates one thing this week and a different
 * thing next month, silently, and the first person to notice will be in front of an audience.
 */
@Component
@Profile("demo")
public class DemoFixtures {

    private static final Logger log = LoggerFactory.getLogger(DemoFixtures.class);
    private static final String FILE = "demo/fixtures.json";

    private final ObjectMapper json;
    private final MockPayUGateway gateway;
    private final JdbcTemplate uat;

    private final Map<String, TransportOrder> transportByOrderId = new LinkedHashMap<>();
    private final Map<String, ProductOrder> productBySpId = new LinkedHashMap<>();
    private List<Recharge> recharges = List.of();

    public DemoFixtures(ObjectMapper json, MockPayUGateway gateway,
                        @Autowired(required = false) @Qualifier("uatJdbcTemplate") JdbcTemplate uat) {
        this.json = json;
        this.gateway = gateway;
        this.uat = uat;
    }

    @PostConstruct
    void load() throws Exception {
        // FAIL, LOUDLY, RATHER THAN SERVE A MIXTURE. If this ever starts beside a real
        // datasource, some answers in the run are evidence and some are invented and nothing
        // afterwards can separate them. A demo that cannot be trusted is worse than no demo.
        if (uat != null) {
            throw new IllegalStateException(
                "The 'demo' profile is active AND the UAT datasource is configured. Demo "
              + "fixtures and real data must never be readable in the same run — a reader "
              + "would have no way to tell which facts were evidence. Run the demo with "
              + "UAT_ENABLED=false, or run against UAT without the demo profile.");
        }

        Fixtures fixtures = json.readValue(new ClassPathResource(FILE).getInputStream(), Fixtures.class);

        fixtures.transportOrders().forEach(o -> transportByOrderId.put(o.orderId(), o));
        fixtures.productOrders().forEach(o -> productBySpId.put(o.spId(), o));
        this.recharges = fixtures.recharges();

        // The gateway is a fixture source too, so it is seeded from the SAME file. One
        // description of the synthetic world; three of them drift.
        for (Recharge r : recharges) {
            gateway.setRecharge(r.orderId(), r.status(), r.amountPaise());
        }

        log.warn("""

            ===========================================================================
              DEMO PROFILE ACTIVE — EVERY FACT BELOW IS SYNTHETIC
              {} transport orders, {} product orders, {} gateway recharges
              Nothing here is read from a live system. Do not quote a number from this
              run as evidence about real partners.
            ===========================================================================""",
            transportByOrderId.size(), productBySpId.size(), recharges.size());
    }

    /** @return the stand-in for the tbl_order row, or empty if this demo has no such order. */
    public Optional<TransportOrder> transportOrder(String orderId) {
        TransportOrder order = transportByOrderId.get(orderId);
        if (order == null) {
            log.warn("DEMO FIXTURE MISS — no transport order '{}'. The concern will reach a "
                   + "human, correctly, because nothing is known about it", orderId);
            return Optional.empty();
        }
        log.warn("DEMO FIXTURE — transport order {} ({})", orderId, order.demonstrates());
        return Optional.of(order);
    }

    /** @return the stand-in for the partner's most recent tbl_sp_order row. */
    public Optional<ProductOrder> productOrder(String spId) {
        ProductOrder order = productBySpId.get(spId);
        if (order == null) {
            log.warn("DEMO FIXTURE MISS — no product order for '{}'", spId);
            return Optional.empty();
        }
        log.warn("DEMO FIXTURE — product order for {} ({})", spId, order.demonstrates());
        return Optional.of(order);
    }

    public List<Recharge> recharges() { return recharges; }
    public Collection<TransportOrder> transportOrders() { return transportByOrderId.values(); }
    public Collection<ProductOrder> productOrders() { return productBySpId.values(); }

    // ------------------------------------------------------------------ the shapes

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fixtures(List<Recharge> recharges,
                           List<TransportOrder> transportOrders,
                           List<ProductOrder> productOrders) {}

    /** A PayU transaction, as tbl_payu_transaction_details_for_sp would carry it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recharge(String orderId, String spId, String status, long amountPaise,
                           String demonstrates) {}

    /**
     * A tbl_order row plus its wallet ledger, in the same shape the provider reads.
     *
     * `computedAmountPaise` is the invented one — see the class comment. Everything else
     * here has a real column behind it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransportOrder(String orderId, String spId,
                                 int transportChargesPaise,
                                 boolean arrivedAt300m,
                                 int unassignCode,
                                 int cashbackPaise,
                                 Double distanceBeyondRadiusKm,
                                 Long computedAmountPaise,
                                 List<Ledger> ledger,
                                 String demonstrates) {

        public List<LedgerEntry> ledgerEntries() {
            return ledger == null ? List.of()
                    : ledger.stream().map(l -> new LedgerEntry(l.action(), l.subaction(), l.amountPaise()))
                            .toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ledger(String action, String subaction, long amountPaise) {}

    /** RELATIVE, never a date. See the class comment — this is the whole reason. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductOrder(String spId, int placedDaysAgo, String demonstrates) {}
}
