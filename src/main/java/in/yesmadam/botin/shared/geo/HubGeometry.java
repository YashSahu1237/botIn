package in.yesmadam.botin.shared.geo;
import java.util.List;

/**
 * A hub's position and reach, as the live system actually stores them.
 *
 * NEITHER FIELD IS A COLUMN YOU CAN SELECT DIRECTLY.
 *
 *   centre  - tbl_hub.lat / tbl_hub.lng, embedded from GeoCode and stored as STRINGS.
 *   radius  - NOT stored at all. It is MAX(end) across that hub's rows in
 *             tbl_servicehub_transportation, which is the same table that prices
 *             transport in distance bands. JobController derives it exactly this way,
 *             so BOTIn deriving it differently would be two systems disagreeing about
 *             where a hub ends.
 *
 * @param radiusKm the outer edge of the furthest transport slab
 */
public record HubGeometry(double latitude, double longitude, double radiusKm) {

    /** Parses the string coordinates as stored. Returns null if either is unusable. */
    public static HubGeometry of(String lat, String lng, double radiusKm) {
        Double a = parse(lat);
        Double b = parse(lng);
        if (a == null || b == null) return null;
        return new HubGeometry(a, b, radiusKm);
    }

    /**
     * The radius, derived the way the live system derives it: the largest `end` of the
     * hub's transport slabs. An empty slab list means the hub has no defined reach, and
     * that is NOT the same as a reach of zero — the caller must treat it as unknown.
     */
    public static Double radiusFromSlabEnds(List<Double> slabEndsKm) {
        if (slabEndsKm == null || slabEndsKm.isEmpty()) return null;
        return slabEndsKm.stream().filter(java.util.Objects::nonNull)
                .max(Double::compare).orElse(null);
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
