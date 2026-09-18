package in.yesmadam.botin.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.escalation.EscalationContext;
import in.yesmadam.botin.process.ProcessVariables;
import in.yesmadam.botin.escalation.EscalationContextRepository;
import in.yesmadam.botin.safety.TicketAction;
import in.yesmadam.botin.safety.TicketActionRepository;
import in.yesmadam.botin.session.HelpSession;
import in.yesmadam.botin.session.HelpSessionRepository;
import in.yesmadam.botin.session.Ticket;
import in.yesmadam.botin.session.TicketRepository;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WHAT THE CASE TOUCHED, STEP BY STEP, FROM THE ENGINE'S OWN RECORD.
 *
 * =========================================================================
 * WHY THIS IS NOT A DIAGRAM WITH A CAPTION
 * =========================================================================
 *
 * The obvious way to explain a flow in a demo is a picture of the BPMN with somebody
 * talking over it. That explains the DESIGN, which is the easy half — and it is exactly as
 * true when the code does something else entirely. What a reviewer is really asking is
 * "did THIS case go through THOSE steps", and a picture cannot answer it.
 *
 * So every step here is read back from Flowable's own history tables: which activities
 * executed, in what order, how long each took. Not a narration of what should have
 * happened — the engine's record of what did. The design note beside each step is the only
 * part written by a person, and it says WHY the step exists, never what it did.
 *
 * =========================================================================
 * WHY THE STEPS THAT DID NOT RUN ARE SHOWN TOO
 * =========================================================================
 *
 * A path is only meaningful against the paths not taken. "This went to resolve" says
 * little; "this went to resolve and therefore never touched escalationContext,
 * agentHandoff or agentConnect" says what the tier model actually did. The full shape is
 * listed every time, and each step is marked taken or not — which also means the audience
 * sees the same fourteen steps for every case, and watches a different subset light up.
 *
 * FOUND BY BUSINESS KEY, NOT BY A STORED ID. The process is started with the help session
 * id as its business key, so a REROUTE — which ends one process and starts another against
 * the same conversation — appears here as two runs under one journey. A single stored
 * process-instance id on the session would have quietly shown only the second one, and the
 * reroute is precisely the case where somebody asks what happened.
 */
@Component
@Profile("console")
public class JourneyService {

    /**
     * WHY THE SHAPE IS READ FROM THE DEPLOYED MODEL AND NOT WRITTEN HERE.
     *
     * The first version of this class hardcoded the fourteen steps of `concern-generic`,
     * on the assumption that every concern runs it. Most do. FORGET_MPIN deliberately does
     * not — it keeps its own three-element process, because the T0 proof is worth more
     * running through its own file than folded into a shared one (see migration V5).
     *
     * So the hardcoded list rendered that concern as "2 of 15 steps taken" with thirteen
     * steps struck through that had never been part of its process at all. It looked like
     * a flow that did almost nothing, which is the opposite of what it actually shows.
     *
     * The shape now comes from the BPMN model Flowable has deployed. Whatever the process
     * is, its own steps are listed, in the order the file declares them. The notes below
     * are keyed by activity id and say WHY a step exists; a step with no note still
     * appears, named, rather than being hidden because nobody wrote a sentence about it.
     */
    private static final Map<String, String> NOTES = Map.ofEntries(
        Map.entry("preFlight",
            "Trigger B is an override, not a fallback, so it is checked before anything is "
          + "fetched or decided. For a mandatory-human concern there is nothing to decide."),
        Map.entry("fetchFacts",
            "Reads the world and concludes nothing. Facts are fetched even for a mandatory-human "
          + "concern, because the agent picking the case up still wants to know what we knew."),
        Map.entry("decide",
            "The only step that decides. It runs the concern's own decision table under a FIRST "
          + "hit policy, where row order is logic rather than formatting."),
        Map.entry("openTicket",
            "THE ONLY PLACE A TICKET IS CREATED. A T0 passes through here creating nothing, "
          + "which is what makes deflected volume impossible to find in a ticket count."),
        Map.entry("needsAgent",
            "Branches on agentRequired, NOT on tier. Tier is the concern table's answer; "
          + "agentRequired is the whole system's, and the pre-flight gate can have set it "
          + "before any table ran."),
        Map.entry("reroute",
            "Ends this concern and starts the target against the SAME conversation. The target "
          + "writes its own answer, exactly as if the partner had arrived through the menu."),
        Map.entry("escalationContext",
            "Written BEFORE the wait. Recomputing it when the agent opens the case would show "
          + "them a different world from the one the decision was made in."),
        Map.entry("agentHandoff",
            "Tells the partner an agent is coming, and leaves the conversation OPEN."),
        Map.entry("agentConnect",
            "THE PROCESS SLEEPS HERE, in the database, for as long as the queue takes. No thread "
          + "is held, no memory is held, and the wait survives a restart or a deployment."),
        Map.entry("agentCompletion",
            "Runs whenever the agent finishes — same instance, restart or no restart."),
        Map.entry("performAction",
            "THE ONLY PLACE MONEY OR STATE MOVES, and a no-op for every tier but T2. The attempt "
          + "is recorded and committed BEFORE the external call, the outcome after, each in its "
          + "own transaction."),
        Map.entry("actionOutcome",
            "An action that FAILED becomes a person's problem rather than a retry: the gateway "
          + "may have done the thing and failed to say so, and retrying that pays twice."),
        Map.entry("resolve",
            "Writes the answer the partner sees, closes the ticket and closes the conversation."),
        Map.entry("finaliseStep",
            "The whole of this concern. It writes the deflection step and finishes — no facts to "
          + "read, no table to consult, and deliberately NO Gate 1, because there is nothing a "
          + "ticket would record."),
        Map.entry("end",
            "The instance completes. Nothing is held open waiting for a rating.")
    );

