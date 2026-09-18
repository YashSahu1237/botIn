package in.yesmadam.botin.facts.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 43, REWRITTEN AGAINST THE DECISION MATRIX.
 *
 * The matrix states the rule exactly: "Express: same day if before 3 PM, next day if after.
 * Standard: ~3 days. Deadline 11 PM."
 *
 * TWO EARLIER READINGS WERE WRONG, AND BOTH ERRED THE SAME WAY. Express was a flat one day,
 * and 11 PM was read as an ORDERING cut-off that pushed the clock to the next day rather
 * than as the hour an order becomes late. Both made orders look MORE on time than they were
 * — the forgiving direction, so nothing would have looked broken. It would simply have
 * deflected complaints that deserved a ticket, quietly.
 *
 * No Spring, no database.
 */
class DeliveryTatServiceTest {

    // Standard 3 days · Express boundary 15:00 · deadline 23:00 — the configured defaults.
    private final DeliveryTatService tat = new DeliveryTatService(3, 15, 23);

    private static ZonedDateTime at(int day, int hour) {
        return ZonedDateTime.of(LocalDate.of(2026, 9, day), LocalTime.of(hour, 0),
                DeliveryTatService.ZONE);
    }

    @Test
    @DisplayName("EXPRESS before 3 PM is due the SAME DAY")
    void expressBeforeTheBoundaryIsSameDay() {
        // 1 Sep at 10:00 -> due 1 Sep at 23:00. The old code said 2 Sep, and would have
        // told a partner to keep waiting on the day their delivery was already late.
        ZonedDateTime deadline = tat.deadline(at(1, 10), true);
        assertEquals(LocalDate.of(2026, 9, 1), deadline.toLocalDate());
        assertEquals(23, deadline.getHour());
    }

    @Test
    @DisplayName("EXPRESS at or after 3 PM is due the NEXT day")
    void expressAfterTheBoundaryIsNextDay() {
        assertEquals(LocalDate.of(2026, 9, 2), tat.deadline(at(1, 15), true).toLocalDate(),
                "3 PM exactly is already 'after' — the boundary belongs to the next day");
        assertEquals(LocalDate.of(2026, 9, 2), tat.deadline(at(1, 20), true).toLocalDate());
    }

    @Test
    @DisplayName("STANDARD is three days from the day it was placed, whatever the hour")
    void standardIgnoresTheHourEntirely() {
        // The hour used to matter here, because 11 PM was read as an ordering cut-off.
        // It is not one: it is the hour the order becomes late on its due date.
        assertEquals(LocalDate.of(2026, 9, 4), tat.deadline(at(1, 10), false).toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 4), tat.deadline(at(1, 23), false).toLocalDate(),
                "placed at 11 PM is still placed on the 1st");
        assertEquals(LocalDate.of(2026, 9, 4), tat.deadline(at(1, 23).plusMinutes(59), false).toLocalDate());
    }

    @Test
    @DisplayName("on time right up to the deadline, late one second after")
    void theBoundaryIsExact() {
        ZonedDateTime placed = at(1, 10);
        ZonedDateTime deadline = tat.deadline(placed, false);

        assertFalse(tat.isPastTat(placed, false, deadline), "AT the deadline is still on time");
        assertFalse(tat.isPastTat(placed, false, deadline.minusMinutes(1)));
        assertTrue(tat.isPastTat(placed, false, deadline.plusSeconds(1)));
    }

    @Test
    @DisplayName("BOTH concerns get the same answer from the same call")
    void oneDefinitionServesBothConcerns() {
        // PROD_DELIVERY_DELAY asks "where is my order"; VIOL_R9_NO_PRODUCT asks "should
        // they have had it". Same inputs, same call, so they cannot disagree and tell
        // one partner two different things.
        ZonedDateTime placed = at(1, 10);
        ZonedDateTime now = at(6, 12);

        Boolean forDeliveryDelay = tat.isPastTat(placed, false, now);
        Boolean forViolationR9   = tat.isPastTat(placed, false, now);

        assertEquals(forDeliveryDelay, forViolationR9);
        assertTrue(forDeliveryDelay);
    }

    @Test
    @DisplayName("an undated order returns NULL, which is not the same as on time")
    void unknownDateIsNull() {
        // False means "on time, tell them to wait". Saying that about an order we cannot
        // date is a guess dressed as an answer. Null reaches the catch-all, then a human.
        assertNull(tat.isPastTat(null, false, at(1, 10)));
        assertNull(tat.isPastTat(at(1, 10), false, null));
        assertNull(tat.deadline(null, false));
        assertNull(tat.expectedDeliveryDate(null, false));
    }

    @Test
    @DisplayName("the timezone is stated, not inherited from whatever the server is set to")
    void timezoneIsExplicit() {
        assertEquals(ZoneId.of("Asia/Kolkata"), DeliveryTatService.ZONE);

        // Same instant, expressed in UTC. The answer must not change.
        ZonedDateTime sameInstantUtc = at(1, 10).withZoneSameInstant(ZoneId.of("UTC"));
        assertEquals(tat.deadline(at(1, 10), false), tat.deadline(sameInstantUtc, false));
    }
}
