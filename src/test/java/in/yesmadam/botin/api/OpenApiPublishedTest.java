package in.yesmadam.botin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PLAN STEP 94 — the contract is published BY the service, and it is complete.
 *
 * WHY THIS IS A TEST AND NOT A MANUAL CHECK. The value of a generated spec is that it cannot
 * drift from the code; the value is lost the moment generation silently stops working. A
 * springdoc that fails to scan a controller does not throw — it publishes a smaller document,
 * and a smaller document looks exactly like a correct one unless somebody counts.
 *
 * So this counts. Every endpoint a client or an agent tool has to call is named here
 * explicitly, and adding an endpoint without adding it to this list is a failing test rather
 * than a contract that quietly omits it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiPublishedTest {

    /** Every path the POC promises. Adding one here is the cheap half of adding an endpoint. */
    private static final List<String> PROMISED = List.of(
            "/help/sessions",
            "/help/sessions/{sessionId}",
            "/help/sessions/{sessionId}/input",
            "/help/sessions/{sessionId}/csat",
            "/help/concerns",
            "/help/concerns/{l1Code}",
            "/agent/tasks",
            "/agent/tasks/{taskId}/claim",
            "/agent/tasks/{taskId}/complete",
            "/tickets/{ticketId}/escalation-context");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("The service publishes its own OpenAPI document")
    void theSpecIsServed() throws Exception {
        JsonNode spec = spec();

        assertTrue(spec.hasNonNull("openapi"), "no OpenAPI version — nothing was generated");
        assertEquals("BOTIn — Service Partner support", spec.at("/info/title").asText());
    }

    @Test
    @DisplayName("EVERY endpoint appears — a spec that quietly shrinks looks like a correct one")
    void nothingIsMissingFromTheContract() throws Exception {
        JsonNode paths = spec().get("paths");
        assertNotNull(paths, "the document has no paths at all");

        List<String> missing = PROMISED.stream().filter(p -> !paths.has(p)).toList();
        assertTrue(missing.isEmpty(),
                "these endpoints exist in the service but not in its published contract: " + missing
              + ". Either springdoc stopped scanning a controller, or a path changed and nobody "
              + "told the people integrating against it");
    }

    @Test
    @DisplayName("The description says the one thing an integrator will otherwise assume wrongly")
    void theFlowOwnershipIsStatedInTheContract() throws Exception {
        String description = spec().at("/info/description").asText();

        // Somebody integrating against this will assume, reasonably, that their client decides
        // what screen comes next — every other support API they have met works that way. Here
        // it never does, and discovering that from endpoint signatures alone is not possible.
        assertTrue(description.contains("nextStep"),
                "the contract must name the descriptor the entire client is built around");
        assertTrue(description.contains("thin renderer"),
                "the flow-ownership split belongs in the published contract, not only in "
              + "our own documentation, which an integrator never reads");
    }

    /**
     * NOTE THE EXPLICIT CHARSET, which is not decoration.
     *
     * `getContentAsString()` with no argument decodes using the RESPONSE's character
     * encoding, and MockMvc falls back to ISO-8859-1 when the response does not name one —
     * which springdoc's `application/json` does not. The em dash in the title came back as
     * `â` plus two control characters, and the failure read as though the title itself
     * were wrong rather than the way the test had read it.
     *
     * Worth remembering beyond this file: any assertion on non-ASCII text through MockMvc
     * needs this. The Hinglish response templates are Roman-script and ASCII today, so they
     * do not hit it — the first one that carries a rupee sign or a Devanagari character
     * will, and it will look like a bug in the message.
     */
    private JsonNode spec() throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
