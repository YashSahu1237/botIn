package in.yesmadam.botin.shared.counter;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * (sp_id, counter_key, period_key) — the composite key of sp_counter.
 *
 * period_key is part of the IDENTITY rather than a field on the row. That is what
 * makes a reset free: a new month is a new key, so the old count is still there to
 * audit and the new one starts at zero without anything being deleted or zeroed.
 */
@Embeddable
public class SpCounterId implements Serializable {

    @Column(name = "sp_id", length = 64)       private String spId;
    @Column(name = "counter_key", length = 64) private String counterKey;
    @Column(name = "period_key", length = 32)  private String periodKey;

    protected SpCounterId() { }

    public SpCounterId(String spId, String counterKey, String periodKey) {
        this.spId = spId;
        this.counterKey = counterKey;
        this.periodKey = periodKey;
    }

    public String getSpId() { return spId; }
    public String getCounterKey() { return counterKey; }
    public String getPeriodKey() { return periodKey; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpCounterId that)) return false;
        return Objects.equals(spId, that.spId)
            && Objects.equals(counterKey, that.counterKey)
            && Objects.equals(periodKey, that.periodKey);
    }

    @Override public int hashCode() { return Objects.hash(spId, counterKey, periodKey); }

    @Override public String toString() { return spId + "/" + counterKey + "/" + periodKey; }
}
