package in.yesmadam.botin.concern.amount.transport;

import in.yesmadam.botin.platform.facts.ConcernFactProvider;
import in.yesmadam.botin.platform.facts.FactRequest;
import in.yesmadam.botin.platform.facts.UatColumn;
import in.yesmadam.botin.shared.geo.GeoService;
import in.yesmadam.botin.shared.geo.HubGeometry;
import in.yesmadam.botin.shared.ledger.DuplicateCreditGuard;
import in.yesmadam.botin.shared.ledger.LedgerEntry;
import in.yesmadam.botin.surface.demo.DemoFixtures;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * TRANSPORT_NOT_RECEIVED — 5,615/month, the highest-volume concern in the build and the
 * only one that pays on three different bases.
 *
 * CROSSES TWO CATALOGS. The booking lives in ysmdm_users, the partner's wallet ledger in
 * ysmdm_admin. Both are on one MySQL instance, so the SQL qualifies the catalog and one
 * connection serves both.
 *
 * selectedReference is the ORDER ID. Without it nothing can be looked up, and the facts
 * come back all-null — which the table sends to a human rather than guessing.
 */
@Component
public class TransportFactProvider implements ConcernFactProvider {

    private static final Logger log = LoggerFactory.getLogger(TransportFactProvider.class);

    /** The booking. arrivedAt300M is a TIMESTAMP — non-null means the partner arrived. */
    private static final String ORDER_SQL = """
            SELECT transport_charges, arrived_at300_m, unassign_status_code,
                   order_status_code, cashback, hub_id
            FROM ysmdm_users.tbl_order WHERE order_id = ?""";

    /** The wallet ledger for this order. CREDIT and DEBIT both occur — see the net rule. */
    private static final String LEDGER_SQL = """
            SELECT action, subaction, amount
            FROM ysmdm_admin.tbl_sp_tranactions
            WHERE orderid = ? AND subaction = 'TRANSPORT'""";

    /** The hub's position, and the slab ends its radius is derived from. */
    private static final String HUB_SQL = """
            SELECT h.lat, h.lng FROM ysmdm_admin.tbl_hub h WHERE h.id = ?""";
    private static final String SLABS_SQL = """
            SELECT t.end FROM ysmdm_admin.tbl_servicehub_transportation t WHERE t.hub_id = ?""";

    private final JdbcTemplate uat;   // null when botin.uat.enabled=false
    private final TransportPathSelector pathSelector;
    private final DuplicateCreditGuard creditGuard;
    private final GeoService geo;
    /** Present only under the `demo` profile. Null everywhere else. */
    private final DemoFixtures demo;
    /** Rs50 per km beyond the radius, in paise. Confirmed by the Decision Matrix. */
    private final long ratePerKmPaise;

    public TransportFactProvider(@Autowired(required = false) @Qualifier("uatJdbcTemplate") JdbcTemplate uat,
                                 TransportPathSelector pathSelector,
                                 DuplicateCreditGuard creditGuard,
                                 GeoService geo,
                                 @Autowired(required = false) DemoFixtures demo,
                                 @Value("${botin.transport.rate-per-km-paise:5000}") long ratePerKmPaise) {
        this.ratePerKmPaise = ratePerKmPaise;
        this.demo = demo;
        this.uat = uat;
        this.pathSelector = pathSelector;
        this.creditGuard = creditGuard;
        this.geo = geo;
    }

    @Override public String concernCode() { return "TRANSPORT_NOT_RECEIVED"; }

    @Override public Set<String> factKeys() {
        return Set.of("alreadyCredited", "computedAmountPaise", "transportPath",
                      "arrivedAt300metre", "cancellationStatus",
                      "lastMinCashbackCredited", "distanceBeyondRadiusKm");
    }

    @Override public List<UatColumn> requiredColumns() {
        return List.of(
            UatColumn.confirmed("ysmdm_users", "tbl_order", "order_id"),
            UatColumn.confirmed("ysmdm_users", "tbl_order", "transport_charges"),
            UatColumn.confirmed("ysmdm_users", "tbl_order", "order_status_code"),
            UatColumn.confirmed("ysmdm_users", "tbl_order", "unassign_status_code"),
            UatColumn.confirmed("ysmdm_users", "tbl_order", "cashback"),
            UatColumn.confirmed("ysmdm_users", "tbl_order", "hub_id"),
            // camelCase field with no @Column in empapi — physical name INFERRED.
            UatColumn.inferred ("ysmdm_users", "tbl_order", "arrived_at300_m"),
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_tranactions", "action"),
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_tranactions", "subaction"),
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_tranactions", "orderid"),
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_tranactions", "amount"),
            // GeoCode is @Embeddable on tbl_hub — column names INFERRED.
            UatColumn.inferred ("ysmdm_admin", "tbl_hub", "lat"),
            UatColumn.inferred ("ysmdm_admin", "tbl_hub", "lng"),
            UatColumn.confirmed("ysmdm_admin", "tbl_servicehub_transportation", "end"));
    }

