package in.yesmadam.botin.platform.session;

import in.yesmadam.botin.platform.api.dto.InputRequest;
import in.yesmadam.botin.platform.api.dto.NextStep;
import in.yesmadam.botin.platform.api.dto.Option;
import in.yesmadam.botin.platform.api.dto.SessionView;
import in.yesmadam.botin.platform.catalogue.CatalogueService;
import in.yesmadam.botin.platform.catalogue.ConcernCatalogue;
import in.yesmadam.botin.platform.classifier.IntentRouter;
import in.yesmadam.botin.platform.csat.CsatService;
import in.yesmadam.botin.platform.process.ConcernProcessRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The flow, for as far as Phase 2 goes: L1 → L2 → hand off.
 *
 * Every transition ends by writing the nextStep onto the help_session row and
 * returning the same view the reconnect endpoint returns. There is one code path,
 * so a reconnect can never disagree with the response that preceded it.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO: it does not create a ticket. Gate 1 is crossed
 * when a concern needs an answer or an action, and which of those it needs is decided
 * by the concern's process and decision table — neither of which exists before Phase 3.
 * Selecting an active concern here records the selection and stops. Keeping the ticket
 * out of this class is what makes the Phase 3 checkpoint — zero rows in ticket after a
 * T0 — a real assertion rather than a formality.
 */
@Service
public class HelpSessionService {

    private static final Logger log = LoggerFactory.getLogger(HelpSessionService.class);

    /** Step names, written to help_session.current_step. Asserted on in tests. */
    public static final String STEP_L1_SELECT = "L1_SELECT";
    public static final String STEP_L2_SELECT = "L2_SELECT";
    public static final String STEP_CONCERN_SELECTED = "CONCERN_SELECTED";
    public static final String STEP_ENDED = "ENDED";

    /** Terminal status for a partner who picked something we have not built. */
    public static final String STATUS_NOT_AVAILABLE = "CLOSED_NOT_AVAILABLE";

    /** Where a session sits between a resolution and the partner rating it. */
    public static final String STEP_AGENT_CONNECT = "AGENT_CONNECT";

    /** The partner typed something we could not place, and was asked to say more. */
    public static final String STEP_FREE_TEXT_CLARIFY = "FREE_TEXT_CLARIFY";

    private final HelpSessionRepository sessions;
    private final CatalogueService catalogue;
    private final ConcernProcessRunner processRunner;
    private final CsatService csat;
    private final IntentRouter intent;
    private final SessionOpener opener;
    private final ObjectMapper json;

    public HelpSessionService(HelpSessionRepository sessions,
                              CatalogueService catalogue,
                              ConcernProcessRunner processRunner,
                              CsatService csat,
                              IntentRouter intent,
                              SessionOpener opener,
                              ObjectMapper json) {
        this.sessions = sessions;
        this.catalogue = catalogue;
        this.processRunner = processRunner;
        this.csat = csat;
        this.intent = intent;
        this.opener = opener;
        this.json = json;
    }

    // ----------------------------------------------------------------- CSAT

    /**
     * The satisfaction answer, on a session that has ALREADY CLOSED.
     *
     * That is the whole mechanism, and the reason there is no timer anywhere: nothing
     * is being held open waiting for this. See CsatService for why (plan step 74).
     *
     * Two outcomes, and the second one is the control that protects the whole target:
     *   NO  -> trigger A. A human takes it, against the same ticket.
     *   YES -> the bounding rule. The agent option is SUPPRESSED, here in closure logic
     *          and never in the client, because a suppression rule that lives in the UI
     *          is one that can be bypassed.
     */
    @Transactional
    public SessionView recordCsat(UUID sessionId, boolean satisfied) {
        HelpSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        if (session.getCsatResult() != null) {
            throw new CsatService.CsatAlreadyRecordedException(sessionId, session.getCsatResult());
        }
        if (!csat.isExpected(session)) {
            throw new CsatService.CsatNotExpectedException(sessionId, session.getStatus());
        }

        session.recordCsat(satisfied ? CsatService.YES : CsatService.NO);
        sessions.saveAndFlush(session);

        if (!satisfied) {
            // Trigger A. The escalation opens or reuses the ticket AND stamps the rating
            // on it in the same committing transaction — see TicketService. Nothing is
            // written to that row from here afterwards: the copy this transaction holds
            // predates that commit, and flushing it would revert the trigger reason.
            session.setProcessInstanceId(csat.escalate(session));
        } else {
            // No escalation, so no other writer, so this one is safe.
            csat.recordOnTicket(session, true);
            log.info("session {} rated satisfied — the agent option is suppressed", sessionId);
        }

        sessions.save(session);

        return view(session, readNextStep(session));
    }

