package in.yesmadam.botin.csat;

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
 * PHASE 10 — plan steps 73 to 81.
 *
 * THE BOUNDING RULE IS THE SINGLE STRONGEST CONTROL ON THE AGENT-CONNECT RATE, and it is
 * the one piece of this system whose failure is invisible from the inside: if it stops
 * working, nothing errors, nothing is logged, and the only symptom is that the 10-20%
 * target quietly stops being met three months later. So it is asserted on the PAYLOAD —
 * what the client can actually see and render — rather than on any internal state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsatAndBoundingRuleTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TicketRepository tickets;

    // ----------------------------------------------- 73: the question is offered

    @Test
    @DisplayName("A resolved session asks to be rated, and is not held open to do it")
    void aResolvedSessionExpectsCsatWithoutStayingOpen() throws Exception {
        JsonNode view = deflect("SP-CS-01");

        assertTrue(view.get("csatExpected").asBoolean(),
                "the partner should be asked whether the deflection actually helped");

        // THE POINT OF THE WHOLE MECHANISM (plan step 74). The session is CLOSED while
        // the question is outstanding. Nothing is held, so there is nothing to time out,
        // and the instance-completes property the nextStep design rests on is intact.
        assertEquals("CLOSED_DEFLECTED", view.get("status").asText());
        assertFalse(view.get("agentOffered").asBoolean(),
                "no agent is offered before the partner has said anything");
    }

    @Test
    @DisplayName("A concern we could not help with is never asked to be rated")
    void nothingToRateWhenNothingWasResolved() throws Exception {
        String sessionId = startSession("SP-CS-02");
        send(sessionId, "AMOUNT_RELATED");
        JsonNode view = send(sessionId, "ARW_TO_BANK");      // CLOSED_NOT_AVAILABLE

        assertFalse(view.get("csatExpected").asBoolean(),
                "asking 'did that help?' after 'we cannot help' is not measurement");

        mvc.perform(post("/help/sessions/" + sessionId + "/csat")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"satisfied\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CSAT_NOT_EXPECTED"));
    }

    // --------------------------------------- 77 and 79: the bounding rule

    @Test
    @DisplayName("THE BOUNDING RULE — a satisfied case offers no agent affordance at all")
    void satisfiedMeansNoRouteToAHuman() throws Exception {
        String sessionId = deflect("SP-CS-03").get("sessionId").asText();

        JsonNode after = csat(sessionId, true);

        assertFalse(after.get("agentOffered").asBoolean(),
                "a partner who said the answer helped must not still see a route to a human — "
              + "without this the 10-20%% ceiling stops meaning anything");
        assertFalse(after.get("csatExpected").asBoolean(), "already answered");
        assertEquals("CLOSED_DEFLECTED", after.get("status").asText(), "still closed, still deflected");

        // And nothing was created. A satisfied deflection is the cheapest possible
        // outcome and must stay that way.
        assertEquals(0, tickets.countBySpId("SP-CS-03"),
                "Gate 1 stays uncrossed when the deflection actually worked");
    }

    @Test
    @DisplayName("The answer is given once — a second one is refused, not overwritten")
    void csatCannotBeChanged() throws Exception {
        String sessionId = deflect("SP-CS-04").get("sessionId").asText();
        csat(sessionId, true);

        mvc.perform(post("/help/sessions/" + sessionId + "/csat")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"satisfied\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CSAT_ALREADY_RECORDED"));
    }

    // ------------------------------------------- 76 and 80: trigger A

    @Test
    @DisplayName("TRIGGER A — a dissatisfied partner gets a human, and the queue shows it")
    void dissatisfiedEscalatesToAnAgent() throws Exception {
        String sessionId = deflect("SP-CS-05").get("sessionId").asText();

        JsonNode after = csat(sessionId, false);

        assertTrue(after.get("agentOffered").asBoolean());
        assertEquals("AGENT_CONNECTING", after.at("/nextStep/code").asText());
        assertEquals("AGENT_CONNECT", after.get("currentStep").asText());
        assertEquals("OPEN", after.get("status").asText(),
                "the conversation has reopened — a person is now on it");

        // A DEFLECTION THAT FAILED IS NO LONGER A DEFLECTION. Gate 1 is crossed here,
        // at the moment the partner says the answer did not help, and not before.
        assertEquals(1, tickets.countBySpId("SP-CS-05"));
        var ticket = tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-CS-05").get(0);
        assertEquals("T3", ticket.getTier());
        assertEquals("A", ticket.getTriggerReason(), "trigger A, not the concern's own decision");
        assertEquals("NO", ticket.getCsatResult(), "the rating is on the ticket, for reporting");

        JsonNode task = queuedTaskFor("SP-CS-05");
        assertNotNull(task, "the case must appear in the agent queue");
        assertEquals("A", task.get("triggerReason").asText());
    }

    @Test
    @DisplayName("The agent sees what the partner was told and rejected")
    void theEscalationContextCarriesTheRejectedAnswer() throws Exception {
        String sessionId = deflect("SP-CS-06").get("sessionId").asText();
        csat(sessionId, false);

        String ticketId = tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-CS-06").get(0).getId().toString();
        String body = mvc.perform(get("/tickets/" + ticketId + "/escalation-context"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode context = json.readTree(body);

        assertEquals("A", context.get("triggerReason").asText());

        // The original fact map went with the completed instance, and this is the
        // honest replacement — arguably the more useful one. "We told them this and they
        // said it did not help" is the first thing the agent needs, as a sentence.
        String facts = context.get("factsSnapshot").asText();
        assertTrue(facts.contains("whatThePartnerWasTold"), facts);
        assertTrue(facts.contains("FORGET_MPIN_DEFLECT"),
                "the exact answer the partner rejected must be in front of the agent: " + facts);
    }

    @Test
    @DisplayName("A ticketed resolution escalates the SAME ticket, not a second one")
    void triggerAReusesTheExistingTicket() throws Exception {
        // PROD_DELIVERY_DELAY with UAT off lands on its catch-all, which is already a
        // T3, so this case has a ticket before CSAT is ever asked. Trigger A must not
        // open another: that would double-count every rejection in the volume reports.
        String sessionId = startSession("SP-CS-07");
        send(sessionId, "PRODUCT_ISSUES");
        JsonNode resolved = send(sessionId, "PROD_DELIVERY_DELAY");

        long afterResolution = tickets.countBySpId("SP-CS-07");
        assertEquals(1, afterResolution, "the concern itself opened one");

        // It went to an agent, so it is not rateable yet — finish it as an agent would.
        if ("AGENT_CONNECTING".equals(resolved.at("/nextStep/code").asText())) {
            String taskId = queuedTaskFor("SP-CS-07").get("taskId").asText();
            claim(taskId, "agent-anita");
            complete(taskId, "agent-anita", "Aapka order kal aa jayega.");
        }

        csat(sessionId, false);

        assertEquals(afterResolution, tickets.countBySpId("SP-CS-07"),
                "trigger A escalates the complaint that already exists — it does not file a new one");
        assertEquals("A", tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-CS-07").get(0).getTriggerReason());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode deflect(String spId) throws Exception {
        String sessionId = startSession(spId);
        send(sessionId, "AMOUNT_RELATED");
        JsonNode view = send(sessionId, "FORGET_MPIN");
        assertEquals("FORGET_MPIN_DEFLECT", view.at("/nextStep/code").asText());
        return view;
    }

    private JsonNode csat(String sessionId, boolean satisfied) throws Exception {
        String body = mvc.perform(post("/help/sessions/" + sessionId + "/csat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"satisfied\":" + satisfied + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void claim(String taskId, String agentId) throws Exception {
        mvc.perform(post("/agent/tasks/" + taskId + "/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"" + agentId + "\"}")).andExpect(status().isOk());
    }

    private void complete(String taskId, String agentId, String note) throws Exception {
        mvc.perform(post("/agent/tasks/" + taskId + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"" + agentId + "\",\"resolutionNote\":\"" + note + "\"}"))
                .andExpect(status().isNoContent());
    }

    private JsonNode queuedTaskFor(String spId) throws Exception {
        String body = mvc.perform(get("/agent/tasks")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode task : json.readTree(body)) {
            if (spId.equals(task.get("spId").asText())) return task;
        }
        return null;
    }

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
