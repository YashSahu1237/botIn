package in.yesmadam.botin.platform.process;

import in.yesmadam.botin.platform.api.dto.NextStep;
import in.yesmadam.botin.platform.catalogue.CatalogueService;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import in.yesmadam.botin.platform.session.HelpSession;
import in.yesmadam.botin.platform.session.HelpSessionRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * PLAN STEP 86 — the partner is in the wrong place, so put them in the right one.
 *
 * The free-text concern's table said REROUTE and named a target. This ends the current
 * concern and starts the target's process against the SAME session, so from the
 * partner's side nothing restarted: they typed a sentence and got an answer about the
 * thing they actually asked about.
 *
 * FREE TEXT IS TRUSTED FOR ROUTING ONLY. That is the whole safety argument for acting on
 * a model's answer at all. The flow we reroute INTO fetches its own facts and runs its
 * own decision table against the backend — so the worst a wrong reroute can do is ask
 * the wrong question, never grant the wrong thing. No money and no state change anywhere
 * downstream depends on what the partner typed.
 *
 * TWO GUARDS, both of which turn a bad reroute into a human rather than a loop:
 *
 *   1. YOU CANNOT REROUTE INTO A TRIAGE CONCERN. The two free-text concerns are the only
 *      ones that can produce a REROUTE, so allowing one as a target allows an infinite
 *      chain — a partner bounced between triage flows forever, each one costing a model
 *      call, with nothing in the logs that looks like an error.
 *   2. THE TARGET MUST BE ACTIVE AND BUILT. The gateway already rejects a category that
 *      is not an active concern, so this is the second of two independent checks on the
 *      same thing. That is deliberate: this one is the last point before a partner is
 *      moved, and it is the only one that can also see whether the target has a process.
 */
@Component("rerouteDelegate")
public class RerouteDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(RerouteDelegate.class);

    /** Concerns whose whole job is to reroute. Never valid as a reroute TARGET. */
    private static final Set<String> TRIAGE_CONCERNS =
            Set.of("VIOL_R4_OTHERS", "OTHER_FREETEXT_TRIAGE");

    private final CatalogueService catalogue;
    private final HelpSessionRepository sessions;
    private final ConcernProcessRunner processRunner;
    private final SessionStepWriter steps;

    public RerouteDelegate(CatalogueService catalogue, HelpSessionRepository sessions,
                           ConcernProcessRunner processRunner, SessionStepWriter steps) {
        this.catalogue = catalogue;
        this.sessions = sessions;
        this.processRunner = processRunner;
        this.steps = steps;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID sessionId = UUID.fromString((String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));
        String from = (String) execution.getVariable(ProcessVariables.L2_CONCERN);
        String target = (String) execution.getVariable(ProcessVariables.REROUTE_TARGET);

        ConcernCatalogue concern = target == null ? null : catalogue.find(target).orElse(null);

        if (concern == null || !concern.isActive() || concern.getProcessKey() == null
                || TRIAGE_CONCERNS.contains(target)) {
            log.warn("cannot reroute {} -> '{}' (unknown, inactive, unbuilt or itself triage) "
                   + "— sending to a human instead", from, target);
            steps.writeAndWait(sessionId, NextStep.message("AGENT_CONNECTING",
                    "Aapki baat ek support agent se karayi ja rahi hai."),
                    AgentHandoffDelegate.STEP_AGENT_CONNECT);
            execution.setVariable(ProcessVariables.AGENT_REQUIRED, true);
            return;
        }

        HelpSession session = sessions.findById(sessionId).orElseThrow(
                () -> new IllegalStateException("no help session " + sessionId));

        // The session MOVES rather than forking. One conversation, one row, and the
        // concern on it is the one actually being handled — otherwise every report about
        // which concerns partners raise would attribute a rerouted case to the triage
        // flow it passed through rather than the thing they needed.
        session.selectConcern(concern.getL1Code(), concern.getL2Code());
        sessions.saveAndFlush(session);

        log.info("rerouting session {} from {} to {}", sessionId, from, target);

        // The target process writes its own nextStep onto the session row, exactly as it
        // would have if the partner had arrived through the menu. Nothing downstream can
        // tell the difference, which is the point.
        processRunner.start(concern.getProcessKey(), sessionId, session.getSpId(),
                concern.getL2Code(), session.getSelectedReference());
    }
}
