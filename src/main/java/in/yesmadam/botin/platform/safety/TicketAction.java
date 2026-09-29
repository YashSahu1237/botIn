package in.yesmadam.botin.platform.safety;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per attempted backend action. The audit log AND the reconciliation
 * source. The unique key on idempotency_key is the dedupe-on-retry guarantee.
 */
@Entity
@Table(name = "ticket_action")
public class TicketAction {

    public static final String ATTEMPTED = "ATTEMPTED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED    = "FAILED";

    @Id private UUID id;

    @Column(name = "ticket_id", nullable = false)               private UUID ticketId;
    @Column(name = "action_type", nullable = false, length = 64) private String actionType;
    @Column(name = "idempotency_key", nullable = false, length = 200) private String idempotencyKey;
    /** The third party's own id for what was acted on. The receipt, and the dedupe key. */
    @Column(name = "external_reference", length = 128)          private String externalReference;
    @Column(nullable = false, length = 24)                      private String status;
    @Column(name = "amount_paise")                              private Long amountPaise;
    @Column(name = "request_payload", length = 4000)  private String requestPayload;
    @Column(name = "response_payload", length = 4000) private String responsePayload;
    @Column(name = "attempted_at", nullable = false)            private Instant attemptedAt;
    @Column(name = "completed_at")                              private Instant completedAt;

    protected TicketAction() { }

    public static TicketAction attempt(UUID ticketId, String actionType, Long amountPaise, String requestPayload) {
        return attempt(ticketId, actionType, null, amountPaise, requestPayload);
    }

    public static TicketAction attempt(UUID ticketId, String actionType, String externalReference,
                                       Long amountPaise, String requestPayload) {
        TicketAction a = new TicketAction();
        a.id = UUID.randomUUID();
        a.ticketId = ticketId;
        a.actionType = actionType;
        a.externalReference = externalReference;
        a.idempotencyKey = idempotencyKey(ticketId, actionType, externalReference);
        a.status = ATTEMPTED;
        a.amountPaise = amountPaise;
        a.requestPayload = requestPayload;
        a.attemptedAt = Instant.now();
        return a;
    }

    /**
     * PLAN STEP 90 — KEYED ON THE THIRD PARTY'S ID WHEN THERE IS ONE, not on ours.
     *
     * The distinction is not academic. A partner who raises the same failed recharge a
     * second time gets a NEW session and a NEW ticket, so `ticketId:actionType` is a
     * different key, the guard sees nothing, and we pay twice for one payment. The
     * gateway's own reference is identical across both contacts, which is precisely the
     * property a duplicate guard needs.
     *
     * The action type stays in the key because one order can legitimately be acted on in
     * more than one way: crediting transport for an order and crediting a recharge
     * against the same order are two different movements and must not block each other.
     *
     * FALLING BACK TO THE TICKET ID IS A REAL WEAKENING, which is why ActionService makes
     * the external reference part of its contract rather than an optional extra. Without
     * a third-party id the guard covers retries WITHIN one ticket and nothing more —
     * enough for an impatient double-tap, useless against a re-raise a week later.
     */
    public static String idempotencyKey(UUID ticketId, String actionType, String externalReference) {
        return externalReference == null || externalReference.isBlank()
                ? actionType + ":ticket:" + ticketId
                : actionType + ":ref:" + externalReference;
    }

    /** For the callers that genuinely have no third-party reference. */
    public static String idempotencyKey(UUID ticketId, String actionType) {
        return idempotencyKey(ticketId, actionType, null);
    }

    /** What actually moved. Set with the outcome, never before it — see TicketActionRecorder. */
    public void recordAmount(long amountPaise) {
        this.amountPaise = amountPaise;
    }

    public void succeeded(String responsePayload) {
        this.status = SUCCEEDED; this.responsePayload = responsePayload; this.completedAt = Instant.now();
    }
    public void failed(String responsePayload) {
        this.status = FAILED; this.responsePayload = responsePayload; this.completedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public String getActionType() { return actionType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getExternalReference() { return externalReference; }
    public String getStatus() { return status; }
    public Long getAmountPaise() { return amountPaise; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
