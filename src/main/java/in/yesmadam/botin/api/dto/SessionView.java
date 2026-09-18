package in.yesmadam.botin.api.dto;

import java.util.UUID;

/**
 * The whole client-visible state of a help session. Returned by every session
 * endpoint, so reconnecting and acting return the same shape — a client that can
 * render a response can render a reconnect, with no second code path.
 *
 * @param csatExpected the resolution is done and nobody has rated it. The client asks
 *                     the satisfaction question when it makes sense to — immediately
 *                     after an answer, or after the partner has actually used a deeplink
 *                     — and posts it to /csat. Nothing is held open waiting for it.
 * @param agentOffered whether "Talk to an Agent" may be shown. THE BOUNDING RULE LIVES
 *                     ON THIS FIELD: on a case the partner confirmed as resolved it is
 *                     false and the client has nothing to render. It is decided in
 *                     closure logic rather than in the UI, because a suppression rule
 *                     the client owns is one an old app version can ignore.
 */
public record SessionView(
        UUID sessionId,
        String spId,
        String status,
        String currentStep,
        String l1Concern,
        String l2Concern,
        NextStep nextStep,
        boolean csatExpected,
        boolean agentOffered) { }
