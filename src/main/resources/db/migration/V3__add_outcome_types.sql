-- V3: outcome_types — the vocabulary the concern mapping actually uses, which
-- the original design had no column for. A concern's outcome types tell you what
-- kinds of ending it can have before any rule is evaluated:
--
--   BOT        the bot resolves it
--   TICKET     a ticket is raised for a human
--   UPHOLD     the decision stands; the appeal is refused (a real outcome, not a failure)
--   REROUTE    this is not the concern the partner actually has — send them elsewhere
--   SELF-SERVE deflect with a deeplink; no ticket
--   V2         out of scope for this build
--   DEPRECATED the concern no longer exists
--   MIXED      more than one of the above depending on the rule
--
-- Stored as a comma-separated list because it is read-mostly reference data and
-- is never queried by element.

ALTER TABLE concern_catalogue ADD COLUMN outcome_types VARCHAR(64);

UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET' WHERE l2_code = 'TRANSPORT_NOT_RECEIVED';
UPDATE concern_catalogue SET outcome_types = 'BOT,REROUTE,TICKET,V2' WHERE l2_code = 'ONLINE_BOOKING_MONEY';
UPDATE concern_catalogue SET outcome_types = 'DEPRECATED' WHERE l2_code = 'SAVING_UNDER_5K';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET' WHERE l2_code = 'RECHARGE_DEBIT_NO_CREDIT';
UPDATE concern_catalogue SET outcome_types = 'SELF-SERVE' WHERE l2_code = 'FORGET_MPIN';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET' WHERE l2_code = 'ARW_TO_BANK';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET' WHERE l2_code = 'MAIN_WALLET_TO_BANK';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET' WHERE l2_code = 'REFERRAL_NOT_RECEIVED';
UPDATE concern_catalogue SET outcome_types = 'BOT' WHERE l2_code = 'CASHBACK_NOT_RECEIVED';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET,UPHOLD' WHERE l2_code = 'VIOL_ARCHITECTURE';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET,UPHOLD' WHERE l2_code = 'VIOL_T1_SAMEDAY_LEAVE';
UPDATE concern_catalogue SET outcome_types = 'BOT,UPHOLD' WHERE l2_code = 'VIOL_R1_FAR_JOB';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET,UPHOLD' WHERE l2_code = 'VIOL_R2_NOT_RESPONDING';
UPDATE concern_catalogue SET outcome_types = 'BOT,REROUTE,UPHOLD' WHERE l2_code = 'VIOL_R3_DELAYED_PREV';
UPDATE concern_catalogue SET outcome_types = 'REROUTE,TICKET' WHERE l2_code = 'VIOL_R4_OTHERS';
UPDATE concern_catalogue SET outcome_types = 'BOT,UPHOLD' WHERE l2_code = 'VIOL_R5_PERIODS';
UPDATE concern_catalogue SET outcome_types = 'REROUTE,TICKET' WHERE l2_code = 'VIOL_R6_RUDE';
UPDATE concern_catalogue SET outcome_types = 'TICKET,UPHOLD' WHERE l2_code = 'VIOL_R7_SMALL_BOOKING';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET,UPHOLD' WHERE l2_code = 'VIOL_R8_R10_EMERGENCY';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET,UPHOLD' WHERE l2_code = 'VIOL_R9_NO_PRODUCT';
UPDATE concern_catalogue SET outcome_types = 'UPHOLD' WHERE l2_code = 'VIOL_R11_VEHICLE';
UPDATE concern_catalogue SET outcome_types = 'TICKET' WHERE l2_code = 'VIOL_R13_CX_UNSATISFIED';
UPDATE concern_catalogue SET outcome_types = 'TICKET,UPHOLD,V2' WHERE l2_code = 'VIOL_T3_OBD_DENIED';
UPDATE concern_catalogue SET outcome_types = 'BOT,REROUTE,UPHOLD' WHERE l2_code = 'VIOL_T5_JOB_REJECT';
UPDATE concern_catalogue SET outcome_types = 'BOT,REROUTE,UPHOLD' WHERE l2_code = 'VIOL_T6_REJECT_OVER_CALL';
UPDATE concern_catalogue SET outcome_types = 'DEPRECATED' WHERE l2_code = 'VIOL_T4_TRAINING';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET' WHERE l2_code = 'PROD_OUT_OF_STOCK';
UPDATE concern_catalogue SET outcome_types = 'BOT,TICKET' WHERE l2_code = 'PROD_DELIVERY_DELAY';
UPDATE concern_catalogue SET outcome_types = 'REROUTE,TICKET' WHERE l2_code = 'PROD_APP_FAILURE';
UPDATE concern_catalogue SET outcome_types = 'REROUTE,TICKET' WHERE l2_code = 'OTHER_FREETEXT_TRIAGE';
UPDATE concern_catalogue SET outcome_types = 'TICKET' WHERE l2_code = 'OTHER_LEGAL';
UPDATE concern_catalogue SET outcome_types = 'SELF-SERVE' WHERE l2_code = 'OTHER_BOOKING_REQ';
UPDATE concern_catalogue SET outcome_types = 'MIXED,SELF-SERVE,TICKET' WHERE l2_code = 'OTHER_HUB_TIME';
UPDATE concern_catalogue SET outcome_types = 'TICKET' WHERE l2_code = 'OTHER_ID_ACTIVATE';
UPDATE concern_catalogue SET outcome_types = 'TICKET' WHERE l2_code = 'OTHER_WRONG_DATA';
UPDATE concern_catalogue SET outcome_types = 'BOT' WHERE l2_code = 'OTHER_BLOCK_CUSTOMER';
UPDATE concern_catalogue SET outcome_types = 'SELF-SERVE,TICKET' WHERE l2_code = 'LEAVE_ALL';
UPDATE concern_catalogue SET outcome_types = 'DEPRECATED' WHERE l2_code = 'FINE_ALL';