    @Override
    public Map<String, Object> fetchFacts(FactRequest request) {
        Map<String, Object> facts = emptyFacts();
        if (request.selectedReference() == null) return facts;

        // NO UAT? THE DEMO FIXTURES ANSWER, IF THIS IS A DEMO. Same precedent as the
        // recharge provider: the alternative is a decision layer that is fully built,
        // fully tested and completely invisible until a read grant lands.
        //
        // The fixtures supply RAW ROWS. The path selector and the duplicate-credit guard
        // below run for real against them, because those derivations are the thing worth
        // showing — a fixture handing over a finished transportPath would demonstrate
        // nothing but the fixture file.
        if (uat == null) {
            if (demo == null) {
                log.debug("transport facts unavailable — no UAT and no demo fixtures");
                return facts;
            }
            return demo.transportOrder(request.selectedReference())
                    .map(order -> derive(
                            order.transportChargesPaise() > 0,
                            creditGuard.alreadyCredited(order.ledgerEntries(), "TRANSPORT"),
                            order.arrivedAt300m(),
                            cancellationOf(order.unassignCode()),
                            order.cashbackPaise() > 0,
                            order.distanceBeyondRadiusKm(),
                            order.computedAmountPaise()))
                    .orElse(facts);
        }

        Long orderId = asLong(request.selectedReference());
        if (orderId == null) return facts;

        Map<String, Object> order = uat.query(ORDER_SQL,
                rs -> rs.next() ? Map.of(
                        "transportCharges", (Object) rs.getInt("transport_charges"),
                        "arrivedAt300M",    rs.getTimestamp("arrived_at300_m") != null,
                        "unassignCode",     rs.getInt("unassign_status_code"),
                        "cashback",         rs.getInt("cashback"),
                        "hubId",            rs.getInt("hub_id"))
                    : null, orderId);
        if (order == null) return facts;

        // Net position, never EXISTS(CREDIT): DEBIT/TRANSPORT is real, so a reversed
        // credit must not read as paid. See DuplicateCreditGuard.
        List<LedgerEntry> ledger = uat.query(LEDGER_SQL,
                (rs, i) -> new LedgerEntry(rs.getString("action"), rs.getString("subaction"),
                        rs.getLong("amount")), orderId);
        boolean alreadyCredited = creditGuard.alreadyCredited(ledger, "TRANSPORT");

        String cancellationStatus = cancellationOf((Integer) order.get("unassignCode"));
        boolean charged = ((Integer) order.get("transportCharges")) > 0;

        Double beyondKm = beyondRadius((Integer) order.get("hubId"));
        Integer transportCharges = (Integer) order.get("transportCharges");
        String path = pathSelector.select(charged, alreadyCredited, cancellationStatus);

        return derive(charged, alreadyCredited, (Boolean) order.get("arrivedAt300M"),
                cancellationStatus, ((Integer) order.get("cashback")) > 0,
                beyondKm, amountFor(path, transportCharges, beyondKm));
    }

