-- =============================================================================
-- BOTIn core schema. PRIMARY datasource only.
-- Flowable creates its own ~25 ACT_* tables separately and is not described here.
-- =============================================================================

-- 1 ---------------------------------------------------------------- catalogue
-- The single source of the L1/L2 taxonomy. Four of its columns are POINTERS at
-- the artifacts a concern needs; adding a row is not enough to add a concern.
CREATE TABLE concern_catalogue (
    l2_code          VARCHAR(64)  PRIMARY KEY,
    l1_code          VARCHAR(64)  NOT NULL,
    l1_label         VARCHAR(160) NOT NULL,
    l2_label         VARCHAR(200) NOT NULL,
    display_order    INT          NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT FALSE,
    mandatory_human  BOOLEAN      NOT NULL DEFAULT FALSE,  -- agent-connect trigger B
    process_key      VARCHAR(64),                          -- -> BPMN
    fact_provider    VARCHAR(64),                          -- -> ConcernFactProvider bean
    dmn_key          VARCHAR(64),                          -- -> decision table
    togglz_flag      VARCHAR(64),                          -- -> kill switch
    default_tier     VARCHAR(8),
    wave             INT,
    june_volume      INT,
    notes            VARCHAR(4000)
);
CREATE INDEX idx_catalogue_l1_active ON concern_catalogue (l1_code, active, display_order);

-- 2 ------------------------------------------------------------- help_session
-- Created the instant Help is tapped. Exists BEFORE any ticket does.
CREATE TABLE help_session (
    id                  UUID         PRIMARY KEY,
    sp_id               VARCHAR(64)  NOT NULL,
    l1_concern          VARCHAR(64),
    l2_concern          VARCHAR(64),
    current_step        VARCHAR(32)  NOT NULL,
    next_step_type      VARCHAR(32),
    -- Written by the process's final Service Task. NOT read from process
    -- variables: ACT_RU_* rows are deleted when an instance completes.
    next_step_payload   VARCHAR(4000),
    selected_reference  VARCHAR(64),
    entry_free_text     VARCHAR(4000),
    process_instance_id VARCHAR(64),
    csat_result         VARCHAR(16),
    status              VARCHAR(24)  NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE  NOT NULL,
    closed_at           TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_session_sp ON help_session (sp_id, started_at DESC);

-- 3 -------------------------------------------------------------------- ticket
-- Created at GATE 1 — an L2 concern needing an answer or action was selected.
-- A T0 deflection never reaches here, which is the point.
CREATE TABLE ticket (
    id               UUID         PRIMARY KEY,
    help_session_id  UUID         NOT NULL REFERENCES help_session (id),
    sp_id            VARCHAR(64)  NOT NULL,
    l1_concern       VARCHAR(64)  NOT NULL,
    l2_concern       VARCHAR(64)  NOT NULL REFERENCES concern_catalogue (l2_code),
    tier             VARCHAR(8),
    dmn_action       VARCHAR(64),
    status           VARCHAR(24)  NOT NULL,
    agent_owner      VARCHAR(64),
    trigger_reason   VARCHAR(8),                           -- A..E once escalated
    csat_result      VARCHAR(16),
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    closed_at        TIMESTAMP WITH TIME ZONE
);
-- Trigger D reads this: same SP + same sub-concern inside a window.
CREATE INDEX idx_ticket_sp_concern ON ticket (sp_id, l2_concern, created_at DESC);

-- 4 ------------------------------------------------------------- ticket_action
-- One row per attempted backend action. This is the audit log AND the
-- reconciliation source. The unique key is the dedupe-on-retry guarantee.
CREATE TABLE ticket_action (
    id               UUID         PRIMARY KEY,
    ticket_id        UUID         NOT NULL REFERENCES ticket (id),
    action_type      VARCHAR(64)  NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL,
    status           VARCHAR(24)  NOT NULL,                -- ATTEMPTED / SUCCEEDED / FAILED
    amount_paise     BIGINT,
    request_payload  VARCHAR(4000),
    response_payload VARCHAR(4000),
    attempted_at     TIMESTAMP WITH TIME ZONE  NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_action_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX idx_action_ticket ON ticket_action (ticket_id);

-- 5 ------------------------------------------------------ conversation_message
-- Highest-volume, lowest-criticality write. Published as an event after commit
-- and written on a separate thread, so transcript volume stays off the
-- partner's response latency.
CREATE TABLE conversation_message (
    id               UUID         PRIMARY KEY,
    help_session_id  UUID         NOT NULL REFERENCES help_session (id),
    ticket_id        UUID         REFERENCES ticket (id),  -- null until Gate 1
    sender           VARCHAR(8)   NOT NULL,                -- SP / BOT / AGENT
    content          VARCHAR(4000)         NOT NULL,
    step_type        VARCHAR(32),
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL
);
CREATE INDEX idx_message_session ON conversation_message (help_session_id, created_at);

-- 6 ------------------------------------------------------- escalation_context
-- Written once, when a T3 trigger fires. Computed facts, not a transcript —
-- this is what an agent opens first.
CREATE TABLE escalation_context (
    ticket_id             UUID        PRIMARY KEY REFERENCES ticket (id),
    l2_concern            VARCHAR(64) NOT NULL,
    category              VARCHAR(64),
    confidence_score      NUMERIC(4,3),
    extracted_reason      VARCHAR(4000),
    original_free_text    VARCHAR(4000),
    medal_band            VARCHAR(24),
    facts_snapshot        VARCHAR(4000),
    recent_ticket_history VARCHAR(4000),
    prior_actions         VARCHAR(4000),
    trigger_reason        VARCHAR(8)  NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 7 ---------------------------------------------------------------- sp_counter
-- NOT in the original design. The concern mapping forces it: the pooled
-- emergency cap, the monthly period-leave count and the 25-job cycle allowances
-- are STATE THIS ENGINE OWNS, not facts it can read from anywhere else.
CREATE TABLE sp_counter (
    sp_id          VARCHAR(64)  NOT NULL,
    counter_key    VARCHAR(64)  NOT NULL,   -- POOLED_EMERGENCY / PERIOD_LEAVE / ...
    period_key     VARCHAR(32)  NOT NULL,   -- '2026-09' or 'cycle:41' — the reset boundary
    count_value    INT          NOT NULL DEFAULT 0,
    last_granted_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (sp_id, counter_key, period_key)
);
