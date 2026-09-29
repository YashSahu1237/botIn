package in.yesmadam.botin.platform.api.dto;
import java.util.List;

/**
 * The client contract. EVERY response carries one of these.
 *
 * The backend decides what happens next; the client renders it. There is no
 * second place where flow logic lives, which is the only way it does not drift.
 *
 * @param type     what to render — see {@link StepType}
 * @param code     a stable identifier for this step, for the client to key
 *                 behaviour and for us to assert on in tests. Never shown.
 * @param prompt   what the partner reads
 * @param options  choices, for MENU and DROPDOWN. Empty otherwise.
 * @param deeplink where to send the partner, for a self-serve deflection. Null otherwise.
 */
public record NextStep(
        StepType type,
        String code,
        String prompt,
        List<Option> options,
        String deeplink) {

    public static NextStep menu(String code, String prompt, List<Option> options) {
        return new NextStep(StepType.MENU, code, prompt, options, null);
    }

    public static NextStep message(String code, String prompt) {
        return new NextStep(StepType.MESSAGE, code, prompt, List.of(), null);
    }

    public static NextStep deeplink(String code, String prompt, String deeplink) {
        return new NextStep(StepType.MESSAGE, code, prompt, List.of(), deeplink);
    }

    public static NextStep text(String code, String prompt) {
        return new NextStep(StepType.TEXT, code, prompt, List.of(), null);
    }
}
