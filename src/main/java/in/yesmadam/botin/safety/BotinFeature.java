package in.yesmadam.botin.safety;

import org.togglz.core.Feature;
import org.togglz.core.annotation.Label;

/**
 * ADR-007 — one kill switch per automated concern.
 *
 * Flag off does NOT mean fail. It means do not automate, send to a human (T3).
 * The partner is still served; only the automation stops.
 *
 * Every constant here must match a concern_catalogue.togglz_flag value, and
 * FeatureNameValidator asserts that at startup — see the class for why that is
 * mandatory rather than tidy.
 */
public enum BotinFeature implements Feature {

    @Label("Transport — auto-credit")            TRANSPORT_AUTO_CREDIT,
    @Label("Recharge — auto-credit")             RECHARGE_AUTO_CREDIT,
    @Label("Violations — auto-remove (R5)")      VIOL_R5_AUTO_REMOVE,
    @Label("Violations — auto-remove (R9)")      VIOL_R9_AUTO_REMOVE;
}
