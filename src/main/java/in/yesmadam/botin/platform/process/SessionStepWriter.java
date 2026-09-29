package in.yesmadam.botin.platform.process;

import in.yesmadam.botin.platform.api.dto.NextStep;
import in.yesmadam.botin.platform.session.HelpSession;
import in.yesmadam.botin.platform.session.HelpSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one place a process is allowed to tell the client what happens next.
 *
 * THE TRAP THIS EXISTS TO AVOID. A delegate's obvious move is to set a process
 * variable and let the caller read it back after startProcessInstanceByKey returns.
 * That works right up until the process completes synchronously — which every T0
 * does — because a completed instance has its ACT_RU_* rows deleted, variables
 * included. The read returns null, and it returns null only for the flows that
 * finish quickly, which is exactly the set that looks fine in a demo.
 *
 * So every process writes its ending HERE, onto the help_session row, and the API
 * layer re-reads it from there. Spike 1 is what established this.
 */
@Component
public class SessionStepWriter {

    private static final Logger log = LoggerFactory.getLogger(SessionStepWriter.class);

    private final HelpSessionRepository sessions;
    private final ObjectMapper json;

    public SessionStepWriter(HelpSessionRepository sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    /**
     * Write the ending and close the session. Used by every terminal outcome — a
     * deflection, a resolution, an upheld appeal.
     *
     * @param terminalStatus the status to close with, so the reason survives on the
     *                       row. CLOSED_DEFLECTED and CLOSED_RESOLVED are different
     *                       facts and the rollout needs to count them separately.
     */
    public void writeAndClose(UUID sessionId, NextStep step, String terminalStatus) {
        HelpSession session = load(sessionId);

        session.setNextStep(step.type().name(), serialise(step));
        session.setCurrentStep("ENDED");
        session.closeAs(terminalStatus);
        sessions.save(session);

        log.info("session {} ended: {} / {}", sessionId, terminalStatus, step.code());
    }

    /**
     * Write the step and leave the session OPEN, because the process is waiting.
     *
     * Needed the moment a T3 exists. The partner has to be told an agent is coming
     * while the instance sits on its User Task, possibly for hours. Closing here would
     * be wrong twice: the conversation is still live, and the agent's completion still
     * has to write the real ending onto this same row.
     *
     * @param currentStep where the session now sits, so a reconnect knows what it is
     *                    looking at and HelpSessionService knows this step takes no
     *                    menu input.
     */
    public void writeAndWait(UUID sessionId, NextStep step, String currentStep) {
        HelpSession session = load(sessionId);

        // A process cannot be waiting on a finished conversation, so if this session was
        // closed, it is not any more. Trigger A is how that happens: a resolution the
        // partner rejected is now with an agent, and a session left sitting at
        // CLOSED_DEFLECTED would go on being counted as a successful deflection — in
        // precisely the cases where the bot was told it got the answer wrong.
        if (!"OPEN".equals(session.getStatus())) {
            log.info("session {} reopened from {} — a person has it now",
                    sessionId, session.getStatus());
            session.reopen();
        }

        session.setNextStep(step.type().name(), serialise(step));
        session.setCurrentStep(currentStep);
        sessions.save(session);

        log.info("session {} waiting at {}: {}", sessionId, currentStep, step.code());
    }

    private HelpSession load(UUID sessionId) {
        return sessions.findById(sessionId).orElseThrow(
                () -> new IllegalStateException("process referenced a session that does not exist: " + sessionId));
    }

    private String serialise(NextStep step) {
        try {
            return json.writeValueAsString(step);
        } catch (JsonProcessingException e) {
            // Better to fail the process than to close a session with an unreadable
            // ending — the partner would see nothing and the row would look fine.
            throw new IllegalStateException("nextStep is not serialisable: " + step, e);
        }
    }
}
