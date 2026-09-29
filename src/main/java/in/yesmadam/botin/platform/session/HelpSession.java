package in.yesmadam.botin.platform.session;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Created the instant Help is tapped — BEFORE any ticket exists. A T0 deflection
 * lives and dies here and never creates a ticket. That is Gate 1, made literal.
 *
 * nextStep lives on THIS row, not in process variables: ACT_RU_* rows are
 * deleted when a process instance completes, so anything the client needs after
 * completion has to be somewhere durable.
 */
@Entity
@Table(name = "help_session")
public class HelpSession {

    @Id private UUID id;

    @Column(name = "sp_id", nullable = false, length = 64)  private String spId;
    @Column(name = "l1_concern", length = 64)               private String l1Concern;
    @Column(name = "l2_concern", length = 64)               private String l2Concern;
    @Column(name = "current_step", nullable = false, length = 32) private String currentStep;
    @Column(name = "next_step_type", length = 32)           private String nextStepType;
    @Column(name = "next_step_payload", length = 4000) private String nextStepPayload;
    @Column(name = "selected_reference", length = 64)       private String selectedReference;
    /** EVERY attempt, joined — not just the last one. See appendFreeText. */
    @Column(name = "entry_free_text", length = 4000)   private String entryFreeText;

    /** How many times we have asked the partner to rephrase. The loop's whole state. */
    @Column(name = "clarification_attempts", nullable = false) private int clarificationAttempts;
    @Column(name = "process_instance_id", length = 64)      private String processInstanceId;
    @Column(name = "csat_result", length = 16)              private String csatResult;
    @Column(nullable = false, length = 24)                  private String status;
    @Column(name = "started_at", nullable = false)          private Instant startedAt;
    @Column(name = "closed_at")                             private Instant closedAt;

    protected HelpSession() { }

    public static HelpSession start(String spId, String entryFreeText) {
        HelpSession s = new HelpSession();
        s.id = UUID.randomUUID();
        s.spId = spId;
        s.entryFreeText = entryFreeText;
        s.currentStep = "STARTED";
        s.status = "OPEN";
        s.startedAt = Instant.now();
        return s;
    }

    public void selectConcern(String l1, String l2) { this.l1Concern = l1; this.l2Concern = l2; }

    /**
     * Keep every attempt, not just the newest.
     *
     * If this conversation reaches a person, the transcript is what they read, and
     * "they told us three times and we never understood" is the thing worth knowing —
     * the last attempt alone hides it. Bounded to the column width: the oldest text is
     * dropped rather than the newest, because the newest is the one closest to what
     * they actually meant.
     */
    public void appendFreeText(String text) {
        if (text == null || text.isBlank()) return;
        String joined = entryFreeText == null || entryFreeText.isBlank()
                ? text.trim()
                : entryFreeText + " | " + text.trim();
        this.entryFreeText = joined.length() <= 4000
                ? joined
                : joined.substring(joined.length() - 4000);
    }

    public void recordClarificationAttempt() { this.clarificationAttempts++; }
    public void setNextStep(String type, String payload) { this.nextStepType = type; this.nextStepPayload = payload; }
    public void setCurrentStep(String step) { this.currentStep = step; }
    public void setSelectedReference(String ref) { this.selectedReference = ref; }
    public void setProcessInstanceId(String pid) { this.processInstanceId = pid; }
    public void recordCsat(String result) { this.csatResult = result; }
    public void close() { closeAs("CLOSED"); }

    /**
     * Close with a reason in the status itself, e.g. CLOSED_NOT_AVAILABLE.
     *
     * The reason belongs on the session rather than in a log line: "how often did a
     * partner pick a concern we have not built" is a number the rollout needs, and
     * it has to be answerable from the database.
     */
    public void closeAs(String terminalStatus) {
        this.status = terminalStatus;
        this.closedAt = Instant.now();
    }

    /**
     * The conversation is live again. Trigger A is the only thing that does this today:
     * the partner said the resolution did not help, and a person is now on it.
     *
     * THE STATUS HAS TO MOVE, and not only for tidiness. A session left at
     * CLOSED_DEFLECTED while an agent works it would still be counted as a successful
     * deflection — inflating the single headline number of the whole project, in the
     * exact cases where the bot was told it got the answer wrong. The closed-at stamp
     * goes too; it is set again by whatever ends the conversation for real.
     */
    public void reopen() {
        this.status = "OPEN";
        this.closedAt = null;
    }

    public UUID getId() { return id; }
    public String getSpId() { return spId; }
    public String getL1Concern() { return l1Concern; }
    public String getL2Concern() { return l2Concern; }
    public String getCurrentStep() { return currentStep; }
    public String getNextStepType() { return nextStepType; }
    public String getNextStepPayload() { return nextStepPayload; }
    public String getSelectedReference() { return selectedReference; }
    public String getEntryFreeText() { return entryFreeText; }
    public int getClarificationAttempts() { return clarificationAttempts; }
    public String getProcessInstanceId() { return processInstanceId; }
    public String getCsatResult() { return csatResult; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
}
