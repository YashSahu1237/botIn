package in.yesmadam.botin.concern.amount.forgetmpin;

import in.yesmadam.botin.platform.api.dto.NextStep;
import in.yesmadam.botin.platform.process.ProcessVariables;
import in.yesmadam.botin.platform.process.ResponseTemplates;
import in.yesmadam.botin.platform.process.SessionStepWriter;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ForgetMpinDelegate.class);

    /** The code this concern resolves under. Its words live in the concern's templates file. */
    private static final String DEFLECT = "FORGET_MPIN_DEFLECT";

    private final SessionStepWriter steps;
    private final ResponseTemplates templates;
    private final String deeplink;

    public ForgetMpinDelegate(SessionStepWriter steps, ResponseTemplates templates,
                              @Value("${botin.deeplinks.forget-mpin}") String deeplink) {
        this.steps = steps;
        this.templates = templates;
        this.deeplink = deeplink;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID sessionId = UUID.fromString(
                (String) execution.getVariable(ProcessVariables.HELP_SESSION_ID));

        // The deeplink is configuration, not a literal. The app team owns where this
        // points, and it will change without this class changing. The WORDS are configuration
        // too now — they were the last partner-facing sentence still living in Java.
        String prompt = templates.promptFor(DEFLECT);
        if (prompt == null) {
            // Same honest failure as ResolveDelegate: say nothing is prepared rather than
            // inventing prose, and name the code in the log. The LINK still goes out, because
            // the link is the whole value of this deflection.
            log.error("no response template for '{}' — the partner gets the unprepared wording. "
                    + "Add it to resources/concern/amount/forgetmpin/templates.properties", DEFLECT);
            prompt = "Is baare mein hum aapko abhi jawab nahi de paa rahe. "
                   + "Neeche diye gaye link par jaayein.";
        }

        NextStep step = NextStep.deeplink(DEFLECT, prompt, deeplink);

        steps.writeAndClose(sessionId, step, "CLOSED_DEFLECTED");
    }
}
