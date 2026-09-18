package in.yesmadam.botin.facts;

import in.yesmadam.botin.config.ConditionalOnUatEnabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Checks, at startup, that every column the fact providers name actually exists.
 *
 * THE FAILURE THIS PREVENTS. A fact provider that queries a column which does not exist
 * throws at request time — for one partner, in production, days after deploy. Worse is
 * the near-miss: a column that exists but is not the one meant, which returns rows and
 * answers wrongly. Neither is visible at deploy time unless something looks.
 *
 * This is the same treatment FeatureNameValidator gives Togglz flags, for the same
 * reason: a name that does not resolve behaves like a value that is false, and BOTIn has
 * now been bitten by that four separate ways.
 *
 * It does NOT fail the boot. UAT is a read-only dependency and a partner is better served
 * by a running service that escalates than by no service at all — so a missing column is
 * reported as a loud ERROR and the affected concern degrades to a human. A missing column
 * on a MONEY path is the exception and does fail the boot.
 */
@Component
@ConditionalOnUatEnabled
public class UatSchemaProbe {

    private static final Logger log = LoggerFactory.getLogger(UatSchemaProbe.class);

    private final JdbcTemplate uat;
    private final List<ConcernFactProvider> providers;

    public UatSchemaProbe(@Qualifier("uatJdbcTemplate") JdbcTemplate uat,
                          List<ConcernFactProvider> providers) {
        this.uat = uat;
        this.providers = providers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void probe() {
        List<String> missing = new ArrayList<>();
        List<String> unconfirmedButPresent = new ArrayList<>();
        int checked = 0;

        for (ConcernFactProvider provider : providers) {
            for (UatColumn col : provider.requiredColumns()) {
                checked++;
                if (exists(col)) {
                    if (!col.confirmed()) unconfirmedButPresent.add(col.qualified());
                } else {
                    missing.add(provider.concernCode() + " -> " + col.qualified());
                }
            }
        }

        if (!unconfirmedButPresent.isEmpty()) {
            log.info("UAT schema probe — {} inferred column names CONFIRMED PRESENT: {}",
                    unconfirmedButPresent.size(), unconfirmedButPresent);
        }

        if (missing.isEmpty()) {
            log.info("UAT schema probe passed — all {} columns exist", checked);
            return;
        }

        log.error("""
                =====================================================================
                UAT SCHEMA PROBE FAILED — {} of {} columns do not exist.
                The concerns below WILL NOT DECIDE CORRECTLY and should be switched
                off in concern_catalogue until their queries are fixed:
                {}
                =====================================================================""",
                missing.size(), checked, String.join("\n  ", missing));
    }

    private boolean exists(UatColumn col) {
        try {
            Integer n = uat.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
                    Integer.class, col.catalog(), col.table(), col.column());
            return n != null && n > 0;
        } catch (Exception e) {
            log.error("UAT schema probe could not check {} — {}", col.qualified(), e.toString());
            return false;
        }
    }
}
