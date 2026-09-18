package in.yesmadam.botin.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.session.HelpSessionRepository;
import in.yesmadam.botin.session.TicketRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PLAN STEPS 85 AND 87 — the partner typed instead of tapping.
 *
 * THE LOOP IS A RATE CONTROL. Every session recovered by a re-prompt is a ticket that
 * never reaches a person, so "how many times do we ask before giving up" is a number
 * with a cost attached in both directions — ask too few and agents get cases the bot
 * could have placed; ask too many and the partner gives up on us instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FreeTextEntryTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TicketRepository tickets;
    @Autowired HelpSessionRepository sessions;

    // ----------------------------------------------------- 85: straight to the concern

    @Test
    @DisplayName("Recognised text skips the menu entirely and resolves")
    void typingYourWayInSkipsTheMenu() throws Exception {
        JsonNode view = start("SP-FT-01", "mera mpin bhool gaya");

        // No L1 menu, no L2 menu. One request in, an answer out.
        assertEquals("FORGET_MPIN_DEFLECT", view.at("/nextStep/code").asText());
        assertEquals("FORGET_MPIN", view.get("l2Concern").asText());
        assertEquals("CLOSED_DEFLECTED", view.get("status").asText());
        assertEquals(0, tickets.countBySpId("SP-FT-01"),
                "a deflection is a deflection however the partner arrived at it");
    }

    @Test
    @DisplayName("Typing your way in and tapping your way in produce the same thing")
    void bothRoutesAgree() throws Exception {
        JsonNode typed = start("SP-FT-02", "mpin bhool gaya");

        String tapped = start("SP-FT-03", null).get("sessionId").asText();
        send(tapped, "AMOUNT_RELATED");
        JsonNode viaMenu = send(tapped, "FORGET_MPIN");

        // There is one door into a concern, so there is nothing that can drift between
        // the route everybody demos and the route nobody does.
        assertEquals(viaMenu.at("/nextStep").toString(), typed.at("/nextStep").toString());
        assertEquals(viaMenu.get("status").asText(), typed.get("status").asText());
    }

    @Test
    @DisplayName("No text at all still gets exactly the menu-driven flow the BRD specifies")
    void theMenuPathIsUntouched() throws Exception {
        JsonNode view = start("SP-FT-04", null);
        assertEquals("L1_MENU", view.at("/nextStep/code").asText());
        assertEquals("L1_SELECT", view.get("currentStep").asText());
    }

    // ----------------------------------------------------- 87: the clarification loop

    @Test
    @DisplayName("THE LOOP — unplaceable text is asked about twice, then handed to a person")
    void twoRePromptsThenAHuman() throws Exception {
        JsonNode first = start("SP-FT-05", "bhai kuch to gadbad hai");

        // 1st failure: asked to say more, and the session is waiting for text — not a
        // menu selection, which would be the bot changing the subject.
        assertEquals("FREE_TEXT_CLARIFY", first.at("/nextStep/code").asText());
        assertEquals("TEXT", first.at("/nextStep/type").asText());
        assertEquals("FREE_TEXT_CLARIFY", first.get("currentStep").asText());
        String sessionId = first.get("sessionId").asText();
        assertEquals(0, tickets.countBySpId("SP-FT-05"), "nothing is filed while we are still asking");

        // 2nd failure: asked once more, and in different words — repeating the same
        // prompt verbatim reads as the bot not listening.
        JsonNode second = sendText(sessionId, "wahi wala issue");
        assertEquals("FREE_TEXT_CLARIFY", second.at("/nextStep/code").asText());
        assertNotEquals(first.at("/nextStep/prompt").asText(), second.at("/nextStep/prompt").asText(),
                "the second ask should not be word-for-word the first");

        // 3rd failure: stop asking. Someone who has rephrased twice is telling us the
        // taxonomy does not cover this, and a third prompt makes them feel unheard.
        JsonNode third = sendText(sessionId, "arre yaar samajh nahi aa raha");
        assertEquals("AGENT_CONNECTING", third.at("/nextStep/code").asText());
        assertEquals(1, tickets.countBySpId("SP-FT-05"));
        assertEquals("TICKET_WITH_TRANSCRIPT",
                tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-FT-05").get(0).getDmnAction());
    }

    @Test
    @DisplayName("Every attempt is kept, so the agent reads the whole transcript")
    void theAgentSeesAllThreeAttempts() throws Exception {
        JsonNode first = start("SP-FT-06", "pehla try");
        String sessionId = first.get("sessionId").asText();
        sendText(sessionId, "doosra try");
        sendText(sessionId, "teesra try");

        String kept = sessions.findById(UUID.fromString(sessionId)).orElseThrow().getEntryFreeText();

        // "They told us three times and we never understood" is the thing worth knowing,
        // and the last attempt alone hides it.
        assertTrue(kept.contains("pehla try"), kept);
        assertTrue(kept.contains("doosra try"), kept);
        assertTrue(kept.contains("teesra try"), kept);
    }

    @Test
    @DisplayName("A partner who rephrases into something we understand is recovered")
    void aRecoveredSessionNeverReachesAnAgent() throws Exception {
        // This is the case the whole loop is for: one extra question turned a ticket
        // into a deflection.
        String sessionId = start("SP-FT-07", "kuch dikkat hai").get("sessionId").asText();

        JsonNode after = sendText(sessionId, "mpin bhool gaya hai mera");

        assertEquals("FORGET_MPIN_DEFLECT", after.at("/nextStep/code").asText());
        assertEquals(0, tickets.countBySpId("SP-FT-07"),
                "a recovered session is a ticket that never happened");
    }

    @Test
    @DisplayName("Risk text skips the loop — it is never asked to rephrase")
    void riskNeverWaitsForAClarification() throws Exception {
        // Asking someone who has just described an accident to "please say a bit more"
        // is the wrong response to the one case where speed matters most.
        JsonNode view = start("SP-FT-08", "accident ho gaya hai");

        assertEquals("AGENT_CONNECTING", view.at("/nextStep/code").asText());
        assertEquals(0, sessions.findById(UUID.fromString(view.get("sessionId").asText()))
                .orElseThrow().getClarificationAttempts(),
                "risk must not be put through the loop even once");
    }

    @Test
    @DisplayName("Empty text on a clarification turn counts as a failure, not an error")
    void emptyTextIsJustAnotherMiss() throws Exception {
        String sessionId = start("SP-FT-09", "hmm").get("sessionId").asText();

        JsonNode after = sendText(sessionId, "");
        assertEquals("FREE_TEXT_CLARIFY", after.at("/nextStep/code").asText());
        assertEquals("OPEN", after.get("status").asText());
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
}
