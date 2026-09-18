package in.yesmadam.botin.payu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PLAN STEP 89 — a stand-in for PayU, with switchable status and induced failures.
 *
 * WHAT IT IS FOR. Recharge has five gateway states and only one of them moves money. In
 * UAT those states exist in whatever proportion real traffic produced them — 68% success,
 * 26% no-response, a handful of failures — so the rare and dangerous cases are exactly
 * the ones that never show up when you need them. This lets a test ask for the state it
 * wants, including the ones that should never happen.
 *
 * AND FOR THE FAILURES, WHICH MATTER MORE. A payment integration is defined by how it
 * behaves when the other side misbehaves, not when it works. `failNextCalls` makes the
 * credit throw on demand, which is the only way to prove the attempt log survives a
 * failure and a retry does not pay twice.
 *
 * IT IS DELIBERATELY NOT A "MOCK" IN THE TEST-DOUBLE SENSE. It is a real bean with real
 * state, wired in for the POC because no sandbox credential exists, and it records what
 * it was asked to do so a reconciliation can read it back. Replacing it with a real
 * client is one bean.
 *
 * IT MOVES NOTHING. There is no money here, and there never will be — the POC's whole
 * safety position is that the action layer is exercised end to end against something
 * that cannot pay anybody.
 */
@Component
public class MockPayUGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPayUGateway.class);

    /** Raw gateway strings, lower case, exactly as the real column holds them. */
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    public static final String NOT_FOUND = "Not Found";

    private final Map<String, String> statuses = new ConcurrentHashMap<>();
    private final Map<String, Long> amounts = new ConcurrentHashMap<>();
    private final Map<String, Long> credited = new ConcurrentHashMap<>();
    private final AtomicInteger failNextCalls = new AtomicInteger(0);

    /**
     * @param rawStatus one of the constants above, or null for the no-response case.
     *
     * NULL IS A REAL STATE, not an unset fixture. A quarter of the rows in UAT carry no
     * status at all — a recharge begun and never confirmed — and it is the single most
     * common non-success outcome, so it has to be settable.
     */
    public void setStatus(String orderId, String rawStatus) {
        if (rawStatus == null) statuses.remove(orderId); else statuses.put(orderId, rawStatus);
    }

    /** A recharge as the gateway knows it: what happened, and for how much. */
    public void setRecharge(String orderId, String rawStatus, long amountPaise) {
        setStatus(orderId, rawStatus);
        amounts.put(orderId, amountPaise);
    }

    public String statusOf(String orderId) {
        return statuses.get(orderId);
    }

    /**
     * What the partner actually paid, in paise.
     *
     * ZERO WHEN UNKNOWN, and zero is refused by the credit service rather than treated
     * as "nothing to pay". An amount we cannot establish is a case for a person: paying
     * zero silently would close the ticket having done nothing.
     */
    public long amountFor(String orderId) {
        return amounts.getOrDefault(orderId, 0L);
    }

    /** Make the next n credit attempts throw, as a dead or angry gateway would. */
    public void failNextCalls(int n) {
        failNextCalls.set(n);
    }

    /**
     * Credit a partner's wallet. Returns the gateway's own reference for the movement.
     *
     * @throws PayUUnavailableException when failure has been induced — checked by nobody
     *         and caught by the action layer, which is the point: the caller must decide
     *         what a failed payment means, and it must not be this class's opinion.
     */
    public String creditWallet(String spId, String orderId, long amountPaise) {
        if (failNextCalls.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            log.warn("MOCK PAYU: induced failure crediting {} for order {}", spId, orderId);
            throw new PayUUnavailableException("induced failure for order " + orderId);
        }

        credited.merge(orderId, amountPaise, Long::sum);
        String reference = "MOCKPAYU-" + orderId;
        log.info("MOCK PAYU: credited {} paise to {} for order {} -> {}",
                amountPaise, spId, orderId, reference);
        return reference;
    }

    /** What this gateway believes it has paid. The reconciliation source for the POC. */
    /**
     * EVERY credit the gateway believes it made. Reconciliation's right-hand side.
     *
     * The interesting direction is not "we say we paid, did they receive it" — that one is
     * obvious. It is the reverse: a credit HERE with no attempt row of ours is money that
     * moved with nothing on our side explaining why, and no amount of reading our own tables
     * would ever reveal it.
     */
    public Map<String, Long> allCredits() {
        return Map.copyOf(credited);
    }

    public long creditedFor(String orderId) {
        return credited.getOrDefault(orderId, 0L);
    }

    public void reset() {
        statuses.clear();
        amounts.clear();
        credited.clear();
        failNextCalls.set(0);
    }

    public static class PayUUnavailableException extends RuntimeException {
        public PayUUnavailableException(String message) { super(message); }
    }
}
