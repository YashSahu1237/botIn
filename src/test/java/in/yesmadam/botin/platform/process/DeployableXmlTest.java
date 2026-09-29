package in.yesmadam.botin.platform.process;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every file the engines auto-deploy must be well-formed XML. No Spring context.
 *
 * WHAT HAPPENED, TWICE. A `.dmn` re-saved by a modeller carried a namespace Flowable
 * cannot parse. A `.bpmn20.xml` carried a decorative rule of hyphens inside an XML
 * comment — and `--` is illegal inside a comment, so the file would not parse at all.
 * Both produced the same result, and it is out of all proportion to the mistake:
 *
 *     Failed to start bean 'springProcessEngineConfiguration'
 *     -> the Spring context failed to load
 *     -> EVERY @SpringBootTest failed:  126 tests run, 95 errors
 *
 * The output names an XML row and column and nothing else. From it alone the service
 * looks broken, when one character in one file is. Worse, the failure is in the class
 * of things that cannot be caught by reading the code around it — the file is correct
 * Java-adjacent XML to the eye, and only a parser disagrees.
 *
 * So a parser is asked, first, with no engine and no context, and the failure says
 * which file and where. Milliseconds, and a sentence instead of a wall.
 */
class DeployableXmlTest {

    /** Everything Flowable picks up on startup. Adding a directory here is cheap. */
    private static final List<String> AUTO_DEPLOYED = List.of(
            "src/main/resources/processes",
            "src/main/resources/dmn");

    @Test
    @DisplayName("every auto-deployed process and decision file parses as XML")
    void everyDeployableFileIsWellFormed() throws Exception {
        List<String> broken = new ArrayList<>();
        int checked = 0;

        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        for (String dir : AUTO_DEPLOYED) {
            Path path = Path.of(dir);
            assertTrue(Files.isDirectory(path), "not a directory: " + dir
                    + " — is the working directory the project root?");

            try (var files = Files.list(path)) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".xml") && !name.endsWith(".dmn")) continue;
                    checked++;
                    try {
                        factory.newDocumentBuilder().parse(file.toFile());
                    } catch (SAXParseException e) {
                        broken.add(name + " line " + e.getLineNumber()
                                 + ", column " + e.getColumnNumber() + ": " + e.getMessage());
                    }
                }
            }
        }

        assertTrue(checked > 0, "no deployable files found at all");

        assertTrue(broken.isEmpty(), """
                These files will not parse, and each one takes the WHOLE APPLICATION
                CONTEXT down with it — not just its own concern:
                  %s

                Two causes have actually occurred here:
                  * a run of hyphens inside an XML comment. `--` is illegal in a
                    comment body; use = for a decorative rule instead.
                  * a modeller re-saving a .dmn as DMN 1.5. See DmnNamespaceTest.
                """.formatted(String.join("\n  ", broken)));
    }

    @Test
    @DisplayName("no XML comment contains a double hyphen")
    void noCommentContainsADoubleHyphen() throws Exception {
        // Caught by the parser above as well, but named separately because the parser's
        // message for it is "The string '--' is not permitted within comments", pointing
        // at a row and column the author reads as arbitrary. This one says which file
        // and why in the assertion itself.
        List<String> offenders = new ArrayList<>();

        for (String dir : AUTO_DEPLOYED) {
            try (var files = Files.list(Path.of(dir))) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".xml") && !name.endsWith(".dmn")) continue;

                    String xml = Files.readString(file);
                    int from = 0;
                    while ((from = xml.indexOf("<!--", from)) >= 0) {
                        int end = xml.indexOf("-->", from + 4);
                        if (end < 0) break;
                        if (xml.substring(from + 4, end).contains("--")) {
                            offenders.add(name + " (comment at offset " + from + ")");
                        }
                        from = end + 3;
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "XML comments containing '--' — illegal, and fatal to the whole engine: "
                + offenders + ". Use '=' for decorative rules inside comments.");
    }
}
