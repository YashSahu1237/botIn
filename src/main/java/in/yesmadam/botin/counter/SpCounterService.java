package in.yesmadam.botin.counter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * PLAN STEP 45. Counters with EXPLICIT reset boundaries.
 *
 * THE RESET IS THE WHOLE DESIGN. A counter that is "reset monthly" by a job that zeroes
 * it is a counter that is wrong whenever the job did not run, and untraceable
 * afterwards because the old value is gone. Here the period is part of the key:
 *
 *     SP-1001 / PERIOD_LEAVE / 2026-09     <- September's count
 *     SP-1001 / PERIOD_LEAVE / 2026-10     <- October starts at zero by existing
 *
 * Nothing resets. Nothing is deleted. Last month is still there to answer a dispute,
 * and there is no job that can fail.
 *
 * WHY THIS ENGINE OWNS THESE AT ALL. The live system has no period-leave marker:
 * leaveTypeCode is FULL_DAY / FIRST_HALF / SECOND_HALF, which is duration, and the
 * reason is free text a partner typed. So "first period leave this month" cannot be
 * read from anywhere — BOTIn has to count its own grants. See docs/DEFERRED.md D-A.
 */
@Service
public class SpCounterService {

    private static final Logger log = LoggerFactory.getLogger(SpCounterService.class);

    /** One period leave per calendar month. */
    public static final String PERIOD_LEAVE = "PERIOD_LEAVE";
    /** The pooled emergency allowance. */
    public static final String POOLED_EMERGENCY = "POOLED_EMERGENCY";
    /** One violation removal per 25-job cycle. */
    public static final String CYCLE_REMOVAL = "CYCLE_REMOVAL";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SpCounterRepository counters;

    public SpCounterService(SpCounterRepository counters) {
        this.counters = counters;
    }

    // ------------------------------------------------------------ period keys

    /** "2026-09". The boundary is the calendar month, not a rolling 30 days. */
    public String monthKey(LocalDate on) {
        return MONTH.format(on);
    }

    /**
     * "cycle:41". The 25-job cycle is NOT ours — it is a row in
     * ysmdm_admin.tbl_sp_job_completion_cycle, with its own start_date, end_date and
     * order_count. We key off its id so our count and the live cycle can never drift
     * apart; when a new cycle row appears, our allowance starts fresh on its own.
     */
    public String cycleKey(long spJobCompletionCycleId) {
        return "cycle:" + spJobCompletionCycleId;
    }

    // --------------------------------------------------------------- reading

    /**
     * How many grants so far in this period. A missing row is zero — a partner who has
     * never taken one and a partner whose row was never created are the same partner.
     */
    @Transactional(readOnly = true)
    public int count(String spId, String counterKey, String periodKey) {
        return counters.findById(new SpCounterId(spId, counterKey, periodKey))
                .map(SpCounter::getCountValue)
                .orElse(0);
    }

    /** True when another grant would stay within the cap. */
    @Transactional(readOnly = true)
    public boolean withinCap(String spId, String counterKey, String periodKey, int cap) {
        return count(spId, counterKey, periodKey) < cap;
    }

    // --------------------------------------------------------------- writing

    /**
     * Record a grant and return the new total.
     *
     * REQUIRES_NEW, for the same reason TicketActionRecorder uses it. A grant that is
     * given to the partner and then rolled back because something later in the flow
     * failed is an allowance spent with no record — the partner keeps the benefit and
     * the counter says they never took it. The tally survives the caller's rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int grant(String spId, String counterKey, String periodKey) {
        SpCounterId id = new SpCounterId(spId, counterKey, periodKey);
        SpCounter counter = counters.findById(id).orElseGet(() -> new SpCounter(id));
        counter.grant();
        counters.saveAndFlush(counter);

        log.info("granted {} for {} in {} — now {}", counterKey, spId, periodKey,
                counter.getCountValue());
        return counter.getCountValue();
    }

    /**
     * Check and grant in one step, so two concurrent requests cannot both see "0 used"
     * and both be granted.
     *
     * @return true if the grant was made, false if the cap was already reached
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean grantIfWithinCap(String spId, String counterKey, String periodKey, int cap) {
        SpCounterId id = new SpCounterId(spId, counterKey, periodKey);
        SpCounter counter = counters.findById(id).orElseGet(() -> new SpCounter(id));

        if (counter.getCountValue() >= cap) {
            log.info("cap reached: {} for {} in {} is {} of {}", counterKey, spId, periodKey,
                    counter.getCountValue(), cap);
            return false;
        }
        counter.grant();
        counters.saveAndFlush(counter);
        return true;
    }
}
