package in.yesmadam.botin.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.catalogue.CatalogueService;
import in.yesmadam.botin.catalogue.ConcernCatalogue;
import in.yesmadam.botin.facts.FactProviderRegistry;
import in.yesmadam.botin.facts.FactRequest;
import in.yesmadam.botin.session.HelpSession;
import in.yesmadam.botin.session.HelpSessionRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Reads the world. Concludes nothing.
 *
 * ONE DELEGATE SERVES EVERY CONCERN (ADR-006): the provider is resolved at runtime from
 * concern_catalogue.fact_provider, so adding a concern adds a bean rather than a code
 * path through here.
 *
 * NOTHING THROWN HERE MAY REACH THE PARTNER. A fact provider talks to a database that
 * belongs to someone else — it can time out, it can meet a renamed column, it can find
 * nothing. Every one of those is "we could not find out", and the correct answer to
 * that is a human, not a 500. So the failure is caught, recorded on the execution, and
 * turned into an escalation by the next step.
 */
@Component("fetchFactsDelegate")
public class FetchFactsDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(FetchFactsDelegate.class);

    /** Read by DecideDelegate. A lookup that blew up must not then be "decided" on. */
    public static final String FACTS_FAILED = "factsFailed";

    private final CatalogueService catalogue;
    private final FactProviderRegistry providers;
    private final HelpSessionRepository sessions;
    private final ObjectMapper json;

    public FetchFactsDelegate(CatalogueService catalogue, FactProviderRegistry providers,
                              HelpSessionRepository sessions, ObjectMapper json) {
        this.catalogue = catalogue;
        this.providers = providers;
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String l2Concern = (String) execution.getVariable(ProcessVariables.L2_CONCERN);
        UUID sessionId = UUID.fromString((String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));

        execution.setVariable(FACTS_FAILED, false);

        // ALREADY CLASSIFIED THIS TURN. The entry point had to classify in order to
        // decide whether to ask the partner to rephrase, so re-fetching here would call
        // a real model twice on the same sentence — doubled cost and doubled latency,
        // for no new information. Nothing depends on this being set; it is a saving,
        // not a contract.
        String carried = (String) execution.getVariable(ProcessVariables.FACTS_JSON);
        if (carried != null && !carried.isBlank() && !"{}".equals(carried)) {
            log.debug("using the classification already made this turn for {}", l2Concern);
            return;
        }

        try {
            ConcernCatalogue concern = catalogue.find(l2Concern).orElseThrow(
                    () -> new IllegalStateException("process running for a concern not in the catalogue: " + l2Concern));

            HelpSession session = sessions.findById(sessionId).orElseThrow(
                    () -> new IllegalStateException("no help session " + sessionId));

            // ticketId is null: Gate 1 has not been crossed yet, and cannot be — whether
            // this case deserves a ticket at all is decided from the facts we are about
            // to fetch. No provider needs it; the field is there for the action phase.
            Map<String, Object> facts = providers.require(concern.getFactProvider())
                    .fetchFacts(new FactRequest(
                            null,
                            (String) execution.getVariable(ProcessVariables.SP_ID),
                            l2Concern,
                            (String) execution.getVariable(ProcessVariables.SELECTED_REFERENCE),
                            session.getEntryFreeText()));

            execution.setVariable(ProcessVariables.FACTS_JSON, json.writeValueAsString(facts));
            log.debug("facts for {} -> {}", l2Concern, facts);

        } catch (Exception e) {
            // Deliberately broad. Anything at all that stops us knowing the facts has
            // the same meaning to the partner, and the same correct response.
            log.error("fact lookup failed for {} — escalating rather than deciding blind", l2Concern, e);
            execution.setVariable(FACTS_FAILED, true);
            execution.setVariable(ProcessVariables.FACTS_JSON, "{}");
        }
    }
}
