package in.yesmadam.botin.platform.api.dto;
/**
 * The satisfaction answer.
 *
 * DELIBERATELY A BOOLEAN AND NOT A SCORE. The bounding rule and trigger A both turn on
 * one distinction — did this resolve your problem or not — and a five-point scale would
 * force someone to decide where the line sits, in the client, differently per screen.
 * A star rating is a measurement; this is a control.
 *
 * @param satisfied true if the resolution actually helped
 */
public record CsatRequest(Boolean satisfied) { }
