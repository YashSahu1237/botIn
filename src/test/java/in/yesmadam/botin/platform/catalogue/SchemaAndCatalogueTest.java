package in.yesmadam.botin.platform.catalogue;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 checkpoint, as a test rather than a manual look at the database.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaAndCatalogueTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ConcernCatalogueRepository catalogue;
    @Autowired RepositoryService repository;
    @Autowired DmnRepositoryService dmnRepository;

    @Test
    @DisplayName("Flyway created our seven tables")
    void ourSevenTablesExist() {
        for (String t : new String[]{"concern_catalogue", "help_session", "ticket",
                                     "ticket_action", "conversation_message",
                                     "escalation_context", "sp_counter"}) {
            Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE UPPER(table_name) = UPPER(?)",
                Integer.class, t);
            assertEquals(1, found, "missing table: " + t);
        }
    }

    @Test
    @DisplayName("Flowable created its own tables alongside, in the same datasource")
    void flowableTablesExist() {
        Integer actTables = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE UPPER(table_name) LIKE 'ACT!_%' ESCAPE '!'",
            Integer.class);
        assertNotNull(actTables);
        assertTrue(actTables > 15, "expected Flowable's tables, found " + actTables);
    }

    @Test
    @DisplayName("the catalogue is readable and only active concerns are offered")
    void catalogueFiltersInactiveConcerns() {
        long total  = catalogue.count();
        long active = catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().size();

        assertTrue(total >= active, "active is a subset of total");
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc()
                 .forEach(c -> assertTrue(c.isActive(), "inactive concern leaked into the menu"));
    }

    @Test
    @DisplayName("every concern carries the outcome vocabulary the mapping actually uses")
    void outcomeTypesAreBackfilledForEveryConcern() {
        var all = catalogue.findAll();
        assertEquals(38, all.size(), "the full taxonomy is seeded, not only the built ones");

        var allowed = java.util.Set.of("BOT", "TICKET", "UPHOLD", "REROUTE",
                                       "SELF-SERVE", "V2", "DEPRECATED", "MIXED");
        all.forEach(c -> {
            assertNotNull(c.getOutcomeTypes(), "no outcome types on " + c.getL2Code());
            for (String t : c.getOutcomeTypes().split(",")) {
                assertTrue(allowed.contains(t),
                        "unknown outcome type '" + t + "' on " + c.getL2Code());
            }
        });
    }

    @Test
    @DisplayName("an active concern always has the artifacts its pointers claim")
    void activePointersAreNotDangling() {
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            // A row is inert until its artifacts exist. Activating one without a
            // process key is the exact mistake this catches — it would route a live
            // partner into nothing at all.
            assertNotNull(c.getProcessKey(), "active concern with no process: " + c.getL2Code());
            assertNotNull(c.getDefaultTier(), "active concern with no tier: " + c.getL2Code());
        });
    }

    @Test
    @DisplayName("THE POINTER RESOLVES — every process_key names a process that is deployed")
    void everyProcessKeyPointsAtSomethingThatExists() {
        // A NON-NULL POINTER IS NOT A WORKING ONE, and this project keeps meeting that
        // distinction. Five concerns sat ACTIVE for two phases naming BPMN files that
        // had never been written. Nothing failed: Flowable was never asked for them,
        // the catalogue looked complete, the tests above passed, and a partner
        // selecting one of those concerns met "we cannot help with that here".
        //
        // Same shape as the Togglz hole, the fact-name drift and the DMN namespace —
        // a name that does not exist behaving exactly like a value that is false. The
        // only reliable answer is to ask the engine whether the thing is really there.
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c ->
                assertEquals(1, repository.createProcessDefinitionQuery()
                                .processDefinitionKey(c.getProcessKey()).latestVersion().count(),
                        "concern " + c.getL2Code() + " is active and points at process '"
                        + c.getProcessKey() + "', which is not deployed. The partner would "
                        + "reach CONCERN_NOT_AVAILABLE and nothing would report it."));
    }

    @Test
    @DisplayName("and every dmn_key names a decision table that is deployed")
    void everyDmnKeyPointsAtSomethingThatExists() {
        catalogue.findByActiveTrueOrderByL1CodeAscDisplayOrderAsc().forEach(c -> {
            if (c.getDmnKey() == null) return;      // decides nothing — see DecisionTableTest
            assertEquals(1, dmnRepository.createDecisionQuery()
                            .decisionKey(c.getDmnKey()).latestVersion().count(),
                    "concern " + c.getL2Code() + " points at decision table '"
                    + c.getDmnKey() + "', which is not deployed");
        });
    }
}