    /** What kind of flow this is, said once at the top rather than on every step. */
    private static final Map<String, String> PROCESS_NOTES = Map.of(
        "concern-generic",
            "ONE PROCESS SERVES EVERY CONCERN. Which facts to fetch, which table to consult and "
          + "which kill switch applies are read at runtime from the concern_catalogue row, so "
          + "adding a concern adds a row, a fact provider and a .dmn file — never a flow file.",
        "forget-mpin",
            "THIS CONCERN KEEPS ITS OWN PROCESS, on purpose. Three elements, no facts, no "
          + "decision table and no Gate 1 — it is the T0 proof, and that claim is worth more "
          + "running through its own file than folded into the shared one. It is also the only "
          + "concern whose answer is a deeplink rather than a message.",
        "csat-escalation",
            "A SEPARATE PROCESS, started after the first one has already completed. Trigger A "
          + "is not a new complaint, so this reuses the existing ticket rather than opening one."
    );

    private final HistoryService history;
    private final RepositoryService repository;
    private final HelpSessionRepository sessions;
    private final TicketRepository tickets;
    private final TicketActionRepository actions;
    private final EscalationContextRepository escalations;
    private final DecisionTrace trace;
    private final ObjectMapper json;

    public JourneyService(HistoryService history, RepositoryService repository,
                          HelpSessionRepository sessions,
                          TicketRepository tickets, TicketActionRepository actions,
                          EscalationContextRepository escalations,
                          DecisionTrace trace, ObjectMapper json) {
        this.escalations = escalations;
        this.json = json;
        this.history = history;
        this.repository = repository;
        this.sessions = sessions;
        this.tickets = tickets;
        this.actions = actions;
        this.trace = trace;
    }

    public Map<String, Object> forSession(String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();

        UUID id = uuid(sessionId);
        HelpSession session = id == null ? null : sessions.findById(id).orElse(null);
        if (session == null) {
            out.put("found", false);
            out.put("why", "No conversation with that id.");
            return out;
        }

        out.put("found", true);
        out.put("sessionId", sessionId);
        out.put("spId", session.getSpId());
        out.put("l1Concern", session.getL1Concern());
        out.put("l2Concern", session.getL2Concern());
        out.put("reference", session.getSelectedReference());
        out.put("status", session.getStatus());
        out.put("entryFreeText", session.getEntryFreeText());

        // WHAT THE CLIENT ACTUALLY SENT. The flow starts before the engine does, and a
        // reviewer asking "how does this work" is asking about the whole path, not the
        // part that happens to be modelled.
        out.put("apiCalls", apiCalls(session));

        List<HistoricProcessInstance> instances = history.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(sessionId)
                .orderByProcessInstanceStartTime().asc()
                .list();

        List<Map<String, Object>> runs = new ArrayList<>();
        for (HistoricProcessInstance instance : instances) runs.add(run(instance, sessionId));
        out.put("runs", runs);

        if (instances.isEmpty()) {
            out.put("note", "No process ran for this conversation. That is an answer rather than "
                          + "a gap: a conversation that ended before a concern was chosen has "
                          + "nothing to run.");
        }
        return out;
    }