    // ---------------------------------------------------------------- start

    @Transactional
    public SessionView start(String spId, String freeText) {

        // PLAN STEP 85. A partner who typed instead of tapping should not be made to
        // navigate a menu to reach the thing they just described.
        //
        // SCOPE NOTE, worth keeping visible: the BRD specifies a menu-driven flow. This
        // path is an ADDITION and must never be the only way in — a partner who types
        // nothing still gets exactly the flow the requirement describes, and a
        // classifier that is down changes nothing about it.
        if (freeText != null && !freeText.isBlank()) {

            // COMMITTED FIRST, then re-read. This request may reach an action, and an
            // action's attempt row is written in its own transaction, which can only see
            // committed rows — so the session it ultimately references must be durable
            // before the process starts. See SessionOpener for the full chain.
            //
            // Re-reading is not ceremony either: the instance SessionOpener returns
            // belongs to a transaction that has ended. findById gives one managed by
            // THIS transaction, so the delegates and this method are working on the same
            // object rather than two copies that quietly disagree.
            UUID sessionId = opener.openCommitted(spId, freeText);
            HelpSession session = sessions.findById(sessionId).orElseThrow(
                    () -> new SessionNotFoundException(sessionId));

            NextStep routed = routeFreeText(session, freeText);
            writeNextStep(session, routed);
            sessions.save(session);
            return view(session, routed);
        }

        HelpSession session = HelpSession.start(spId, null);
        NextStep step = l1Menu();
        session.setCurrentStep(STEP_L1_SELECT);
        writeNextStep(session, step);
        sessions.save(session);
        return view(session, step);
    }

    // ---------------------------------------------------------------- input

    @Transactional
    public SessionView input(UUID sessionId, InputRequest request) {
        HelpSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        if (!"OPEN".equals(session.getStatus())) {
            throw new SessionClosedException(sessionId, session.getStatus());
        }

        // What the partner is talking about, when the client knows it. Recorded before
        // the concern is entered, because the fact providers and the action layer both
        // read it from the session row rather than from the turn.
        if (request.reference() != null && !request.reference().isBlank()) {
            session.setSelectedReference(request.reference().trim());
        }

        NextStep step = switch (session.getCurrentStep()) {
            case STEP_L1_SELECT -> handleL1(session, request.selection());
            case STEP_L2_SELECT -> handleL2(session, request.selection(), request.freeText());
            // The partner was asked to say more. This turn carries text, not a choice.
            case STEP_FREE_TEXT_CLARIFY -> routeFreeText(session, request.freeText());
            default -> throw new UnexpectedInputException(sessionId, session.getCurrentStep());
        };

        writeNextStep(session, step);
        sessions.save(session);
        return view(session, step);
    }

    private NextStep handleL1(HelpSession session, String selection) {
        if (selection == null || !catalogue.l1Exists(selection)) {
            // Re-offer rather than error. An unknown code is a stale client or a
            // typo, and neither deserves a dead end.
            return l1Menu();
        }

        List<Option> concerns = catalogue.activeConcernsIn(selection);
        session.selectConcern(selection, null);

        if (concerns.isEmpty()) {
            // A whole L1 path with nothing built under it yet. Same defined ending
            // as an inactive concern — the partner should not be able to tell the
            // difference, because from where they stand there is none.
            return notAvailable(session);
        }

        session.setCurrentStep(STEP_L2_SELECT);
        return NextStep.menu("L2_MENU", "Ismein se kya hua?", concerns);
    }

