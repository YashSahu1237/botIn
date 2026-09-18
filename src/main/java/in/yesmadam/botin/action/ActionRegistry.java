package in.yesmadam.botin.action;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Action code → the one service that performs it.
 *
 * FAILS AT STARTUP ON A DUPLICATE, like FactProviderRegistry. Two services claiming one
 * action code is two things that might both pay a partner, and which one wins would
 * depend on bean ordering — a coin toss decided by classpath scanning.
 *
 * ABSENCE IS NOT AN ERROR HERE, and that is the difference from the fact providers. A
 * decision table may legitimately emit an action nothing implements yet: that is a
 * concern whose rules are written before its plumbing, which is the normal order. The
 * caller treats "nobody performs this" as "do not automate" and sends the case to a
 * person — so an unbuilt action is slow, never wrong.
 */
@Component
public class ActionRegistry {

    private final Map<String, ActionService> byCode = new HashMap<>();

    public ActionRegistry(List<ActionService> services) {
        for (ActionService service : services) {
            ActionService clash = byCode.put(service.actionCode(), service);
            if (clash != null) {
                throw new IllegalStateException(
                    "two action services claim '" + service.actionCode() + "': "
                  + clash.getClass().getName() + " and " + service.getClass().getName()
                  + ". Which one pays the partner would depend on bean ordering.");
            }
        }
    }

    public Optional<ActionService> find(String actionCode) {
        return Optional.ofNullable(byCode.get(actionCode));
    }

    public boolean canPerform(String actionCode) {
        return actionCode != null && byCode.containsKey(actionCode);
    }
}
