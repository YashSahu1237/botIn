package in.yesmadam.botin.platform.process;

import in.yesmadam.botin.platform.action.ActionRegistry;
import in.yesmadam.botin.platform.action.ActionRequest;
import in.yesmadam.botin.platform.action.ActionResult;
import in.yesmadam.botin.platform.action.ActionService;
import in.yesmadam.botin.platform.safety.TicketAction;
import in.yesmadam.botin.platform.safety.TicketActionRecorder;
import in.yesmadam.botin.platform.session.TicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * THE ONLY PLACE MONEY OR STATE MOVES. Runs for a T2 and does nothing for anything else.
 *
 * THE ORDER OF THE FOUR STEPS IS THE SAFETY ARGUMENT, and it is the whole of ADR-005:
 *
 *   1. RECORD THE ATTEMPT, in its own transaction, committed before anything else.
 *   2. CALL THE EXTERNAL SERVICE.
 *   3. RECORD THE OUTCOME, in its own transaction.
 *   4. Let the surrounding transaction do whatever it does.
 *
 * Step 1 commits first so that if the process dies at step 2 — the JVM is killed, the
 * pod is rescheduled, the gateway hangs — the evidence that we called somebody SURVIVES.
 * A retry then collides with the idempotency key and refuses. Record-after-call is the
 * intuitive order and it is the one that pays twice: the call succeeds, the process
 * dies, nothing was written, and the retry looks like a first attempt.
 *
 * REQUIRES_NEW IS WHAT MAKES THAT TRUE. Without it the ATTEMPTED row is inside the
 * caller's transaction and a rollback erases it — including a rollback caused by the
 * very failure that makes the retry happen.
 *
 * A FAILED ACTION GOES TO A HUMAN, NOT TO A RETRY. Nothing here retries automatically.
 * The gateway may have succeeded and failed to tell us, so the safe move is to record
 * what we know and let a person look — which is also what the partner would want.
 */
@Component("performActionDelegate")
public class PerformActionDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(PerformActionDelegate.class);

    private final ActionRegistry actions;
    private final TicketActionRecorder recorder;
    private final TicketService tickets;
    private final ObjectMapper json;

    public PerformActionDelegate(ActionRegistry actions, TicketActionRecorder recorder,
                                 TicketService tickets, ObjectMapper json) {
        this.actions = actions;
        this.recorder = recorder;
        this.tickets = tickets;
        this.json = json;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String tier = (String) execution.getVariable(ProcessVariables.TIER);
        if (!"T2".equals(tier)) return;

        String actionCode = (String) execution.getVariable(ProcessVariables.ACTION);
        String ticketId = (String) execution.getVariable(ProcessVariables.TICKET_ID);

        // DecideDelegate already refused to produce a T2 with no service behind it, so
        // reaching here without one means those two have drifted apart. Loud, and a
        // human, rather than a silent no-op that looks like a successful resolution.
        Optional<ActionService> service = actions.find(actionCode);
        if (service.isEmpty() || ticketId == null) {
            log.error("T2 reached the action step with no service for '{}' (ticket {}) — "
                    + "DecideDelegate and ActionRegistry disagree", actionCode, ticketId);
            execution.setVariable(ProcessVariables.AGENT_REQUIRED, true);
            execution.setVariable(ProcessVariables.TIER, "T3");
            return;
        }

        perform(execution, service.get(), UUID.fromString(ticketId), actionCode);
    }

    private void perform(DelegateExecution execution, ActionService service,
                         UUID ticketId, String actionCode) {

        ActionRequest request = new ActionRequest(
                ticketId,
                (String) execution.getVariable(ProcessVariables.SP_ID),
                (String) execution.getVariable(ProcessVariables.L2_CONCERN),
                (String) execution.getVariable(ProcessVariables.SELECTED_REFERENCE),
                facts(execution));

        String externalReference = service.externalReferenceFor(request);

        // 1. ATTEMPT — committed in its own transaction before anything leaves this JVM.
        Optional<TicketAction> attempt = recorder.recordAttempt(
                ticketId, actionCode, externalReference, null, describe(request));

        if (attempt.isEmpty()) {
            // Already attempted, under this key. Not an error and not a failure — the
            // partner has their money and the correct response is to say so plainly.
            log.info("{} already attempted for reference {} — telling the partner, not paying again",
                    actionCode, externalReference);
            execution.setVariable(ProcessVariables.ACTION, alreadyDoneCode(actionCode));
            execution.setVariable(ProcessVariables.TIER, "T1");
            return;
        }

        try {
            // 2. THE EXTERNAL CALL.
            ActionResult result = service.execute(request);

            // 3. OUTCOME, its own transaction again.
            // THE AMOUNT GOES ON THE ROW. Without it the ledger says a credit succeeded and
            // not how much, and nothing can be reconciled against the gateway.
            recorder.recordOutcome(attempt.get().getId(), true, describeResult(result),
                    result.amountPaise());
            execution.setVariable(ProcessVariables.ACTION_REFERENCE, result.externalReference());
            log.info("{} succeeded for {} -> {}", actionCode, externalReference, result.externalReference());

        } catch (Exception e) {
            recorder.recordOutcome(attempt.get().getId(), false, e.getClass().getSimpleName() + ": " + e.getMessage());

            // NO AUTOMATIC RETRY. The gateway may have done the thing and failed to tell
            // us, and a retry on that is a second payment. A person decides.
            log.error("{} FAILED for reference {} — escalating, not retrying", actionCode, externalReference, e);

            // THE TICKET MOVES TOO, not just the process variables. Gate 1 wrote it as a
            // T2 before the call; leaving it there would count this failure as automation
            // that worked, in the one metric meant to show whether automating this
            // concern was a good idea.
            tickets.escalateAfterFailedAction(ticketId, "AGENT_ACTION_FAILED",
                    DecideDelegate.TRIGGER_CONCERN_DECISION);

            execution.setVariable(ProcessVariables.TIER, "T3");
            execution.setVariable(ProcessVariables.ACTION, "AGENT_ACTION_FAILED");
            execution.setVariable(ProcessVariables.OUTCOME_TYPE, "TICKET");
            execution.setVariable(ProcessVariables.AGENT_REQUIRED, true);
            execution.setVariable(ProcessVariables.TRIGGER_REASON, DecideDelegate.TRIGGER_CONCERN_DECISION);
        }
    }

    /** INFORM_ALREADY_* is the partner-facing half of the duplicate guard. */
    private String alreadyDoneCode(String actionCode) {
        return "AUTO_CREDIT_WALLET".equals(actionCode) ? "INFORM_ALREADY_CREDITED" : "INFORM_ALREADY_DONE";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> facts(DelegateExecution execution) {
        try {
            String factsJson = (String) execution.getVariable(ProcessVariables.FACTS_JSON);
            return json.readValue(factsJson == null ? "{}" : factsJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String describe(ActionRequest request) {
        try { return json.writeValueAsString(request); } catch (Exception e) { return "{}"; }
    }

    private String describeResult(ActionResult result) {
        try { return json.writeValueAsString(result); } catch (Exception e) { return "{}"; }
    }
}
