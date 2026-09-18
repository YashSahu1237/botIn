package in.yesmadam.botin.facts.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;

/**
 * PLAN STEP 43. The ONE definition of "is this product order late".
 *
 * TWO CONCERNS READ THIS AND THEY MUST NEVER DISAGREE:
 *
 *   PROD_DELIVERY_DELAY  - the partner asks where their order is      (2,306/month)
 *   VIOL_R9_NO_PRODUCT   - the partner is fined for not having it
 *
 * If those two ever compute "late" differently, the same partner is told their order
 * is on time AND that they should have had it. The concern mapping flags this
 * explicitly: R9 must use the shared TAT, NOT a flat three days. A later checkpoint
 * changes the rule here once and asserts both concerns move together.
 *
 * WHAT THE LIVE SYSTEM SAYS, from reading empapi:
 *
 *   - Express eligibility is decided by PINCODE, from tbl_settings under the key
 *     express_delivery_pincodes. It is NOT a flag on the order.
 *   - The existing ticketing concern is literally named
 *     TEEN_DIN_SE_JYADA_HO_GAYE_PRODUCT_NAHI_AAYA - "more than three days have
 *     passed" - which is evidence that three days is today's standard TAT.
 *
 * THE RULE, FROM THE DECISION MATRIX — no longer an assumption:
 *   "Express: same day if before 3 PM, next day if after. Standard: ~3 days. Deadline 11 PM."
 *
 * Two earlier readings were wrong and both erred the same way: Express as a flat one day,
 * and 11 PM as an ORDERING cut-off that pushed the clock to the next day rather than as the
 * hour an order becomes late. Both made orders look more on time than they were — the
 * forgiving direction, so nothing looked broken. It would simply have deflected complaints
 * that deserved a ticket.
 */
@Service
public class DeliveryTatService {

    /** Stated, never inherited from whatever timezone the server happens to be set to. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final int standardDays;
    private final int expressCutoffHour;
    private final int deadlineHour;

    public DeliveryTatService(@Value("${botin.tat.standard-days:3}") int standardDays,
                              @Value("${botin.tat.express-cutoff-hour:15}") int expressCutoffHour,
                              @Value("${botin.tat.deadline-hour:23}") int deadlineHour) {
        this.standardDays = standardDays;
        this.expressCutoffHour = expressCutoffHour;
        this.deadlineHour = deadlineHour;
    }

    // EXPRESS USED TO BE LOOKED UP BY PINCODE HERE, AND THAT WAS WRONG.
    //
    // The Decision Matrix lists "order type (Express/Standard)" as a backend check — it is
    // an ATTRIBUTE ON THE ORDER, not a property of where the partner lives. The pincode
    // list in tbl_settings was a guess, and a plausible one, which is exactly why it
    // survived three phases without anybody noticing.

    /**
     * The instant after which the order is late.
     *
     * =====================================================================
     * BOTH HALVES OF THIS WERE WRONG UNTIL THE DECISION MATRIX ARRIVED
     * =====================================================================
     *
     * The matrix says: "Express: same day if before 3 PM, next day if after.
     * Standard: ~3 days. Deadline 11 PM."
     *
     *   1. EXPRESS IS NOT A NUMBER OF DAYS. It is a same-day/next-day rule with a 3 PM
     *      boundary. The old code treated it as one day and a config value, which gave the
     *      right answer for an afternoon order and the wrong one for a morning order —
     *      telling a partner to keep waiting on the day their delivery was already due.
     *
     *   2. 11 PM IS THE DEADLINE, NOT AN ORDERING CUT-OFF. The old code read it as "nothing
     *      ships after 11 PM, so the clock starts tomorrow", which pushed every late-evening
     *      order a full day further out. The matrix reads it the other way: 11 PM on the due
     *      date is the moment the order becomes late.
     *
     * Both errors ran in the same direction — telling a partner their order was still on
     * time when it was not. That is the forgiving direction, so nothing would have looked
     * broken; it would just have quietly deflected complaints that deserved a ticket.
     */
    public ZonedDateTime deadline(ZonedDateTime placedAt, boolean express) {
        if (placedAt == null) return null;

        ZonedDateTime local = placedAt.withZoneSameInstant(ZONE);
        LocalDate due = express
                ? (local.getHour() < expressCutoffHour ? local.toLocalDate() : local.toLocalDate().plusDays(1))
                : local.toLocalDate().plusDays(standardDays);

        return due.atTime(deadlineHour, 0).atZone(ZONE);
    }

    /** The date a partner should be told to expect, for the T1 "please wait" reply. */
    public LocalDate expectedDeliveryDate(ZonedDateTime placedAt, boolean express) {
        ZonedDateTime d = deadline(placedAt, express);
        return d == null ? null : d.toLocalDate();
    }

    /**
     * Is the order past its TAT right now?
     *
     * @return null when the order date is unknown. Null is NOT false: false means "on
     *         time, tell them to wait", and saying that about an order we cannot date
     *         is a guess dressed as an answer. The decision tables send null to a human.
     */
    public Boolean isPastTat(ZonedDateTime placedAt, boolean express, ZonedDateTime now) {
        if (placedAt == null || now == null) return null;
        ZonedDateTime deadline = deadline(placedAt, express);
        return now.isAfter(deadline);
    }

    /** Convenience for callers that only have a delivered/not-delivered picture. */
    public Boolean isPastTat(ZonedDateTime placedAt, boolean express) {
        return isPastTat(placedAt, express, ZonedDateTime.now(ZONE));
    }

    public int standardDays()      { return standardDays; }
    public int expressCutoffHour() { return expressCutoffHour; }
    public int deadlineHour()      { return deadlineHour; }
}
