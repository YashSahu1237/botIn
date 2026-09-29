package in.yesmadam.botin.platform.safety;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.repository.FeatureState;
import org.togglz.core.repository.StateRepository;
import org.togglz.core.repository.jdbc.JDBCStateRepository;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 91 — a kill switch's position belongs in the database.
 *
 * THE THING BEING PROVEN IS A NEGATIVE, so it is worth stating plainly: after this step,
 * a restart cannot put a switch back. Somebody turns off automatic wallet credits at 2am
 * because the gateway is double-paying; the next routine deploy — unrelated, by somebody
 * who never heard about the incident — must not turn them back on.
 *
 * A test cannot restart the application, so it does the next most honest thing: it writes
 * through the application's repository and reads back through a SEPARATE repository object
 * built on the same datasource. A value that survives that journey lives in the database
 * and nowhere else, which is the whole claim.
 *
 * THIS TEST DELIBERATELY USES A FLAG NOBODY ELSE TOUCHES. Feature state is now durable and
 * global, so it is shared fixture in exactly the sense that burned MoneyPathTest — a test
 * that flips RECHARGE_AUTO_CREDIT here would change what a test in another class sees.
 */
@SpringBootTest
@ActiveProfiles("test")
class TogglzStateTest {

    /** Not used by any other test, and not wired into a process yet. */
    private static final BotinFeature MINE = BotinFeature.VIOL_R5_AUTO_REMOVE;

    /** Never written to by anything, which is the point of the last test here. */
    private static final BotinFeature UNTOUCHED = BotinFeature.VIOL_R9_AUTO_REMOVE;

    @Autowired StateRepository repository;
    @Autowired FeatureManager features;
    @Autowired @Qualifier("dataSource") DataSource primary;

    @AfterEach
    void leaveItOff() {
        repository.setFeatureState(new FeatureState(MINE, false));
    }

    @Test
    @DisplayName("The state repository is the JDBC one, not the in-memory default")
    void stateIsHeldInTheDatabase() {
        assertInstanceOf(JDBCStateRepository.class, repository,
                "an in-memory repository means every restart silently re-enables whatever "
              + "an operator turned off");
    }

    @Test
    @DisplayName("A flip written by the app is readable by a DIFFERENT repository — it is on disk")
    void aFlipOutlivesTheObjectThatMadeIt() {
        repository.setFeatureState(new FeatureState(MINE, true));

        // A second repository over the same datasource — as close to "after a restart" as a
        // test can get without one. It shares no memory with the bean above.
        StateRepository asIfRestarted = new JDBCStateRepository(primary);

        FeatureState readBack = asIfRestarted.getFeatureState(MINE);
        assertNotNull(readBack, "nothing was persisted at all");
        assertTrue(readBack.isEnabled(), "the switch came back in the wrong position");

        // And the other direction, because a kill switch that only survives being turned ON
        // is the useless half of the guarantee.
        repository.setFeatureState(new FeatureState(MINE, false));
        assertFalse(new JDBCStateRepository(primary).getFeatureState(MINE).isEnabled(),
                "OFF is the position that matters — it is the one somebody set during an incident");
    }

    @Test
    @DisplayName("The state lives in a real table, in OUR database")
    void thereIsATableAndItIsInThePrimaryDatabase() {
        repository.setFeatureState(new FeatureState(MINE, true));

        JdbcTemplate jdbc = new JdbcTemplate(primary);

        // ASK THE DATABASE WHAT THE TABLE IS CALLED rather than asserting a name out of
        // Togglz's internals. The claim under test is "the state is in a table in our
        // database", not "that table is spelled TOGGLZ" — and a library is entitled to
        // rename its own storage in a version we have not read yet.
        List<String> stateTables = jdbc.queryForList(
                "select table_name from information_schema.tables "
              + "where upper(table_name) like '%TOGGLZ%'", String.class);

        assertEquals(1, stateTables.size(),
                "expected exactly one Togglz state table in the primary database, found "
              + stateTables + ". Togglz creates and migrates its own, exactly as Flowable "
              + "does with ACT_*. Flyway owns OUR tables; a library owns its own");

        Integer rows = jdbc.queryForObject(
                "select count(*) from " + stateTables.get(0) + " where FEATURE_NAME = ?",
                Integer.class, MINE.name());
        assertEquals(1, rows, "the flip did not reach the table");
    }

    @Test
    @DisplayName("A feature nobody has configured reads as OFF — a fresh database automates nothing")
    void neverConfiguredMeansDoNotAutomate() {
        assertNull(repository.getFeatureState(UNTOUCHED), "precondition: nothing has written this row");
        assertFalse(features.isActive(UNTOUCHED),
                "no row must mean 'not switched on', never 'switched on'. A fresh database "
              + "must pay nobody until a person deliberately says otherwise");
    }
}
