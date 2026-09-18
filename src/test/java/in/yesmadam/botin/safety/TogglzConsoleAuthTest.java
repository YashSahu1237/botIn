package in.yesmadam.botin.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 91 — the console, over a real HTTP connection.
 *
 * THE ONLY TEST IN THIS SUITE THAT STARTS A REAL SERVER, and it has to. MockMvc calls the
 * Spring MVC dispatcher directly: it never touches a servlet registered beside the
 * dispatcher, and it never runs the servlet filter chain. A console test on MockMvc would
 * pass without ever executing the code that protects the console — the most expensive kind
 * of green there is.
 *
 * WHAT IS ACTUALLY AT STAKE. This page can stop, or restart, automatic payments to every
 * partner in the country. "It is only a POC" is how an unauthenticated admin page reaches
 * production: nobody adds authentication to a thing that already works.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "botin.togglz.console.user=console-operator",
                "botin.togglz.console.password=a-password-only-this-test-knows"
        })
@ActiveProfiles("test")
class TogglzConsoleAuthTest {

    private static final String PATH = "/togglz-console";
    private static final String USER = "console-operator";
    private static final String PASSWORD = "a-password-only-this-test-knows";

    @Autowired TestRestTemplate http;

    @Test
    @DisplayName("No credentials — 401, and a realm so a browser knows to ask")
    void anonymousIsRefused() {
        ResponseEntity<String> response = http.getForEntity(PATH, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE),
                "without this header the page looks broken rather than protected, and the "
              + "fix somebody reaches for is removing the filter");
    }

    @Test
    @DisplayName("The right user with the wrong password is nobody")
    void aWrongPasswordIsRefused() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.withBasicAuth(USER, "not-the-password").getForEntity(PATH, String.class)
                        .getStatusCode());
    }

    @Test
    @DisplayName("The right password with the wrong user is nobody either")
    void aWrongUserIsRefused() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.withBasicAuth("someone-else", PASSWORD).getForEntity(PATH, String.class)
                        .getStatusCode());
    }

    @Test
    @DisplayName("The correct credential gets through to a console that is actually there")
    void theOperatorGetsIn() {
        HttpStatusCode status = http.withBasicAuth(USER, PASSWORD)
                .getForEntity(PATH, String.class).getStatusCode();

        // Deliberately not asserting 200 on a specific page: what this class guarantees is
        // the gate, and the console's own routing is Togglz's business. Anything at or above
        // 400 is a real failure though — 401/403 means the gate or Togglz's own check
        // refuses the one person who should get in, and 404 means the page was never
        // mounted, which would make the three tests above pass for the wrong reason.
        assertTrue(status.value() < 400,
                "the authenticated operator got " + status.value() + " from " + PATH);
    }

    @Test
    @DisplayName("AND NOTHING ELSE IS SECURED — the partner API still answers with no credentials")
    void theRestOfTheServiceIsUntouched() {
        // The entire argument for a hand-written filter over spring-boot-starter-security is
        // that it is mounted on one path and changes nothing else. That is a claim about the
        // other 165 tests, so it gets asserted here rather than assumed.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = http.postForEntity("/help/sessions",
                new HttpEntity<>("{\"spId\":\"SP-CONSOLE-01\"}", headers), String.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "a partner must never meet a login page");
    }
}
