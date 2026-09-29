package in.yesmadam.botin.platform.safety;

import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

/**
 * ADR-007 — one switch per automated concern, readable at runtime, no restart.
 *
 * FLAG OFF DOES NOT MEAN FAIL. It means do not automate: send the partner to a person.
 * They are still served; only the automation stops. That is the difference between a
 * kill switch and an outage, and it is why flipping one is a safe thing to do at 2am
 * with a partner mid-session.
 *
 * WHY THIS WRAPPER EXISTS RATHER THAN A DIRECT Togglz CALL. `FeatureManager` takes a
 * `NamedFeature`, and Togglz resolves a name that no enum declares to FALSE — SILENTLY.
 * With fail-to-human semantics that reads as "kill switch off", so a typo in a catalogue
 * row would route an entire concern to agents forever with nothing in any log. Spike 4
 * found that hole; `FeatureNameValidator` closes it at startup; this class is where the
 * lookup that could reopen it lives, so there is exactly one of them.
 *
 * A CONCERN WITH NO FLAG IS ALLOWED. Most concerns never move money and need no switch,
 * and requiring a flag for all of them would mean forty switches nobody maintains.
 * Absence here means "nothing to disable", not "disabled".
 */
@Component
public class KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);

    private final FeatureManager features;

    public KillSwitch(FeatureManager features) {
        this.features = features;
    }

    /**
     * @return true when this concern's automation may run. True when it has no flag.
     */
    public boolean isAutomationAllowed(ConcernCatalogue concern) {
        String flag = concern == null ? null : concern.getTogglzFlag();
        if (flag == null || flag.isBlank()) return true;

        boolean active = features.isActive(new NamedFeature(flag));
        if (!active) {
            log.info("kill switch {} is OFF — {} will not automate", flag, concern.getL2Code());
        }
        return active;
    }

    /** By name, for the places that have a flag rather than a concern row. */
    public boolean isEnabled(String flag) {
        return flag == null || flag.isBlank() || features.isActive(new NamedFeature(flag));
    }
}
