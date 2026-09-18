package in.yesmadam.botin.counter;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpCounterRepository extends JpaRepository<SpCounter, SpCounterId> { }
