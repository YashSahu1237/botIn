package in.yesmadam.botin.platform.api.dto;
/**
 * One L1 path, derived from the catalogue rather than declared anywhere.
 *
 * @param activeConcerns how many L2 concerns under it are switched on. A group with
 *                       zero is still returned — hiding it would make the menu
 *                       change shape as concerns are enabled, and a partner who
 *                       learned where "Violations" sits should keep finding it there.
 */
public record L1Group(String code, String label, int activeConcerns) { }
