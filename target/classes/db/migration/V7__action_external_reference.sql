-- =============================================================================
-- V7 — the third party's own reference for what an action acted on.
--
-- PLAN STEP 90, and the column that makes a retry safe.
--
-- Until now idempotency_key was `ticketId:actionType`. That protects a retry of
-- ONE ticket and nothing else — and the case it fails on is the one that actually
-- happens: a partner raises a failed recharge, we credit them, and a week later
-- they raise THE SAME recharge again because the balance confused them. That is a
-- new session and a new ticket, so the old key differs, the unique constraint sees
-- nothing, and we pay twice for one payment.
--
-- The gateway's own order reference is identical across both contacts. Keying on it
-- means the second attempt collides with the first and the partner is told they
-- have already been credited — which is both true and the answer they needed.
--
-- The column is nullable because not every action has a third-party id. Where it is
-- null the key falls back to the ticket, and that weaker guarantee is recorded on
-- the row rather than assumed: `external_reference IS NULL` is the query that finds
-- every action a re-raise could still double.
--
-- No backfill. Existing rows were written under the old scheme and their keys stay
-- valid; rewriting them would change an audit log, which is the one thing an audit
-- log must never do.
-- =============================================================================

ALTER TABLE ticket_action
    ADD COLUMN external_reference VARCHAR(128);

CREATE INDEX idx_action_external_ref ON ticket_action (external_reference);