    /**
     * THE FACTS AS THEY WERE STORED, read from the engine's history rather than re-fetched.
     *
     * =====================================================================
     * WHY THIS EXISTS WHEN /console/trace ALREADY SHOWS THE FACTS
     * =====================================================================
     *
     * The trace is an in-memory ring of the last 200 conversations and is gone on
     * restart. It is the right thing for "what just happened" and the wrong thing for
     * "what did we believe when we paid this partner three weeks ago".
     *
     * `factsJson` is a process variable, so Flowable keeps it in ACT_HI_VARINST for as
     * long as the history is kept. That copy is durable, and this reads it. Same bytes
     * the decision saw — not a second fetch, which could answer differently the moment a
     * fact moves underneath it.
     *
     * It is also the answer to "can you show me the facts" when there is no SQL client on
     * the machine and the demo is running on in-memory H2, where there is nothing to
     * connect to.
     */
    public Map<String, Object> storedFacts(String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);

        List<Map<String, Object>> runs = new ArrayList<>();
        for (HistoricProcessInstance instance : history.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(sessionId)
                .orderByProcessInstanceStartTime().asc().list()) {

            Map<String, Object> run = new LinkedHashMap<>();
            run.put("instanceId", instance.getId());
            run.put("processKey", instance.getProcessDefinitionKey());
            run.put("startedAt", instance.getStartTime());
            run.put("source", "ACT_HI_VARINST, variable factsJson");

            for (HistoricVariableInstance v : history.createHistoricVariableInstanceQuery()
                    .processInstanceId(instance.getId()).list()) {
                if (!ProcessVariables.FACTS_JSON.equals(v.getVariableName())) continue;
                run.put("stored", v.getValue() == null ? null : String.valueOf(v.getValue()));
                run.put("facts", parsed(String.valueOf(v.getValue())));
            }
            if (!run.containsKey("facts")) {
                run.put("facts", null);
                run.put("note", "no factsJson on this run — a concern with no facts to read, "
                              + "or one that ended before they were fetched.");
            }
            runs.add(run);
        }

