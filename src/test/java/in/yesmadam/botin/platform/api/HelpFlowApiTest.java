package in.yesmadam.botin.platform.api;

import in.yesmadam.botin.platform.session.TicketRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 2 end to end, over HTTP.
 *
 * These drive the real endpoints rather than the service, because the contract the
 * client depends on is the JSON, not the Java. A refactor that keeps the service
 * green and changes the payload shape is still a broken client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HelpFlowApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TicketRepository tickets;

    // ------------------------------------------------------------ the catalogue

    @Test
    @DisplayName("The L1 menu comes from the catalogue, covering all six paths")
    void l1MenuIsDerivedFromTheCatalogue() throws Exception {
        String body = mvc.perform(get("/help/concerns"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode groups = json.readTree(body);
        assertEquals(6, groups.size(), "six L1 paths are seeded");

        // A path with nothing built under it is still offered. Hiding it would make
        // the menu change shape as concerns are switched on.
        boolean someGroupIsEmpty = false;
        for (JsonNode g : groups) {
            assertTrue(g.has("code") && g.has("label") && g.has("activeConcerns"));
            if (g.get("activeConcerns").asInt() == 0) someGroupIsEmpty = true;
        }
        assertTrue(someGroupIsEmpty, "at least one L1 path has no active concern yet");
    }

    @Test
    @DisplayName("Only active L2 concerns are listed, never the seeded-but-unbuilt ones")
    void onlyActiveConcernsAreOffered() throws Exception {
        String body = mvc.perform(get("/help/concerns/AMOUNT_RELATED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode options = json.readTree(body);
        assertEquals(3, options.size(), "three of the Amount Related concerns are active");

        for (JsonNode o : options) {
            String code = o.get("code").asText();
            assertNotEquals("SAVING_UNDER_5K", code, "DEPRECATED concerns must never be offered");
            assertNotEquals("ARW_TO_BANK", code, "inactive concerns must never be offered");
        }
    }

    @Test
    @DisplayName("An L1 path that does not exist is a 404, not an empty list")
    void unknownL1PathIs404() throws Exception {
        mvc.perform(get("/help/concerns/NOT_A_REAL_PATH")).andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------- the flow

    @Test
    @DisplayName("Help tapped → L1 menu, and no ticket exists")
    void startingASessionOffersTheL1MenuAndCreatesNoTicket() throws Exception {
        long before = tickets.count();

        String body = mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spId\":\"SP-1001\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode view = json.readTree(body);
        assertEquals("OPEN", view.get("status").asText());
        assertEquals("L1_SELECT", view.get("currentStep").asText());
        assertEquals("MENU", view.at("/nextStep/type").asText());
        assertEquals("L1_MENU", view.at("/nextStep/code").asText());
        assertEquals(6, view.at("/nextStep/options").size());

        assertEquals(before, tickets.count(),
                "Gate 1 is not crossed by opening a session — no ticket may exist yet");
    }

    @Test
    @DisplayName("L1 → L2 → an active concern is accepted, still with no ticket")
    void theHappyPathReachesTheConcernBoundary() throws Exception {
        String sessionId = startSession("SP-1002");
        long before = tickets.count();

        JsonNode afterL1 = send(sessionId, "AMOUNT_RELATED");
        assertEquals("L2_SELECT", afterL1.get("currentStep").asText());
        assertEquals("L2_MENU", afterL1.at("/nextStep/code").asText());
        assertEquals("AMOUNT_RELATED", afterL1.get("l1Concern").asText());

        JsonNode afterL2 = send(sessionId, "FORGET_MPIN");
        assertEquals("FORGET_MPIN", afterL2.get("l2Concern").asText());

        // From Phase 3 this concern runs its BPMN process, which deflects and closes.
        // The detail of that ending belongs to ForgetMpinT0Test; what this test cares
        // about is that selection reached the process at all.
        assertEquals("FORGET_MPIN_DEFLECT", afterL2.at("/nextStep/code").asText());
        assertEquals("CLOSED_DEFLECTED", afterL2.get("status").asText());

        // The concern's own process decides whether Gate 1 is crossed. This one is a
        // T0, so it never is.
        assertEquals(before, tickets.count(),
                "selecting a concern does not open a ticket — the process decides that");
    }

    @Test
    @DisplayName("An inactive concern gives the defined not-available ending, not an error")
    void selectingAnUnbuiltConcernEndsCleanly() throws Exception {
        String sessionId = startSession("SP-1003");
        send(sessionId, "AMOUNT_RELATED");

        // Seeded, real, and not built. The partner must not meet a 500 or a blank menu.
        JsonNode view = send(sessionId, "ARW_TO_BANK");

        assertEquals("MESSAGE", view.at("/nextStep/type").asText());
        assertEquals("CONCERN_NOT_AVAILABLE", view.at("/nextStep/code").asText());
        assertFalse(view.at("/nextStep/prompt").asText().isBlank(), "the ending must say something");
        assertEquals("CLOSED_NOT_AVAILABLE", view.get("status").asText(),
                "the reason is on the row, so the rollout can count how often this happens");
    }

    @Test
    @DisplayName("A concern that does not exist ends the same way as one that is not built")
    void unknownConcernCodeEndsTheSameWay() throws Exception {
        String sessionId = startSession("SP-1004");
        send(sessionId, "AMOUNT_RELATED");

        JsonNode view = send(sessionId, "NO_SUCH_CONCERN");
        assertEquals("CONCERN_NOT_AVAILABLE", view.at("/nextStep/code").asText());
    }

    @Test
    @DisplayName("A concern from a different L1 path is not selectable from this one")
    void concernsCannotBeReachedFromTheWrongPath() throws Exception {
        String sessionId = startSession("SP-1005");
        send(sessionId, "AMOUNT_RELATED");

        // Active, but it lives under VIOLATIONS. Accepting it would let a client skip
        // the menu and reach any concern by guessing a code. (Was VIOL_R5_PERIODS
        // until V4 parked that concern — this test needs one that is still active.)
        JsonNode view = send(sessionId, "VIOL_R4_OTHERS");
        assertEquals("CONCERN_NOT_AVAILABLE", view.at("/nextStep/code").asText());
    }

    @Test
    @DisplayName("An unknown L1 code re-offers the menu rather than dead-ending")
    void unknownL1SelectionReoffersTheMenu() throws Exception {
        String sessionId = startSession("SP-1006");
        JsonNode view = send(sessionId, "NOT_A_PATH");

        assertEquals("L1_MENU", view.at("/nextStep/code").asText());
        assertEquals("OPEN", view.get("status").asText());
    }

    // ---------------------------------------------------------------- reconnect

    @Test
    @DisplayName("Reconnecting returns exactly the step the partner was left on")
    void reconnectReturnsTheSameNextStep() throws Exception {
        String sessionId = startSession("SP-1007");
        JsonNode afterL1 = send(sessionId, "VIOLATIONS");

        String body = mvc.perform(get("/help/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode reconnected = json.readTree(body);

        assertEquals(afterL1.at("/nextStep").toString(), reconnected.at("/nextStep").toString(),
                "the reconnect payload and the response payload are the same object");
        assertEquals("L2_SELECT", reconnected.get("currentStep").asText());
    }

    @Test
    @DisplayName("An unknown session id is a 404 with a named error, not a stack trace")
    void unknownSessionIs404() throws Exception {
        mvc.perform(get("/help/sessions/11111111-2222-3333-4444-555555555555"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("A closed session refuses further input with 409")
    void closedSessionRefusesInput() throws Exception {
        String sessionId = startSession("SP-1008");
        send(sessionId, "AMOUNT_RELATED");
        send(sessionId, "ARW_TO_BANK");   // ends the session

        mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selection\":\"ANYTHING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SESSION_CLOSED"));
    }

    @Test
    @DisplayName("Starting without an SP id is rejected")
    void spIdIsRequired() throws Exception {
        mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String startSession(String spId) throws Exception {
        String body = mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spId\":\"" + spId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("sessionId").asText();
    }

    private JsonNode send(String sessionId, String selection) throws Exception {
        String body = mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selection\":\"" + selection + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
