package in.yesmadam.botin.process;

import in.yesmadam.botin.api.dto.NextStep;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The last thing that happens before the process STOPS and waits for a person.
 *
 * WHY THIS IS A SEPARATE STEP FROM THE USER TASK. The instant the User Task is reached
 * the process is asleep, and it may sleep for hours. The partner is still holding a
 * phone. So the step they see has to be written BEFORE the wait begins, not after it
 * ends — otherwise the API call that started all this returns with nothing to render.
 *
 * AND WHY IT WRITES WITHOUT CLOSING. Every other ending in this system closes the
 * session. This one must not: the conversation is still live, and the agent's
 * completion has to write the real ending onto this same row later. A closed session
 * would also refuse the reconnect the partner is most likely to make — they will come
 * back to check whether anyone has replied.
 */
@Component("agentHandoffDelegate")
public class AgentHandoffDelegate implements JavaDelegate {

    /** The session sits here for as long as the queue takes. */
    public static final String STEP_AGENT_CONNECT = "AGENT_CONNECT";

    private final SessionStepWriter steps;
    private final ResponseTemplates templates;

    public AgentHandoffDelegate(SessionStepWriter steps, ResponseTemplates templates) {
        this.steps = steps;
        this.templates = templates;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID sessionId = UUID.fromString(
                (String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));

        // Deliberately the same sentence for every trigger. The partner does not need to
        // know whether they are here because a rule said so, because a lookup failed, or
        // because their concern always reaches a person — and telling them would leak
        // how the routing works to someone who could then aim at it.
        NextStep step = NextStep.message("AGENT_CONNECTING", templates.promptFor("AGENT_CONNECTING"));

        steps.writeAndWait(sessionId, step, STEP_AGENT_CONNECT);
    }
}
