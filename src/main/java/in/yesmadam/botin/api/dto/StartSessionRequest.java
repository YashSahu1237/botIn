package in.yesmadam.botin.api.dto;

/**
 * @param spId     the Service Partner. Required.
 * @param freeText optional. What the partner typed before picking anything.
 *                 Phase 2 stores it and still shows the L1 menu; Phase 11 sends it
 *                 to /classify-intent and skips straight to the concern.
 */
public record StartSessionRequest(String spId, String freeText) { }
