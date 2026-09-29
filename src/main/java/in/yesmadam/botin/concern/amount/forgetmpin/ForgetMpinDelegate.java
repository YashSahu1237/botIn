package in.yesmadam.botin.concern.amount.forgetmpin;

import in.yesmadam.botin.platform.api.dto.NextStep;
import in.yesmadam.botin.platform.process.ProcessVariables;
import in.yesmadam.botin.platform.process.SessionStepWriter;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * FORGET_MPIN, the whole of it.
 *
 * A pure deflection: show the partner where to reset their own MPIN, end the
 * session. No ticket, no action, no agent. The bean name must match the
 * delegateExpression in forget-mpin.bpmn20.xml — ${forgetMpinDelegate}.
 */
@Component("forgetMpinDelegate")
public class ForgetMpinDelegate implements JavaDelegate {

    private final SessionStepWriter steps;
    private final String deeplink;

    public ForgetMpinDelegate(SessionStepWriter steps,
                              @Value("${botin.deeplinks.forget-mpin}") String deeplink) {
        this.steps = steps;
        this.deeplink = deeplink;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID sessionId = UUID.fromString(
                (String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));

        // The deeplink is configuration, not a literal. The app team owns where this
        // points, and it will change without this class changing.
        NextStep step = NextStep.deeplink(
                "FORGET_MPIN_DEFLECT",
                "MPIN aap khud reset kar sakte hain. Neeche diye gaye link par jaayein.",
                deeplink);

        steps.writeAndClose(sessionId, step, "CLOSED_DEFLECTED");
    }
}
