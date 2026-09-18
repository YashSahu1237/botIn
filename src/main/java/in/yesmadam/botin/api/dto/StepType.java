package in.yesmadam.botin.api.dto;

/**
 * The five shapes a client can render. The backend owns 100% of the flow logic;
 * the client is a thin renderer that knows how to draw these five things and
 * nothing else.
 *
 * Adding a sixth type is a client release. Adding a new concern is not — that is
 * the whole point of keeping this list short and closed.
 */
public enum StepType {

    /** A list of choices, rendered inline. Used for L1 and L2 selection. */
    MENU,

    /** A list of choices too long to render inline — bookings, orders, violations. */
    DROPDOWN,

    /** Free text entry. */
    TEXT,

    /** Terminal text, with no input expected. A resolution, a deflection, a refusal. */
    MESSAGE,

    /** The satisfaction prompt. Rendered after every T0, T1 and T2 resolution. */
    CSAT
}
