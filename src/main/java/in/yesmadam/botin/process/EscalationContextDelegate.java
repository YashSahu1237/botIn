package in.yesmadam.botin.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.escalation.EscalationContext;
import in.yesmadam.botin.escalation.EscalationContextRepository;
import in.yesmadam.botin.safety.TicketAction;
import in.yesmadam.botin.safety.TicketActionRepository;
import in.yesmadam.botin.session.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Writes the handover, one row, at the moment the case becomes a human's.
 *
 * THE POINT OF THIS STEP IS THAT AN AGENT SHOULD NEVER HAVE TO ASK THE PARTNER WHAT
 * ALREADY HAPPENED. Everything the bot knew, and what it did with it, is on one row
 * before the task appears in the queue.
 *
 * It is written HERE, before the User Task, and never recomputed when the agent opens
 * it. Recomputing would show the agent a different world from the one the decision was
 * made in — the wallet may have moved since, the order may have arrived — and then
 * "why did the bot say that" stops being answerable.
 */
@Component("escalationContextDelegate")
public class EscalationContextDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(EscalationContextDelegate.class);

    /** Trigger D's window, and the window an agent cares about: "again this week". */
    private static final int REPEAT_WINDOW_DAYS = 7;

    private final EscalationContextRepository contexts;
    private final TicketRepository tickets;
    private final TicketActionRepository actions;
    private final HelpSessionRepository sessions;
    private final ObjectMapper json;

    public EscalationContextDelegate(EscalationContextRepository contexts, TicketRepository tickets,
                                     TicketActionRepository actions, HelpSessionRepository sessions,
                                     ObjectMapper json) {
        this.contexts = contexts;
        this.tickets = tickets;
        this.actions = actions;
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID ticketId = UUID.fromString((String) execution.getVariable(ProcessVariables.TICKET_ID));
        UUID sessionId = UUID.fromString((String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));
        String spId = (String) execution.getVariable(ProcessVariables.SP_ID);
        String l2Concern = (String) execution.getVariable(ProcessVariables.L2_CONCERN);

        HelpSession session = sessions.findById(sessionId).orElseThrow();

        EscalationContext context = EscalationContext
                .of(ticketId, l2Concern, (String) execution.getVariable(ProcessVariables.TRIGGER_REASON))
                .withFacts(factsWithDecision(execution))
                .withFreeText(session.getEntryFreeText())
                .withHistory(historyOf(spId, l2Concern, ticketId), priorActionsOf(spId, ticketId));

        // category, confidenceScore and extractedReason stay null — they come from the
        // classifier, which does not exist yet. medalBand stays null too: it is not in
        // any table this service can read. All four are in the signal register as
        // absent, so an empty field here is a known gap rather than a lost value.
        contexts.save(context);

        log.info("escalation context written for ticket {} trigger={}",
                ticketId, context.getTriggerReason());
    }

    /**
     * The facts AND what the decision made of them.
     *
     * The facts alone answer "what did it know". An agent's first question is the other
     * one — "why did it give up" — so the tier, the action and the trigger travel with
     * them rather than being reconstructed from the ticket.
     */
    private String factsWithDecision(DelegateExecution execution) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tier", execution.getVariable(ProcessVariables.TIER));
        snapshot.put("action", execution.getVariable(ProcessVariables.ACTION));
        snapshot.put("outcomeType", execution.getVariable(ProcessVariables.OUTCOME_TYPE));
        snapshot.put("factsFailed", execution.getVariable(FetchFactsDelegate.FACTS_FAILED));
        snapshot.put("facts", raw((String) execution.getVariable(ProcessVariables.FACTS_JSON)));
        return write(snapshot);
    }

    /** "Is this the third time this week?" — the question an agent asks before anything else. */
    private String historyOf(String spId, String l2Concern, UUID thisTicket) {
        Instant since = Instant.now().minus(REPEAT_WINDOW_DAYS, ChronoUnit.DAYS);

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("ticketsAllTime", tickets.countBySpId(spId));
        // Minus one: this ticket was created moments ago by the step before this one,
        // and counting it would make every first contact look like a repeat.
        history.put("sameConcernLast7Days",
                Math.max(0, tickets.countBySpIdAndL2ConcernAndCreatedAtAfter(spId, l2Concern, since) - 1));
        history.put("recent", tickets.findTop10BySpIdOrderByCreatedAtDesc(spId).stream()
                .filter(t -> !t.getId().equals(thisTicket))
                .map(t -> Map.of(
                        "concern", t.getL2Concern(),
                        "tier", String.valueOf(t.getTier()),
                        "status", t.getStatus(),
                        "createdAt", String.valueOf(t.getCreatedAt())))
                .toList());
        return write(history);
    }

    /**
     * What we have already DONE for this partner, money included.
     *
     * The single most expensive thing an agent can get wrong is paying someone twice.
     * The attempt log is the record that prevents it, so it goes in front of them
     * rather than being something they could look up.
     */
    private String priorActionsOf(String spId, UUID thisTicket) {
        List<UUID> ticketIds = tickets.findTop10BySpIdOrderByCreatedAtDesc(spId).stream()
                .map(Ticket::getId).filter(id -> !id.equals(thisTicket)).toList();

        if (ticketIds.isEmpty()) return write(List.of());

        List<Map<String, Object>> prior = actions.findByTicketIdIn(ticketIds).stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ticketId", String.valueOf(a.getTicketId()));
                    m.put("action", a.getActionType());
                    m.put("status", a.getStatus());
                    m.put("amountPaise", a.getAmountPaise());
                    m.put("attemptedAt", String.valueOf(a.getAttemptedAt()));
                    return m;
                })
                .toList();
        return write(prior);
    }

    /** Re-read so the snapshot nests as an object rather than an escaped string. */
    private Object raw(String factsJson) {
        try {
            return json.readValue(factsJson == null ? "{}" : factsJson, Object.class);
        } catch (Exception e) {
            return factsJson;
        }
    }

    /**
     * A snapshot that will not serialise must not take the escalation down with it. The
     * partner is already waiting on a person; losing the handover over a formatting
     * problem would be the one failure that actually reaches them.
     */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            log.error("could not serialise part of the escalation context", e);
            return "{\"error\":\"snapshot could not be serialised\"}";
        }
    }
}
