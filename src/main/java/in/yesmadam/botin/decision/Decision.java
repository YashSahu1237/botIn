package in.yesmadam.botin.decision;

import java.util.Map;

/**
 * What a decision table said. Three fields every table returns, plus the raw map for
 * the outputs only some tables have (the per-km rate, for one).
 *
 * @param tier        T0..T3, or "-" for a REROUTE, which has no tier
 * @param action      a stable code. The response template and the action service are
 *                    both keyed on it, so it is a contract, not a label
 * @param outcomeType BOT, TICKET, UPHOLD, REROUTE or SELF-SERVE — the concern mapping's
 *                    own vocabulary, kept rather than translated
 */
public record Decision(String tier, String action, String outcomeType, Map<String, Object> raw) {

    public boolean movesMoney()  { return "T2".equals(tier); }
    public boolean needsTicket() { return "TICKET".equals(outcomeType); }
    public boolean isReroute()   { return "REROUTE".equals(outcomeType); }

    public long numeric(String output) {
        Object v = raw.get(output);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
