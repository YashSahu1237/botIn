package in.yesmadam.botin.platform.decision;
import org.flowable.dmn.api.DmnRepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 93 — THE CHECKPOINT THE WHOLE DMN ARGUMENT RESTS ON.
 *
 * Every decision in this service is in a table rather than in Java, and the reason given
 * for that, repeatedly, is: a business owner can change a threshold without a developer,
 * a rebuild or a restart. That is a large claim and until now it has been an assertion in
 * documents. This is the test that makes it a fact.
 *
 * WHAT IS DEMONSTRATED. The transport cap is Rs300. A claim for Rs250 is paid. The cap is
 * then edited to Rs200 in the XML and that one file — not the application, not a jar — is
 * redeployed into the running engine. The SAME claim, with the SAME facts, is now refused
 * and sent to a person. Nothing was recompiled and nothing was restarted, and the next
 * request behaves differently.
 *
 * WHY THE CAP IS THE RIGHT THING TO PROVE IT WITH. It is a number that a finance owner has
 * a real opinion about, that will change, and that has money on the other side of it. If
 * changing Rs300 to Rs200 needs an engineer and a deployment window, then in practice it
 * does not change, and the tables were a lot of ceremony for nothing.
 *
 * THE TEST PUTS THE ORIGINAL BACK. Deployments are engine-wide and outlive this class, so
 * a lowered cap would silently follow every test that runs afterwards — the same shared
 * durable state that burned MoneyPathTest. The restore is in @AfterEach, not at the end of
 * the test method, so a failure cannot leave it behind.
 */
@SpringBootTest
@ActiveProfiles("test")
class DmnHotRedeployTest {

    private static final Path FILE = Path.of("src/main/resources/dmn/transport-not-received-decision.dmn");
    private static final String RESOURCE = "transport-not-received-decision.dmn";
    private static final String KEY = "transport-not-received-decision";

    /** Rs300 in paise, as the cap row reads today. */
    private static final String CAP_NOW = "&gt; 30000";
    /** Rs200. Below the claim below, which is the whole mechanism of the test. */
    private static final String CAP_LOWERED = "&gt; 20000";

    @Autowired DecisionService decisions;
    @Autowired DmnRepositoryService dmnRepository;

    @AfterEach
    void putTheRealTableBack() throws Exception {
        deploy("restore-original-cap", Files.readString(FILE, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("THE CHECKPOINT — editing the cap in the .dmn changes the next decision, no restart")
    void aThresholdChangesWithoutARebuild() throws Exception {
        Map<String, Object> claim = aTransportClaimFor(25_000L);   // Rs250

        // ---- before: Rs250 is under the Rs300 cap, so the bot pays -------------------
        Decision before = decisions.decide(KEY, claim);
        assertEquals("T2", before.tier());
        assertEquals("AUTO_CREDIT_TRANSPORT", before.action(),
                "precondition: Rs250 is under the cap as the file stands");

        // ---- the edit a business owner would make -----------------------------------
        String original = Files.readString(FILE, StandardCharsets.UTF_8);
        assertTrue(original.contains(CAP_NOW),
                "the cap row no longer reads " + CAP_NOW + " — this test is out of date, which "
              + "is itself worth knowing");
        String lowered = original.replace(CAP_NOW, CAP_LOWERED);

        // ---- redeploy THAT FILE, into the engine that is already running -------------
        // No context restart, no new application, nothing recompiled. One resource.
        deploy("lower-the-transport-cap", lowered);

        // ---- after: the same claim, the same facts, a different answer ---------------
        Decision after = decisions.decide(KEY, claim);
        assertEquals("T3", after.tier(), "the lowered cap did not take effect");
        assertEquals("TICKET_EXCEEDS_CAP", after.action());
        assertEquals("TICKET", after.outcomeType(),
                "and it reaches a person rather than failing — a cap is a handover, not an error");
    }

    @Test
    @DisplayName("...and the restore works, so the cap a later test sees is the real one")
    void theOriginalComesBack() throws Exception {
        deploy("lower-the-transport-cap", Files.readString(FILE, StandardCharsets.UTF_8)
                .replace(CAP_NOW, CAP_LOWERED));
        assertEquals("TICKET_EXCEEDS_CAP", decisions.decide(KEY, aTransportClaimFor(25_000L)).action());

        putTheRealTableBack();

        assertEquals("AUTO_CREDIT_TRANSPORT", decisions.decide(KEY, aTransportClaimFor(25_000L)).action(),
                "a redeploy that cannot be undone is a one-way door, and the next test class "
              + "would inherit a cap nobody set");
    }

    /**
     * THE NUMBER MUST NOT EXIST IN JAVA — no context, no engine, just text.
     *
     * The step is only worth anything if the table is the ONLY place the cap lives. A
     * constant in a delegate that agrees with the table today turns tomorrow's redeploy into
     * a silent disagreement: the table says Rs200, the code says Rs300, and which one wins
     * depends on which is consulted first. That is far worse than having no table at all.
     */
    @Test
    @DisplayName("the cap appears in no Java source anywhere")
    void theCapLivesOnlyInTheTable() throws Exception {
        List<Path> offenders;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("30000")
                                || Files.readString(p, StandardCharsets.UTF_8).contains("30_000");
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .toList();
        }
        assertTrue(offenders.isEmpty(),
                "the transport cap is hard-coded in Java as well as in the decision table: "
              + offenders + ". Two sources of truth for one number, and only one of them "
              + "changes when the table is redeployed");
    }

    // ------------------------------------------------------------------ helpers

    private void deploy(String name, String dmnXml) {
        dmnRepository.createDeployment()
                .name(name)
                .addString(RESOURCE, dmnXml)
                .deploy();
    }

    /**
     * EVERY DECLARED INPUT IS PRESENT, and that is not padding.
     *
     * Strict mode treats an ABSENT variable as an evaluation error, and a failed input
     * expression takes the WHOLE table down — the catch-all included. A fact map missing one
     * key would not fall through to row 10; it would return nothing at all, and the failure
     * would look like the redeploy did not work.
     */
    private static Map<String, Object> aTransportClaimFor(long amountPaise) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("alreadyCredited", false);
        facts.put("computedAmountPaise", amountPaise);
        facts.put("transportPath", "PATH_1");     // customer paid, the SP never got it
        facts.put("arrivedAt300metre", true);
        facts.put("cancellationStatus", "NONE");
        facts.put("lastMinCashbackCredited", false);
        facts.put("distanceBeyondRadiusKm", 0);
        return facts;
    }
}
