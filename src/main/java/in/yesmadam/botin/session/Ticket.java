package in.yesmadam.botin.session;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Created at GATE 1 — an L2 concern needing an answer or action was selected. */
@Entity
@Table(name = "ticket")
public class Ticket {

    @Id private UUID id;

    @Column(name = "help_session_id", nullable = false) private UUID helpSessionId;
    @Column(name = "sp_id", nullable = false, length = 64)      private String spId;
    @Column(name = "l1_concern", nullable = false, length = 64) private String l1Concern;
    @Column(name = "l2_concern", nullable = false, length = 64) private String l2Concern;
    @Column(length = 8)                       private String tier;
    @Column(name = "dmn_action", length = 64) private String dmnAction;
    @Column(nullable = false, length = 24)    private String status;
    @Column(name = "agent_owner", length = 64) private String agentOwner;
    /** Which of triggers A-E sent this to a human. */
    @Column(name = "trigger_reason", length = 8) private String triggerReason;
    @Column(name = "csat_result", length = 16)   private String csatResult;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "closed_at")                    private Instant closedAt;

    protected Ticket() { }

    public static Ticket openAtGate1(HelpSession session) {
        return openAtGate1(session.getId(), session.getSpId(),
                session.getL1Concern(), session.getL2Concern());
    }

    /**
     * FIELDS PASSED IN, NOT READ FROM THE SESSION — and that is not a style preference.
     *
     * Gate 1 commits in its own transaction (see TicketService), and a separate
     * transaction cannot see what the caller has merely FLUSHED. Reading the session
     * there returned the row as it was before this request touched it: l1_concern and
     * l2_concern still null, because selecting the concern had not been committed. The
     * insert then failed on NOT NULL — loudly, which was lucky. Had those columns been
     * nullable, every ticket would have been filed against no concern at all.
     */
    public static Ticket openAtGate1(UUID helpSessionId, String spId, String l1Concern, String l2Concern) {
        Ticket t = new Ticket();
        t.id = UUID.randomUUID();
        t.helpSessionId = helpSessionId;
        t.spId = spId;
        t.l1Concern = l1Concern;
        t.l2Concern = l2Concern;
        t.status = "OPEN";
        t.createdAt = Instant.now();
        return t;
    }

    public void recordDecision(String tier, String dmnAction) { this.tier = tier; this.dmnAction = dmnAction; }
    public void escalate(String triggerReason) { this.tier = "T3"; this.triggerReason = triggerReason; this.status = "AWAITING_AGENT"; }
    public void assignTo(String agentId) { this.agentOwner = agentId; this.status = "WITH_AGENT"; }
    public void recordCsat(String result) { this.csatResult = result; }
    public void close() { this.status = "CLOSED"; this.closedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getHelpSessionId() { return helpSessionId; }
    public String getSpId() { return spId; }
    public String getL1Concern() { return l1Concern; }
    public String getL2Concern() { return l2Concern; }
    public String getTier() { return tier; }
    public String getDmnAction() { return dmnAction; }
    public String getStatus() { return status; }
    public String getAgentOwner() { return agentOwner; }
    public String getTriggerReason() { return triggerReason; }
    public String getCsatResult() { return csatResult; }
    public Instant getCreatedAt() { return createdAt; }
}
