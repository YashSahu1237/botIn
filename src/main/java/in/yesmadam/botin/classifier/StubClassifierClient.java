package in.yesmadam.botin.classifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PLAN STEP 83 — the deterministic stub, behind the real interface.
 *
 * NOT A TEST MOCK. This is a real bean, selected at startup whenever no classifier URL
 * is configured, which is the state the project is actually in: the LLM track is blocked
 * on procurement and everything downstream of it is not. With this in place the reroute
 * path, the confidence floor, the clarification loop and the agent handoff can all be
 * built, demonstrated and argued about now, and swapping in a real model later changes
 * one property in one file.
 *
 * DETERMINISTIC ON PURPOSE. Keyword matching, fixed confidences, no randomness. A test
 * asserting "this text reroutes and that text does not" has to mean the same thing on
 * every run, and a stub that guessed would make every failure ambiguous.
 *
 * CONSTRUCTED BY ClassifierConfig, not by component scanning. Which client is in play is
 * one decision in one place — a pair of @ConditionalOnProperty annotations looked
 * exclusive and was not, and took the whole context down when a config line made the
 * property present-but-empty. See ClassifierConfig.
 *
 * ITS CONFIDENCES ARE HONEST ABOUT WHAT IT IS. A keyword hit returns 0.95 — not because
 * matching "mpin" is an achievement, but because the FIXTURE is supposed to represent an
 * unambiguous case. Everything it does not recognise returns NO_MATCH, which is most
 * real partner text, and that is the correct shape: this stub is not a classifier and
 * must never look like one in a demo.
 */
public class StubClassifierClient implements ClassifierClient {

    private static final Logger log = LoggerFactory.getLogger(StubClassifierClient.class);

    /** Unambiguous fixture phrases → the concern they name. Order matters: first hit wins. */
    private static final Map<String, String> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("mpin", "FORGET_MPIN");
        KEYWORDS.put("pin bhool", "FORGET_MPIN");
        KEYWORDS.put("recharge", "RECHARGE_DEBIT_NO_CREDIT");
        KEYWORDS.put("paise cut", "RECHARGE_DEBIT_NO_CREDIT");
        KEYWORDS.put("transport", "TRANSPORT_NOT_RECEIVED");
        KEYWORDS.put("travel", "TRANSPORT_NOT_RECEIVED");
        KEYWORDS.put("order nahi aaya", "PROD_DELIVERY_DELAY");
        KEYWORDS.put("delivery", "PROD_DELIVERY_DELAY");
        KEYWORDS.put("product", "PROD_DELIVERY_DELAY");
    }

    /** Words that mean a person should see this now, whatever it is about. */
    private static final Map<String, Boolean> RISK = new LinkedHashMap<>();
    static {
        RISK.put("accident", true);
        RISK.put("hospital", true);
        RISK.put("police", true);
    }

    @Override
    public Classification classifyIntent(String text) {
        return classify(text);
    }

    @Override
    public Classification classifyReason(String l2Concern, String text) {
        Classification result = classify(text);

        // "It is this concern after all" is not a reroute. Saying so would send the
        // partner back into the flow they are already in, which reads as the bot
        // ignoring them.
        if (result.matched() && result.category().equals(l2Concern)) {
            return new Classification(null, result.confidence(), result.extractedReason(),
                    result.sentiment(), result.abuseFlag(), result.riskFlag());
        }
        return result;
    }

    @Override
    public String describe() { return "stub (deterministic, keyword-matched)"; }

    private Classification classify(String text) {
        if (text == null || text.isBlank()) return Classification.NO_MATCH;
        String lower = text.toLowerCase();

        boolean risk = RISK.keySet().stream().anyMatch(lower::contains);

        for (Map.Entry<String, String> entry : KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return new Classification(entry.getValue(), 0.95,
                        "matched fixture phrase '" + entry.getKey() + "'",
                        risk ? "NEGATIVE" : "NEUTRAL", false, risk);
            }
        }

        // The common case, and correctly so. Most real text will not be a fixture, and
        // a stub that pretended otherwise would make the demo a lie.
        return risk
                ? new Classification(null, 0.0, null, "NEGATIVE", false, true)
                : Classification.NO_MATCH;
    }
}
