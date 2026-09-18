-- ============================================================================
-- UAT READ PROBE — every statement is a SELECT or a SHOW. Nothing writes.
--
-- HOW TO RUN (from your Mac, on the office network or VPN):
--
--   1. Put the credentials in a file OUTSIDE this repository:
--
--        umask 077
--        cat > ~/.botin-uat.cnf <<'CNF'
--        [client]
--        host=uatw.yesmadam.com
--        port=3306
--        user=yash-chatbot
--        password=PUT_IT_HERE
--        CNF
--        chmod 600 ~/.botin-uat.cnf
--
--      A defaults file keeps the password out of your shell history and out of
--      `ps`, which a `-p<password>` on the command line does not.
--
--   2. Run:
--
--        mysql --defaults-extra-file=~/.botin-uat.cnf \
--              --force --table < tools/uat-probe.sql > uat-probe-output.txt 2>&1
--
--      --force keeps going if one statement is refused, so a single missing
--      grant does not hide the other twenty answers.
--
--   3. Send me uat-probe-output.txt. It contains schema and counts. Read §5
--      before sending — that section is the only one that returns row data.
-- ============================================================================

SELECT '===== 0. WHO AM I, AND WHAT MAY I DO =====' AS section;
SELECT CURRENT_USER() AS effective_user, USER() AS connected_as, @@hostname AS server, VERSION() AS version;
SHOW GRANTS;

SELECT '===== 1. WHICH CATALOGS ARE VISIBLE =====' AS section;
SELECT schema_name
FROM information_schema.schemata
WHERE schema_name IN ('ysmdm_admin','ysmdm_users','ysmdm_employees')
ORDER BY schema_name;
-- Three rows expected. Fewer means the GRANT did not cover them all.

SELECT '===== 2. EVERY COLUMN BOTIn ASSUMES — does it exist? =====' AS section;
-- This is the same check the service runs at startup (UatSchemaProbe).
-- Any row with exists_ = 0 is a fact provider that will silently return nulls.
SELECT t.sch, t.tbl, t.col,
       (SELECT COUNT(*) FROM information_schema.columns c
         WHERE c.table_schema = t.sch AND c.table_name = t.tbl AND c.column_name = t.col) AS exists_
FROM (
  SELECT 'ysmdm_users'  AS sch, 'tbl_order' AS tbl, 'order_id'             AS col
  UNION ALL SELECT 'ysmdm_users','tbl_order','transport_charges'
  UNION ALL SELECT 'ysmdm_users','tbl_order','order_status_code'
  UNION ALL SELECT 'ysmdm_users','tbl_order','unassign_status_code'
  UNION ALL SELECT 'ysmdm_users','tbl_order','cashback'
  UNION ALL SELECT 'ysmdm_users','tbl_order','hub_id'
  UNION ALL SELECT 'ysmdm_users','tbl_order','arrived_at300_m'      -- INFERRED name
  UNION ALL SELECT 'ysmdm_users','tbl_order','order_type'
  UNION ALL SELECT 'ysmdm_admin','tbl_sp_tranactions','action'
  UNION ALL SELECT 'ysmdm_admin','tbl_sp_tranactions','subaction'
  UNION ALL SELECT 'ysmdm_admin','tbl_sp_tranactions','orderid'
  UNION ALL SELECT 'ysmdm_admin','tbl_sp_tranactions','amount'
  UNION ALL SELECT 'ysmdm_admin','tbl_hub','lat'                    -- INFERRED name
  UNION ALL SELECT 'ysmdm_admin','tbl_hub','lng'                    -- INFERRED name
  UNION ALL SELECT 'ysmdm_admin','tbl_servicehub_transportation','end'
) t;

SELECT '===== 3. N2 — WHICH unassign_status_code IS NR, WHICH IS CR? =====' AS section;
-- 3a. Every column on tbl_order whose name mentions NR, CR, unassign or cancel.
SELECT column_name, data_type, column_comment
FROM information_schema.columns
WHERE table_schema='ysmdm_users' AND table_name='tbl_order'
  AND (column_name LIKE '%nr%' OR column_name LIKE '%cr%'
       OR column_name LIKE '%unassign%' OR column_name LIKE '%cancel%')
ORDER BY column_name;

-- 3b. How the codes are actually distributed. The live enum is
--     REJECT=1, CANCEL=2, UNASSIGN=3, UNASSIGN_WITH_REMOVE_SP=4.
SELECT unassign_status_code, COUNT(*) AS orders
FROM ysmdm_users.tbl_order
WHERE unassign_status_code IS NOT NULL AND unassign_status_code <> 0
GROUP BY unassign_status_code
ORDER BY orders DESC;

-- 3c. Is there a lookup table that names these codes?
SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_schema IN ('ysmdm_admin','ysmdm_users')
  AND (table_name LIKE '%unassign%' OR table_name LIKE '%cancel%'
       OR table_name LIKE '%reason%' OR table_name LIKE '%status_master%')
ORDER BY table_schema, table_name;

SELECT '===== 4. N3 — WHAT LINKS A WALLET CREDIT TO A PayU TRANSACTION? =====' AS section;
-- 4a. The wallet ledger, in full. One of these columns is the link.
SELECT column_name, data_type, column_comment
FROM information_schema.columns
WHERE table_schema='ysmdm_admin' AND table_name='tbl_sp_tranactions'
ORDER BY ordinal_position;

-- 4b. The PayU side, in full.
SELECT column_name, data_type, column_comment
FROM information_schema.columns
WHERE table_schema='ysmdm_employees' AND table_name='tbl_payu_transaction_details_for_sp'
ORDER BY ordinal_position;

-- 4c. The vocabulary. 'subaction' for a recharge credit is the value we need.
SELECT action, subaction, COUNT(*) AS rows_
FROM ysmdm_admin.tbl_sp_tranactions
GROUP BY action, subaction
ORDER BY rows_ DESC
LIMIT 60;

SELECT '===== 5. REAL ORDER IDS TO DEMO WITH (no names, no phone numbers) =====' AS section;
-- Ids and amounts only. Enough to replace the fixture file with live rows.
-- 5a. A transport claim the customer paid for.
SELECT order_id, transport_charges, unassign_status_code, cashback, hub_id,
       (arrived_at300_m IS NOT NULL) AS arrived
FROM ysmdm_users.tbl_order
WHERE transport_charges > 0
ORDER BY order_id DESC
LIMIT 5;

-- 5b. A transport claim OVER the Rs300 cap — the row that must become a ticket.
SELECT order_id, transport_charges
FROM ysmdm_users.tbl_order
WHERE transport_charges > 30000
ORDER BY order_id DESC
LIMIT 5;

-- 5c. A cancelled booking.
SELECT order_id, unassign_status_code, cashback,
       (arrived_at300_m IS NOT NULL) AS arrived
FROM ysmdm_users.tbl_order
WHERE unassign_status_code <> 0
ORDER BY order_id DESC
LIMIT 5;

SELECT '===== PROBE COMPLETE =====' AS section;
