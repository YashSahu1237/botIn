-- =============================================================================
-- V5 — point every built concern at the generic process.
--
-- Until now each concern named its own BPMN file and only FORGET_MPIN had one, so
-- the other five active concerns were catalogued, decidable and unreachable: an
-- active row with a process_key naming a file that does not exist ends at
-- CONCERN_NOT_AVAILABLE, which is correct behaviour and not a working concern.
--
-- concern-generic.bpmn20.xml is the same shape for all of them — pre-flight gate,
-- facts, decision, Gate 1, then either an answer or a person — with every
-- concern-specific detail read from the catalogue row at runtime.
--
-- WHAT THIS ACTUALLY TURNS ON. With UAT unreachable, the fact providers return
-- all-nulls, the decision tables land on their catch-alls, and these five concerns
-- route to an agent. That is the honest state of the system and it is what
-- docs/SIGNAL-REGISTER.md describes. What changes here is that the path is now
-- REAL: a ticket is opened, an escalation context is written, and a task appears
-- in the agent queue, rather than the partner meeting "not available".
--
-- FORGET_MPIN KEEPS ITS OWN PROCESS. It is the T0 proof — the assertion that a
-- deflection creates no ticket — and that assertion is worth more running through
-- its own three-element file than folded into a shared one. It is also the only
-- concern whose outcome is a deeplink rather than a message.
-- =============================================================================

UPDATE concern_catalogue
   SET process_key = 'concern-generic'
 WHERE l2_code IN (
        'TRANSPORT_NOT_RECEIVED',
        'RECHARGE_DEBIT_NO_CREDIT',
        'PROD_DELIVERY_DELAY',
        'VIOL_R4_OTHERS',
        'OTHER_FREETEXT_TRIAGE'
       );
