package in.yesmadam.botin.facts;

import org.springframework.stereotype.Component;

/**
 * PLAN STEP 49 — the derived Transport path, as one named fact in one place.
 *
 * ====================================================================
 * THIS IS AN ASSUMPTION. IT IS NOT A DOCUMENTED RULE.
 * ====================================================================
 *
 * The concern mapping labels Transport rules 2 to 8 as Path 1, Path 2 and Path 3, but
 * CONFIRMED BY THE DECISION MATRIX (rows 2-9), which was previously an assumption:
 *   Path 1 = customer-paid transport charged but not in the SP wallet
 *   Path 2 = cancellation cases (arrived / not arrived, NR / by agent / CR)
 *   Path 3 = distance beyond the hub radius
 * This code already did exactly that. DEFERRED D-C is closed.
 *
 * The original note, kept because it explains why the method exists at all: the mapping
 * nowhere said what assigns a claim to a path. Without a selector the paths are not
 * mutually exclusive, and under a FIRST hit policy that is not a visible conflict — the
 * first matching row simply wins. A claim that was cancelled by an agent AND lies
 * outside the hub radius matches both rule 4 and rule 8, which pay different amounts,
 * and the table would quietly pick whichever sits higher.
 *
 * The reading taken here:
 *   PATH_1  a customer transport charge exists that never reached the partner's wallet
 *   PATH_2  the booking was cancelled — who cancelled it decides the rest
 *   PATH_3  neither, so this is ordinary travel, judged against the hub radius
 *
 * It lives alone, in one method, with its own test, so replacing it with the real rule
 * is a single edit rather than a search.
 */
@Component
public class TransportPathSelector {

    public static final String PATH_1 = "PATH_1";
    public static final String PATH_2 = "PATH_2";
    public static final String PATH_3 = "PATH_3";

    public String select(Boolean customerTransportCharged,
                         Boolean alreadyCredited,
                         String cancellationStatus) {

        if (Boolean.TRUE.equals(customerTransportCharged) && !Boolean.TRUE.equals(alreadyCredited)) {
            return PATH_1;
        }
        if (cancellationStatus != null && !cancellationStatus.isBlank()) {
            return PATH_2;
        }
        return PATH_3;
    }
}
