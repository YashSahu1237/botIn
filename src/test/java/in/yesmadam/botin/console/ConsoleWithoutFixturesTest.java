package in.yesmadam.botin.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE POINT OF SPLITTING THE PROFILES, ASSERTED.
 *
 * The console used to live inside the demo profile, and the demo profile REFUSES to start
 * beside a real UAT datasource — correctly, because half-real data is worse than either kind.
 * The consequence only surfaced when somebody asked the obvious question: it meant the console
 * COULD NEVER SHOW REAL DATA. By construction, not by accident.
 *
 * So this test runs the console WITHOUT the fixtures, which is the configuration a read grant
 * would actually be demonstrated in. If the console ever acquires a dependency on something
 * synthetic, this fails — and it fails now rather than on the morning somebody wants to show a
 * senior the system deciding about a real partner.
 *
 * It also pins the part that matters most: the banner is READ FROM THE SERVER. A demo where the
 * audience cannot tell whether a number is evidence or invention is worse than no demo, because
 * it produces confident wrong beliefs — and a hard-coded string in a page is how that happens
 * quietly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "console"})
class ConsoleWithoutFixturesTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("The console runs with NO demo fixtures — the configuration a read grant needs")
    void itDependsOnNothingSynthetic() throws Exception {
        mvc.perform(get("/console")).andExpect(status().isOk());

        JsonNode state = json.readTree(mvc.perform(get("/console/state"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertFalse(state.get("fixtures").asBoolean(), "no demo profile, so no fixtures");
    }

    @Test
    @DisplayName("It SAYS there are no facts rather than quietly showing none")
    void theBannerTellsTheTruthAboutThisWorld() throws Exception {
        JsonNode state = json.readTree(mvc.perform(get("/console/state"))
                .andReturn().getResponse().getContentAsString());

        // UAT is off in the test profile and fixtures are absent, so every concern reaches a
        // human — correctly, because nothing can be read. The screen has to SAY so. Silence
        // here looks identical to a system that decided everything needs an agent.
        assertEquals("NO_FACTS", state.get("mode").asText());
        assertTrue(state.get("banner").asText().contains("No fact source"),
                "the banner must name the state it is in: " + state.get("banner").asText());
    }

    @Test
    @DisplayName("THE EXPLANATION, END TO END — a real conversation, then the trace of its decision")
    void theTraceDescribesADecisionThatActuallyHappened() throws Exception {
        // A CONCERN THAT KNOWS NOTHING, which is this world's normal case and the stronger test.
        //
        // The first version of this used FORGET_MPIN, and it failed: that concern keeps its own
        // single-step process and never evaluates a table at all, so there was correctly no
        // trace to find. Worth remembering before a demo — clicking the T0 case first shows an
        // empty explanation, because there is genuinely nothing to explain.
        //
        // Transport with no UAT and no fixtures returns every fact as NULL. The table is still
        // evaluated, every row fails to match, and the catch-all fires. That is what most of
        // this system does today, so it is the case the panel most needs to handle.
        String sessionId = json.readTree(mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spId\":\"SP-TRACE-1\"}"))
                .andReturn().getResponse().getContentAsString()).get("sessionId").asText();

        mvc.perform(post("/help/sessions/" + sessionId + "/input")
                .contentType(MediaType.APPLICATION_JSON).content("{\"selection\":\"AMOUNT_RELATED\"}"));
        mvc.perform(post("/help/sessions/" + sessionId + "/input")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"selection\":\"TRANSPORT_NOT_RECEIVED\",\"reference\":\"9999\"}"));

        JsonNode trace = json.readTree(mvc.perform(get("/console/trace/" + sessionId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertTrue(trace.get("found").asBoolean(),
                "no decision was recorded for a conversation that certainly made one");
        assertEquals("TRANSPORT_NOT_RECEIVED", trace.get("concern").asText());

        // NULLS ARE THE EXPLANATION HERE, so they must be reported rather than omitted. A
        // panel that lists only the facts it knows makes an unreachable rule look arbitrary.
        boolean anyUnknown = false;
        for (JsonNode fact : trace.get("facts")) if (!fact.get("known").asBoolean()) anyUnknown = true;
        assertTrue(anyUnknown, "every fact came back null, so the panel must SAY they are not known");
        assertTrue(trace.has("facts"), "the facts are the first half of any explanation");

        // The grid. An empty one renders as "this decision had no rules", which is a lie that
        // looks exactly like the truth — so the endpoint has to carry rows, and one must have
        // fired.
        assertTrue(trace.has("rules") && trace.get("rules").size() > 0,
                "the console would draw an empty table and nobody could tell it was broken");

        long fired = 0;
        for (JsonNode rule : trace.get("rules")) if (rule.get("fired").asBoolean()) fired++;
        assertEquals(1, fired, "exactly one row produces the answer under FIRST");
    }

    @Test
    @DisplayName("A conversation that decided nothing says so, rather than returning an empty panel")
    void noDecisionIsAnAnswerNotASilence() throws Exception {
        JsonNode trace = json.readTree(mvc.perform(get("/console/trace/" + java.util.UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertFalse(trace.get("found").asBoolean());
        assertTrue(trace.get("why").asText().length() > 20,
                "'nothing to show' must come with a sentence explaining why. A blank panel is "
              + "indistinguishable from a broken one — this project has now paid for that twice");
    }

    @Test
    @DisplayName("Inspection works without fixtures, and flag flipping is not here at all")
    void inspectionNeedsNoSyntheticWorld() throws Exception {
        mvc.perform(get("/console/tickets/SP-NOBODY")).andExpect(status().isOk());
        mvc.perform(get("/console/flags")).andExpect(status().isOk());

        // Flipping stays in the demo controls, where the only gateway it can reach is a mock.
        mvc.perform(get("/demo/flags")).andExpect(status().isNotFound());
    }
}
