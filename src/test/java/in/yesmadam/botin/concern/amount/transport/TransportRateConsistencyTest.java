package in.yesmadam.botin.concern.amount.transport;

import java.math.BigDecimal;
import in.yesmadam.botin.platform.money.Rupees;
import in.yesmadam.botin.platform.decision.Decision;
import in.yesmadam.botin.platform.decision.DecisionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE TRANSPORT RATE EXISTS IN TWO PLACES. THIS IS WHAT STOPS THEM DRIFTING.
 *
 * The Decision Matrix settles the rate: "(distance − radius) × Rs50". It now lives in
 * configuration, because `TransportFactProvider` has to compute the amount BEFORE the
 * decision table runs — the Rs300 cap is a ROW in that table, so the amount is one of its
 * inputs, and a rate cannot come out of the thing it is an input to.
 *
 * It also still lives in the table, as rule 9's `ratePerKmRupees` output.
 *
 * TWO SOURCES OF TRUTH FOR ONE NUMBER IS A REAL COST and it is being paid deliberately. The
 * compensating check is this test: it asks the CONFIGURATION for the rate, asks the RUNNING
 * ENGINE for the rate, and fails if they differ. Note that it does not share a constant
 * between them — that is the failure mode it exists to prevent, because two values derived
 * from one source agree with each other while disagreeing with reality.
 *
 * If a business owner edits the rate in the .dmn and redeploys it — which step 93 proved
 * they can, with no rebuild — this test is what tells somebody the configured half is now
 * stale, and that partners are being paid one number and told another.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransportRateConsistencyTest {

    private static final String TRANSPORT = "transport-not-received-decision";

    @Autowired DecisionService decisions;

    @Value("${botin.transport.rate-per-km-rupees}")
    long configuredRate;

    @Test
    @DisplayName("The rate the provider multiplies by is the rate the table publishes")
    void theTwoHalvesOfTheRateAgree() {
        // A Path 3 claim beyond the radius — the only row that carries a per-km rate.
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("alreadyCredited", false);
        claim.put("computedAmountRupees", Rupees.of(160));
        claim.put("transportPath", "PATH_3");
        claim.put("arrivedAt300metre", true);
        claim.put("cancellationStatus", "NONE");
        claim.put("lastMinCashbackCredited", false);
        claim.put("distanceBeyondRadiusKm", 3.2);

        Decision decision = decisions.decide(TRANSPORT, claim);

        assertEquals("AUTO_CREDIT_DISTANCE", decision.action(), "precondition: this is the per-km row");
        assertEquals(configuredRate, decision.numeric("ratePerKmRupees"),
                "the decision table pays at one rate and TransportFactProvider computes at "
              + "another. A partner would be credited a figure the table does not agree with, "
              + "and the cap would be measured against the wrong amount");
    }
}
