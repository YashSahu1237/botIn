package in.yesmadam.botin.facts;

import org.springframework.stereotype.Component;

/**
 * Turns whatever the PayU column actually contains into a closed set the decision
 * table can compare against.
 *
 * WHY THIS EXISTS RATHER THAN LETTING THE TABLE READ THE RAW COLUMN.
 *
 * The real values in UAT, counted over the whole table:
 *
 *     success      74,796
 *     NULL         26,374      <- 26% of every row
 *     failure          36
 *     Not Found        11
 *
 * Three things follow, and each of them would have been a silent failure:
 *
 *   1. The values are LOWER CASE. The decision table was written against "SUCCESS"
 *      and "FAILED" from the concern mapping's prose. Neither would ever have
 *      matched, so the one row that credits a wallet was dead on arrival and every
 *      recharge claim would have fallen to an agent — with nothing to see but a
 *      rising agent queue.
 *
 *   2. THERE IS NO "PENDING". The mapping's first rule is "PayU = Pending -> create
 *      ticket". That state does not exist in the data. What does exist is NULL, on
 *      a quarter of all rows, which almost certainly means the same thing: a
 *      recharge was begun and no gateway callback was ever recorded against it.
 *      It is treated as its own outcome here and NOT as an error.
 *
 *   3. "Not Found" is a real status, not a bug. The gateway was asked about a
 *      transaction and had no record of it.
 *
 * Normalising in Java rather than in the table also avoids testing for null inside a
 * DMN input expression — and an input expression that fails takes the whole decision
 * down, catch-all included.
 */
@Component
public class PayuStatusNormaliser {

    /** The gateway confirmed the money was taken. */
    public static final String SUCCESS = "SUCCESS";

    /** The gateway confirmed the payment did not go through. */
    public static final String FAILED = "FAILED";

    /** The gateway has no record of the transaction at all. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** A recharge was started and nothing was ever written back. 26% of rows. */
    public static final String NO_RESPONSE = "NO_RESPONSE";

    /** Something new appeared in the column. Fail to a human, loudly enough to notice. */
    public static final String UNRECOGNISED = "UNRECOGNISED";

    public String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return NO_RESPONSE;
        }
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case "success" -> SUCCESS;
            case "failure", "failed" -> FAILED;
            case "not found", "not_found", "notfound" -> NOT_FOUND;
            default -> UNRECOGNISED;
        };
    }
}
