package in.yesmadam.botin.platform.safety;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.togglz.console.TogglzConsoleServlet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 91 — THE GUARANTEE, asserted without starting anything.
 *
 *      There is no configuration of this service that produces an unauthenticated console.
 *
 * No Spring context, no database, no server: this reads the one decision that makes the
 * statement true. Deliberately in the style of DeployableXmlTest — a check that costs
 * milliseconds and cannot be broken by something unrelated is a check that stays run.
 *
 * WHY "NOT MOUNTED" AND NOT "MOUNTED BUT LOCKED". A misconfiguration should remove the
 * page, not expose it. With no credential there is nothing behind the URL to attack, no
 * login form to brute-force, and no chance that a later change to the filter ordering
 * quietly opens something that was only ever protected by a filter.
 */
class TogglzConsoleMountingTest {

    private final TogglzConsoleConfig config = new TogglzConsoleConfig();

    @Test
    @DisplayName("NO CREDENTIAL, NO CONSOLE — neither the servlet nor its gate is registered")
    void withoutAPasswordNothingIsMounted() {
        ServletRegistrationBean<TogglzConsoleServlet> servlet =
                config.togglzConsoleServlet("/togglz-console", "");
        FilterRegistrationBean<TogglzConsoleAuthFilter> gate =
                config.togglzConsoleAuthFilter("/togglz-console", "botin-ops", "");

        assertFalse(servlet.isEnabled(),
                "an unset password must remove the page, not serve it to everybody");
        assertFalse(gate.isEnabled(), "and nothing is left half-registered");
    }

    @Test
    @DisplayName("A null password is the same decision as a blank one")
    void nullIsNotADifferentCase() {
        // Three states again — missing, empty, set — and the Phase 11a incident was two
        // beans that treated them as two. One method, one if, all three visible.
        assertFalse(config.togglzConsoleServlet("/togglz-console", null).isEnabled());
        assertFalse(config.togglzConsoleServlet("/togglz-console", "   ").isEnabled());
    }

    @Test
    @DisplayName("With a credential the console is mounted, and the gate covers its assets too")
    void withAPasswordBothAreMounted() {
        ServletRegistrationBean<TogglzConsoleServlet> servlet =
                config.togglzConsoleServlet("/togglz-console", "a-real-password");
        FilterRegistrationBean<TogglzConsoleAuthFilter> gate =
                config.togglzConsoleAuthFilter("/togglz-console", "botin-ops", "a-real-password");

        assertTrue(servlet.isEnabled());
        assertTrue(gate.isEnabled());

        // The page AND everything under it. A login that protects the HTML and serves the
        // JavaScript to anyone is a strange thing to have built.
        assertTrue(gate.getUrlPatterns().contains("/togglz-console"));
        assertTrue(gate.getUrlPatterns().contains("/togglz-console/*"));

        // ONE path. The reason this filter exists instead of Spring Security is that it
        // touches nothing else, and that is only true if it is mapped to nothing else.
        assertEquals(2, gate.getUrlPatterns().size(),
                "this gate must cover the console and NOTHING else");
    }

    @Test
    @DisplayName("Togglz is told nobody is an admin unless the gate authenticated them")
    void theUserProviderAnswersOnlyForAnAuthenticatedRequest() {
        // Nothing bound on this thread — no console request is in flight.
        assertNull(config.togglzConsoleUserProvider().getCurrentUser(),
                "with no request in flight Togglz's own check must have nobody to say yes to");
    }
}
