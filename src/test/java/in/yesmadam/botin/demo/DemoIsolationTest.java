package in.yesmadam.botin.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PLAN STEP 95 — THE ASSERTION THAT MAKES THE DEMO CONTROLS ACCEPTABLE.
 *
 * `DemoControlController` can flip a kill switch and change what a payment gateway
 * reports. In a real deployment that is an attack surface with money on the other side.
 * The reason it is safe is `@Profile("demo")` — the controller does not EXIST otherwise.
 *
 * "We remembered the annotation" is not a guarantee. It is one line, it is invisible at
 * every call site, and removing it breaks nothing that anybody would notice. So this test
 * runs WITHOUT the demo profile — the ordinary configuration, the one production would
 * inherit — and asks the application whether those URLs answer.
 *
 * NOTE WHAT IS ASSERTED: 404, not 401 or 403. Absent, not merely refused. There is no
 * login page to brute-force and no endpoint to probe, because nothing is mapped there.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoIsolationTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("Without the demo profile the control endpoints DO NOT EXIST")
    void theControlSurfaceIsAbsentInAnOrdinaryRun() throws Exception {
        mvc.perform(get("/demo/flags")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/fixtures")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/tickets/SP-1/count")).andExpect(status().isNotFound());

        // The partner console lives on its OWN profile now, so it is absent here too — a
        // page left on a production deployment is how a "temporary" control surface becomes
        // permanent, and there is no file to forget to delete because nothing serves it.
        mvc.perform(get("/console")).andExpect(status().isNotFound());
        mvc.perform(get("/console/state")).andExpect(status().isNotFound());

        mvc.perform(post("/demo/flags/RECHARGE_AUTO_CREDIT")
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/demo/gateway/fail-next/5")).andExpect(status().isNotFound());
        mvc.perform(post("/demo/reset")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("And neither the controller nor the fixtures are even beans")
    void nothingDemoRelatedIsWired() {
        assertEquals(0, context.getBeanNamesForType(DemoControlController.class).length,
                "the demo control surface must not exist outside the demo profile");
        assertEquals(0, context.getBeanNamesForType(DemoFixtures.class).length,
                "synthetic facts must not be loadable in a run that could reach real data");
    }

    @Test
    @DisplayName("The partner API is untouched by any of it")
    void theRealServiceStillWorks() throws Exception {
        // The point of the two assertions above is that they cost nothing elsewhere.
        mvc.perform(post("/help/sessions")
                .contentType("application/json").content("{\"spId\":\"SP-ISOLATION-1\"}"))
                .andExpect(status().isCreated());
    }
}
