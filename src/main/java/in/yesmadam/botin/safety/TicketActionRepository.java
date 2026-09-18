package in.yesmadam.botin.safety;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketActionRepository extends JpaRepository<TicketAction, UUID> {
    Optional<TicketAction> findByIdempotencyKey(String idempotencyKey);
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * What has already been attempted for this partner, across their recent tickets.
     *
     * Goes into the escalation context. The most expensive mistake an agent can make is
     * paying someone a second time, and the attempt log is the only record that stops
     * it — so it belongs in front of them, not one lookup away.
     */
    List<TicketAction> findByTicketIdIn(Collection<UUID> ticketIds);

    /** Everything we believe moved. Reconciliation's left-hand side. */
    List<TicketAction> findByStatus(String status);
}
