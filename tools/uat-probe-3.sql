-- ============================================================================
-- UAT READ PROBE 3 — one question only.
--
--   Which order_status_code values mean CANCELLED_NR, CANCELLED_CR and
--   CANCELLED_BY_AGENT?
--
--   RUN:  .venv-probe/bin/python tools/uat_probe.py --sql tools/uat-probe-3.sql
--   OUT:  uat-probe-3-output.txt
--
-- Read-only. Aggregates and metadata only — no row from tbl_order is selected,
-- so nothing here can return a customer name, address or phone number.
-- ============================================================================

SELECT '===== 1. IS THERE A TABLE THAT NAMES THE ORDER STATUSES? =====' AS section;
SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_schema IN ('ysmdm_admin','ysmdm_users')
  AND (table_name LIKE '%order_status%' OR table_name LIKE '%orderstatus%'
       OR table_name LIKE '%status_master%' OR table_name LIKE '%order_state%')
ORDER BY table_schema, table_name;

SELECT '===== 2. EVERY STATUS-ISH COLUMN ON tbl_order =====' AS section;
-- If a varchar status sits beside the code, the codes name themselves.
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema='ysmdm_users' AND table_name='tbl_order'
  AND (column_name LIKE '%status%' OR column_name LIKE '%state%')
ORDER BY ordinal_position;

SELECT '===== 3. THE CODES THEMSELVES =====' AS section;
SELECT order_status_code, COUNT(*) AS orders
FROM ysmdm_users.tbl_order
GROUP BY order_status_code
ORDER BY orders DESC;

SELECT '===== 4. WHICH CODE IS NR, WHICH IS CR — FROM THE DATA =====' AS section;
-- tbl_order carries nrTicketCount and crTicketCount. If one status code is where
-- the NR counts live and another is where the CR counts live, the mapping is
-- settled by evidence rather than by anyone's memory.
SELECT order_status_code,
       COUNT(*)                                     AS orders,
       SUM(nrTicketCount > 0)                       AS with_nr,
       SUM(crTicketCount > 0)                       AS with_cr,
       SUM(nrTicketCount > 0 AND crTicketCount > 0) AS with_both,
       SUM(cancel_at IS NOT NULL)                   AS has_cancel_at
FROM ysmdm_users.tbl_order
GROUP BY order_status_code
ORDER BY orders DESC;

SELECT '===== 5. HOW THE TWO CODES RELATE =====' AS section;
-- order_status_code is what the partner sees happened. unassign_status_code is
-- HOW it happened. This says whether one implies the other.
SELECT order_status_code, unassign_status_code, COUNT(*) AS orders
FROM ysmdm_users.tbl_order
WHERE unassign_status_code <> 0 OR cancel_at IS NOT NULL
GROUP BY order_status_code, unassign_status_code
ORDER BY orders DESC
LIMIT 40;

SELECT '===== 6. DOES TRANSPORT GET PAID DIFFERENTLY PER STATUS? =====' AS section;
-- A sanity check on the rules: NR should look like "we owe them", CR like "we do not".
SELECT order_status_code,
       COUNT(*)                        AS orders,
       SUM(transport_charges > 0)      AS with_transport_charge,
       SUM(cashback > 0)               AS with_cashback
FROM ysmdm_users.tbl_order
WHERE cancel_at IS NOT NULL OR unassign_status_code <> 0
GROUP BY order_status_code
ORDER BY orders DESC;

SELECT '===== PROBE 3 COMPLETE =====' AS section;
