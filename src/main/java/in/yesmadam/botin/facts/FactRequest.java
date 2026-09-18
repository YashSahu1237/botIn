package in.yesmadam.botin.facts;

import java.util.UUID;

/** Everything a provider is allowed to know. Deliberately small. */
public record FactRequest(
        UUID ticketId,
        String spId,
        String l2Concern,
        String selectedReference,   // order id, fine id, violation id — per concern
        String freeText
) { }
