package in.yesmadam.botin.reconcile;

import in.yesmadam.botin.payu.MockPayUGateway;
import in.yesmadam.botin.safety.TicketAction;
import in.yesmadam.botin.safety.TicketActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * PLAN STEP 61 — DOES WHAT WE BELIEVE WE PAID MATCH WHAT ACTUALLY MOVED?
 *
 * =========================================================================
 * WHY THIS IS THE LAST SAFETY CHECK, NOT AN EXTRA ONE
 * =========================================================================
 *
 * Everything before this asks whether a payment was DECIDED correctly. The cap escalates
 * instead of clamping. The duplicate guard refuses a second credit. The attempt row commits
 * before the call so a crash cannot erase the evidence. All of it is about doing the right
 * thing at the moment of paying.
 *
 * None of it answers the question an auditor asks: **is our record of what we paid the same
 * as what left the building?** Two systems agreeing at the moment of a transaction is not
 * the same as two systems agreeing a week later, and the gap between them is where money
 * quietly disappears.
 *
 * =========================================================================
 * THE TWO DIRECTIONS ARE NOT EQUALLY INTERESTING
 * =========================================================================
 *
 * WE SAY WE PAID, THE GATEWAY DID NOT — bad, and visible: a partner complains that the money
 * they were promised never arrived, and our ticket says it was credited. Somebody finds out.
 *
 * THE GATEWAY PAID, WE HAVE NO RECORD — worse, and silent. Nobody complains about money they
 * received. There is no partner to raise it, no ticket to look at, and no amount of reading
 * our own tables will ever show it, because the evidence is entirely on the other side. That
 * is the direction this job exists for, and it is why it reads the gateway's list rather than
 * just checking our own rows against it.
 *
 * =========================================================================
 * WHAT IT DOES NOT DO
 * =========================================================================
 *
 * It does not correct anything. A reconciliation job that fixes what it finds is a second,
 * unsupervised payment path — and one that runs without anybody watching, on exactly the
 * cases nobody understood the first time. It reports. A person decides.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** Actions that move money. A state change with no amount has nothing to reconcile. */
    private static final Set<String> MOVES_MONEY = Set.of(
            "AUTO_CREDIT_WALLET", "AUTO_CREDIT_TRANSPORT", "AUTO_CREDIT_DISTANCE");

    private final TicketActionRepository actions;
    private final MockPayUGateway gateway;

    public ReconciliationService(TicketActionRepository actions, MockPayUGateway gateway) {
        this.actions = actions;
        this.gateway = gateway;
    }

    public Report reconcile() {
        Map<String, Long> ourLedger = new LinkedHashMap<>();
        List<Discrepancy> unrecorded = new ArrayList<>();

        long believedPaise = 0;
        int noAmount = 0;

        for (TicketAction a : actions.findByStatus(TicketAction.SUCCEEDED)) {
            if (!MOVES_MONEY.contains(a.getActionType())) continue;

            // A SUCCEEDED credit with no amount is its own finding. It means something paid a
            // partner and did not write down how much, which makes this job blind to it.
            if (a.getAmountPaise() == null) { noAmount++; continue; }
            if (a.getExternalReference() == null) { noAmount++; continue; }

            ourLedger.merge(a.getExternalReference(), a.getAmountPaise(), Long::sum);
            believedPaise += a.getAmountPaise();
        }

        Map<String, Long> theirLedger = gateway.allCredits();
        List<Discrepancy> mismatched = new ArrayList<>();

        for (Map.Entry<String, Long> ours : ourLedger.entrySet()) {
            long theirs = theirLedger.getOrDefault(ours.getKey(), 0L);
            if (theirs != ours.getValue()) {
                mismatched.add(new Discrepancy(ours.getKey(), ours.getValue(), theirs));
            }
        }

        // THE SILENT DIRECTION. Money the gateway moved that we have no row for.
        for (Map.Entry<String, Long> theirs : theirLedger.entrySet()) {
            if (theirs.getValue() != 0 && !ourLedger.containsKey(theirs.getKey())) {
                unrecorded.add(new Discrepancy(theirs.getKey(), 0L, theirs.getValue()));
            }
        }

        Report report = new Report(ourLedger.size(), believedPaise, mismatched, unrecorded, noAmount);
        if (report.clean()) {
            log.info("reconciliation clean — {} credit(s), {} paise, both ledgers agree",
                    report.creditsChecked(), report.believedPaise());
        } else {
            log.error("RECONCILIATION FOUND A GAP — {} mismatched, {} unrecorded by us, "
                    + "{} credit(s) with no amount recorded", mismatched.size(), unrecorded.size(), noAmount);
        }
        return report;
    }

    /**
     * @param mismatched  we and the gateway both know the reference and disagree on the amount
     * @param unrecorded  THE GATEWAY MOVED MONEY WE HAVE NO ROW FOR. The silent direction
     * @param creditsWithNoAmount a succeeded credit that never recorded its size — invisible here
     */
    public record Report(int creditsChecked, long believedPaise,
                         List<Discrepancy> mismatched, List<Discrepancy> unrecorded,
                         int creditsWithNoAmount) {

        public boolean clean() {
            return mismatched.isEmpty() && unrecorded.isEmpty() && creditsWithNoAmount == 0;
        }
    }

    public record Discrepancy(String externalReference, long weBelievePaise, long gatewaySaysPaise) { }
}
