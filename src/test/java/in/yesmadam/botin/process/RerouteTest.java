package in.yesmadam.botin.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.session.TicketRepository;
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
 * PLAN STEP 86 — RerouteDelegate, which is the ONE routing mechanism in the system.
 *
 * Text typed at the entry point and text typed inside a concern both arrive at the same
 * place: the triage concern's process, its decision table, and this delegate. There is
 * deliberately no second path that sends a confident match straight to its target —
 * two mechanisms for one behaviour means two places to fix a routing bug, and the one
 * that gets fixed is the one that gets demoed.
 *
 * THE ASSERTION THAT MATTERS is that a rerouted session is INDISTINGUISHABLE from one
 * that arrived through the menu. If it were not, every report and every later phase
 * would have two cases to handle forever.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RerouteTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TicketRepository tickets;

    @Test
    @DisplayName("A rerouted session is indistinguishable from one that came through the menu")
    void theReroutedSessionLooksLikeAnyOther() throws Exception {
        JsonNode viaText = start("SP-RR-02", "mpin bhool gaya");

        String direct = start("SP-RR-03", null).get("sessionId").asText();
        send(direct, "AMOUNT_RELATED");
        JsonNode viaMenu = send(direct, "FORGET_MPIN");

        assertEquals(viaMenu.at("/nextStep").toString(), viaText.at("/nextStep").toString(),
                "two routes, one answer — nothing downstream should be able to tell them apart");
        assertEquals(viaMenu.get("status").asText(), viaText.get("status").asText());
        assertEquals(viaMenu.get("l2Concern").asText(), viaText.get("l2Concern").asText(),
                "the session MOVES to the real concern — otherwise every report attributes "
              + "this case to the triage flow it passed through");
    }

    @Test
    @DisplayName("GATE 1 — a reroute files nothing, because a reroute is not an outcome")
    void rerouteDoesNotCrossGate1() throws Exception {
        // The bug this exists for: openTicket runs BEFORE the gateway, and a reroute
        // carries tier "-" rather than "T0". Without the check, the triage concern filed
        // a ticket and THEN handed the partner on to the real concern, which decided for
        // itself — so every rerouted case was counted twice, and a partner who was
        // correctly understood and correctly deflected still finished with a ticket that
        // no tier ever asked for.
        start("SP-RR-01", "mera mpin bhool gaya");

        assertEquals(0, tickets.countBySpId("SP-RR-01"),
                "the target concern crosses Gate 1 on its own terms, or not at all");
    }

    @Test
    @DisplayName("Text the classifier cannot place reaches a human with the transcript")
    void unplaceableTextGoesToAPerson() throws Exception {
        String sessionId = start("SP-RR-04", "bhai kal wala kaam ka kya hua").get("sessionId").asText();
        sendText(sessionId, "wahi wala");
        JsonNode view = sendText(sessionId, "arre samajh nahi aa raha");

        assertEquals("AGENT_CONNECTING", view.at("/nextStep/code").asText());
        assertEquals("OTHER_FREETEXT_TRIAGE", view.get("l2Concern").asText(),
                "no reroute happened, so the concern must not have moved");

        var ticket = tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-RR-04").get(0);
        assertEquals("T3", ticket.getTier());
        assertEquals("TICKET_WITH_TRANSCRIPT", ticket.getDmnAction(),
                "the agent needs to know this arrived as unplaceable free text");
    }

    @Test
    @DisplayName("A risk word is never rerouted, however confidently the text was matched")
    void riskFlaggedTextIsNeverRerouted() throws Exception {
        // The one that matters most. Without trigger E sitting above the match row, a
        // partner describing an accident is handed a self-serve deeplink by a model that
        // was 95% sure it knew what they meant.
        JsonNode view = start("SP-RR-05", "accident ho gaya aur mpin bhi bhool gaya");

        assertEquals("AGENT_CONNECTING", view.at("/nextStep/code").asText());
        assertNotEquals("FORGET_MPIN", view.get("l2Concern").asText(),
                "a risk-flagged message must never be self-served");
    }

    @Test
    @DisplayName("IN-CONCERN FREE TEXT — the reason names a different concern, so the session moves")
    void textTypedInsideAConcernReroutes() throws Exception {
        // PLAN STEP 86, AND IT WAS UNREACHABLE UNTIL THE DEMO SCRIPT DROVE IT.
        //
        // Every other test in this class reroutes from the ENTRY point, where the text
        // arrives before any concern exists. Nobody had driven the path the step actually
        // describes: the partner picks "Other", types a reason, and is moved. Through the
        // API that did not work — handleL2 took the selection and discarded the text, so
        // the concern ran with nothing to classify and the partner sat where they started.
        //
        // No error, no log line, no failing test. Built, green, documented as done, and
        // dead. This test is the one that would have caught it.
        String sessionId = start("SP-RR-06", null).get("sessionId").asText();
        send(sessionId, "VIOLATIONS");

        JsonNode view = sendWithText(sessionId, "VIOL_R4_OTHERS", "transport ka paisa nahi mila");

        assertEquals("TRANSPORT_NOT_RECEIVED", view.get("l2Concern").asText(),
                "the session MOVES to the concern the text named — it does not fork, and it "
              + "does not stay on the triage concern it passed through");
        assertNotEquals("VIOL_R4_OTHERS", view.get("l2Concern").asText());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode start(String spId, String freeText) throws Exception {
        String body = freeText == null
                ? "{\"spId\":\"" + spId + "\"}"
                : "{\"spId\":\"" + spId + "\",\"freeText\":\"" + freeText + "\"}";
        String response = mvc.perform(post("/help/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode sendText(String sessionId, String text) throws Exception {
        String body = mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode send(String sessionId, String selection) throws Exception {
        String body = mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selection\":\"" + selection + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** A selection and the text that came with it — one turn, as "Other" actually is. */
    private JsonNode sendWithText(String sessionId, String selection, String text) throws Exception {
        String body = mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selection\":\"" + selection + "\",\"freeText\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
