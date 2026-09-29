package in.yesmadam.botin.platform.process;

import in.yesmadam.botin.platform.catalogue.CatalogueService;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * STAGE 1 OF THE TWO-STAGE DECISION — the pre-flight gate. Agent-connect TRIGGER B.
 *
 * Runs before facts are fetched and before any decision table is consulted, because
 * for a mandatory-human concern there is nothing to decide. Knowing which concern the
 * partner picked is already enough.
 *
 * TRIGGER B IS AN OVERRIDE, NOT A FALLBACK — and that distinction is the whole reason
 * this is a separate step rather than a column on the concern's own table. ADR-008 says
 * tier comes out of the decision table at runtime. B says: for these concerns, do not
 * ask the table at all. A row in the table could be overruled by row order or by a fact
 * that failed to load. A gate ahead of the table cannot be.
 *
 * It is also the cheapest possible check. concern_catalogue.mandatory_human is one
 * boolean on a row already loaded, so a concern that must reach a human costs no UAT
 * query, no decision evaluation, and no time at all.
 */
@Component("preFlightDelegate")
public class PreFlightDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(PreFlightDelegate.class);

    /** The five cross-cutting triggers are A..E. This is B. */
    public static final String TRIGGER_MANDATORY_HUMAN = "B";

    private final CatalogueService catalogue;
    private final String defaultQueue;

    public PreFlightDelegate(CatalogueService catalogue,
                             @Value("${botin.agent.default-queue}") String defaultQueue) {
        this.catalogue = catalogue;
        this.defaultQueue = defaultQueue;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String l2Concern = (String) execution.getVariable(ProcessVariables.L2_CONCERN);

        boolean mandatoryHuman = catalogue.find(l2Concern)
                .map(ConcernCatalogue::isMandatoryHuman)
                .orElse(false);

        execution.setVariable(ProcessVariables.MANDATORY_HUMAN, mandatoryHuman);
        execution.setVariable(ProcessVariables.AGENT_QUEUE, defaultQueue);

        // Set explicitly, even though it is about to be set again downstream. The
        // gateway reads this variable, and a gateway whose condition variable is
        // ABSENT is an evaluation error rather than a false — the same absent-versus-
        // null distinction that governs the decision tables.
        execution.setVariable(ProcessVariables.AGENT_REQUIRED, mandatoryHuman);
        execution.setVariable(ProcessVariables.REROUTE_REQUIRED, false);
        execution.setVariable(ProcessVariables.REROUTE_TARGET, null);

        if (mandatoryHuman) {
            log.info("pre-flight: {} is mandatory-human — trigger B, no decision table will run",
                    l2Concern);
        }
    }
}
