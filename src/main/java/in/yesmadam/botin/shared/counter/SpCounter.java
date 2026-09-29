package in.yesmadam.botin.shared.counter;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A durable per-partner tally with an explicit reset boundary.
 *
 * NOT in the original design. The concern mapping forces it: the pooled emergency cap,
 * the monthly period-leave count and the per-cycle allowances are STATE THIS ENGINE
 * OWNS, not facts it can read from anywhere else.
 */
@Entity
@Table(name = "sp_counter")
public class SpCounter {

    @EmbeddedId private SpCounterId id;

    @Column(name = "count_value", nullable = false) private int countValue;
    @Column(name = "last_granted_at")               private Instant lastGrantedAt;

    protected SpCounter() { }

    public SpCounter(SpCounterId id) {
        this.id = id;
        this.countValue = 0;
    }

    /** One grant. Recording WHEN matters as much as how many, for disputes. */
    public void grant() {
        this.countValue++;
        this.lastGrantedAt = Instant.now();
    }

    public SpCounterId getId() { return id; }
    public int getCountValue() { return countValue; }
    public Instant getLastGrantedAt() { return lastGrantedAt; }
}
