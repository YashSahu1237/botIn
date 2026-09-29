package in.yesmadam.botin.platform.csat;

import in.yesmadam.botin.platform.catalogue.CatalogueService;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import in.yesmadam.botin.platform.process.ConcernProcessRunner;
import in.yesmadam.botin.platform.process.ProcessVariables;
import in.yesmadam.botin.platform.session.HelpSession;
import in.yesmadam.botin.platform.session.TicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * PHASE 10 — the satisfaction answer, trigger A, and the bounding rule.
 *
 * =========================================================================
 * PLAN STEP 74 — THE DECISION, AND WHY. This settles open question O-15.
 * =========================================================================
 *
 * Trigger A is the awkward one: the other four fire while the process is running, and A
 * fires AFTER it has finished. The two candidate mechanisms were:
 *
 *   (a) keep the instance alive on a Receive Task until the partner answers, or
 *   (b) let the instance complete, and take the answer on its own endpoint.
 *
 * (b), for three reasons, and the first is the one that decides it:
 *
 *   1. (a) COSTS EVERY RESOLVED SESSION, TO SERVE THE FEW THAT ARE UNHAPPY. Every T0,
 *      T1 and T2 would hold ACT_RU_* rows until answered or timed out. T0 is the highest
 *      volume in the system and the tier that is supposed to cost nothing. Paying runtime
 *      state on all of them to catch the minority who say "No" is backwards.
 *   2. (a) WEAKENS THE PROPERTY THE WHOLE DESIGN RESTS ON. "A completed instance has its
 *      runtime rows deleted" is why nextStep lives on the help_session row (ADR-001).
 *      Keeping instances alive so that a later answer has somewhere to land would make
 *      that property conditional, and a conditional invariant is not one.
 *   3. IT MATCHES WHAT ACTUALLY HAPPENS. A satisfaction answer is an afterthought on a
 *      finished conversation. A partner given a deeplink has to go and USE it before
 *      they can say whether it helped; asking in the same breath asks them to rate an
 *      answer they have not tried yet.
 *
 * AND THE TIMER DISAPPEARS. Plan step 75 called for a boundary timer for "never
 * answered". Under (b) there is nothing to time out: the session is already closed and
 * nothing is being held. "Never answered" is simply csat_result staying null, which is a
 * number a report can count. One timer, one job and one class of stuck session, all
 * removed by not holding state in the first place.
 */
@Service
public class CsatService {

    private static final Logger log = LoggerFactory.getLogger(CsatService.class);

    /** The fifth trigger to exist. A: the partner said the resolution did not help. */
    public static final String TRIGGER_DISSATISFIED = "A";

    /** The process that hosts the User Task when trigger A fires. */
    public static final String ESCALATION_PROCESS = "csat-escalation";

    public static final String YES = "YES";
    public static final String NO = "NO";

    /**
     * The endings a satisfaction question makes sense after.
     *
     * CLOSED_NOT_AVAILABLE is deliberately absent. Asking "did that help?" straight
     * after telling a partner we cannot help them is not measurement, it is rudeness,
     * and the answer would be noise in the only number this is here to produce.
     */
    private static final Set<String> RESOLVED = Set.of(
            "CLOSED_DEFLECTED", "CLOSED_RESOLVED", "CLOSED_AGENT_RESOLVED");

    private final CatalogueService catalogue;
    private final TicketRepository tickets;
    private final ConcernProcessRunner processRunner;
    private final ObjectMapper json;
    private final String agentQueue;

    public CsatService(CatalogueService catalogue, TicketRepository tickets,
                       ConcernProcessRunner processRunner, ObjectMapper json,
                       @Value("${botin.agent.default-queue}") String agentQueue) {
        this.catalogue = catalogue;
        this.tickets = tickets;
        this.processRunner = processRunner;
        this.json = json;
        this.agentQueue = agentQueue;
    }

    /** True when this session has been resolved and nobody has answered yet. */
    public boolean isExpected(HelpSession session) {
        return RESOLVED.contains(session.getStatus()) && session.getCsatResult() == null;
    }

    /**
     * THE BOUNDING RULE — the single strongest control on the agent-connect rate.
     *
     * On any case the partner confirms as resolved, the "Talk to an Agent" option is
     * SUPPRESSED. Without it every satisfied partner still sees a route to a human and
     * the 10-20% ceiling stops meaning anything.
     *
     * It is enforced HERE, in closure logic, and never in the client. A suppression rule
     * that lives in the UI is a suppression rule that can be bypassed — by an old app
     * version, by a rebuilt screen, or by anyone who can call the API directly.
     *
     * THE ONE EXCEPTION IS TRIGGER B. A mandatory-human concern keeps the option
     * whatever the partner answered, because for those concerns the whole point is that
     * a person is supposed to see it.
     */
    public boolean offersAgentAfter(HelpSession session, boolean satisfied) {
        return !satisfied || isMandatoryHuman(session);
    }

