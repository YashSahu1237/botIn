package in.yesmadam.botin.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE CALLER'S MISTAKES MUST NOT BE REPORTED AS OURS.
 *
 * ==========================================================================
 * WHY THIS TEST EXISTS — it is a regression guard for a bug that was shipped
 * ==========================================================================
 *
 * `ApiExceptionHandler` ends with a catch-all on `Exception`, which is correct: a
 * partner-facing surface must never leak an internal message. It was also catching
 * SPRING'S OWN way of saying "that is not a real address", so every mistyped URL in this
 * service answered 500.
 *
 * That is worse than it sounds, and the damage is not to the caller:
 *
 *   - An app team with a typo is told the backend is broken, and files a bug against us.
 *   - Every scan, probe and stale bookmark writes a full stack trace to the error log.
 *   - Real 500s sit in that noise. An alert on 5xx rate is worthless when the baseline is
 *     "somebody browsed a wrong path", and the first instinct during a genuine incident is
 *     to assume it is more of the same.
 *
 * Nothing failed. Nothing was logged as wrong. The service was simply lying about whose
 * fault it was, in the direction that costs an on-call engineer the most.
 *
 * It was found by a test looking for something else entirely — DemoIsolationTest wanted a
 * 404 from an endpoint that should not exist. That is worth noticing about tests: the ones
 * that assert a specific status code, rather than "not a success", find things.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorMappingTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("A URL that does not exist is a 404")
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/help/nothing-here")).andExpect(status().isNotFound());
        mvc.perform(get("/no/such/thing")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("The right path with the wrong verb is a 405, and says so")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        // /help/sessions exists — for POST. A GET is a client error with an obvious fix,
        // and 405 carries that fix in the status code itself.
        mvc.perform(get("/help/sessions")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Malformed JSON is a 400 — the caller CAN fix this one")
    void unreadableBodyIsBadRequest() throws Exception {
        mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spId\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("...and a well-formed request still works, which is the point")
    void theHappyPathIsUnaffected() throws Exception {
        mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spId\":\"SP-ERRMAP-1\"}"))
                .andExpect(status().isCreated());
    }
}
