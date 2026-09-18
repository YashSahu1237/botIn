package in.yesmadam.botin.process;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * What the partner actually reads, keyed by the DMN action code.
 *
 * KEYED ON THE ACTION, NOT THE TIER. Two T1 outcomes can say opposite things — "we
 * already paid you" and "you did not travel, so there is nothing to pay" are both T1
 * and must never be confused. The action is the stable contract the decision tables
 * already emit, so it is the right key, and a table can change its mind about a tier
 * without changing a single word the partner sees.
 *
 * NO FALLBACK PROSE. An action with no template gets a message that admits we have
 * nothing prepared, and the ERROR beside it names the code. A generic "your request has
 * been processed" would be the worst possible failure mode here: it reads as success
 * for an outcome nobody wrote a sentence for.
 *
 * Hinglish, in Devanagari-free Roman script, matching how partners are addressed in the
 * existing SOP. Phase 6 extends this per concern; the shape does not change.
 */
@Component
public class ResponseTemplates {

    private static final Map<String, String> BY_ACTION = Map.ofEntries(

            // ---- transport ---------------------------------------------------
            Map.entry("INFORM_ALREADY_PAID",
                    "Is booking ka transport amount aapke wallet mein already credit ho chuka hai."),
            Map.entry("DENY_DID_NOT_TRAVEL",
                    "Record ke hisaab se is booking ke liye aap travel nahi kiye, isliye transport amount applicable nahi hai."),
            Map.entry("DENY_CASHBACK_COMPENSATES",
                    "Customer ne last minute cancel kiya tha, aur uske liye aapko cashback mil chuka hai. Transport alag se nahi milega."),
            Map.entry("DENY_WITHIN_HUB",
                    "Yeh job aapke hub ke radius ke andar tha, isliye transport amount applicable nahi hai."),

            // ---- the ones that MOVE MONEY ------------------------------------
            // Deliberately concrete: "credit ho gaya" and nothing vaguer. A partner who
            // has just been paid should be told so in words they can check against their
            // balance, not in a sentence that could equally mean "we are looking into it".
            Map.entry("AUTO_CREDIT_WALLET",
                    "Aapka recharge amount wallet mein credit kar diya gaya hai. "
                  + "Balance check kar lijiye."),
            Map.entry("AUTO_CREDIT_TRANSPORT",
                    "Transport amount aapke wallet mein credit kar diya gaya hai."),
            Map.entry("AUTO_CREDIT_DISTANCE",
                    "Aapki travel distance ke hisaab se transport amount wallet mein "
                  + "credit kar diya gaya hai."),
            Map.entry("INFORM_ALREADY_DONE",
                    "Yeh request pehle hi process ho chuki hai."),

            // ---- recharge ----------------------------------------------------
            Map.entry("INFORM_ALREADY_CREDITED",
                    "Yeh recharge aapke wallet mein already credit ho chuka hai."),
            Map.entry("ASK_RECHARGE_AGAIN",
                    "Aapka recharge fail hua tha. Agar paise cut gaye hain to bank 5-7 din mein wapas kar dega. "
                  + "Aap dobara recharge kar sakte hain."),

            // ---- product delivery --------------------------------------------
            Map.entry("SHOW_EXPECTED_DELIVERY",
                    "Aapka order abhi delivery time ke andar hai. Thoda intezaar karein, jaldi pahunch jayega."),

            // ---- every ending that hands over to a person ---------------------
            Map.entry("AGENT_CONNECTING",
                    "Aapki baat ek support agent se karayi ja rahi hai. Woh jald hi aapse connect karenge.")
    );

    /** @return the prompt for this action, or null if nobody has written one. */
    public String promptFor(String action) {
        return BY_ACTION.get(action);
    }

    public boolean has(String action) {
        return BY_ACTION.containsKey(action);
    }
}
