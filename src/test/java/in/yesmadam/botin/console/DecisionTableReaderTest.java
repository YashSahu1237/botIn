package in.yesmadam.botin.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE READER THAT COULD FAIL QUIETLY.
 *
 * `DecisionTableReader` parses the `.dmn` files so the console can draw the grid a reviewer is
 * shown. Its failure mode is the dangerous kind: a namespace change, a renamed element, a file
 * moved — and it returns an EMPTY table rather than throwing. The console then renders a panel
 * with no rows, which looks like "this decision had no rules" rather than "the reader broke".
 *
 * So every deployed table is parsed here, and the row count is checked against a count taken a
 * completely different way — by counting `<rule ` in the raw text. Two independent methods that
 * have to agree, for the same reason `ProviderContractTest` restates its fact names instead of
 * sharing a constant: a check that derives both sides from one source agrees with itself while
 * disagreeing with reality.
 */
class DecisionTableReaderTest {

    private static final Path DMN = Path.of("src/main/resources/dmn");

    private final DecisionTableReader reader = new DecisionTableReader();

    @Test
    @DisplayName("Every deployed table parses, with the rows the file actually contains")
    void everyTableParsesAndTheRowCountsAgree() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        try (Stream<Path> files = Files.list(DMN)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".dmn")) continue;
                checked++;

                String key = name.substring(0, name.length() - ".dmn".length());
                Optional<DecisionTableReader.Table> parsed = reader.read(key);

                if (parsed.isEmpty()) { problems.add(key + ": did not parse at all"); continue; }
                DecisionTableReader.Table table = parsed.get();

                // Counted from the raw text, deliberately not from the parse.
                long rowsInFile = Files.readString(file, StandardCharsets.UTF_8)
                        .split("<rule ", -1).length - 1;

                if (table.rules().size() != rowsInFile) {
                    problems.add(key + ": parsed " + table.rules().size() + " rows, file has " + rowsInFile);
                }
                if (table.inputs().isEmpty()) problems.add(key + ": no input columns");
                if (table.outputs().isEmpty()) problems.add(key + ": no output columns");
            }
        }

        assertTrue(checked > 0, "no .dmn files found — is the working directory the project root?");
        assertTrue(problems.isEmpty(), "the console would draw these tables wrongly: " + problems);
    }

    @Test
    @DisplayName("The rule comments survive — they are the most valuable thing in the file")
    void thePolicySentencesAreKept() throws Exception {
        // The engine can hand back a deployed table; it cannot hand back the COMMENTS. In this
        // project those sentences ARE the policy — "moved above every paying row because FIRST
        // would otherwise never reach it" is the explanation a reviewer actually needs. Losing
        // them silently would turn the console back into a grid of cells.
        DecisionTableReader.Table transport = reader.read("transport-not-received-decision").orElseThrow();

        long explained = transport.rules().stream().filter(r -> !r.why().isBlank()).count();

        assertTrue(explained >= transport.rules().size() - 2,
                "only " + explained + " of " + transport.rules().size() + " rows kept their "
              + "explanation — the reader is dropping comments, or the file has stopped carrying them");
    }

    @Test
    @DisplayName("NO ROW IS LEFT BLANK — a paired rule inherits the explanation above it")
    void everyRowSaysWhatItIsFor() {
        // The 5a/5b split in the transport table is one concept across two rows, with a single
        // comment. Left alone the second row renders as an empty cell under the heading "what
        // this row is for" — the first thing a reviewer points at, and "I don't know, it's
        // blank" is a poor answer about a row that moves money.
        DecisionTableReader.Table transport = reader.read("transport-not-received-decision").orElseThrow();

        List<Integer> blank = transport.rules().stream()
                .filter(r -> r.why().isBlank()).map(DecisionTableReader.Rule::index).toList();

        assertTrue(blank.isEmpty(), "these rows would render with no description at all: " + blank);

        assertTrue(transport.rules().stream().anyMatch(r -> r.why().contains("continued")),
                "an inherited explanation must be MARKED as inherited, or it reads as a second "
              + "and separate justification for the same rule");
    }

    @Test
    @DisplayName("An empty condition reads as ANY, not as a blank box")
    void emptyCellsAreLabelled() {
        DecisionTableReader.Table transport = reader.read("transport-not-received-decision").orElseThrow();

        assertTrue(transport.rules().stream().flatMap(r -> r.when().stream()).anyMatch("—"::equals),
                "a rule that ignores a column must SAY it ignores it. An empty cell in a grid "
              + "reads as missing data rather than as 'this condition does not apply'");
    }

    @Test
    @DisplayName("A key with no file returns empty rather than throwing")
    void anUnknownTableIsNotAnError() {
        assertTrue(reader.read("no-such-decision").isEmpty(),
                "the console must degrade to 'no grid' rather than failing a page");
    }
}
