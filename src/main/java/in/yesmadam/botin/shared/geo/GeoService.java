package in.yesmadam.botin.shared.geo;
import org.springframework.stereotype.Service;

/**
 * PLAN STEP 42. Distance, and distance BEYOND THE HUB RADIUS.
 *
 * Two things here are deliberate rather than incidental.
 *
 * 1. THE FORMULA IS COPIED FROM THE LIVE SYSTEM, CONSTANT INCLUDED.
 *    empapi's Utilities.calculateDistance uses EARTH_RADIUS = 6371000 and returns
 *    metres as an int. BOTIn uses the same, so the two systems can never disagree
 *    about how far apart two points are. A "better" formula here would be a defect:
 *    a partner told one distance by the app and paid for another.
 *
 * 2. DISTANCE IS MEASURED FROM THE RADIUS EDGE, NOT THE HUB CENTRE.
 *    The concern mapping is explicit: travel is reimbursed for the part OUTSIDE the
 *    hub's reach. A job 7 km from a hub with a 5 km radius is 2 km of reimbursable
 *    travel, not 7. Measuring from the centre would overpay every single claim.
 */
@Service
public class GeoService {

    /** Metres. The same constant the live system uses. */
    static final int EARTH_RADIUS_METRES = 6_371_000;

    /**
     * Great-circle distance in METRES, truncated to a whole metre exactly as the live
     * system truncates it.
     */
    public int distanceMetres(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) (EARTH_RADIUS_METRES * c);
    }

    /**
     * Kilometres of travel BEYOND the hub's radius.
     *
     * Zero when the job sits inside the radius — never negative, because a job well
     * inside a hub must not net off against anything.
     *
     * @return null when the hub geometry is unknown. Null is NOT zero: zero means
     *         "inside the hub, deny", and returning that for a hub whose coordinates
     *         are missing would deny a claim we never actually assessed.
     */
    public Double kmBeyondRadius(HubGeometry hub, Double jobLat, Double jobLng) {
        if (hub == null || jobLat == null || jobLng == null) return null;

        double straightLineKm =
                distanceMetres(hub.latitude(), hub.longitude(), jobLat, jobLng) / 1000.0;

        double beyond = straightLineKm - hub.radiusKm();
        return beyond > 0 ? beyond : 0.0;
    }
}
