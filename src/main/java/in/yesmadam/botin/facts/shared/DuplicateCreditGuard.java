package in.yesmadam.botin.facts.shared;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PLAN STEP 44. "Have we already paid this?" — answered by NET POSITION, not by the
 * existence of a credit.
 *
 * WHY THAT DISTINCTION MATTERS. UAT contains both:
 *
 *     CREDIT / TRANSPORT
 *     DEBIT  / TRANSPORT
 *
 * and the live source carries the string "Unused transport for ", so transport IS
 * clawed back in some cases. An EXISTS(CREDIT) check would tell a partner "already
 * paid" for money that was subsequently taken back — a refusal they cannot argue with
 * and we cannot defend.
 *
 * Net position is also the right shape for a partial claw-back: credit Rs200, debit
 * Rs50, net Rs150 still paid.
 */
@Service
public class DuplicateCreditGuard {

    /**
     * Net paise credited under one sub-action. Credits add, debits subtract.
     * Rows with a different sub-action are ignored entirely.
     */
    public long netPaise(List<LedgerEntry> ledger, String subaction) {
        if (ledger == null || subaction == null) return 0L;
        return ledger.stream()
                .filter(e -> subaction.equalsIgnoreCase(e.subaction()))
                .mapToLong(e -> e.isCredit() ?  e.amountPaise()
                              : e.isDebit()  ? -e.amountPaise()
                              : 0L)
                .sum();
    }

    /**
     * True when the partner is currently up on this sub-action.
     *
     * A net of exactly zero — credited then fully reversed — reads as NOT credited,
     * because from the partner's side the money is not there.
     */
    public boolean alreadyCredited(List<LedgerEntry> ledger, String subaction) {
        return netPaise(ledger, subaction) > 0L;
    }
}
