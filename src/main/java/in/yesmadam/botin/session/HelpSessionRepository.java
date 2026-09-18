package in.yesmadam.botin.session;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface HelpSessionRepository extends JpaRepository<HelpSession, UUID> { }
