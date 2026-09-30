package in.yesmadam.botin.surface.console;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * THE STEP-BY-STEP PATH IS READ FROM THE ENGINE, NOT NARRATED.
 *
 * This is the endpoint a reviewer is shown when they ask "how does this actually work" —
 * so the thing worth testing is not that it returns something, but that what it returns
 * came from the engine's own history rather than from a story we wrote.
 *
 * The sharpest assertion here is the last one: a T0 must show a path that NEVER TOUCHED
 * the agent branch. A journey view that listed every step as taken would look informative
 * and prove nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
// console for the endpoint, demo for a world to drive, test for H2 and no UAT.
@ActiveProfiles({"console", "demo", "test"})
class JourneyTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode call(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String raise(String spId, String l1, String l2, String reference) throws Exception {
        String body = json.readTree(mvc.perform(post("/help/sessions")
                .contentType("application/json")
                .content("{\"spId\":\"" + spId + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("sessionId").asText();

        mvc.perform(post("/help/sessions/" + body + "/input")
                .contentType("application/json")
                .content("{\"selection\":\"" + l1 + "\"}"));
        mvc.perform(post("/help/sessions/" + body + "/input")
                .contentType("application/json")
                .content(reference == null
                        ? "{\"selection\":\"" + l2 + "\"}"
                        : "{\"selection\":\"" + l2 + "\",\"reference\":\"" + reference + "\"}"));
        return body;
    }

    private List<String> taken(JsonNode journey) {
        List<String> out = new ArrayList<>();
        for (JsonNode run : journey.get("runs")) {
            for (JsonNode step : run.get("steps")) {
                if (step.get("taken").asBoolean()) out.add(step.get("id").asText());
            }
        }
        return out;
    }

    @Test
    @DisplayName("A deflection shows a path that never went near the agent branch")
    void aT0NeverTouchesTheAgentPath() throws Exception {
        String sid = raise("SP-JOURNEY-1", "AMOUNT_RELATED", "FORGET_MPIN", null);
        JsonNode journey = call("/console/journey/" + sid);

        assertTrue(journey.get("found").asBoolean(), "the journey was not found");
        List<String> visited = taken(journey);

        // THIS CONCERN KEEPS ITS OWN PROCESS (V5): start, one service task, end. An earlier
        // version of this test asserted the shared process's steps here and failed — the
        // test was wrong, not the system, and it is recorded because the same mistake made
        // the console render this concern as "2 of 15 steps taken".
        assertEquals(List.of("start", "finaliseStep", "end"), visited,
                "the deflection process is not the three elements it is meant to be");

        // THE POINT OF THE VIEW: what it cannot do. Not "did not" — CANNOT. These steps are
        // absent from this process entirely, so the claim is structural rather than
        // conditional on a variable that could be set differently tomorrow.
        List<String> shape = new ArrayList<>();
        for (JsonNode step : journey.get("runs").get(0).get("steps")) {
            shape.add(step.get("id").asText());
        }
        assertFalse(shape.contains("openTicket"),
                "this process has a Gate 1 step — a deflection could then create a ticket");
        assertFalse(shape.contains("escalationContext"), "a deflection can write an escalation context");
        assertFalse(shape.contains("agentHandoff"), "a deflection can tell the partner an agent is coming");
        assertFalse(shape.contains("agentConnect"), "a deflection can queue an agent task");
        assertFalse(shape.contains("performAction"), "a deflection can move money");
    }

    @Test
    @DisplayName("Every step of the shape is listed, taken or not — the shape is the explanation")
    void theWholeShapeIsAlwaysShown() throws Exception {
        String sid = raise("SP-JOURNEY-2", "AMOUNT_RELATED", "FORGET_MPIN", null);
        JsonNode journey = call("/console/journey/" + sid);

        JsonNode run = journey.get("runs").get(0);
        JsonNode steps = run.get("steps");

        // THE SHAPE COMES FROM THE DEPLOYED MODEL, NOT FROM A LIST IN THE CODE.
        // FORGET_MPIN keeps its own three-element process (V5), and a hardcoded
        // concern-generic shape rendered it as "2 of 15 steps taken" with thirteen steps
        // struck through that were never part of it — a flow that looked like it did
        // almost nothing. So: this process has FEW steps, and all of them are its own.
        assertEquals("forget-mpin", run.get("processKey").asText());
        assertTrue(steps.size() <= 5,
                "forget-mpin is a three-element process; the shape came from somewhere else: "
              + steps.size());
        for (JsonNode step : steps) {
            assertTrue(step.get("taken").asBoolean(),
                    step.get("id").asText() + " is in this process and did not run");
        }
        assertTrue(run.hasNonNull("processNote"),
                "a concern with its own process must say why it has one");
    }

    @Test
    @DisplayName("A concern on the shared process shows the shared shape, with the paths not taken")
    void theSharedProcessShowsWhatItDidNotDo() throws Exception {
        String sid = raise("SP-JOURNEY-5", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7004");
        JsonNode journey = call("/console/journey/" + sid);

        JsonNode run = journey.get("runs").get(0);
        assertEquals("concern-generic", run.get("processKey").asText());

        int notTaken = 0;
        for (JsonNode step : run.get("steps")) {
            if (!step.get("taken").asBoolean()) notTaken++;
        }
        // A view where everything looks taken would seem informative and prove nothing.
        assertTrue(notTaken > 0, "every step reported as taken on a bot-resolved case");
    }

    @Test
    @DisplayName("The client's own calls are part of the flow, not just the modelled part")
    void theApiCallsAreShownToo() throws Exception {
        String sid = raise("SP-JOURNEY-3", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7002");
        JsonNode journey = call("/console/journey/" + sid);

        JsonNode calls = journey.get("apiCalls");
        assertEquals(3, calls.size(), "the three calls that drive one concern were not all shown");
        assertTrue(calls.get(0).get("endpoint").asText().contains("/help/sessions"));
    }

    @Test
    @DisplayName("A conversation that never ran a process says so, rather than looking empty")
    void nothingToShowIsAnAnswer() throws Exception {
        String sid = json.readTree(mvc.perform(post("/help/sessions")
                .contentType("application/json").content("{\"spId\":\"SP-JOURNEY-4\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("sessionId").asText();

        JsonNode journey = call("/console/journey/" + sid);
        assertTrue(journey.get("found").asBoolean());
        assertEquals(0, journey.get("runs").size());
        assertTrue(journey.hasNonNull("note"), "an empty journey explained nothing");
    }

    @Test
    @DisplayName("An unknown conversation is absent rather than silently empty")
    void anUnknownSessionIsNotFound() throws Exception {
        JsonNode journey = call("/console/journey/not-a-uuid");
        assertFalse(journey.get("found").asBoolean());
    }

    @Test
    @DisplayName("A case that reached a person prints the handover the agent will open")
    void theHandoverIsShownWhenItGoesToAnAgent() throws Exception {
        // An unreadable gateway state. Nothing may move on a state we cannot read, so this
        // goes to a person — which is the only way the escalation step runs at all.
        String sid = raise("SP-JOURNEY-6", "AMOUNT_RELATED", "RECHARGE_DEBIT_NO_CREDIT",
                           "DEMO-RCH-UNKNOWN");
        JsonNode journey = call("/console/journey/" + sid);

        JsonNode escalation = null;
        for (JsonNode step : journey.get("runs").get(0).get("steps")) {
            if ("escalationContext".equals(step.get("id").asText())) escalation = step;
        }
        assertNotNull(escalation, "the shared process has no escalation step");
        assertTrue(escalation.get("taken").asBoolean(),
                "an unreadable gateway state did not reach a person");

        // WRITTEN BEFORE THE WAIT, and readable. An agent opening an empty context would
        // have to ask the partner to repeat themselves, which is the thing this prevents.
        JsonNode detail = escalation.get("detail");
        assertNotNull(detail, "the escalation step carried no handover");
        assertTrue(detail.has("What we knew when we decided"),
                "the facts snapshot is missing from the handover");

        // The reason must be on it. "Why did this go to a human" has to be answerable from
        // the row, because every T3 ends with the same sentence to the partner.
        boolean hasReason = false;
        for (JsonNode e : escalation.get("evidence")) {
            if (e.get("k").asText().startsWith("why a person has this")) hasReason = true;
        }
        assertTrue(hasReason, "the handover does not say why a person has this case");
    }

    @Test
    @DisplayName("A case the bot resolved carries no handover at all")
    void nothingIsHandedOverWhenNobodyIsNeeded() throws Exception {
        String sid = raise("SP-JOURNEY-7", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7004");
        JsonNode journey = call("/console/journey/" + sid);

        for (JsonNode step : journey.get("runs").get(0).get("steps")) {
            if ("escalationContext".equals(step.get("id").asText())) {
                assertFalse(step.get("taken").asBoolean(),
                        "a bot-resolved case wrote an escalation context");
                assertFalse(step.has("detail"),
                        "a bot-resolved case carried a handover — it would be shown to nobody");
            }
        }
    }

    @Test
    @DisplayName("Step numbers run 1,2,3… — sub-millisecond steps do not scramble them")
    void theOrderIsStableWhenStepsShareAMillisecond() throws Exception {
        // Every step here takes under a millisecond, so several share a start timestamp and
        // the database's order among them is arbitrary. It showed on screen as "1, 4, 3, 5".
        String sid = raise("SP-JOURNEY-8", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7004");
        JsonNode journey = call("/console/journey/" + sid);

        int expected = 0;
        for (JsonNode step : journey.get("runs").get(0).get("steps")) {
            if (!step.get("taken").asBoolean()) continue;
            expected++;
            assertEquals(expected, step.get("order").asInt(),
                    "step " + step.get("id").asText() + " is numbered out of sequence");
        }
        assertTrue(expected > 3, "too few steps ran to prove anything");
    }

    @Test
    @DisplayName("The stored facts are readable from history, and are the ones the decision saw")
    void theFactsAreReadableAfterTheFact() throws Exception {
        String sid = raise("SP-JOURNEY-9", "AMOUNT_RELATED", "TRANSPORT_NOT_RECEIVED", "7002");

        JsonNode stored = call("/console/facts/" + sid);
        assertTrue(stored.get("found").asBoolean(), "no stored facts for a concern that reads them");

        JsonNode facts = stored.get("runs").get(0).get("facts");
        assertNotNull(facts, "factsJson was not kept in history");

        // The seven Transport facts, by name. A blob that parses but holds something else
        // would satisfy a looser assertion and prove nothing.
        for (String name : List.of("alreadyCredited", "computedAmountRupees", "transportPath",
                                   "arrivedAt300metre", "cancellationStatus",
                                   "lastMinCashbackCredited", "distanceBeyondRadiusKm")) {
            assertTrue(facts.has(name), "the stored facts are missing " + name);
        }

        // AND THEY MUST AGREE WITH THE TRACE. Two views of one decision that can disagree
        // are worse than one view, because a reviewer will be shown whichever is open.
        JsonNode trace = call("/console/trace/" + sid);
        for (JsonNode f : trace.get("facts")) {
            String name = f.get("name").asText();
            assertTrue(facts.has(name), "the trace shows " + name + " but history did not store it");
        }
    }
}
