-- =============================================================================
-- V4. Two unrelated changes, both consequences of reading the live schema.
-- =============================================================================

-- 1 ------------------------------------------- park the two unbuildable concerns
--
-- VIOL_R5_PERIODS and VIOL_R9_NO_PRODUCT read violation rows matched on
-- violation_code. tbl_violation_master contains exactly seven rows:
--
--   SP_101 Job Reject
--   SP_102 Same-Day Leave with Active Job
--   SP_103 Job Reassign / Unassign / SP Defaulter
--   SP_104 Customer Cancellation / Denied on OBD
--   SP_105 Training Session Missed
--   SP_106 Job Rejected on Call
--   SP_107 Weekend/SDL Leave Violation
--
-- Neither a period-leave violation nor a no-product violation exists. The R1-R13
-- numbering the concern mapping uses appears nowhere in the system. Any query
-- written for these two returns zero rows, and under the decision tables zero
-- prior removals reads as "allowance available" — which GRANTS the removal. So
-- leaving them switched on would not fail safe; it would auto-remove violations
-- that were never checked.
--
-- Parked rather than deleted: the rows, the decision tables and their tests all
-- stay. Switching them back on is one UPDATE once the violation types exist.
-- See docs/DEFERRED.md.

UPDATE concern_catalogue SET active = FALSE WHERE l2_code = 'VIOL_R5_PERIODS';
UPDATE concern_catalogue SET active = FALSE WHERE l2_code = 'VIOL_R9_NO_PRODUCT';

-- 2 --------------------------------------------------- map to the live taxonomy
--
-- SpTicketingConcernEnum in the existing service is already a numbered L1/L2
-- taxonomy, and it is the same taxonomy this build re-derived from the concern
-- mapping. Without carrying its code, BOTIn's counts can never be reconciled
-- against the existing ticketing reports — "transport tickets last month" would
-- mean two different things depending on which system was asked, with no way to
-- tell which. One column now; a data-reconciliation project later.
--
-- Only codes confirmed by reading the enum are set. The rest stay NULL rather
-- than guessed: a wrong mapping here is worse than an absent one, because it
-- would silently merge two different concerns in a report.

ALTER TABLE concern_catalogue ADD COLUMN legacy_concern_code INT;

UPDATE concern_catalogue SET legacy_concern_code =  30 WHERE l2_code = 'ONLINE_BOOKING_MONEY';
UPDATE concern_catalogue SET legacy_concern_code =  31 WHERE l2_code = 'RECHARGE_DEBIT_NO_CREDIT';
UPDATE concern_catalogue SET legacy_concern_code =  32 WHERE l2_code = 'TRANSPORT_NOT_RECEIVED';
UPDATE concern_catalogue SET legacy_concern_code =  35 WHERE l2_code = 'FORGET_MPIN';
UPDATE concern_catalogue SET legacy_concern_code =  39 WHERE l2_code = 'CASHBACK_NOT_RECEIVED';
UPDATE concern_catalogue SET legacy_concern_code =  50 WHERE l2_code = 'PROD_OUT_OF_STOCK';
UPDATE concern_catalogue SET legacy_concern_code =  56 WHERE l2_code = 'PROD_DELIVERY_DELAY';
UPDATE concern_catalogue SET legacy_concern_code =  62 WHERE l2_code = 'OTHER_LEGAL';
UPDATE concern_catalogue SET legacy_concern_code =  65 WHERE l2_code = 'OTHER_HUB_TIME';
UPDATE concern_catalogue SET legacy_concern_code =  67 WHERE l2_code = 'OTHER_ID_ACTIVATE';
UPDATE concern_catalogue SET legacy_concern_code =  68 WHERE l2_code = 'OTHER_WRONG_DATA';
UPDATE concern_catalogue SET legacy_concern_code = 130 WHERE l2_code = 'REFERRAL_NOT_RECEIVED';
UPDATE concern_catalogue SET legacy_concern_code = 160 WHERE l2_code = 'OTHER_BLOCK_CUSTOMER';
