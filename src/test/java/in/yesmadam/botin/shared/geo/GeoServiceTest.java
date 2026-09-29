package in.yesmadam.botin.shared.geo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN STEP 42. Arithmetic, so no Spring context — these run instantly.
 */
class GeoServiceTest {

    private final GeoService geo = new GeoService();

    // Two points about 1.11 km apart: 0.01 degrees of latitude at this longitude.
    private static final double DELHI_LAT = 28.6139, DELHI_LNG = 77.2090;

    @Test
    @DisplayName("the formula matches the live system's, constant included")
    void matchesTheLiveSystemsDistance() {
        // empapi: EARTH_RADIUS = 6371000, returns metres as an int. 0.01 degrees of
        // latitude is ~1112 m anywhere on Earth. If this number ever moves, BOTIn and
        // the app are telling a partner two different distances.
        int metres = geo.distanceMetres(DELHI_LAT, DELHI_LNG, DELHI_LAT + 0.01, DELHI_LNG);
        assertTrue(metres > 1100 && metres < 1120, "expected ~1112 m, got " + metres);
    }

    @Test
    @DisplayName("the same point is zero away from itself")
    void samePointIsZero() {
        assertEquals(0, geo.distanceMetres(DELHI_LAT, DELHI_LNG, DELHI_LAT, DELHI_LNG));
    }

    @Test
    @DisplayName("distance is measured from the RADIUS EDGE, not the hub centre")
    void measuredFromTheEdge() {
        // THE RULE THIS EXISTS FOR. A job ~5.56 km from a hub with a 5 km radius is
        // roughly 0.56 km of reimbursable travel. Measuring from the centre would pay
        // for 5.56 km — ten times too much, on every single claim.
        HubGeometry hub = new HubGeometry(DELHI_LAT, DELHI_LNG, 5.0);
        Double beyond = geo.kmBeyondRadius(hub, DELHI_LAT + 0.05, DELHI_LNG);

        assertNotNull(beyond);
        assertTrue(beyond > 0.4 && beyond < 0.7,
                "expected ~0.56 km beyond a 5 km radius, got " + beyond);
    }

    @Test
    @DisplayName("a job inside the radius is zero, never negative")
    void insideTheRadiusIsZero() {
        HubGeometry hub = new HubGeometry(DELHI_LAT, DELHI_LNG, 10.0);
        assertEquals(0.0, geo.kmBeyondRadius(hub, DELHI_LAT + 0.01, DELHI_LNG));
    }

    @Test
    @DisplayName("unknown geometry returns NULL, which is not the same as zero")
    void unknownIsNullNotZero() {
        // Zero means "inside the hub, deny". Returning it for a hub whose coordinates
        // are missing would deny a claim nobody ever assessed. Null sends it to the
        // catch-all, and from there to a human.
        assertNull(geo.kmBeyondRadius(null, DELHI_LAT, DELHI_LNG));
        assertNull(geo.kmBeyondRadius(new HubGeometry(DELHI_LAT, DELHI_LNG, 5.0), null, null));
    }

    @Test
    @DisplayName("hub coordinates are STRINGS in the database and may not parse")
    void coordinatesAreParsedDefensively() {
        assertNotNull(HubGeometry.of("28.6139", "77.2090", 5.0));
        assertNotNull(HubGeometry.of("  28.6139 ", " 77.2090 ", 5.0), "whitespace is survivable");
        assertNull(HubGeometry.of("", "77.2090", 5.0));
        assertNull(HubGeometry.of("not a number", "77.2090", 5.0));
        assertNull(HubGeometry.of(null, null, 5.0));
    }

    @Test
    @DisplayName("the radius is MAX(end) of the hub's transport slabs, as the live code derives it")
    void radiusIsTheFurthestSlabEdge() {
        assertEquals(12.0, HubGeometry.radiusFromSlabEnds(List.of(3.0, 7.0, 12.0)));
        assertEquals(12.0, HubGeometry.radiusFromSlabEnds(List.of(12.0, 3.0)), "order must not matter");
    }

    @Test
    @DisplayName("a hub with no slabs has an UNKNOWN reach, not a reach of zero")
    void noSlabsMeansUnknown() {
        // A radius of zero would make every job "outside the hub" and pay for all of it.
        assertNull(HubGeometry.radiusFromSlabEnds(List.of()));
        assertNull(HubGeometry.radiusFromSlabEnds(null));
    }
}
