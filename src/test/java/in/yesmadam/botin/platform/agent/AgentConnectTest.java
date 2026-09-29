package in.yesmadam.botin.platform.agent;

import in.yesmadam.botin.platform.process.ProcessVariables;
import in.yesmadam.botin.platform.session.TicketRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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
 * PHASE 9 — agent connect, plan steps 68 to 72.
 *
 * Driven over HTTP, because the handover has two clients — the partner's app and an
 * agent console — and the thing that has to hold is that both see a consistent story
 * through the JSON, not through the Java.
 *
 * THE CONCERN USED HERE IS OTHER_FREETEXT_TRIAGE, on purpose. It needs no UAT, no
 * selected reference and no credential, and with no classifier built it lands on its
 * catch-all every time — so it is a genuine T3 produced by the real decision path
 * rather than a fixture pretending to be one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentConnectTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TicketRepository tickets;
    @Autowired TaskService taskService;
    @Autowired RuntimeService runtimeService;

    // ------------------------------------------------------- 68: reaching the queue

    @Test
    @DisplayName("A T3 tells the partner an agent is coming and leaves the session OPEN")
    void theSessionStaysOpenWhileTheProcessWaits() throws Exception {
        JsonNode view = reachAgentConnect("SP-AC-01");

        assertEquals("AGENT_CONNECTING", view.at("/nextStep/code").asText());
        assertFalse(view.at("/nextStep/prompt").asText().isBlank(),
                "the partner must be told something before the wait begins");

        // The two assertions this whole step exists for. A closed session would refuse
        // the reconnect the partner is most likely to make — coming back to check.
        assertEquals("OPEN", view.get("status").asText(),
                "the conversation is still live; the agent has not answered yet");
        assertEquals("AGENT_CONNECT", view.get("currentStep").asText());
    }

    @Test
    @DisplayName("Gate 1 is crossed: a ticket exists, escalated and awaiting an agent")
    void gate1IsCrossedAndTheTicketIsWaiting() throws Exception {
        reachAgentConnect("SP-AC-02");

        var ticket = tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-AC-02").get(0);
        assertEquals("T3", ticket.getTier());
        assertEquals("AWAITING_AGENT", ticket.getStatus());
        assertEquals("OTHER_FREETEXT_TRIAGE", ticket.getL2Concern());
        assertNotNull(ticket.getTriggerReason(), "an escalated ticket must say what sent it");
    }

    // -------------------------------------------- 69: the context, written in advance

    @Test
    @DisplayName("The escalation context is written BEFORE the agent arrives, with the facts")
    void theContextIsWrittenBeforeAnyoneOpensIt() throws Exception {
        reachAgentConnect("SP-AC-03");
        String ticketId = tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-AC-03").get(0).getId().toString();

        String body = mvc.perform(get("/tickets/" + ticketId + "/escalation-context"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode context = json.readTree(body);

        // CONCERN, not one of A-E: the concern's own table returned T3. Merging that
        // with the five cross-cutting triggers would overstate how often they fire.
        assertEquals("CONCERN", context.get("triggerReason").asText());
        assertEquals("OTHER_FREETEXT_TRIAGE", context.get("l2Concern").asText());

        String facts = context.get("factsSnapshot").asText();
        assertTrue(facts.contains("classificationMatched"),
                "the agent must see the facts the decision was made on: " + facts);
        assertTrue(facts.contains("\"tier\":\"T3\""),
                "and what the bot concluded from them: " + facts);

        assertNotNull(context.get("recentTicketHistory"), "\"again this week?\" is the first question");
        assertNotNull(context.get("priorActions"), "\"have we already paid them?\" is the second");

        // Known-absent, and absent on purpose rather than lost. Both are in the register.
        assertTrue(context.get("category").isNull(), "no classifier yet");
        assertTrue(context.get("medalBand").isNull(), "medal band is not readable from our data");
    }

    @Test
    @DisplayName("A ticket that never went to a human has no context, and says so with a 404")
    void noContextWhereThereWasNoEscalation() throws Exception {
        mvc.perform(get("/tickets/" + UUID.randomUUID() + "/escalation-context"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 70: the agent API

    @Test
    @DisplayName("The waiting case appears in the queue with enough to triage it")
    void theQueueListsTheWaitingCase() throws Exception {
        reachAgentConnect("SP-AC-04");

        JsonNode task = queuedTaskFor("SP-AC-04");
        assertNotNull(task, "the case is not in the agent queue");
        assertEquals("OTHER_FREETEXT_TRIAGE", task.get("l2Concern").asText());
        assertEquals("CONCERN", task.get("triggerReason").asText());
        assertTrue(task.get("assignee").isNull(), "nobody has picked it up yet");
        assertFalse(task.get("ticketId").asText().isBlank());
    }

    @Test
    @DisplayName("Claiming assigns the ticket, and a second agent is refused")
    void claimingIsExclusive() throws Exception {
        reachAgentConnect("SP-AC-05");
        String taskId = queuedTaskFor("SP-AC-05").get("taskId").asText();

        mvc.perform(post("/agent/tasks/" + taskId + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-anita\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value("agent-anita"));

        assertEquals("WITH_AGENT",
                tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-AC-05").get(0).getStatus(),
                "the ticket follows the task — one state, not two");

        // Two agents reaching for the same partner is a race between people, so the
        // second is told whose it is rather than quietly handed a duplicate.
        mvc.perform(post("/agent/tasks/" + taskId + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-bilal\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AGENT_TASK_ALREADY_CLAIMED"));
    }

    @Test
    @DisplayName("An unclaimed task cannot be completed")
    void completingRequiresAClaim() throws Exception {
        reachAgentConnect("SP-AC-06");
        String taskId = queuedTaskFor("SP-AC-06").get("taskId").asText();

        mvc.perform(post("/agent/tasks/" + taskId + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-anita\",\"resolutionNote\":\"done\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AGENT_TASK_NOT_CLAIMED"));
    }

    @Test
    @DisplayName("A task id that no longer exists is a 404, not a 500")
    void unknownTaskIs404() throws Exception {
        mvc.perform(post("/agent/tasks/does-not-exist/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-anita\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("AGENT_TASK_NOT_FOUND"));
    }

    // ------------------------------------------------- 71: the wait is in the database

    @Test
    @DisplayName("THE RESTART PROPERTY — the whole wait lives in the database, not in memory")
    void theWaitSurvivesOutsideTheProcessThatStartedIt() throws Exception {
        JsonNode view = reachAgentConnect("SP-AC-07");
        String sessionId = view.get("sessionId").asText();

        // The HTTP request that started all this has returned. Nothing is holding a
        // thread, a transaction, or an object. Everything the process needs to carry on
        // is now readable from the engine's own tables by a caller that knows nothing
        // about where it came from.
        var task = taskService.createTaskQuery()
                .taskDefinitionKey(AgentTaskService.AGENT_TASK_KEY)
                .processVariableValueEquals(ProcessVariables.HELP_SESSION_ID, sessionId)
                .includeProcessVariables()
                .singleResult();

        assertNotNull(task, "the task is not persisted — the wait would not survive a restart");
        assertEquals(sessionId, task.getProcessVariables().get(ProcessVariables.HELP_SESSION_ID));
        assertNotNull(task.getProcessVariables().get(ProcessVariables.TICKET_ID),
                "the ticket id must survive the wait, or the completion cannot close it");

        assertEquals(1, runtimeService.createProcessInstanceQuery()
                        .processInstanceId(task.getProcessInstanceId()).count(),
                "the instance is alive and parked");

        // This is the in-process statement of the property. The JVM-kill evidence is
        // spike 1, which did exactly this and then killed the JVM between the two
        // halves; what is asserted here is that this build kept that property — the
        // state is in ACT_RU_*, and the completion path reads it from there rather than
        // from anything the first request left behind.
    }

    // ----------------------------------------------- 72: completion closes everything

    @Test
    @DisplayName("The agent's own words reach the partner, and everything closes")
    void completionClosesTheTicketAndTheSession() throws Exception {
        JsonNode started = reachAgentConnect("SP-AC-08");
        String sessionId = started.get("sessionId").asText();
        String taskId = queuedTaskFor("SP-AC-08").get("taskId").asText();

        mvc.perform(post("/agent/tasks/" + taskId + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-anita\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/agent/tasks/" + taskId + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"agent-anita\","
                               + "\"resolutionNote\":\"Aapka transport amount kal tak aa jayega.\"}"))
                .andExpect(status().isNoContent());

        String body = mvc.perform(get("/help/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = json.readTree(body);

        assertEquals("CLOSED_AGENT_RESOLVED", view.get("status").asText());
        assertEquals("AGENT_RESOLVED", view.at("/nextStep/code").asText());

        // Verbatim. A note written by a person and then reworded by the system is a
        // message nobody actually sent.
        assertEquals("Aapka transport amount kal tak aa jayega.",
                view.at("/nextStep/prompt").asText());

        assertEquals("CLOSED", tickets.findTop10BySpIdOrderByCreatedAtDesc("SP-AC-08").get(0).getStatus());
        assertNull(queuedTaskFor("SP-AC-08"), "a completed case must leave the queue");
    }

    // ----------------------------------------------------- Gate 1 has not moved

    @Test
    @DisplayName("A T0 deflection still creates no ticket and no agent task")
    void deflectionStillCrossesNothing() throws Exception {
        String sessionId = startSession("SP-AC-09");
        send(sessionId, "AMOUNT_RELATED");
        JsonNode view = send(sessionId, "FORGET_MPIN");

        assertEquals("FORGET_MPIN_DEFLECT", view.at("/nextStep/code").asText());
        assertEquals(0, tickets.countBySpId("SP-AC-09"),
                "Gate 1 must stay where it is, however much is built on the other side of it");
        assertNull(queuedTaskFor("SP-AC-09"));
    }

    // ------------------------------------------------------------------ helpers

    /** Menu, menu, and out the other side onto a real T3. */
    private JsonNode reachAgentConnect(String spId) throws Exception {
        String sessionId = startSession(spId);
        send(sessionId, "OTHER_ISSUES");
        return send(sessionId, "OTHER_FREETEXT_TRIAGE");
    }

    /** The queue as an agent console would read it. Null when nothing is waiting. */
    private JsonNode queuedTaskFor(String spId) throws Exception {
        String body = mvc.perform(get("/agent/tasks"))
                .andExpect(status().isOk())
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
