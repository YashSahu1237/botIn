package in.yesmadam.botin.console;

import in.yesmadam.botin.catalogue.CatalogueService;
import in.yesmadam.botin.catalogue.ConcernCatalogue;

import in.yesmadam.botin.demo.DemoFixtures;
import in.yesmadam.botin.safety.BotinFeature;
import in.yesmadam.botin.session.Ticket;
import in.yesmadam.botin.session.TicketRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * THE PARTNER CONSOLE — a renderer, and the architecture's central claim made checkable.
 *
 * =========================================================================
 * WHY THIS IS SEPARATE FROM THE DEMO CONTROLS
 * =========================================================================
 *
 * It used to be one profile, and that was wrong in a way that only showed up when somebody
 * asked the right question: the demo profile REFUSES to start beside a real UAT datasource —
 * correctly, because half-real data is worse than either kind — so a console living inside it
 * COULD NEVER SHOW REAL DATA. By construction, not by accident.
 *
 * That was the right trade while fixtures were the only thing to show. It stops being right
 * the moment a read grant lands and somebody wants to watch the system decide about an actual
 * partner. So the console has its own profile and depends on nothing synthetic:
 *
 *     --spring.profiles.active=demo,console    fixtures, gateway controls, flag flipping
 *     --spring.profiles.active=console         whatever the service is really reading
 *
 * The page asks `/console/state` which world it is in and says so in a banner. Nobody should
 * ever have to infer that from the data.
 *
 * =========================================================================
 * WHAT IS DELIBERATELY NOT HERE
 * =========================================================================
 *
 * FLIPPING A KILL SWITCH. That stays in the demo controls, where the only gateway it can
 * affect is a mock. Against real data the operator surface is the authenticated Togglz
 * console, and an unauthenticated endpoint that turns automatic payments on and off would
 * undo the whole point of putting a gate in front of that page.
 *
 * This controller reads. It does not decide anything and it does not change anything.
 *
 * THIS IS STILL NOT A PRODUCTION SURFACE. It exposes ticket state for any partner id to
 * anyone who can reach it. It is a demonstration and inspection tool for a POC, and the
 * profile is how it stays switched off everywhere else.
 */
@RestController
@RequestMapping("/console")
@Profile("console")
public class ConsoleController {

    private final TicketRepository tickets;
    private final FeatureManager features;
    private final org.springframework.core.env.Environment env;

    /**
     * `ObjectProvider` rather than an optional dependency, because the QUESTION is whether the
     * bean exists at all — and that is exactly what this asks, without the console needing the
     * demo profile to be present in order to notice that it is not.
     */
    private final ObjectProvider<DemoFixtures> fixtures;
    private final DecisionTrace trace;
    private final DecisionTableReader tables;
    private final JourneyService journeys;
    private final CatalogueService catalogue;

    public ConsoleController(TicketRepository tickets, FeatureManager features,
                             org.springframework.core.env.Environment env,
                             ObjectProvider<DemoFixtures> fixtures,
                             DecisionTrace trace, DecisionTableReader tables,
                             JourneyService journeys, CatalogueService catalogue) {
        this.tickets = tickets;
        this.features = features;
        this.env = env;
        this.fixtures = fixtures;
        this.trace = trace;
        this.tables = tables;
        this.journeys = journeys;
        this.catalogue = catalogue;
    }