    /**
     * TRIGGER A. The partner said no, so a human takes it.
     *
     * The ticket is REUSED where one exists, not replaced: this is the same complaint,
     * now escalated. Counting it as a second ticket would inflate every volume number
     * and hide the fact that the bot's answer was rejected — which is the one thing this
     * trigger is here to surface.
     *
     * @return the process instance now waiting on an agent
     */
    public String escalate(HelpSession session) {
        ConcernCatalogue concern = catalogue.find(session.getL2Concern()).orElse(null);

        Map<String, Object> variables = new HashMap<>();
        variables.put(ProcessVariables.HELP_SESSION_ID, session.getId().toString());
        variables.put(ProcessVariables.SP_ID, session.getSpId());
        variables.put(ProcessVariables.L2_CONCERN, session.getL2Concern());
        variables.put(ProcessVariables.SELECTED_REFERENCE, session.getSelectedReference());
        variables.put(ProcessVariables.MANDATORY_HUMAN, concern != null && concern.isMandatoryHuman());
        variables.put(ProcessVariables.AGENT_QUEUE, agentQueue);

        // Preset rather than decided: the decision has already been made, by the partner.
        variables.put(ProcessVariables.TIER, "T3");
        variables.put(ProcessVariables.ACTION, "AGENT_CSAT_DISSATISFIED");
        variables.put(ProcessVariables.OUTCOME_TYPE, "TICKET");
        variables.put(ProcessVariables.AGENT_REQUIRED, true);
        variables.put(ProcessVariables.TRIGGER_REASON, TRIGGER_DISSATISFIED);

        // Carried IN rather than written after. Gate 1 commits in its own transaction,
        // and a second write from out here would be flushed from a copy of the ticket
        // loaded before that commit — quietly reverting the trigger back to whatever it
        // was. One writer per row per request.
        variables.put(ProcessVariables.CSAT_RESULT, NO);
        variables.put(ProcessVariables.FACTS_JSON, snapshotOf(session));

        String instanceId = processRunner.startWith(ESCALATION_PROCESS, session.getId(), variables);
        log.info("trigger A: session {} escalated after CSAT=NO -> instance {}",
                session.getId(), instanceId);
        return instanceId;
    }

    /**
     * What the agent gets to see, given the original facts are gone with the instance.
     *
     * This is the honest reconstruction, and it is arguably MORE useful than the original
     * fact map: it carries the exact words the partner was shown and rejected. "The bot
     * told them this and they said it did not help" is the first thing the agent needs,
     * and it is a sentence rather than a lookup.
     */
    private String snapshotOf(HelpSession session) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("trigger", "A — partner answered NO to the satisfaction prompt");
        snapshot.put("whatThePartnerWasTold", raw(session.getNextStepPayload()));

        tickets.findByHelpSessionId(session.getId()).stream().findFirst().ifPresent(t -> {
            snapshot.put("botTier", t.getTier());
            snapshot.put("botAction", t.getDmnAction());
        });

        snapshot.put("originalFreeText", session.getEntryFreeText());
        try {
            return json.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("could not serialise the CSAT snapshot for session {}", session.getId(), e);
            return "{\"trigger\":\"A\"}";
        }
    }

    /** Record the answer on the ticket too, where one exists, so reports can join them. */
    public void recordOnTicket(HelpSession session, boolean satisfied) {
        tickets.findByHelpSessionId(session.getId())
                .forEach(t -> t.recordCsat(satisfied ? YES : NO));
    }

    private boolean isMandatoryHuman(HelpSession session) {
        return catalogue.find(session.getL2Concern())
                .map(ConcernCatalogue::isMandatoryHuman)
                .orElse(false);
    }

    private Object raw(String payload) {
        try {
            return payload == null ? null : json.readValue(payload, Object.class);
        } catch (Exception e) {
            return payload;
        }
    }

    public static class CsatAlreadyRecordedException extends RuntimeException {
        public CsatAlreadyRecordedException(UUID id, String existing) {
            super("Session " + id + " already recorded CSAT=" + existing
                + ". A satisfaction answer is given once; changing it would rewrite the "
                + "only number this measures.");
        }
    }

    public static class CsatNotExpectedException extends RuntimeException {
        public CsatNotExpectedException(UUID id, String status) {
            super("Session " + id + " is " + status + ", which is not a resolved outcome. "
                + "There is nothing to rate.");
        }
    }
}
