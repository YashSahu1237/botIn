package in.yesmadam.botin.console;

import org.springframework.stereotype.Component;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.*;

/**
 * A DECISION TABLE, AS SOMETHING A PERSON CAN READ.
 *
 * =========================================================================
 * WHY THE FILE AND NOT THE ENGINE
 * =========================================================================
 *
 * Flowable can hand back the deployed definition, but it cannot hand back the COMMENTS — and
 * in this project the comments are the most valuable thing in the file. Every rule carries a
 * sentence explaining what it is for and, where it matters, why it sits where it sits:
 *
 *     2. THE CAP. Mapping rule 9, moved above every paying row because
 *        FIRST would otherwise never reach it.
 *
 * (The real comment in the file names the amount. This one deliberately does not — see below.)
 *
 * That sentence is the difference between a reviewer seeing a grid of cells and a reviewer
 * understanding the policy. So this reads the source file and keeps the comment that precedes
 * each rule as that rule's description.
 *
 * =========================================================================
 * THE ONE RISK, AND WHY IT IS ACCEPTABLE
 * =========================================================================
 *
 * The file on disk could differ from what is deployed — most obviously after the step 93
 * hot-redeploy, which changes the running table without touching the file. So this is
 * explicitly a reading aid, not a source of truth: the ANSWER always comes from the engine,
 * and the row highlighted as fired comes from the engine's own audit. If the two ever
 * disagree, the engine is right and the reader is stale.
 *
 * =========================================================================
 * WHY THIS CLASS QUOTES NO THRESHOLD
 * =========================================================================
 *
 * The first version of this comment quoted the cap in full, amount included — and
 * `DmnHotRedeployTest` failed the build, because that test asserts the number appears in NO
 * Java source anywhere.
 *
 * It was right, and the instinct to carve out an exception for comments was wrong. A comment
 * that quotes a threshold goes stale exactly like a constant does: change the cap, redeploy the
 * table, and this file now describes a policy that no longer exists — with nothing to catch it,
 * because a comment cannot fail a test.
 *
 * A guard with no exceptions is worth more than a guard with one reasonable-looking exception,
 * so the comment lost the number rather than the test losing its teeth.
 */
@Component
public class DecisionTableReader {

    public Optional<Table> read(String dmnKey) {
        for (String candidate : List.of("dmn/" + dmnKey + ".dmn", "dmn/" + dmnKey + ".dmn.xml")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(candidate)) {
                if (in == null) continue;
                return Optional.of(parse(in));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Table parse(InputStream in) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(in);

        List<String> inputs = new ArrayList<>();
        for (Node n : children(doc, "input")) {
            Node expr = first(n, "inputExpression");
            inputs.add(expr == null ? label(n) : expr.getTextContent().trim());
        }

        List<String> outputs = new ArrayList<>();
        for (Node n : children(doc, "output")) outputs.add(label(n));

        List<Rule> rules = new ArrayList<>();
        int index = 0;
        String carried = "";
        for (Node n : children(doc, "rule")) {
            List<String> in2 = new ArrayList<>();
            for (Node c : childElements(n, "inputEntry")) in2.add(cell(c));
            List<String> out = new ArrayList<>();
            for (Node c : childElements(n, "outputEntry")) out.add(cell(c));

            // A ROW WITH NO COMMENT OF ITS OWN INHERITS THE ONE ABOVE IT.
            //
            // Some rules come in pairs under a single explanation — the 5a/5b split exists
            // because a comma-separated list of values failed to evaluate and took the entire
            // table down with it, catch-all included. One value per row costs two rows and
            // cannot fail that way.
            //
            // Left alone, the second of such a pair renders as a BLANK CELL in the column
            // headed "what this row is for", which is the first thing a reviewer points at —
            // and "I don't know, it's blank" is a bad answer about a row that moves money.
            // Marked as continued rather than silently duplicated, so nobody reads it as a
            // second, separate justification.
            String why = precedingComment(n);
            if (why.isBlank() && !carried.isBlank()) {
                why = carried + "  — continued (one value per row; see the row above)";
            } else if (!why.isBlank()) {
                carried = why;
            }

            rules.add(new Rule(index++, why, in2, out));
        }

        return new Table(inputs, outputs, rules);
    }

    /** ANY is a cell with no condition — shown as such rather than as an empty box. */
    private static String cell(Node entry) {
        Node text = first(entry, "text");
        String value = text == null ? "" : text.getTextContent().trim();
        return value.isEmpty() ? "—" : value;
    }

    /** The XML comment immediately above a rule, which is where the policy is written down. */
    private static String precedingComment(Node rule) {
        for (Node prev = rule.getPreviousSibling(); prev != null; prev = prev.getPreviousSibling()) {
            if (prev.getNodeType() == Node.COMMENT_NODE) {
                return prev.getNodeValue().replaceAll("\\s+", " ").trim();
            }
            if (prev.getNodeType() == Node.ELEMENT_NODE) break;   // a different rule; stop
        }
        return "";
    }

    private static String label(Node n) {
        Node a = n.getAttributes() == null ? null : n.getAttributes().getNamedItem("label");
        return a == null ? "?" : a.getNodeValue();
    }

    private static List<Node> children(Document doc, String tag) {
        NodeList list = doc.getElementsByTagNameNS("*", tag);
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) out.add(list.item(i));
        return out;
    }

    private static List<Node> childElements(Node parent, String tag) {
        List<Node> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && tag.equals(k.getLocalName())) out.add(k);
        }
        return out;
    }

    private static Node first(Node parent, String tag) {
        List<Node> found = childElements(parent, tag);
        return found.isEmpty() ? null : found.get(0);
    }

    public record Table(List<String> inputs, List<String> outputs, List<Rule> rules) { }

    /** @param why the sentence from the file. Often the whole explanation somebody needs. */
    public record Rule(int index, String why, List<String> when, List<String> then) { }
}