    /**
     * THE TEXT ARRIVES WITH THE SELECTION, and until now it was silently dropped.
     *
     * =====================================================================
     * FOUND BY THE DEMO SCRIPT, not by the test suite, and that is the point
     * =====================================================================
     *
     * Plan step 86 is "in-concern free text: the partner picks VIOL_R4_OTHERS, types a
     * reason that names a different concern, and is rerouted". The decision table for it,
     * `RerouteDelegate`, and the reroute guards were all built and are all green — but
     * every test drove reroute from the ENTRY point, where text arrives before any concern
     * exists. NOTHING EVER DROVE THE IN-CONCERN PATH, and through the API it did not work:
     * `handleL2` took the selection and discarded the text, so the concern ran with nothing
     * to classify and the partner sat exactly where they started.
     *
     * No error, no log line, no failing test. The feature was built, tested, documented as
     * done — and unreachable. It took a script driving the real API to notice, which is the
     * argument for having one.
     *
     * WHY ONE TURN RATHER THAN TWO. "Other — please describe" is one screen: the partner
     * chooses it and types their reason together. Splitting that into two round trips would
     * be a worse conversation for no gain, and the DTO already carries both fields.
     *
     * The text is APPENDED rather than assigned, for the same reason it is at the entry
     * point: "they told us three times and we never understood" is the thing worth knowing,
     * and keeping only the newest attempt hides it.
     */
    private NextStep handleL2(HelpSession session, String selection, String freeText) {
        if (freeText != null && !freeText.isBlank()) {
            session.appendFreeText(freeText);
        }

        if (selection == null) {
            return NextStep.menu("L2_MENU", "Ismein se kya hua?",
                    catalogue.activeConcernsIn(session.getL1Concern()));
        }

        ConcernCatalogue concern = catalogue.find(selection).orElse(null);

        // Unknown code, or a concern that exists but is not built: one ending, on
        // purpose. Step 37 of the plan — a defined "not available" step, not a 500
        // and not an empty menu.
        if (concern == null || !concern.isActive() || !concern.getL1Code().equals(session.getL1Concern())) {
            log.info("session {} selected unavailable concern {}", session.getId(), selection);
            session.selectConcern(session.getL1Concern(), concern == null ? null : selection);
            return notAvailable(session);
        }

        return enterConcern(session, concern, null);
    }

    /**
     * Free text, from the entry point or from a clarification turn — one path for both.
     *
     * EVERY ATTEMPT IS KEPT. The transcript, not just the last try, is what an agent
     * ends up reading if this reaches one, and "they told us three times and we never
     * understood" is the thing worth knowing. Only the NEWEST text is classified,
     * because a concatenation of failed attempts is not a better question.
     */
    private NextStep routeFreeText(HelpSession session, String text) {
        session.appendFreeText(text);

        IntentRouter.Outcome outcome = intent.route(session.getSpId(), text,
                session.getClarificationAttempts());

        if (outcome.isClarification()) {
            session.recordClarificationAttempt();
            session.setCurrentStep(STEP_FREE_TEXT_CLARIFY);
            return outcome.clarification();
        }

        // ALWAYS THE TRIAGE CONCERN — never the matched one directly. Its process, its
        // table and RerouteDelegate are the single routing mechanism, shared with text
        // typed inside a concern. Sending a confident match straight to its target would
        // be a second mechanism doing the same job, and the one that drifts is always
        // the one nobody demos.
        ConcernCatalogue triage = catalogue.find(IntentRouter.TRIAGE_CONCERN).orElseThrow(
                () -> new IllegalStateException("the triage concern is missing from the catalogue"));

        return enterConcern(session, triage, outcome.factsJson());
    }

