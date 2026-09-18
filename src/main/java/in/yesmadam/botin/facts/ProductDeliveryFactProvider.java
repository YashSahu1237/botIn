package in.yesmadam.botin.facts;

import in.yesmadam.botin.demo.DemoFixtures;
import in.yesmadam.botin.facts.shared.DeliveryTatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * PROD_DELIVERY_DELAY — 2,306/month, second highest in the build. "Where is my order?"
 *
 * Reads the SHARED TAT service, and that is the point rather than a convenience. The
 * parked VIOL_R9_NO_PRODUCT reads the same one. If the two ever computed "late"
 * differently, the same partner would be told their order is on time AND that they should
 * already have had it.
 *
 * EXPRESS IS AN ATTRIBUTE OF THE ORDER — corrected. This provider previously decided it by
 * looking the partner's pincode up in a settings list, which was a guess, a plausible one,
 * and wrong. The Decision Matrix lists "order type (Express/Standard)" as a backend check.
 * The column name is still inferred; an unreadable value degrades to Standard, which can
 * only ever tell a partner to wait longer than they should, never to call an on-time order
 * late.
 */
@Component
public class ProductDeliveryFactProvider implements ConcernFactProvider {

    private static final Logger log = LoggerFactory.getLogger(ProductDeliveryFactProvider.class);

    /** The partner's most recent product order. delivered_at non-null means it arrived. */
    private static final String ORDER_SQL = """
            SELECT created_at, delivered_at, order_status_code, order_type
            FROM ysmdm_admin.tbl_sp_order
            WHERE spid = ? ORDER BY id DESC LIMIT 1""";


    private final JdbcTemplate uat;
    private final DeliveryTatService tat;
    /** Present only under the `demo` profile. Null everywhere else. */
    private final DemoFixtures demo;

    public ProductDeliveryFactProvider(@Autowired(required = false) @Qualifier("uatJdbcTemplate") JdbcTemplate uat,
                                       DeliveryTatService tat,
                                       @Autowired(required = false) DemoFixtures demo) {
        this.uat = uat;
        this.tat = tat;
        this.demo = demo;
    }

    @Override public String concernCode() { return "PROD_DELIVERY_DELAY"; }

    @Override public Set<String> factKeys() { return Set.of("pastElevenPmDeadline"); }

    @Override public List<UatColumn> requiredColumns() {
        return List.of(
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_order", "spid"),
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_order", "created_at"),
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_order", "delivered_at"),
            UatColumn.confirmed("ysmdm_admin", "tbl_sp_order", "order_status_code"),
            // EXPRESS IS AN ATTRIBUTE OF THE ORDER, confirmed by the Decision Matrix —
            // not a pincode lookup, which is what this provider used to do. The COLUMN
            // NAME is still inferred: the matrix names the concept ("order type
            // Express/Standard"), not the column. UatSchemaProbe reports the truth at
            // startup, and until it is confirmed an unreadable value degrades to Standard.
            UatColumn.inferred("ysmdm_admin", "tbl_sp_order", "order_type"));
    }

    @Override
    public Map<String, Object> fetchFacts(FactRequest request) {
        Map<String, Object> facts = emptyFacts();

        // NO UAT? THE DEMO FIXTURES ANSWER, IF THIS IS A DEMO.
        //
        // The fixture says "placed N days ago" rather than giving a date, and the REAL TAT
        // service does the rest. A fixture carrying a fixed timestamp would demonstrate one
        // thing this week and a different thing next month — silently, and the first person
        // to notice would be standing in front of an audience.
        if (uat == null) {
            if (demo == null) return facts;
            return demo.productOrder(request.spId())
                    .map(order -> {
                        Map<String, Object> demoFacts = emptyFacts();
                        demoFacts.put("pastElevenPmDeadline", tat.isPastTat(
                                ZonedDateTime.now(DeliveryTatService.ZONE).minusDays(order.placedDaysAgo()),
                                false));   // Express is undeterminable — see below
                        return demoFacts;
                    })
                    .orElse(facts);
        }

        Map<String, Object> order = uat.query(ORDER_SQL,
                rs -> {
                    if (!rs.next()) return null;
                    Map<String, Object> row = new HashMap<>();
                    row.put("placedAt", rs.getTimestamp("created_at"));
                    row.put("orderType", rs.getString("order_type"));
                    return row;
                }, request.spId());

        Timestamp placedAt = order == null ? null : (Timestamp) order.get("placedAt");

        if (placedAt == null) {
            // No order at all, or none we could read. NOT "on time" — null, which the
            // table sends to a human. Telling a partner with no order to keep waiting is
            // worse than admitting we do not know.
            log.debug("no product order found for {}", request.spId());
            return facts;
        }

        // EXPRESS COMES OFF THE ORDER, which is the correction the Decision Matrix forced.
        //
        // ANYTHING WE CANNOT READ IS TREATED AS STANDARD, and that is a deliberate choice
        // rather than a fallback: Standard is the slower TAT, so an unreadable order type
        // can only ever make us tell a partner to wait LONGER than they should — never to
        // call an on-time order late. Wrong in the direction that costs patience rather
        // than credibility.
        boolean express = "EXPRESS".equalsIgnoreCase(String.valueOf(order.get("orderType")));

        ZonedDateTime placed = placedAt.toInstant().atZone(DeliveryTatService.ZONE);
        facts.put("pastElevenPmDeadline", tat.isPastTat(placed, express));
        return facts;
    }

}
