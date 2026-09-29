package in.yesmadam.botin.platform.escalation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EscalationContextRepository extends JpaRepository<EscalationContext, UUID> { }