    /**
     * The ONE way into a concern, whichever route the partner arrived by.
     *
     * The menu path and the free-text path both end here on purpose. If typing your way
     * in took a different route from tapping your way in, the two would drift — and the
     * one that drifts is always the less-exercised one, which is the free-text path
     * nobody demos.
     */
    private NextStep enterConcern(HelpSession session, ConcernCatalogue concern, String factsJson) {
        session.selectConcern(concern.getL1Code(), concern.getL2Code());
        session.setCurrentStep(STEP_CONCERN_SELECTED);

        // A row is inert until its artifacts exist. An active concern with no process
        // is a seeding mistake, and the partner must not pay for it — they get the
        // same defined ending, and we get a loud log line.
        if (concern.getProcessKey() == null) {
            log.error("concern {} is ACTIVE but has no process_key — check the catalogue seed",
                    concern.getL2Code());
            return notAvailable(session);
        }

        // The session must be on disk before the process starts: the delegate loads it
        // by id to write its ending.
        sessions.saveAndFlush(session);

        // factsJson, when present, is the classification already made for this turn.
        // Carrying it in means a real model is called ONCE per turn rather than twice —
        // and the process is not built around it: without it, it simply re-fetches.
        String instanceId = processRunner.start(
                concern.getProcessKey(), session.getId(), session.getSpId(),
                concern.getL2Code(), session.getSelectedReference(), factsJson);
        session.setProcessInstanceId(instanceId);

        // A T0 completes inside that call, so its ACT_RU_* rows — variables included —
        // are already gone. The ending is on the session row. Read it from there, never
        // from the instance. This is ADR-001 and spike 1.
        NextStep written = readNextStep(session);
        if (written == null) {
            log.error("process {} completed without writing a nextStep for session {}",
                    concern.getProcessKey(), session.getId());
            return notAvailable(session);
        }
        return written;
    }

    private NextStep notAvailable(HelpSession session) {
        session.setCurrentStep(STEP_ENDED);
        session.closeAs(STATUS_NOT_AVAILABLE);
        return NextStep.message("CONCERN_NOT_AVAILABLE",
                "Is baare mein abhi yahan madad nahi mil paayegi. Support team se baat karein.");
    }

    // ------------------------------------------------------------- reconnect

    @Transactional(readOnly = true)
    public SessionView view(UUID sessionId) {
        HelpSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        return view(session, readNextStep(session));
    }

    // ---------------------------------------------------------------- internals

    private NextStep l1Menu() {
        List<Option> options = catalogue.l1Groups().stream()
                .map(g -> new Option(g.code(), g.label()))
                .toList();
        return NextStep.menu("L1_MENU", "Aapko kis cheez mein dikkat hai?", options);
    }

    private SessionView view(HelpSession s, NextStep step) {
        boolean answered = s.getCsatResult() != null;
        return new SessionView(s.getId(), s.getSpId(), s.getStatus(), s.getCurrentStep(),
                s.getL1Concern(), s.getL2Concern(), step,
                csat.isExpected(s),
                // THE AFFORDANCE THE BOUNDING RULE CONTROLS. Absent until the partner has
                // answered — before that the bot's resolution stands on its own — and then
                // present only if they said it did not help, or the concern is one a person
                // is always meant to see (trigger B, the stated exception).
                answered && csat.offersAgentAfter(s, CsatService.YES.equals(s.getCsatResult())));
    }

    /**
     * nextStep is persisted as JSON on the session row. Phase 3 onwards the final
     * Service Task of a completed process writes it here for exactly the same reason
     * it is written here now: process variables are gone the moment the instance ends.
     */
    private void writeNextStep(HelpSession session, NextStep step) {
        try {
            session.setNextStep(step.type().name(), json.writeValueAsString(step));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("nextStep is not serialisable: " + step, e);
        }
    }

    private NextStep readNextStep(HelpSession session) {
        if (session.getNextStepPayload() == null) return null;
        try {
            return json.readValue(session.getNextStepPayload(), NextStep.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "stored nextStep for session " + session.getId() + " will not parse", e);
        }
    }

    // ---------------------------------------------------------------- failures

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(UUID id) { super("No help session " + id); }
    }

    public static class SessionClosedException extends RuntimeException {
        public SessionClosedException(UUID id, String status) {
            super("Help session " + id + " is " + status + " and takes no more input");
        }
    }

    public static class UnexpectedInputException extends RuntimeException {
        public UnexpectedInputException(UUID id, String step) {
            super("Help session " + id + " is at step " + step + ", which expects no input");
        }
    }
}