        out.put("runs", runs);
        out.put("found", !runs.isEmpty());
        if (runs.isEmpty()) {
            out.put("note", "No process ran for this conversation, so no facts were stored.");
        }
        return out;
    }

    /** The stored JSON, as an object, so a caller does not have to parse a string. */
    private Object parsed(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            return json.readValue(stored, Map.class);
        } catch (Exception e) {
            return stored;
        }
    }

    private List<Map<String, Object>> apiCalls(HelpSession session) {
        List<Map<String, Object>> calls = new ArrayList<>();
        calls.add(call("POST /help/sessions",
                session.getEntryFreeText() == null
                        ? "{\"spId\":\"" + session.getSpId() + "\"}"
                        : "{\"spId\":\"" + session.getSpId() + "\",\"freeText\":\"…\"}",
                session.getEntryFreeText() == null
                        ? "Opens the conversation and returns the L1 menu, built from the catalogue."
                        : "Opens the conversation with free text — no menu is shown, and the "
                        + "triage concern's own table does the routing."));
        if (session.getL1Concern() != null) {
            calls.add(call("POST /help/sessions/{id}/input",
                    "{\"selection\":\"" + session.getL1Concern() + "\"}",
                    "The L1 choice. Returns the active L2s under it — again from the catalogue, "
                  + "so switching a concern on in the database makes it appear with nothing rebuilt."));
        }
        if (session.getL2Concern() != null) {
            calls.add(call("POST /help/sessions/{id}/input",
                    session.getSelectedReference() == null
                            ? "{\"selection\":\"" + session.getL2Concern() + "\"}"
                            : "{\"selection\":\"" + session.getL2Concern() + "\",\"reference\":\""
                              + session.getSelectedReference() + "\"}",
                    "THE CALL THAT STARTS THE ENGINE. Everything below happens inside this one "
                  + "request."));
        }
        return calls;
    }

    private Map<String, Object> call(String endpoint, String body, String what) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("endpoint", endpoint);
        c.put("body", body);
        c.put("what", what);
        return c;
    }

    private Map<String, Object> run(HistoricProcessInstance instance, String sessionId) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("instanceId", instance.getId());
        run.put("processKey", instance.getProcessDefinitionKey());
        run.put("processNote", PROCESS_NOTES.get(instance.getProcessDefinitionKey()));
        run.put("completed", instance.getEndTime() != null);

        Map<String, Object> variables = new LinkedHashMap<>();
        for (HistoricVariableInstance v : history.createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).list()) {
            variables.put(v.getVariableName(), v.getValue());
        }

        // THE SHAPE FIRST, because it is also the tiebreak below.
        List<FlowNode> shape = shapeOf(instance.getProcessDefinitionId());
        Map<String, Integer> declared = new LinkedHashMap<>();
        for (int i = 0; i < shape.size(); i++) declared.put(shape.get(i).getId(), i);

        // THE ENGINE'S RECORD, in the order it happened. Not a re-run and not a guess.
        //
        // Sequence flows are skipped. Flowable records them as activity instances too, and
        // counting them made the step numbers jump — a three-element process reported its
        // end event as step 4.
        //
        // SORTED IN JAVA, NOT BY THE QUERY. Start times have millisecond precision and
        // these steps take under a millisecond each, so several share a timestamp and the
        // database's order among them is arbitrary — which showed on screen as "1, 4, 3, 5".
        // Declaration order breaks the tie: within one millisecond, the order the file
        // declares them is the only evidence of sequence there is.
        List<HistoricActivityInstance> ran = new ArrayList<>();
        for (HistoricActivityInstance a : history.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId()).list()) {
            if (!"sequenceFlow".equals(a.getActivityType())) ran.add(a);
        }
        ran.sort(Comparator
                .comparingLong((HistoricActivityInstance a) ->
                        a.getStartTime() == null ? 0L : a.getStartTime().getTime())
                .thenComparingInt(a -> declared.getOrDefault(a.getActivityId(), Integer.MAX_VALUE)));

        Map<String, HistoricActivityInstance> visited = new LinkedHashMap<>();
        Map<String, Integer> order = new LinkedHashMap<>();
        int position = 0;
        for (HistoricActivityInstance a : ran) {
            if (!visited.containsKey(a.getActivityId())) order.put(a.getActivityId(), ++position);
            visited.put(a.getActivityId(), a);
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        for (FlowNode node : shape) {
            HistoricActivityInstance actual = visited.get(node.getId());

            String note = NOTES.get(node.getId());
            if (note == null && node.getDocumentation() != null) note = node.getDocumentation();

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", node.getId());
            step.put("name", node.getName() == null || node.getName().isBlank()
                             ? node.getId() : node.getName());
            step.put("design", note == null ? "" : note);
            step.put("taken", actual != null);
            step.put("order", order.get(node.getId()));
            step.put("ms", actual == null ? null : actual.getDurationInMillis());
            step.put("evidence", actual == null ? List.of()
                                                : evidence(node.getId(), variables, sessionId));
            // THE HANDOVER ITSELF, when there was one. Only the escalation step carries it,
            // so it appears exactly when a case reached a person and never otherwise.
            if (actual != null && "escalationContext".equals(node.getId())) {
                step.put("detail", handover(sessionId));
            }
            steps.add(step);
        }
        run.put("steps", steps);
        return run;
    }

    /**
     * The process's own steps, in the order its file declares them. Sequence flows are not
     * steps; everything else is, including gateways, because "which way did it branch" is
     * one of the questions this view exists to answer.
     */
    private List<FlowNode> shapeOf(String processDefinitionId) {
        List<FlowNode> nodes = new ArrayList<>();
        try {
            BpmnModel model = repository.getBpmnModel(processDefinitionId);
            if (model == null || model.getMainProcess() == null) return nodes;
            for (FlowElement element : model.getMainProcess().getFlowElements()) {
                if (element instanceof FlowNode node) nodes.add(node);
            }
        } catch (Exception e) {
            // A journey that cannot read the model still shows the API calls and the
            // evidence. Better a partial answer than a page that fails to load.
            return nodes;
        }
        return nodes;
    }

    /** What THIS run did at that step, read back from what it wrote. Never re-derived. */
    private List<Map<String, Object>> evidence(String activityId,
                                               Map<String, Object> variables, String sessionId) {
        List<Map<String, Object>> facts = new ArrayList<>();
        switch (activityId) {
            case "preFlight" -> add(facts, "mandatoryHuman", variables.get("mandatoryHuman"));

            case "fetchFacts" -> trace.forSession(sessionId).ifPresent(t -> {
                int known = 0;
                for (Object value : t.facts().values()) if (value != null) known++;
                add(facts, "facts read", known + " of " + t.facts().size() + " known");
                // A NULL IS WHY MOST RULES DO NOT FIRE, so the unknown ones are named. The
                // full list with values is in the rule trace beside this.
                t.facts().forEach((name, value) -> {
                    if (value == null) add(facts, name, "not readable");
                });
            });

            case "decide" -> trace.forSession(sessionId).ifPresent(t -> {
                add(facts, "decision table", t.dmnKey());
                t.rules().stream().filter(DecisionTrace.RuleOutcome::fired).findFirst()
                        .ifPresent(fired -> add(facts, "rule that fired", "row " + (fired.index() + 1)));
                add(facts, "tier", t.tier());
                add(facts, "action", t.action());
                add(facts, "outcome type", t.outcomeType());
            });

            case "openTicket" -> {
                Ticket ticket = ticketFor(sessionId);
                if (ticket == null) {
                    add(facts, "ticket", "NONE — a T0 crosses this gate creating nothing");
                } else {
                    add(facts, "ticket", ticket.getId());
                    add(facts, "tier on the ticket", ticket.getTier());
                    add(facts, "why", ticket.getTriggerReason());
                }
            }

            case "needsAgent" -> {
                add(facts, "agentRequired", variables.get("agentRequired"));
                add(facts, "rerouteRequired", variables.get("rerouteRequired"));
            }

            case "reroute" -> add(facts, "rerouted to", variables.get("rerouteTarget"));

            case "escalationContext" -> {
                EscalationContext context = escalationFor(sessionId);
                if (context == null) {
                    add(facts, "handover", "no row — the step ran but wrote nothing");
                    break;
                }
                add(facts, "why a person has this", context.getTriggerReason());
                add(facts, "concern", context.getL2Concern());
                add(facts, "written at", context.getCreatedAt());
                add(facts, "what the partner typed",
                        context.getOriginalFreeText() == null
                                ? "nothing — they arrived through the menu"
                                : context.getOriginalFreeText());
                // THE ABSENT FIELDS ARE NAMED, not hidden. Three come from a classifier that
                // does not exist yet and one from a table this service cannot read. An empty
                // field an agent cannot account for is worse than a field that says why it
                // is empty — see docs/SIGNAL-REGISTER.md.
                if (context.getCategory() == null) {
                    add(facts, "category / confidence / extracted reason",
                            "absent — these come from the classifier, which is not built");
                }
                if (context.getMedalBand() == null) {
                    add(facts, "medal band", "absent — not in any table this service can read");
                }
            }

            case "performAction" -> {
                Ticket ticket = ticketFor(sessionId);
                List<TicketAction> rows = ticket == null ? List.of()
                        : actions.findByTicketIdIn(List.of(ticket.getId()));
                if (rows.isEmpty()) {
                    add(facts, "money", "nothing moved — this is a no-op for every tier but T2");
                }
                for (TicketAction row : rows) {
                    add(facts, row.getActionType(),
                            row.getStatus()
                          + (row.getAmountPaise() == null ? "" : " · " + row.getAmountPaise() + " paise")
                          + (row.getExternalReference() == null ? "" : " · ref " + row.getExternalReference()));
                }
            }

            case "actionOutcome" -> add(facts, "needed a human after the call",
                                        variables.get("agentRequired"));

            case "resolve" -> {
                add(facts, "action", variables.get("action"));
                add(facts, "outcome type", variables.get("outcomeType"));
            }

            default -> { }
        }
        return facts;
    }

    /**
     * WHAT THE AGENT OPENS. Written BEFORE the wait, never recomputed when the case is
     * picked up — recomputing would show the agent a different world from the one the
     * decision was made in, which is the one thing they must not be shown.
     *
     * Three blocks, in the order an agent reads them: what we knew, what this partner has
     * been through lately, and what was already attempted on their behalf.
     */
    private Map<String, Object> handover(String sessionId) {
        Map<String, Object> blocks = new LinkedHashMap<>();
        EscalationContext context = escalationFor(sessionId);
        if (context == null) return blocks;
        blocks.put("What we knew when we decided", pretty(context.getFactsSnapshot()));
        blocks.put("This partner, last 7 days", pretty(context.getRecentTicketHistory()));
        blocks.put("What was already attempted", pretty(context.getPriorActions()));
        return blocks;
    }

    private EscalationContext escalationFor(String sessionId) {
        Ticket ticket = ticketFor(sessionId);
        return ticket == null ? null : escalations.findById(ticket.getId()).orElse(null);
    }

    /** Stored as JSON. Rendered readably, or handed back as-is if it will not parse. */
    private String pretty(String stored) {
        if (stored == null || stored.isBlank()) return "—";
        try {
            return json.writerWithDefaultPrettyPrinter()
                       .writeValueAsString(json.readTree(stored));
        } catch (Exception e) {
            return stored;
        }
    }

    private Ticket ticketFor(String sessionId) {
        UUID id = uuid(sessionId);
        if (id == null) return null;
        List<Ticket> found = tickets.findByHelpSessionId(id);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void add(List<Map<String, Object>> into, String key, Object value) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("k", key);
        pair.put("v", value == null ? "—" : String.valueOf(value));
        into.add(pair);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
