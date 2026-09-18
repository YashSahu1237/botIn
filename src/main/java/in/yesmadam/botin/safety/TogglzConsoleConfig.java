package in.yesmadam.botin.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.togglz.console.TogglzConsoleServlet;
import org.togglz.core.user.SimpleFeatureUser;
import org.togglz.core.user.UserProvider;

/**
 * MOUNTING THE CONSOLE — plan step 91, and the rule it enforces is one sentence:
 *
 *      THERE IS NO WAY TO GET AN UNAUTHENTICATED CONSOLE.
 *
 * Not "it is authenticated by default", not "remember to set it in production". With no
 * credential configured the servlet is NOT REGISTERED — the URL 404s because there is
 * nothing behind it. A misconfiguration therefore removes the page instead of exposing it,
 * which is the direction a mistake should fail in when the page moves money.
 *
 * =========================================================================
 * WHY THE STARTER DOES NOT REGISTER IT
 * =========================================================================
 *
 * `togglz.console.enabled` is a boolean, and the condition that matters here is not a
 * boolean — it is "only if a credential exists". A property cannot express that, so the
 * registration is ours and the starter's switch is off. ONE OWNER. Two registrations of the
 * same servlet on the same path is the kind of thing that works on one machine.
 *
 * And a related lesson this project already paid for once, in Phase 11a: two beans whose
 * conditions were exclusive over two of three states both matched, and the context died.
 * The condition here is an `if` inside a method, where "missing", "empty" and "set" are
 * visibly one decision.
 *
 * =========================================================================
 * WHAT THE CONSOLE IS FOR, SO THE AUDIENCE IS CLEAR
 * =========================================================================
 *
 * An operations person, during an incident, turning off one concern's automation without a
 * deployment and without a developer. That is the whole value of ADR-007, and a switch
 * that needs an engineer to flip it is not a kill switch — it is a code change with extra
 * steps.
 */
@Configuration
public class TogglzConsoleConfig {

    private static final Logger log = LoggerFactory.getLogger(TogglzConsoleConfig.class);

    /** The console servlet, or a registration that is switched off when nobody can log in. */
    @Bean
    public ServletRegistrationBean<TogglzConsoleServlet> togglzConsoleServlet(
            @Value("${botin.togglz.console.path}") String path,
            @Value("${botin.togglz.console.password}") String password) {

        ServletRegistrationBean<TogglzConsoleServlet> registration =
                new ServletRegistrationBean<>(new TogglzConsoleServlet(), path + "/*");
        registration.setName("togglzConsole");
        registration.setLoadOnStartup(-1);

        if (isBlank(password)) {
            registration.setEnabled(false);
            log.warn("KILL-SWITCH CONSOLE IS NOT MOUNTED — TOGGLZ_CONSOLE_PASSWORD is not set. "
                   + "Flags can still be read and are still enforced; they cannot be changed "
                   + "without a restart. Set the variable to mount it at {}.", path);
        } else {
            log.info("kill-switch console mounted at {}, behind HTTP basic authentication", path);
        }
        return registration;
    }

    /**
     * The gate, on that path and no other.
     *
     * Registered against the URL PATTERN rather than the servlet name so it also covers the
     * console's static resources — a login that protects the page but serves its JavaScript
     * to anyone is a strange thing to have built.
     */
    @Bean
    public FilterRegistrationBean<TogglzConsoleAuthFilter> togglzConsoleAuthFilter(
            @Value("${botin.togglz.console.path}") String path,
            @Value("${botin.togglz.console.user}") String user,
            @Value("${botin.togglz.console.password}") String password) {

        FilterRegistrationBean<TogglzConsoleAuthFilter> registration =
                new FilterRegistrationBean<>(new TogglzConsoleAuthFilter(user, password));
        registration.addUrlPatterns(path, path + "/*");
        registration.setName("togglzConsoleAuth");
        registration.setOrder(Integer.MIN_VALUE);   // before anything else can serve the page
        registration.setEnabled(!isBlank(password));
        return registration;
    }

    /**
     * Who Togglz thinks is logged in — nobody, unless the filter above let them through.
     *
     * Togglz's own `secured` check asks a `UserProvider` whether the current user is a
     * feature admin, and with no provider it has no way to say yes. This makes the two
     * agree: the ONLY person Togglz will ever call an admin is one this filter has already
     * authenticated on this thread. Defence in depth rather than a second, different answer
     * to the same question.
     *
     * `@Primary` because the starter contributes a no-op provider of its own; this is the
     * one that should win, and stating it beats relying on the ordering of two conditions.
     */
    @Bean
    @Primary
    public UserProvider togglzConsoleUserProvider() {
        return () -> {
            String operator = TogglzConsoleAuthFilter.currentOperator();
            return operator == null ? null : new SimpleFeatureUser(operator, true);
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