    /**
     * WHAT THE CLAIM IS WORTH — previously left null, now computed.
     *
     * =========================================================================
     * THE RULE, FROM THE DECISION MATRIX
     * =========================================================================
     *
     *   Path 1 / Path 2 — the partner should receive the transport charge the CUSTOMER
     *                     paid. The amount is already on the order; nothing is computed.
     *   Path 3         — "Credit (distance − radius) × Rs50". `distanceBeyondRadiusKm` is
     *                     already measured from the radius EDGE, so the subtraction has
     *                     happened before this method sees it.
     *
     * The Rs300 cap is NOT applied here, deliberately. The cap is a row in the decision
     * table, above every paying row, and it produces a TICKET rather than a smaller
     * payment. Clamping here would turn a handover into a silent underpayment that nobody
     * ever sees — which is the one thing that row exists to prevent.
     *
     * =========================================================================
     * THE ASSUMPTION THAT REMAINS, ISOLATED HERE
     * =========================================================================
     *
     * The matrix marks partial-kilometre handling as OPEN: prorate, round up, or round
     * down. This prorates — the literal reading of "(distance − radius) × Rs50", and the
     * only one of the three that invents nothing. If the answer is rounding, it is a change
     * to `kilometresCharged` and nowhere else.
     */
    private Long amountFor(String path, Integer transportChargesPaise, Double beyondRadiusKm) {
        if (TransportPathSelector.PATH_3.equals(path)) {
            if (beyondRadiusKm == null || beyondRadiusKm <= 0) return null;
            return Math.round(kilometresCharged(beyondRadiusKm) * ratePerKmPaise);
        }
        // Paths 1 and 2 pay what the customer was charged. Null when we cannot read it —
        // never zero, because zero is an amount and null is an admission.
        return transportChargesPaise == null ? null : transportChargesPaise.longValue();
    }

    /** OPEN: prorate / round up / round down. Prorating is the only option that assumes nothing. */
    private double kilometresCharged(double beyondRadiusKm) {
        return beyondRadiusKm;
    }

    /**
     * ONE DERIVATION, both sources. The real read and the demo fixture differ in where the
     * row came from and in nothing else — which is the only way a demo is worth watching.
     */
    private Map<String, Object> derive(boolean customerCharged, boolean alreadyCredited,
                                       Boolean arrivedAt300m, String cancellationStatus,
                                       boolean cashbackCredited, Double beyondRadiusKm,
                                       Long computedAmountPaise) {
        Map<String, Object> facts = emptyFacts();
        facts.put("alreadyCredited", alreadyCredited);
        facts.put("arrivedAt300metre", arrivedAt300m);
        facts.put("cancellationStatus", cancellationStatus);
        facts.put("lastMinCashbackCredited", cashbackCredited);
        facts.put("transportPath", pathSelector.select(customerCharged, alreadyCredited, cancellationStatus));
        facts.put("distanceBeyondRadiusKm", beyondRadiusKm);
        facts.put("computedAmountPaise", computedAmountPaise);
        return facts;
    }

    /**
     * UnAssignStatus in the live enum: REJECT=1, CANCEL=2, UNASSIGN=3,
     * UNASSIGN_WITH_REMOVE_SP=4. The decision table speaks in NR / BY_AGENT / CR.
     *
     * MAPPING UNCONFIRMED. tbl_order also carries nrTicketCount and crTicketCount, so NR
     * and CR exist as first-class concepts somewhere other than this column. Until the
     * mapping is confirmed, only the cases that are unambiguous are named; anything else
     * stays null and reaches a human.
     */
    private String cancellationOf(Integer unassignCode) {
        if (unassignCode == null || unassignCode == 0) return null;
        return switch (unassignCode) {
            case 3, 4 -> "CANCELLED_BY_AGENT";   // unassigned by the business
            default   -> null;                   // TODO(confirm): which code is NR, which CR
        };
    }

    private Double beyondRadius(Integer hubId) {
        if (hubId == null) return null;
        List<Double> slabEnds = uat.query(SLABS_SQL, (rs, i) -> rs.getDouble("end"), hubId);
        Double radiusKm = HubGeometry.radiusFromSlabEnds(slabEnds);
        if (radiusKm == null) return null;

        HubGeometry hub = uat.query(HUB_SQL,
                rs -> rs.next() ? HubGeometry.of(rs.getString("lat"), rs.getString("lng"), radiusKm)
                                : null, hubId);
        if (hub == null) return null;

        // THE HUB IS KNOWN; THE JOB IS NOT. tbl_order carries no latitude or longitude.
        // tbl_fifty_metre_radius_log has lat/lng per order_id, but those record where the
        // PARTNER was, not where the job is — measuring hub-to-partner would pay for the
        // distance they happened to be standing at, which is not the rule.
        //
        // So Path 3 cannot be measured yet and returns null, not zero. Zero would read as
        // "inside the hub" and DENY every Path 3 claim without assessing one.
        // TODO(schema): where is the job's own latitude/longitude?
        log.debug("hub {} geometry resolved (radius {} km) but the job location is not "
                + "available — Path 3 cannot be measured", hubId, hub.radiusKm());
        return null;
    }

    private static Long asLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }
}
