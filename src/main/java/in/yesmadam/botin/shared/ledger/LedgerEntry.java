package in.yesmadam.botin.shared.ledger;
import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
/**
 * One row of the partner's wallet ledger, as tbl_sp_tranactions stores it.
 *
 * The real column values, confirmed against UAT:  action = CREDIT | DEBIT,
 * subaction = TRANSPORT (among others).
 */
public record LedgerEntry(String action, String subaction, BigDecimal amountRupees) {

    public static final String CREDIT = "CREDIT";
    public static final String DEBIT  = "DEBIT";

    public boolean isCredit() { return CREDIT.equalsIgnoreCase(action); }
    public boolean isDebit()  { return DEBIT.equalsIgnoreCase(action); }
}
