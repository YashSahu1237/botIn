package in.yesmadam.botin.facts;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a provider by the concern_catalogue.fact_provider string.
 *
 * Fails at STARTUP on a duplicate or a missing bean, for the same reason the
 * Togglz validator exists: a lookup that returns null at runtime becomes a
 * decision table full of nulls, which falls to the catch-all and looks like a
 * business outcome rather than a bug.
 */
@Component
public class FactProviderRegistry {

    private final Map<String, ConcernFactProvider> byConcernCode = new HashMap<>();

    public FactProviderRegistry(List<ConcernFactProvider> providers) {
        for (ConcernFactProvider p : providers) {
            ConcernFactProvider clash = byConcernCode.put(p.concernCode(), p);
            if (clash != null) {
                throw new IllegalStateException(
                    "two fact providers claim concern '" + p.concernCode() + "': "
                  + clash.getClass().getName() + " and " + p.getClass().getName());
            }
        }
    }

    public ConcernFactProvider require(String concernCode) {
        ConcernFactProvider p = byConcernCode.get(concernCode);
        if (p == null) {
            throw new IllegalStateException(
                "no ConcernFactProvider for '" + concernCode + "'. Registered: " + byConcernCode.keySet());
        }
        return p;
    }

    public boolean has(String concernCode) { return byConcernCode.containsKey(concernCode); }
}
