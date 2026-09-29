package in.yesmadam.botin.shared.counter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpCounterRepository extends JpaRepository<SpCounter, SpCounterId> { }
