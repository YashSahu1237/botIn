package in.yesmadam.botin.concern.amount.recharge;

import in.yesmadam.botin.integration.payu.MockPayUGateway;
import in.yesmadam.botin.platform.action.ActionRequest;
import in.yesmadam.botin.platform.action.ActionResult;
import in.yesmadam.botin.platform.action.ActionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AUTO_CREDIT_WALLET — the first action in this system that moves money.
 *
 * The recharge case: the partner paid, the gateway took it, the wallet never moved. This
 * puts it right.
 *
 * ITS IDEMPOTENCY KEY IS THE ORDER ID, NOT THE TICKET ID (plan step 90), and that choice
 * is the whole reason this concern is safe to automate. Consider what actually happens:
 * a partner raises a failed recharge, the bot credits them, and a week later they raise
 * THE SAME recharge again — perhaps because the wallet balance confused them, perhaps
 * because someone told them to. That second contact is a new session and a new ticket.
 * A key built from our ticket id is different, the duplicate guard sees nothing, and we
 * pay them twice for one payment. The gateway's order id is identical in both, so the
 * guard fires and the partner is told they have already been credited.
 *
 * THE GUARD IS THE UNIQUE CONSTRAINT ON ticket_action.idempotency_key, not a SELECT. A
 * check-then-act would still double-pay under two concurrent requests, which is exactly
 * what an impatient partner tapping twice produces.
 */
@Component
public class WalletCreditService implements ActionService {

    private static final Logger log = LoggerFactory.getLogger(WalletCreditService.class);

    private final MockPayUGateway gateway;

    public WalletCreditService(MockPayUGateway gateway) {
        this.gateway = gateway;
    }

    @Override public String actionCode() { return "AUTO_CREDIT_WALLET"; }

    /**
     * The order id — the partner's own reference for the recharge, and the one the
     * gateway also knows it by.
     *
     * Null when the partner never gave one, and null means the guard falls back to the
     * ticket id. That is weaker, so this concern's flow collects the reference BEFORE
     * the decision rather than after it.
     */
    @Override
    public String externalReferenceFor(ActionRequest request) {
        return request.reference();
    }

    @Override
    public ActionResult execute(ActionRequest request) {
        long amountPaise = request.numericFact("amountPaise");

        if (amountPaise <= 0) {
            // A credit of zero is not a credit, and a credit of a negative number is a
            // debit. Either means the facts and the table disagree about what happened,
            // and the honest response is to refuse and let the attempt row show why.
            throw new IllegalStateException(
                    "refusing to credit " + amountPaise + " paise for order "
                    + request.reference() + " — the decision produced no usable amount");
        }

        String reference = gateway.creditWallet(request.spId(), request.reference(), amountPaise);
        log.info("credited {} paise to {} for order {}", amountPaise, request.spId(), request.reference());

        return ActionResult.of(reference, amountPaise);
    }
}
