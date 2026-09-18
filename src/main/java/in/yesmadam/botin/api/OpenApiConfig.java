package in.yesmadam.botin.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * PLAN STEP 94 — the client contract, published by the service that implements it.
 *
 * WHY GENERATED AND NOT WRITTEN. A spec maintained beside the code disagrees with it the
 * first time somebody is in a hurry, and nothing anywhere reports the disagreement. This one
 * is read out of the running application: if an endpoint changes shape, the published
 * contract changes with it, and if it does not appear here it does not exist.
 *
 * WHAT THE READER OF THIS SPEC MOST NEEDS TO KNOW is not in any endpoint signature, so it is
 * stated in the description below: the backend owns the entire flow, and the client is a
 * renderer. Somebody integrating against this will otherwise assume — reasonably, from every
 * other support API they have met — that the client decides what to show next. Here it never
 * does. It draws whatever `nextStep` describes.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI botinOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BOTIn — Service Partner support")
                        .version("POC")
                        .description("""
                            Bot-first support for Service Partners.

                            ## The one thing to understand before integrating

                            **The backend owns 100% of the flow. The client is a thin renderer.**

                            Every response carries a `nextStep` descriptor with a `type` — one of
                            MENU, DROPDOWN, TEXT, MESSAGE, CSAT — plus the options or content that
                            go with it. The client draws what it is told and posts back what the
                            partner did. It never decides what comes next, never holds a copy of
                            the menu, and never needs to know a concern exists.

                            The consequence, which is the point: **adding a concern is a data
                            change**, visible on the next request with no deploy on either side.
                            Adding a SIXTH step type would be a client release. That asymmetry is
                            deliberate.

                            ## Reconnecting

                            `nextStep` is persisted on the session row, so `GET /help/sessions/{id}`
                            returns exactly what the previous response returned. A partner who loses
                            the app mid-conversation resumes where they were rather than starting
                            again.

                            ## Tiers, and what the partner never sees

                            A case is resolved by the bot (T0/T1), by an automated action (T2), or
                            by a person (T3). A T0 deflection creates **no ticket at all** — that is
                            the system's central claim, and it is why deflected volume cannot appear
                            in ticket counts. None of this vocabulary is exposed to the partner.
                            """))
                .tags(List.of(
                        new Tag().name("help").description(
                                "What the partner's app calls. Start a session, send input, "
                              + "reconnect, answer CSAT"),
                        new Tag().name("concerns").description(
                                "The taxonomy, read from the catalogue. Derived from data, not "
                              + "hard-coded — a concern switched on appears here immediately"),
                        new Tag().name("agent").description(
                                "The queue side of a handover. An agent claims a Flowable User "
                              + "Task, reads the context gathered before the handover, completes it")));
    }
}
