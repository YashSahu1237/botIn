package in.yesmadam.botin.platform.facts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One implementation per concern, resolved at runtime by
 * concern_catalogue.fact_provider. A single generic delegate serves all of them
 * (ADR-006), so adding a concern adds a bean rather than a code path.
 *
 * THE SPLIT THAT MATTERS: what data to fetch is CODE, because fetching correctly
 * is an engineering concern. What to conclude from it is CONFIGURATION — a
 * decision table — because concluding correctly is a business concern that will
 * be tuned. A fact provider must never decide anything.
 *
 * THE CONTRACT IS STRICTER THAN IT LOOKS:
 *
 *   fetchFacts() MUST return EVERY key in factKeys(), on every path, including when
 *   UAT is unreachable and every value is null.
 *
 * That is not tidiness. The decision engine runs in strict mode: a DMN input naming a
 * variable that is ABSENT from the map is an evaluation error, and an evaluation error
 * takes the whole decision down — catch-all included. A key present with a null value
 * evaluates fine and lands on the catch-all, which is a human. So a provider that
 * returns fewer keys when it knows less turns "we could not find out" into "the table
 * exploded".
 *
 * Start from emptyFacts() and overwrite what you learn. Then the map is complete by
 * construction rather than by remembering.
 */
public interface ConcernFactProvider {

    /** Must match a concern_catalogue.fact_provider value. */
    String concernCode();

    /**
     * Exactly the inputs this concern's decision table reads, by name.
     *
     * The same list as TABLE_INPUTS in DecisionTableTest, and a test asserts the two
     * agree. A name differing by one character behaves like a value that is false — the
     * table read `arrivedAt300m` while the provider supplied `arrivedAt300metre`, and
     * every Path 2 claim would have reached an agent with nothing reporting it.
     */
    Set<String> factKeys();

    /**
     * Every UAT column this provider queries, declared so UatSchemaProbe can check it at
     * startup rather than at a partner's expense.
     */
    default List<UatColumn> requiredColumns() { return List.of(); }

    /**
     * @return facts keyed exactly as the decision table's inputExpressions name them.
     *         Never throws for missing data — returns nulls instead.
     */
    Map<String, Object> fetchFacts(FactRequest request);

    /** Every declared key, present and null. The correct starting point for fetchFacts. */
    default Map<String, Object> emptyFacts() {
        Map<String, Object> facts = new HashMap<>();
        factKeys().forEach(k -> facts.put(k, null));
        return facts;
    }
}
