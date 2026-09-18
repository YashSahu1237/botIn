package in.yesmadam.botin.facts;

import in.yesmadam.botin.payu.MockPayUGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RECHARGE_DEBIT_NO_CREDIT — 883/month. The partner recharged, money left their account,
 * the wallet did not move.
 *
 * THE GATEWAY TABLE IS IN A THIRD CATALOG, ysmdm_employees.
 *
 * payuStatus is NORMALISED, never the raw column. The real values are lower case and
 * there is no "pending": success 74,796 | NULL 26,374 | failure 36 | Not Found 11. The
 * table was originally written against "SUCCESS"/"FAILED" from the concern mapping's
 * prose and would never have matched. See PayuStatusNormaliser.
 */
@Component
public class RechargeFactProvider implements ConcernFactProvider {

    private static final Logger log = LoggerFactory.getLogger(RechargeFactProvider.class);

    private static final String PAYU_SQL = """
            SELECT status FROM ysmdm_employees.tbl_payu_transaction_details_for_sp
            WHERE spId = ? AND orderId = ? ORDER BY id DESC LIMIT 1""";

    private final JdbcTemplate uat;
    private final PayuStatusNormaliser statuses;
    private final MockPayUGateway mockGateway;

    public RechargeFactProvider(@Autowired(required = false) @Qualifier("uatJdbcTemplate") JdbcTemplate uat,
                                PayuStatusNormaliser statuses,
                                MockPayUGateway mockGateway) {
        this.uat = uat;
        this.statuses = statuses;
        this.mockGateway = mockGateway;
    }

    @Override public String concernCode() { return "RECHARGE_DEBIT_NO_CREDIT"; }

    @Override public Set<String> factKeys() {
        return Set.of("payuStatus", "alreadyCredited", "amountPaise");
    }

    @Override public List<UatColumn> requiredColumns() {
        return List.of(
            // camelCase fields with no @Column — physical names INFERRED. MySQL is
            // case-insensitive about identifiers on most configurations, but the probe
            // checks information_schema, which is not.
            UatColumn.inferred("ysmdm_employees", "tbl_payu_transaction_details_for_sp", "sp_id"),
            UatColumn.inferred("ysmdm_employees", "tbl_payu_transaction_details_for_sp", "order_id"),
            UatColumn.confirmed("ysmdm_employees", "tbl_payu_transaction_details_for_sp", "status"));
    }

    @Override
    public Map<String, Object> fetchFacts(FactRequest request) {
        Map<String, Object> facts = emptyFacts();

        // `spSatisfied` IS GONE, and so is the rule that read it. The Decision Matrix
        // confirms the behaviour — "PayU = Failed and SP not satisfied → create ticket on
        // SP request" — and the system already delivers it: the bot answers, the partner
        // says it did not help, and trigger A escalates that same ticket to a person.
        // A table row as well would be a second mechanism for one behaviour.
        if (request.selectedReference() == null) return facts;

        // NO UAT? THE MOCK GATEWAY ANSWERS, AND SAYS SO LOUDLY.
        //
        // Without this the entire money path would be undemonstrable until a SELECT
        // grant lands — leaving the most safety-critical code in the system unexercised
        // for as long as that takes, which is exactly backwards. The warning is not
        // decoration: facts from a mock must never be mistaken for facts from a gateway,
        // and anyone reading a log during a demo needs to see which they are looking at.
        if (uat == null) {
            String orderId = request.selectedReference();
            log.warn("UAT IS OFF — recharge facts for order {} come from the MOCK gateway", orderId);
            facts.put("payuStatus", statuses.normalise(mockGateway.statusOf(orderId)));
            facts.put("alreadyCredited", mockGateway.creditedFor(orderId) > 0);
            facts.put("amountPaise", mockGateway.amountFor(orderId));
            return facts;
        }

        String raw = uat.query(PAYU_SQL, rs -> rs.next() ? rs.getString("status") : null,
                request.spId(), request.selectedReference());

        facts.put("payuStatus", statuses.normalise(raw));

        // alreadyCredited needs the wallet row that references this gateway transaction.
        // tbl_sp_tranactions carries paytmtrnxid and upitxnid but no confirmed PayU
        // reference column, and the sub-action value for a recharge credit has not been
        // observed. Left null rather than guessed: a wrong "not yet credited" pays twice.
        // TODO(confirm): which column links a wallet credit to a PayU transaction, and
        //                what is the sub-action value for a recharge?
        log.debug("recharge facts for {} ref={} -> {}", request.spId(),
                request.selectedReference(), facts.get("payuStatus"));
        return facts;
    }
}
