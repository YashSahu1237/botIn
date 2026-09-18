-- Seeds the FULL taxonomy: all 38 concerns, only the 8 POC ones active.
-- Seeding everything makes the L1 menu real, and forces the "concern not
-- available" path to be exercised constantly rather than discovered late.
--
-- togglz_flag is set ONLY where a matching BotinFeature constant exists.
-- FeatureNameValidator fails startup otherwise, by design.

INSERT INTO concern_catalogue
 (l2_code, l1_code, l1_label, l2_label, display_order, active, mandatory_human,
  process_key, fact_provider, dmn_key, togglz_flag, default_tier, wave, june_volume, notes)
VALUES
  ('TRANSPORT_NOT_RECEIVED', 'AMOUNT_RELATED', 'Amount Related', 'Transport amount nahi mila', 10, TRUE, FALSE, 'transport-not-received', 'TRANSPORT_NOT_RECEIVED', 'transport-not-received-decision', 'TRANSPORT_AUTO_CREDIT', 'T1', 1, 5615, 'OPEN: partial-km rounding (prorate / round up / down). Haversine straight-line, measured from radius EDGE.'),
  ('ONLINE_BOOKING_MONEY', 'AMOUNT_RELATED', 'Amount Related', 'Online Booking ke pese nahi mile', 20, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, 1868, 'Only ~15% of this bucket is genuine. Commission deny-and-explain deferred to v2.'),
  ('SAVING_UNDER_5K', 'AMOUNT_RELATED', 'Amount Related', 'Saving account <Rs5000 to bank transfer', 30, FALSE, FALSE, NULL, NULL, NULL, NULL, NULL, NULL, 1042, 'DEPRECATED: the Rs5,000 minimum condition has been REMOVED. 100% of these are mis-tagged.'),
  ('RECHARGE_DEBIT_NO_CREDIT', 'AMOUNT_RELATED', 'Amount Related', 'Recharge kiya, pese cut gaye, aaye nahi', 40, TRUE, FALSE, 'recharge-debit-no-credit', 'RECHARGE_DEBIT_NO_CREDIT', 'recharge-debit-no-credit-decision', 'RECHARGE_AUTO_CREDIT', 'T1', 1, 883, 'Dup-guard on PayU txn ID prevents double-credit on re-raise.'),
  ('FORGET_MPIN', 'AMOUNT_RELATED', 'Amount Related', 'Forget MPIN', 50, TRUE, FALSE, 'forget-mpin', 'FORGET_MPIN', 'forget-mpin-decision', NULL, 'T0', 1, 738, '100% deflection. Changed from old SOP where an agent clicked Reset MPIN.'),
  ('ARW_TO_BANK', 'AMOUNT_RELATED', 'Amount Related', 'ARW (Saving) to bank transfer', 60, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, 650, 'Cashfree alert must be batched: ONE per low-balance window, deduped across ARW + Main Wallet. OPEN: stakeholder email list.'),
  ('MAIN_WALLET_TO_BANK', 'AMOUNT_RELATED', 'Amount Related', 'Main Wallet to bank transfer', 70, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, 316, 'Shares the Cashfree health-check service with ARW.'),
  ('REFERRAL_NOT_RECEIVED', 'AMOUNT_RELATED', 'Amount Related', 'Referral ka amount nhi aya', 80, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, 192, 'Onboarding check placed BEFORE referrer check. Continuous-bonus cadence (month-2 missing) = v2; v1 shows latest txn.'),
  ('CASHBACK_NOT_RECEIVED', 'AMOUNT_RELATED', 'Amount Related', 'Cashback nahi aya', 90, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, 176, 'Fully bot-resolvable on all four branches - no human path.'),
  ('VIOL_ARCHITECTURE', 'VIOLATIONS', 'Violations', 'ARCHITECTURE (applies to all types)', 10, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'STRIKE REMOVAL IS AUTOMATIC IN BACKEND once no violations remain for that day - never a bot step. 5 strikes = possible deactivation.'),
  ('VIOL_T1_SAMEDAY_LEAVE', 'VIOLATIONS', 'Violations', 'Type 1: Same-Day Leave with Active Job', 20, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'Only GRANTED removals count; denied claims don''t burn allowance. OPEN: final pooled cap number.'),
  ('VIOL_R1_FAR_JOB', 'VIOLATIONS', 'Violations', 'Type 2 R1: Denying due to Far job', 30, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'Shares the haversine/hub-radius service with Transport Path 3.'),
  ('VIOL_R2_NOT_RESPONDING', 'VIOLATIONS', 'Violations', 'Type 2 R2: Not Responding / Not contactable', 40, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'Tiers: assigned within 1hr confirm 30min before; same day >1hr 60min; one day before or earlier 120min.'),
  ('VIOL_R3_DELAYED_PREV', 'VIOLATIONS', 'Violations', 'Type 2 R3: Delayed due to last job (also R12)', 50, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'Sub-reasons auto-classified from backend signals (no reliance on SP''s word). Uses SCHEDULED end, not actual.'),
  ('VIOL_R4_OTHERS', 'VIOLATIONS', 'Violations', 'Type 2 R4: Others', 60, TRUE, FALSE, 'viol-r4-others', 'VIOL_R4_OTHERS', 'viol-r4-others-decision', NULL, 'T3', 1, NULL, 'Safe to trust text for ROUTING only - downstream flows self-validate against backend.'),
  ('VIOL_R5_PERIODS', 'VIOLATIONS', 'Violations', 'Type 2 R5: Periods Leave', 70, TRUE, FALSE, 'viol-r5-periods', 'VIOL_R5_PERIODS', 'viol-r5-periods-decision', 'VIOL_R5_AUTO_REMOVE', 'T1', 1, NULL, NULL),
  ('VIOL_R6_RUDE', 'VIOLATIONS', 'Violations', 'Type 2 R6: Rude behaviour with Customer', 80, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T3', NULL, NULL, 'OPEN: does the bot ask SP to confirm/contest the tag directly, or infer from the explanation? Never auto-removes.'),
  ('VIOL_R7_SMALL_BOOKING', 'VIOLATIONS', 'Violations', 'Type 2 R7: Small Amount Booking', 90, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'Unassign only.'),
  ('VIOL_R8_R10_EMERGENCY', 'VIOLATIONS', 'Violations', 'Type 2 R8 Health + R10 Death / Any emergency', 100, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'SINGLE shared counter across Type-1 emergency + R8 + R10 - prevents stacking 2+2+2. OPEN: final number.'),
  ('VIOL_R9_NO_PRODUCT', 'VIOLATIONS', 'Violations', 'Type 2 R9: Not Having Product / Equipment', 110, TRUE, FALSE, 'viol-r9-no-product', 'VIOL_R9_NO_PRODUCT', 'viol-r9-no-product-decision', 'VIOL_R9_AUTO_REMOVE', 'T1', 1, NULL, 'ALIGNED: must use the shared Express/Standard TAT (11PM deadline), NOT a flat 3 days.'),
  ('VIOL_R11_VEHICLE', 'VIOLATIONS', 'Violations', 'Type 2 R11: Vehicle / Ride Issue', 120, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'No removal path at all - simplest rule in the set.'),
  ('VIOL_R13_CX_UNSATISFIED', 'VIOLATIONS', 'Violations', 'Type 2 R13: Customer not satisfied with service', 130, FALSE, TRUE, NULL, NULL, NULL, NULL, 'T3', NULL, NULL, 'Reschedule only.'),
  ('VIOL_T3_OBD_DENIED', 'VIOLATIONS', 'Violations', 'Type 3: Customer Cancellation / Denied on OBD', 140, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'v2 reuses the existing Whisper to Claude call-analysis pipeline.'),
  ('VIOL_T5_JOB_REJECT', 'VIOLATIONS', 'Violations', 'Type 5: Job Reject', 150, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'Policy permits denial for: feeling threatened/unsafe, past bad experience, cross-gender. Real-time danger goes via SOS emergency trigger, NOT this flow.'),
  ('VIOL_T6_REJECT_OVER_CALL', 'VIOLATIONS', 'Violations', 'Type 6: Job Rejected over call', 160, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, NULL, 'v2: analyse recording to verify intent rather than trusting SP self-report.'),
  ('VIOL_T4_TRAINING', 'VIOLATIONS', 'Violations', 'Type 4: Training Session Missed', 170, FALSE, FALSE, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'Excluded by decision.'),
  ('PROD_OUT_OF_STOCK', 'PRODUCT_ISSUES', 'Product Issues', 'Out of stock', 10, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T1', NULL, 2807, 'Shared step: ask product name, show 2-3 best matches, SP picks exact item. Reused by every Product L2.'),
  ('PROD_DELIVERY_DELAY', 'PRODUCT_ISSUES', 'Product Issues', 'Delay in delivery', 20, TRUE, FALSE, 'prod-delivery-delay', 'PROD_DELIVERY_DELAY', 'prod-delivery-delay-decision', NULL, 'T1', 1, 2306, 'SHARED TAT FUNCTION - Violation R9 must use this, not a flat 3 days.'),
  ('PROD_APP_FAILURE', 'PRODUCT_ISSUES', 'Product Issues', 'App se product nahi lag raha', 30, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T3', NULL, 873, 'Remaining Product Issues L2s deferred.'),
  ('OTHER_FREETEXT_TRIAGE', 'OTHER_ISSUES', 'Other Issues', 'GLOBAL: free-text triage', 10, TRUE, FALSE, 'other-freetext-triage', 'OTHER_FREETEXT_TRIAGE', 'other-freetext-triage-decision', NULL, 'T3', 1, NULL, NULL),
  ('OTHER_LEGAL', 'OTHER_ISSUES', 'Other Issues', 'Legal help chahiye', 20, FALSE, TRUE, NULL, NULL, NULL, NULL, 'T3', NULL, NULL, NULL),
  ('OTHER_BOOKING_REQ', 'OTHER_ISSUES', 'Other Issues', 'Booking banwani hai', 30, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T0', NULL, NULL, NULL),
  ('OTHER_HUB_TIME', 'OTHER_ISSUES', 'Other Issues', 'Hub / Time change karwana hai', 40, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T0', NULL, 2635, 'OPEN: reduce-hours - deny-and-explain, or ticket? SP can only increase hours via the current link.'),
  ('OTHER_ID_ACTIVATE', 'OTHER_ISSUES', 'Other Issues', 'ID Activate / Deactivate karani hai', 50, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T3', NULL, NULL, NULL),
  ('OTHER_WRONG_DATA', 'OTHER_ISSUES', 'Other Issues', 'Rating / exclusive lead / wrong data in app', 60, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T3', NULL, 1771, NULL),
  ('OTHER_BLOCK_CUSTOMER', 'OTHER_ISSUES', 'Other Issues', 'Issue with customer / Block customer', 70, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T2', NULL, NULL, 'Recommend an explicit confirm step before executing - it''s a state change on who can rebook the SP.'),
  ('LEAVE_ALL', 'LEAVES_RELATED', 'Leaves Related', 'All leave types (apply / remove)', 10, FALSE, FALSE, NULL, NULL, NULL, NULL, 'T0', NULL, 2891, 'Whole L1 collapses to self-serve + app-failure ticket.'),
  ('FINE_ALL', 'FINE_RELATED', 'Fine Related', 'ALL fine sub-concerns', 10, FALSE, FALSE, NULL, NULL, NULL, NULL, NULL, NULL, 12781, 'Job-related fines will also be stopped. Largest single simplification in the redesign.');
