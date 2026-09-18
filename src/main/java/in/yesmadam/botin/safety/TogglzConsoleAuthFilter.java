package in.yesmadam.botin.safety;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * THE GATE IN FRONT OF THE CONSOLE — plan step 91.
 *
 * The console can turn off automatic wallet credits for every partner in the country, and
 * turn them back on. Unauthenticated, it is a URL that moves money, and "it is only a POC"
 * is exactly how an unauthenticated admin page reaches production: nobody adds
 * authentication to a thing that already works.
 *
 * =========================================================================
 * WHY THIS IS A FILTER AND NOT SPRING SECURITY
 * =========================================================================
 *
 * Spring Security is the right answer for Wave 1 and decision D-7 says so. It is not the
 * answer for one path in a POC, because `spring-boot-starter-security` secures EVERY
 * endpoint the moment it is on the classpath and enables CSRF on every POST. The 165 tests
 * in this suite would then be passing or failing on how well a `SecurityFilterChain` was
 * written, which is a large blast radius bought for one page.
 *
 * This filter is mounted on ONE path and touches nothing else, and a test asserts exactly
 * that: the partner API still answers with no credentials. When Spring Security arrives it
 * replaces this class and deletes it — it is not a foundation, it is a gate on one door.
 *
 * =========================================================================
 * TWO DETAILS THAT ARE NOT DECORATION
 * =========================================================================
 *
 * `MessageDigest.isEqual` rather than `String.equals`. String comparison returns as soon as
 * two bytes differ, so the time it takes leaks how much of the password was right. It is a
 * marginal attack and a free defence.
 *
 * The credential arrives from the environment with NO fallback value, and
 * `TogglzConsoleConfig` refuses to mount the console at all when it is missing. A default
 * password is worse than no password: it looks like security, it is committed to the
 * repository, and it is the same on every machine that ever runs this.
 */
public class TogglzConsoleAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TogglzConsoleAuthFilter.class);

    /** Who got through, for the request that is currently on this thread. */
    private static final ThreadLocal<String> AUTHENTICATED = new ThreadLocal<>();

    private final String user;
    private final String password;

    public TogglzConsoleAuthFilter(String user, String password) {
        this.user = user;
        this.password = password;
    }

    /** @return the console operator on this thread, or null if this is not a console request. */
    public static String currentOperator() {
        return AUTHENTICATED.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader("Authorization");
        if (!isValid(presented)) {
            // The realm is what makes a browser ask. Without this header the page is simply
            // broken rather than protected, and somebody "fixes" it by removing the filter.
            response.setHeader("WWW-Authenticate", "Basic realm=\"BOTIn kill switches\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            log.warn("rejected an unauthenticated request to the kill-switch console from {}",
                    request.getRemoteAddr());
            return;
        }

        // NAMED, AND ONLY FOR THE LENGTH OF THIS REQUEST. Bound so Togglz's own
        // authorisation has a user to recognise; cleared in a finally so a pooled thread
        // cannot carry an operator into somebody else's request.
        AUTHENTICATED.set(user);
        try {
            log.info("kill-switch console opened by '{}'", user);
            chain.doFilter(request, response);
        } finally {
            AUTHENTICATED.remove();
        }
    }

    private boolean isValid(String authorizationHeader) {
        if (password == null || password.isBlank()) return false;   // cannot happen; see the config
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) return false;

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return false;
        }

        int split = decoded.indexOf(':');
        if (split < 0) return false;

        return constantTimeEquals(decoded.substring(0, split), user)
            && constantTimeEquals(decoded.substring(split + 1), password);
    }

    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                                     expected.getBytes(StandardCharsets.UTF_8));
    }
}
