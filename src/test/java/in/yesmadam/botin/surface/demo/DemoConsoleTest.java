package in.yesmadam.botin.surface.demo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE CLAIM THE PARTNER CONSOLE EXISTS TO MAKE, ASSERTED.
 *
 * "The backend owns 100% of the flow; the client is a thin renderer, and adding a concern is a
 * data change" is the load-bearing sentence of this architecture. Everywhere else it is a
 * sentence. The console is the same sentence in a form somebody can disbelieve and then check.
 *
 * It only stays true while nobody helps. The pressure to help is constant and reasonable-sounding
 * — "transport needs an order id, so let us just ask for one when transport is picked". The
 * moment that line exists, adding a concern is a client release again, and nothing anywhere
 * reports that the claim has quietly stopped being true.
 *
 * So: no concern code from the catalogue, and no tier, may appear in that file. No Spring
 * context, no server — it reads two files and compares strings, in milliseconds.
 */
class DemoConsoleTest {

    private static final Path CONSOLE = Path.of("src/main/resources/console/console.html");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** Not "T1" on its own — that appears inside words. The quoted forms a client would use. */
    private static final List<String> TIERS = List.of("\"T0\"", "\"T1\"", "\"T2\"", "\"T3\"",
                                                      "'T0'", "'T1'", "'T2'", "'T3'");

    @Test
    @DisplayName("The console names no concern the catalogue knows about")
    void theRendererIsIgnorantOfEveryConcern() throws Exception {
        String page = Files.readString(CONSOLE, StandardCharsets.UTF_8);

        List<String> leaked = seededConcernCodes().stream().filter(page::contains).sorted().toList();

        assertTrue(leaked.isEmpty(),
                "the partner console names concerns it must know nothing about: " + leaked
              + ". The moment it special-cases one, 'adding a concern is a data change' stops "
              + "being true — and nothing else in the system reports that.");
    }

    @Test
    @DisplayName("...and no tier either. Tiers are ours, not the partner's")
    void theRendererIsIgnorantOfTiers() throws Exception {
        String page = Files.readString(CONSOLE, StandardCharsets.UTF_8);

        List<String> leaked = TIERS.stream().filter(page::contains).toList();

        assertTrue(leaked.isEmpty(),
                "the partner console branches on a tier: " + leaked + ". A partner has no use "
              + "for that vocabulary, and a client that reads it has taken on a decision the "
              + "backend is supposed to own.");
    }

    @Test
    @DisplayName("It renders the five step types and nothing beyond them")
    void itHandlesTheWholeContractAndOnlyTheContract() throws Exception {
        String page = Files.readString(CONSOLE, StandardCharsets.UTF_8);

        // The contract is five types. A sixth would be a client release — which is the
        // asymmetry the design is built on, so it should be visible here.
        for (String type : List.of("MENU", "DROPDOWN", "TEXT", "MESSAGE", "CSAT")) {
            assertTrue(page.contains(type), "the console cannot render a " + type + " step");
        }
    }

    /** Every l2_code the migrations seed, in the order they are applied. */
    private static Set<String> seededConcernCodes() throws Exception {
        Pattern row = Pattern.compile("\\('([A-Z0-9_]{4,})',\\s*'[A-Z0-9_]+'");
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path f : files.sorted().toList()) {
                Matcher m = row.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) codes.add(m.group(1));
            }
        }
        assertFalse(codes.isEmpty(), "found no seeded concerns — is the working directory the project root?");
        return codes;
    }
}
