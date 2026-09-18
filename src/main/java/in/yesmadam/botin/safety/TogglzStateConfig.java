package in.yesmadam.botin.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.togglz.core.repository.StateRepository;
import org.togglz.core.repository.jdbc.JDBCStateRepository;

import javax.sql.DataSource;

/**
 * WHERE A KILL SWITCH'S POSITION IS KEPT — plan step 91.
 *
 * =========================================================================
 * WHY THIS MATTERS MORE THAN IT LOOKS
 * =========================================================================
 *
 * Until now the flags lived in an in-memory repository, which means a restart put every
 * switch back to its default. Think about what that is: somebody turns OFF automatic
 * wallet credits at 2am because the gateway is double-paying, and the next deploy —
 * routine, unrelated, by somebody who never heard about the incident — turns it back on.
 * The switch that exists to stop money moving is undone by a process whose whole purpose
 * is to be uneventful.
 *
 * A flag's position is an OPERATIONAL DECISION, so it belongs in the database beside
 * every other operational fact, not in the memory of one process.
 *
 * =========================================================================
 * THE TABLE IS TOGGLZ'S, NOT OURS, AND FLYWAY DOES NOT OWN IT
 * =========================================================================
 *
 * `Flyway owns the schema` is a standing rule here, and this looks like an exception. It
 * is not: the rule is about OUR tables. `JDBCStateRepository` creates and migrates its own
 * `TOGGLZ` table, exactly as Flowable creates and migrates its ~25 `ACT_*` tables, and for
 * the same reason — a library's private storage is the library's business, and hand-writing
 * a migration to match a schema we do not control means guessing at column widths that the
 * next library version may change underneath us.
 *
 * The line that matters is between a library's storage and our domain. `ticket`,
 * `help_session` and `ticket_action` are ours and Flyway owns every one of them.
 *
 * =========================================================================
 * NO CACHE, AND THAT IS DELIBERATE
 * =========================================================================
 *
 * Togglz ships `CachingStateRepository` and the temptation is obvious — a flag is read on
 * every automated decision. It is refused here because of what the step 92 checkpoint
 * actually asserts: flip the switch, and the NEXT request stops automating. With a cache
 * the next request keeps paying until a TTL expires, which is precisely the window an
 * operator is trying to close. A kill switch with a delay is not a kill switch, and one
 * indexed read on a table with four rows is not the bottleneck worth trading it for.
 *
 * =========================================================================
 * THE PRIMARY DATASOURCE, NAMED EXPLICITLY
 * =========================================================================
 *
 * `@Qualifier("dataSource")` rather than injection by type. There are two datasources in
 * this service and the other one is UAT — read-only, someone else's, and the place a
 * SELECT-only GRANT would turn this into a startup failure at best. Type-based injection
 * happens to pick the right one today because UAT is not `@Primary`; naming it means it
 * cannot start picking the wrong one because of a change somewhere else.
 */
@Configuration
public class TogglzStateConfig {

    private static final Logger log = LoggerFactory.getLogger(TogglzStateConfig.class);

    /**
     * `@Primary` because the starter contributes an in-memory repository of its own.
     * Stating which one wins beats relying on the order two conditions are evaluated in —
     * and the consequence of getting it wrong is silent: flags that appear to work, and
     * quietly forget everything on restart.
     */
    @Bean
    @Primary
    public StateRepository togglzStateRepository(@Qualifier("dataSource") DataSource dataSource) {
        log.info("togglz state: JDBC, primary datasource, no cache — a flip survives a restart "
               + "and takes effect on the next request");
        return new JDBCStateRepository(dataSource);
    }
}
