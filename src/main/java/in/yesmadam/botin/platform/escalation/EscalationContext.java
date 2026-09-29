package in.yesmadam.botin.platform.escalation;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What an agent opens FIRST. Written once, at the moment a case becomes a human's.
 *
 * Computed facts, not a transcript. The transcript is a separate, lazy call behind a
 * "view full conversation" control — because an agent picking up a case needs to know
 * what we already know, not to read the conversation that produced it.
 *
 * WHY IT IS WRITTEN AT ESCALATION AND NEVER RECOMPUTED. Every field here is a snapshot
 * of what the system believed when it gave up. Recomputing at the moment the agent
 * opens it would show them a different picture from the one the decision was made on —
 * the wallet may have moved, the order may have been delivered — and then "why did the
 * bot do that" becomes unanswerable. This row is evidence, so it is immutable.
 */
@Entity
@Table(name = "escalation_context")
public class EscalationContext {

    /** One per ticket, and the ticket id IS the key. A second escalation is not a second row. */
    @Id
    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "l2_concern", nullable = false, length = 64) private String l2Concern;

    /** From the classifier. Null until Phase 11 — see the register. */
    @Column(length = 64)                              private String category;
    @Column(name = "confidence_score", precision = 4, scale = 3) private BigDecimal confidenceScore;
    @Column(name = "extracted_reason", length = 4000) private String extractedReason;

    /** What the partner actually typed, kept verbatim beside anything derived from it. */
    @Column(name = "original_free_text", length = 4000) private String originalFreeText;

    /** The partner's medal band. Not available from our own data — see the register. */
    @Column(name = "medal_band", length = 24)         private String medalBand;

    /** The exact fact map the decision table was given. */
    @Column(name = "facts_snapshot", length = 4000)   private String factsSnapshot;

    @Column(name = "recent_ticket_history", length = 4000) private String recentTicketHistory;
    @Column(name = "prior_actions", length = 4000)         private String priorActions;

    /**
     * A..E for the five defined triggers, or CONCERN.
     *
     * CONCERN is not one of the five and is deliberately not pretending to be. The five
     * triggers are CROSS-CUTTING — they belong to the session and fire regardless of
     * which concern is in play. A T3 that came out of the concern's own decision table
     * is a different thing: the rule itself said a human should handle this case. Both
     * end with an agent, and a report that merged them would overstate how often the
     * cross-cutting triggers fire.
     */
    @Column(name = "trigger_reason", nullable = false, length = 8) private String triggerReason;

    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected EscalationContext() { }

    public static EscalationContext of(UUID ticketId, String l2Concern, String triggerReason) {
        EscalationContext c = new EscalationContext();
        c.ticketId = ticketId;
        c.l2Concern = l2Concern;
        c.triggerReason = triggerReason;
        c.createdAt = Instant.now();
        return c;
    }

    public EscalationContext withFacts(String factsSnapshot) {
        this.factsSnapshot = truncate(factsSnapshot);
        return this;
    }

    public EscalationContext withFreeText(String freeText) {
        this.originalFreeText = truncate(freeText);
        return this;
    }

    public EscalationContext withHistory(String recentTicketHistory, String priorActions) {
        this.recentTicketHistory = truncate(recentTicketHistory);
        this.priorActions = truncate(priorActions);
        return this;
    }

    public EscalationContext withClassification(String category, BigDecimal confidence, String extractedReason) {
        this.category = category;
        this.confidenceScore = confidence;
        this.extractedReason = truncate(extractedReason);
        return this;
    }

    /**
     * The columns are VARCHAR(4000) and a fact map can grow. Truncating loses the tail
     * of a snapshot; failing the write would lose the whole escalation and strand a
     * partner mid-handoff. The marker makes a truncated value obviously truncated
     * rather than quietly short.
     */
    private static String truncate(String value) {
        if (value == null || value.length() <= 4000) return value;
        return value.substring(0, 3985) + "...[cut]";
    }

    public UUID getTicketId() { return ticketId; }
    public String getL2Concern() { return l2Concern; }
    public String getCategory() { return category; }
    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public String getExtractedReason() { return extractedReason; }
    public String getOriginalFreeText() { return originalFreeText; }
    public String getMedalBand() { return medalBand; }
    public String getFactsSnapshot() { return factsSnapshot; }
    public String getRecentTicketHistory() { return recentTicketHistory; }
    public String getPriorActions() { return priorActions; }
    public String getTriggerReason() { return triggerReason; }
    public Instant getCreatedAt() { return createdAt; }
}
