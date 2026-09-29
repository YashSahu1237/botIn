package in.yesmadam.botin.platform.session;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface HelpSessionRepository extends JpaRepository<HelpSession, UUID> { }
