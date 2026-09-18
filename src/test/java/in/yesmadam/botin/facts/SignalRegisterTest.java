package in.yesmadam.botin.facts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 47 — keeps docs/SIGNAL-REGISTER.md from quietly becoming fiction.
 *
 * The register says which facts are real today and which are always null. Its whole value
 * is that someone can read it instead of reading six providers, so a register that has
 * drifted is worse than no register: it is a confident answer that is wrong.
 *
 * Drift here has the same shape as every other failure this project has hit — a fact is
 * added, it works, nothing errors, and the document silently stops describing the system.
 * So the document is asserted rather than trusted.
 *
 * WHAT THIS CANNOT CHECK: whether a row's STATUS is honest. Nothing can test that a fact
 * marked LIVE is genuinely populated against a real database — that is what UatSchemaProbe
 * reports at startup. This test only guarantees that every fact is ACCOUNTED FOR, which is
 * the part that rots on its own.
 */
@SpringBootTest
@ActiveProfiles("test")
class SignalRegisterTest {

    private static final Path REGISTER = Path.of("docs/SIGNAL-REGISTER.md");

    @Autowired List<ConcernFactProvider> providers;

    @Test
    @DisplayName("every fact a provider declares appears in the signal register")
    void everyFactIsAccountedFor() throws IOException {
        assertTrue(Files.exists(REGISTER), "docs/SIGNAL-REGISTER.md is missing — plan step 47");
        String register = Files.readString(REGISTER);

        List<String> undocumented = new ArrayList<>();
        for (ConcernFactProvider provider : providers) {
            for (String fact : provider.factKeys()) {
                // Backticked so a fact name cannot be "found" inside ordinary prose.
                if (!register.contains("`" + fact + "`")) {
                    undocumented.add(provider.concernCode() + "." + fact);
                }
            }
        }

        assertTrue(undocumented.isEmpty(), """
                These facts exist in code and are not in docs/SIGNAL-REGISTER.md:
                  %s

                Add a row saying where the signal comes from, whether it is populated today,
                which rules it gates, and what its absence costs a partner. A fact nobody
                has written that down for is a fact nobody has decided about.
                """.formatted(String.join("\n  ", undocumented)));
    }

    @Test
    @DisplayName("every concern with a provider is named in the register")
    void everyConcernHasASection() throws IOException {
        String register = Files.readString(REGISTER);

        List<String> missing = providers.stream()
                .map(ConcernFactProvider::concernCode)
                .filter(code -> !register.contains(code))
                .toList();

        assertTrue(missing.isEmpty(),
                "concerns with a fact provider but no entry in the signal register: " + missing);
    }
}
