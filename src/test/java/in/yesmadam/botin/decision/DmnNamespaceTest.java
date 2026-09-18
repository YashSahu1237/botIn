package in.yesmadam.botin.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the ONE property of a .dmn file whose failure is not local to that file.
 *
 * WHAT HAPPENED. A decision table was opened in a DMN modeller and re-saved. The
 * modeller exported DMN 1.5 — namespace .../spec/DMN/20230324/MODEL/ — which Flowable
 * 7.0.1 cannot parse. The result was not "that one concern broke":
 *
 *     Failed to start bean 'dmnEngineConfiguration'
 *     -> the Spring context failed to load
 *     -> EVERY @SpringBootTest failed:  109 tests run, 80 errors
 *
 * One file, one attribute, and the whole service stops booting. The error message names
 * an XML element and a line number and says nothing about DMN versions, so from the
 * output alone it looks like the application is broken rather than one file.
 *
 * This test reads the files as TEXT, with no Spring context, so it fails in milliseconds
 * with a sentence that says what to do. Editing rules in a modeller is a workflow we
 * WANT — a business owner changing a threshold should not need a developer — so the fix
 * is a guardrail, not a rule against modellers.
 */
class DmnNamespaceTest {

    /** DMN 1.3. The only model namespace Flowable 7.0.1 accepts. */
    private static final String SUPPORTED = "https://www.omg.org/spec/DMN/20191111/MODEL/";

    private static final Path DMN_DIR = Path.of("src/main/resources/dmn");

    @Test
    @DisplayName("every .dmn file uses the DMN 1.3 namespace Flowable can parse")
    void everyTableUsesTheSupportedNamespace() throws IOException {
        List<String> wrong = new ArrayList<>();
        List<Path> files = listDmnFiles();

        assertFalse(files.isEmpty(), "no .dmn files found — is the working directory right?");

        for (Path f : files) {
            String xml = Files.readString(f);
            if (!xml.contains(SUPPORTED)) {
                String found = xml.replaceAll("(?s).*spec/DMN/(\\d+)/MODEL.*", "$1");
                wrong.add(f.getFileName() + " (found DMN " + found + ")");
            }
        }

        assertTrue(wrong.isEmpty(), """
                These decision tables use a DMN version Flowable 7.0.1 cannot parse:
                  %s

                THIS BREAKS EVERY CONCERN, not just the ones listed — the DMN engine
                fails to start and the whole application context dies with it.

                Almost always the cause is a modeller that exports DMN 1.5. Re-export as
                DMN 1.3, or change the namespace by hand to:
                  %s
                """.formatted(String.join("\n  ", wrong), SUPPORTED));
    }

    @Test
    @DisplayName("no modeller diagram blocks — they carry namespaces nothing else declares")
    void noOrphanedDiagramExtensions() throws IOException {
        // A DMNDI block is harmless in itself, but it is where modeller-specific
        // namespaces (kie, dc, di) arrive, and those travel with the version bump that
        // does the damage. Nothing here renders diagrams, so the block is pure risk.
        List<String> withDiagrams = new ArrayList<>();
        for (Path f : listDmnFiles()) {
            if (Files.readString(f).contains("DMNDI")) withDiagrams.add(f.getFileName().toString());
        }
        assertTrue(withDiagrams.isEmpty(),
                "modeller diagram blocks left in: " + withDiagrams
                + " — strip the <dmndi:DMNDI> element and its namespace declarations");
    }

    private List<Path> listDmnFiles() throws IOException {
        try (var paths = Files.list(DMN_DIR)) {
            return paths.filter(p -> p.toString().endsWith(".dmn")).sorted().toList();
        }
    }
}