    /**
     * WHAT THIS CONVERSATION TOUCHED, step by step.
     *
     * The rule trace answers "why that answer". This answers the question a reviewer asks
     * next and which no diagram can: "did THIS case actually go through THOSE steps". Every
     * step is read back from the engine's own history tables — which activities executed, in
     * what order — with the steps that did NOT run listed too, because a path only means
     * something against the paths not taken.
     */
    /**
     * THE DECISION TABLES, WITHOUT RUNNING ANYTHING.
     *
     * The rule trace shows a table in the context of one conversation, which is the right
     * frame for "why did it answer that" and the wrong one for "what are the rules".
     * Somebody asking the second question should not have to invent a case first.
     *
     * These two endpoints read the .dmn files directly: every table, every row, in file
     * order, with the XML comment above each rule as its explanation. The IDE's graphical
     * modeller cannot show these files at all — they carry no DMNDI layout section, having
     * been written as XML rather than drawn — and auto-generating one would rewrite the
     * file and take the comments with it.
     */
    @GetMapping("/tables")
    public List<Map<String, Object>> tables() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConcernCatalogue concern : catalogue.all()) {
            if (!concern.isActive() || concern.getDmnKey() == null) continue;
            tables.read(concern.getDmnKey()).ifPresent(table -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dmnKey", concern.getDmnKey());
                row.put("concern", concern.getL2Code());
                row.put("label", concern.getL2Label());
                row.put("rules", table.rules().size());
                row.put("inputs", table.inputs().size());
                out.add(row);
            });
        }
        return out;
    }

    @GetMapping("/table/{dmnKey}")
    public Map<String, Object> table(@PathVariable String dmnKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dmnKey", dmnKey);
        tables.read(dmnKey).ifPresentOrElse(table -> {
            out.put("found", true);
            out.put("hitPolicy", "FIRST");
            out.put("note", "Hit policy is FIRST: the first row whose every non-empty cell "
                          + "matches produces the answer, and nothing below it is consulted. "
                          + "ROW ORDER IS LOGIC. An empty cell means the column does not "
                          + "matter for that row.");
            out.put("inputs", table.inputs());
            out.put("outputs", table.outputs());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DecisionTableReader.Rule rule : table.rules()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("n", rule.index() + 1);
                row.put("why", rule.why());
                row.put("when", rule.when());
                row.put("then", rule.then());
                rows.add(row);
            }
            out.put("rules", rows);
        }, () -> {
            out.put("found", false);
            out.put("why", "No decision table with that key. The keys are in "
                         + "concern_catalogue.dmn_key, and /console/tables lists them.");
        });
        return out;
    }

    /**
     * THE FACTS, AS THEY WERE STORED. Durable — read from the engine's history table, not
     * from the in-memory trace and not re-fetched. This is the endpoint for "show me what
     * the bot actually knew", including long after the conversation, and including when
     * the demo is running on in-memory H2 where there is no database to connect to.
     */
    @GetMapping("/facts/{sessionId}")
    public Map<String, Object> facts(@PathVariable String sessionId) {
        return journeys.storedFacts(sessionId);
    }

    @GetMapping("/journey/{sessionId}")
    public Map<String, Object> journey(@PathVariable String sessionId) {
        return journeys.forSession(sessionId);
    }

    /**
     * WHAT JUST HAPPENED, for one conversation — the endpoint this console exists for.
     *
     * Four things, in the order they occurred: the facts that were read, the table that was
     * consulted, the row that fired, and the rows that did not. That sequence is the system's
     * reasoning, and until now the only way to see it was to read the code.
     *
     * Everything here is recorded BY the decision as it was made. Nothing is re-run and nothing
     * is inferred — a second evaluation would be a second answer, and the two could differ the
     * moment a fact changed underneath them, which for a money-moving action is precisely when
     * somebody is asking.
     */
    @GetMapping("/trace/{sessionId}")
    public Map<String, Object> explain(@PathVariable String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();

        var entry = trace.forSession(sessionId).orElse(null);
        if (entry == null) {
            out.put("found", false);
            out.put("why", "No decision table was evaluated for this conversation, so there is "
                         + "nothing to explain — which is an answer, not a gap. A pure "
                         + "deflection runs a single step and has no rules to consult; so does "
                         + "a conversation that ended before a concern was chosen.");
            return out;
        }

        out.put("found", true);
        out.put("concern", entry.concern());
        out.put("dmnKey", entry.dmnKey());
        out.put("tier", entry.tier());
        out.put("action", entry.action());
        out.put("outcomeType", entry.outcomeType());

        // EVERY fact, nulls included. A null is why most rules do not fire, so hiding the
        // nulls hides the answer to the question being asked.
        List<Map<String, Object>> facts = new ArrayList<>();
        entry.facts().forEach((k, v) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", k);
            f.put("value", v == null ? null : String.valueOf(v));
            f.put("known", v != null);
            facts.add(f);
        });
        facts.sort(Comparator.comparing((Map<String, Object> f) -> (Boolean) f.get("known")).reversed());
        out.put("facts", facts);

        Map<Integer, DecisionTrace.RuleOutcome> outcomes = new HashMap<>();
        entry.rules().forEach(r -> outcomes.put(r.index(), r));

        tables.read(entry.dmnKey()).ifPresent(table -> {
            out.put("inputs", table.inputs());
            out.put("outputs", table.outputs());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var rule : table.rules()) {
                var o = outcomes.get(rule.index());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("n", rule.index() + 1);
                row.put("why", rule.why());
                row.put("when", rule.when());
                row.put("then", rule.then());
                row.put("matched", o != null && o.matched());
                row.put("fired", o != null && o.fired());
                rows.add(row);
            }
            out.put("rules", rows);
        });
        return out;
    }

    /** The page itself. */
    @GetMapping(value = "", produces = MediaType.TEXT_HTML_VALUE)
    public String page() throws Exception {
        return new String(new ClassPathResource("console/console.html").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * WHICH WORLD IS THIS? The banner reads this, and it is the most important endpoint here.
     *
     * A demo where the audience cannot tell whether a number is evidence or invention is worse
     * than no demo, because it produces confident wrong beliefs. This makes the answer a fact
     * on the screen rather than something the presenter has to remember to say.
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        boolean fixtures = this.fixtures.getIfAvailable() != null;
        boolean uat = Boolean.parseBoolean(env.getProperty("botin.uat.enabled", "false"));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("fixtures", fixtures);
        state.put("uat", uat);
        state.put("mode", fixtures ? "FIXTURES" : uat ? "LIVE" : "NO_FACTS");
        state.put("banner", fixtures
                ? "Demo profile — every fact behind these answers is SYNTHETIC. "
                + "Nothing is read from a live system."
                : uat
                ? "LIVE FACTS — the answers below are decided from the read-only replica. "
                + "Nothing is written to any system outside this service."
                : "No fact source is configured. Every concern will reach a human, correctly — "
                + "there is nothing for the tables to read.");
        return state;
    }

    /** The count, and the row. "Why did this go to a human" must be answerable on screen. */
    @GetMapping("/tickets/{spId}")
    public Map<String, Object> ticketsFor(@PathVariable String spId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", tickets.countBySpId(spId));

        Optional<Ticket> latest = tickets.findTop10BySpIdOrderByCreatedAtDesc(spId).stream().findFirst();
        Map<String, Object> row = new LinkedHashMap<>();
        latest.ifPresentOrElse(t -> {
            row.put("tier", String.valueOf(t.getTier()));
            row.put("action", String.valueOf(t.getDmnAction()));
            row.put("status", String.valueOf(t.getStatus()));
            row.put("triggerReason", String.valueOf(t.getTriggerReason()));
            row.put("csatResult", String.valueOf(t.getCsatResult()));
        }, () -> row.put("tier", "none"));
        out.put("latest", row);
        return out;
    }

    /** Read only. Flipping one is the demo controls' job, or the Togglz console's. */
    @GetMapping("/flags")
    public List<Map<String, Object>> flags() {
        return Arrays.stream(BotinFeature.values())
                .map(f -> Map.<String, Object>of(
                        "flag", f.name(),
                        "enabled", features.isActive(new NamedFeature(f.name()))))
                .toList();
    }
}
