-- ============================================================================
-- UAT READ PROBE 2 — follow-ups from probe 1.
--
--   RUN:  .venv-probe/bin/python tools/uat_probe.py --sql tools/uat-probe-2.sql
--   OUT:  uat-probe-2-output.txt
--
-- Read-only. Every row limit is small and no free-text blob is selected, so
-- nothing here can return a customer name, address or phone number.
-- ============================================================================

SELECT '===== A. tbl_order — EVERY COLUMN. Two of my names were wrong =====' AS section;
SELECT ordinal_position AS pos, column_name, data_type
FROM information_schema.columns
WHERE table_schema='ysmdm_users' AND table_name='tbl_order'
ORDER BY ordinal_position;

SELECT '===== B. N2 — DOES THE CODE PREDICT NR vs CR? =====' AS section;
-- The decisive question. If code 1 rows carry nrTicketCount and code 2 rows
-- carry crTicketCount (or any clean split), the mapping is settled by data
-- rather than by opinion.
SELECT unassign_status_code,
       COUNT(*)                          AS orders,
       SUM(nrTicketCount > 0)            AS with_nr,
       SUM(crTicketCount > 0)            AS with_cr,
       SUM(nrTicketCount > 0 AND crTicketCount > 0) AS with_both
FROM ysmdm_users.tbl_order
WHERE unassign_status_code <> 0
GROUP BY unassign_status_code
ORDER BY unassign_status_code;

-- Is there a table that NAMES these codes?
SELECT ordinal_position AS pos, column_name, data_type
FROM information_schema.columns
WHERE table_schema='ysmdm_admin' AND table_name='tbl_reason'
ORDER BY ordinal_position;

SELECT ordinal_position AS pos, column_name, data_type
FROM information_schema.columns
WHERE table_schema='ysmdm_users' AND table_name='tbl_order_cancel_due_to_transpotation'
ORDER BY ordinal_position;

SELECT '===== C. N3 — THE PayU LINK. Shapes only, newest 5 of each =====' AS section;
-- The ledger side. txnData and every name column are deliberately NOT selected.
SELECT id, spid, amount, subaction, aorat,
       paytmoid, paytmtrnxid, paytmstatusid, upitxnid, upitxnRef, cashreeTransaction
FROM ysmdm_admin.tbl_sp_tranactions
WHERE action='CREDIT' AND subaction='WALLET RECHARGE'
ORDER BY id DESC LIMIT 5;

-- The PayU side. txnData is a blob and is NOT selected.
SELECT id, orderId, mihpayid, spId, USER_ID, status, transaction_amount, addedon
FROM ysmdm_employees.tbl_payu_transaction_details_for_sp
ORDER BY id DESC LIMIT 5;

-- How many recharge credits carry each candidate id at all?
SELECT COUNT(*) AS recharge_credits,
       SUM(paytmoid    IS NOT NULL AND paytmoid    <> '') AS has_paytmoid,
       SUM(paytmtrnxid IS NOT NULL AND paytmtrnxid <> '') AS has_paytmtrnxid,
       SUM(upitxnid    IS NOT NULL AND upitxnid    <> '') AS has_upitxnid,
       SUM(orderid IS NOT NULL AND orderid <> 0)          AS has_orderid
FROM ysmdm_admin.tbl_sp_tranactions
WHERE action='CREDIT' AND subaction='WALLET RECHARGE';

-- What the PayU status vocabulary is — this drives payuStatus normalisation.
SELECT status, COUNT(*) AS rows_
FROM ysmdm_employees.tbl_payu_transaction_details_for_sp
GROUP BY status ORDER BY rows_ DESC LIMIT 20;

SELECT '===== D. Q6 — WHERE IS THE JOB LAT/LONG? =====' AS section;
SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_name LIKE '%cs_address%' OR table_name LIKE '%address%'
ORDER BY table_schema, table_name LIMIT 20;

SELECT ordinal_position AS pos, table_schema, column_name, data_type
FROM information_schema.columns
WHERE table_name = 'cs_address'
ORDER BY table_schema, ordinal_position;

SELECT '===== E. REAL ORDER IDS TO DEMO WITH =====' AS section;
SELECT order_id, transport_charges, unassign_status_code, cashback, hub_id
FROM ysmdm_users.tbl_order
WHERE transport_charges > 0 ORDER BY order_id DESC LIMIT 5;

-- Over the Rs300 cap. Probe 1 returned 0 rows for > 30000, so the units may be
-- RUPEES rather than paise. Both are checked here; whichever returns rows tells us.
SELECT 'paise (>30000)' AS units, COUNT(*) AS rows_ FROM ysmdm_users.tbl_order WHERE transport_charges > 30000
UNION ALL
SELECT 'rupees (>300)',  COUNT(*) FROM ysmdm_users.tbl_order WHERE transport_charges > 300;

SELECT transport_charges, COUNT(*) AS orders
FROM ysmdm_users.tbl_order
WHERE transport_charges > 0
GROUP BY transport_charges ORDER BY orders DESC LIMIT 15;

SELECT order_id, unassign_status_code, cashback
FROM ysmdm_users.tbl_order
WHERE unassign_status_code <> 0 ORDER BY order_id DESC LIMIT 5;

SELECT '===== PROBE 2 COMPLETE =====' AS section;
