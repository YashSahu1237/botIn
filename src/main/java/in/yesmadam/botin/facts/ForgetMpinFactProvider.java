package in.yesmadam.botin.facts;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * FORGET_MPIN — 738/month, 100% deflectable, and it needs NOTHING.
 *
 * Its decision table has one row that always matches, so there is nothing to look up and
 * no way to be wrong. The provider exists anyway so that every active concern resolves
 * one — the registry fails at startup on a missing provider, and a concern quietly
 * exempted from that rule is a hole in it.
 *
 * Worth noting for a later version: tbl_sp.mpin_hash and tbl_sp.dailyMpinAttempts exist,
 * so this could one day tell a partner how many attempts they have left rather than only
 * handing them a link.
 */
@Component
public class ForgetMpinFactProvider implements ConcernFactProvider {

    @Override public String concernCode() { return "FORGET_MPIN"; }

    @Override public Set<String> factKeys() { return Set.of("l2Concern"); }

    @Override
    public Map<String, Object> fetchFacts(FactRequest request) {
        Map<String, Object> facts = emptyFacts();
        facts.put("l2Concern", request.l2Concern());
        return facts;
    }
}
