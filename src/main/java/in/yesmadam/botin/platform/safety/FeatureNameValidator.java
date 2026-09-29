package in.yesmadam.botin.platform.safety;

import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.togglz.core.manager.FeatureManager;

import java.util.*;

/**
 * PROVEN NECESSARY BY THE R&D LADDER — not a precaution.
 *
 * The Togglz spike showed that a MISSPELT flag name does not throw. It reads as
 * "off". Combined with fail-to-human semantics, one typo in
 * concern_catalogue.togglz_flag would silently route an entire concern to human
 * agents: no exception, nothing in the logs, and the only symptom is an
 * agent-connect rate drifting upward for reasons nobody can trace.
 *
 * Resolving a flag by runtime String (NamedFeature) is what the catalogue forces
 * on us, and it bypasses enum type safety. This is the compensating check, and
 * it fails the application at startup rather than in production.
 */
@Component
public class FeatureNameValidator {

    private static final Logger log = LoggerFactory.getLogger(FeatureNameValidator.class);

    private final ConcernCatalogueRepository catalogue;
    private final FeatureManager features;

    public FeatureNameValidator(ConcernCatalogueRepository catalogue, FeatureManager features) {
        this.catalogue = catalogue;
        this.features = features;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateEveryCatalogueFlagExists() {
        Set<String> declared = new HashSet<>();
        for (BotinFeature f : BotinFeature.values()) declared.add(f.name());

        List<String> unknown = new ArrayList<>();
        for (ConcernCatalogue c : catalogue.findAll()) {
            String flag = c.getTogglzFlag();
            if (flag == null || flag.isBlank()) continue;
            if (!declared.contains(flag)) {
                unknown.add(c.getL2Code() + " -> '" + flag + "'");
            }
        }

        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                "concern_catalogue references togglz flags that are not declared in BotinFeature: "
              + unknown + ". Togglz resolves an unknown flag name to FALSE silently, which with "
              + "fail-to-human semantics would route these concerns to agents with no error. "
              + "Fix the catalogue row or add the enum constant.");
        }
        log.info("togglz flag validation passed — {} declared features, all catalogue references resolve",
                 declared.size());
    }

    /**
     * SAY OUT LOUD WHICH SWITCHES ARE ON — plan step 91.
     *
     * From this step the flags live in the database, so their state is whatever the last
     * person to touch the console left them at, possibly weeks ago and possibly during an
     * incident nobody wrote down. That is the correct behaviour and it creates a new way to
     * be confused: an engineer watching a concern route every partner to an agent, checking
     * the catalogue, the decision table and the delegate, and finding nothing wrong —
     * because nothing is wrong, the switch is simply off.
     *
     * A FEATURE WITH NO ROW READS AS OFF. That is Togglz's own default and it is the right
     * one here: a concern is not automated until somebody deliberately says so, so a fresh
     * database automates nothing and pays nobody. But it does mean "off" and "never
     * configured" look identical at runtime, which is exactly why this line prints both.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportEveryFlagState() {
        for (BotinFeature f : BotinFeature.values()) {
            boolean active = features.isActive(f);
            log.info("kill switch {} = {}{}", f.name(), active ? "ON (automating)" : "OFF (to a human)",
                     active ? "" : " — off and never-configured look the same here");
        }
    }
}
