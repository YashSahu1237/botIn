package in.yesmadam.botin.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.yesmadam.botin.session.TicketRepository;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
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
 * PHASE 3 CHECKPOINT — the first concern, end to end, over HTTP.
 *
 * FORGET_MPIN is a T0: the partner is shown where to reset their own MPIN and the
 * session ends. The assertion that matters is the negative one — NO TICKET. Deflection
 * that quietly files a ticket is not deflection, it is a ticket with extra steps, and
 * the only way to know which one you built is to count the rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForgetMpinT0Test {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TicketRepository tickets;
    @Autowired RepositoryService repositoryService;
    @Autowired RuntimeService runtimeService;

    @Test
    @DisplayName("the process is deployed from the classpath, once")
    void processIsDeployed() {
        long count = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("forget-mpin").count();
        assertEquals(1, count, "forget-mpin should be deployed exactly once");
    }

    @Test
    @DisplayName("T0 end to end: the partner gets a deeplink and the session closes")
    void forgetMpinDeflects() throws Exception {
        String sessionId = start("SP-3001");
        send(sessionId, "AMOUNT_RELATED");
        JsonNode view = send(sessionId, "FORGET_MPIN");

        assertEquals("MESSAGE", view.at("/nextStep/type").asText());
        assertEquals("FORGET_MPIN_DEFLECT", view.at("/nextStep/code").asText());
        assertFalse(view.at("/nextStep/deeplink").asText().isBlank(),
                "a self-serve deflection without a deeplink deflects nobody");
        assertEquals("CLOSED_DEFLECTED", view.get("status").asText());
        assertEquals("ENDED", view.get("currentStep").asText());
    }

    @Test
    @DisplayName("THE CHECKPOINT — a completed T0 files nothing, for this partner or this session")
    void aDeflectionCreatesNoTicket() throws Exception {
        long before = tickets.count();

        String sessionId = start("SP-3002");
        send(sessionId, "AMOUNT_RELATED");
        send(sessionId, "FORGET_MPIN");

        // Three ways of asking the same question, because this is the assertion the
        // whole tier model rests on.
        assertEquals(before, tickets.count(),
                "the deflection added a ticket somewhere");
        assertEquals(0, tickets.countBySpId("SP-3002"),
                "Gate 1 was crossed by a T0 — this partner's journey filed something");
        assertEquals(0, tickets.countByHelpSessionId(java.util.UUID.fromString(sessionId)),
                "a T0 session must never own a ticket");
    }

    @Test
    @DisplayName("the process instance is gone, which is why nextStep is not kept in it")
    void theInstanceCompletesAndItsRuntimeStateDisappears() throws Exception {
        String sessionId = start("SP-3003");
        send(sessionId, "AMOUNT_RELATED");
        send(sessionId, "FORGET_MPIN");

        long running = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(sessionId).count();
        assertEquals(0, running,
                "a T0 completes synchronously — its ACT_RU_* rows, variables included, are deleted");
    }

    @Test
    @DisplayName("reconnect still returns the deeplink, after the instance is gone")
    void theEndingSurvivesTheInstanceItCameFrom() throws Exception {
        String sessionId = start("SP-3004");
        send(sessionId, "AMOUNT_RELATED");
        JsonNode atClose = send(sessionId, "FORGET_MPIN");

        String body = mvc.perform(get("/help/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode reconnected = json.readTree(body);

        // This is the test that justifies SessionStepWriter existing at all. Had the
        // delegate left the ending in a process variable, this assertion would fail —
        // and it would fail only for the fast flows, which is the cruellest way to
        // find out.
        assertEquals(atClose.at("/nextStep").toString(), reconnected.at("/nextStep").toString(),
                "the ending lives on the help_session row, not in the process");
        assertEquals("FORGET_MPIN_DEFLECT", reconnected.at("/nextStep/code").asText());
    }

    @Test
    @DisplayName("a closed deflection takes no further input")
    void aDeflectedSessionIsFinished() throws Exception {
        String sessionId = start("SP-3005");
        send(sessionId, "AMOUNT_RELATED");
        send(sessionId, "FORGET_MPIN");

        mvc.perform(post("/help/sessions/" + sessionId + "/input")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selection\":\"ANYTHING\"}"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ helpers

    private String start(String spId) throws Exception {
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
