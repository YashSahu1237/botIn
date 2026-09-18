# UAT schema map — read from the empapi source

Every `TODO(schema)` placeholder, resolved against the real entities. Read-only; nothing in
that repository was modified.

**Three things this changed, before the table of names:**

1. **There are three databases, not one.** The facts a single concern needs are spread
   across all three.
2. **The L1/L2 taxonomy already exists in production code.** BOTIn has been building a
   parallel one.
3. **The violation codes in the concern mapping do not exist in the system.** Two of our
   eight active concerns are affected.

---

## FINDING 1 — three catalogs, not one database

`DBConstants` declares:

| Catalog | Holds |
|---|---|
| `ysmdm_admin` | the partner, wallet, passbook, leave, violations, cycles, product orders, hubs |
| `ysmdm_users` | **`tbl_order`** — the customer booking — and customer cancellation |
| `ysmdm_employees` | **`tbl_payu_transaction_details_for_sp`** — SP wallet recharges |

A commented-out block shows the staging names are `sta_ysmdm_*`.

**Why it matters to us.** TRANSPORT_NOT_RECEIVED needs the booking (`ysmdm_users`) *and* the
passbook (`ysmdm_admin`) in one decision. RECHARGE_DEBIT_NO_CREDIT needs PayU
(`ysmdm_employees`) *and* the wallet (`ysmdm_admin`). Our design assumed **one** read-only
datasource.

**Two ways forward, and this is a decision rather than a detail:**

- **One connection, catalog-qualified SQL** — `SELECT ... FROM ysmdm_users.tbl_order` — which
  works if the MySQL user has SELECT on all three and they live on the same server. Simplest,
  and it is how empapi itself does it.
- **Three read-only datasources.** More moving parts, more GRANTs, no benefit unless the
  databases are on separate servers.

**Needed from you: are all three on the same MySQL instance in UAT?** If yes, the first
option stands and the change to our code is small. Also note this is **MySQL**, not Postgres
— our own tables stay Postgres, but the fact-provider SQL has to be MySQL dialect.

---

## FINDING 2 — the L1/L2 taxonomy already exists

`SpTicketingConcernEnum` is a live, numbered taxonomy, and it is **our taxonomy**:

| L1 | Code | Our `l1_code` |
|---|---|---|
| Job Related | 1 | — (not in our build) |
| Leaves Related | 2 | `LEAVES_RELATED` |
| Amount Related | 3 | `AMOUNT_RELATED` |
| Fine Related | 4 | `FINE_RELATED` |
| Product Issues | 5 | `PRODUCT_ISSUES` |
| Other Issues | 6 | `OTHER_ISSUES` |
| Rejoin | 7 | — |
| Violations | 8 | `VIOLATIONS` |
| Hydra Issues | 9 | — |
| Delivery Issues | −1 | — |

And the L2s are there too, with numbers:

| Our `l2_code` | Existing constant | Code |
|---|---|---|
| `TRANSPORT_NOT_RECEIVED` | `TRANSPORTATION_AMOUNT_MILA_NAHI` | **32** |
| `RECHARGE_DEBIT_NO_CREDIT` | `RECHARGE_KIYA_PAISE_CAT_GAYE_AAYE_NAHI` | **31** |
| `FORGET_MPIN` | `FORGET_MPIN` | **35** |
| `ONLINE_BOOKING_MONEY` | `ONLINE_BOOKING_KE_PAISE_NAHI_MILE` | **30** |
| `CASHBACK_NOT_RECEIVED` | `CASHBACK_AMOUNT_NAHI_AAYA` | **39** |
| `REFERRAL_NOT_RECEIVED` | `REFFERAL_AMOUNT_NAHI_AAYA` | **130** |
| `PROD_OUT_OF_STOCK` | `PRODUCT_NOT_AVAILABLE` | **50** |
| `PROD_DELIVERY_DELAY` | `TEEN_DIN_SE_JYADA_HO_GAYE_PRODUCT_NAHI_AAYA` | **56** |
| `OTHER_LEGAL` | `LEGAL_HELP_CHAHIYA` | **62** |
| `OTHER_BLOCK_CUSTOMER` | `ISSUE_WITH_CUSTOMER_BLOCK_CUSTOMER` | **160** |
| `OTHER_ID_ACTIVATE` | `ID_ACTIVE_DEACTIVATE_KARANI_HAI` | **67** |
| `OTHER_WRONG_DATA` | `MERE_PARTNER_APP_ME_DATA_WRONG_SHOW_HO_RAHA_HAI` | **68** |
| `OTHER_HUB_TIME` | `HUB_CHANGE_KARWANA_HAI` / `TIME_CHANGE_KARWANA_HAI` | **65 / 163** |

**Recommendation: add a `legacy_concern_code` column to `concern_catalogue` and populate it.**

Without it, BOTIn's counts can never be reconciled against the existing ticketing reports —
"transport tickets last month" would mean two different things depending on which system you
asked, and nobody would be able to tell which. It is one column and one migration now; it is
a data-backfill project later.

Note `PROD_DELIVERY_DELAY` is literally named *"three days have passed"* in the live enum,
while the concern mapping says R9 must use the shared Express/Standard TAT and **not** a flat
three days. The label is evidence that the flat-three-days rule is what exists today.

**Also: `tbl_sp_ticketing` and `tbl_sp_ticketing_executive_comment` already exist**, with
`SpTicketingStatusEnum`. Our `ticket` table may be duplicating a live one. That is a question
for the hosting decision (D-1), not for Phase 4 — but it should be asked before BOTIn creates
a second ticket store.

---

## FINDING 3 — the violation codes do not match

> **SUPERSEDED BY A SCOPE DECISION, 15 September.** Violations are out of the POC.
> This finding stands as fact, but it no longer blocks anything and no query is needed.
> See `docs/SCOPE-VIOLATIONS-OUT.md`.

`ViolationCode` in the live system has **seven** codes:

| Code | Meaning | Our mapping's equivalent |
|---|---|---|
| `SP_101` | Job Reject | `VIOL_T5_JOB_REJECT` |
| `SP_102` | Same-Day Leave with Active Job | `VIOL_T1_SAMEDAY_LEAVE` |
| `SP_103` | Job Reassign / Unassign / SP Defaulter | — |
| `SP_104` | Customer Cancellation / Denied on OBD | `VIOL_T3_OBD_DENIED` |
| `SP_105` | Training Session Missed | `VIOL_T4_TRAINING` (marked DEPRECATED) |
| `SP_106` | Job Rejected on Call | `VIOL_T6_REJECT_OVER_CALL` |
| `SP_107` | Weekend/SDL Leave Violation | — |

**Our two active violation concerns are not in that list:**

- `VIOL_R5_PERIODS` — period leave
- `VIOL_R9_NO_PRODUCT` — no product

The mapping's R1–R13 numbering appears nowhere in the code. `tbl_violation_master` stores
`code` as a **string column**, so R-codes may exist as *data* while the enum covers only the
subset the code branches on.

~~**Needed from you: `SELECT id, code, description FROM ysmdm_admin.tbl_violation_master;`**~~
**No longer needed — violations are out of POC scope.**

One query. Until it lands, the two violation concerns cannot be wired, because there is no
way to know which rows to read.

---

## The fact map

### TRANSPORT_NOT_RECEIVED

| Fact | Real source |
|---|---|
| `arrivedAt300metre` | **`ysmdm_users.tbl_order.arrivedAt300M`** — a TIMESTAMP. Non-null means arrived. An exact match for the fact, already recorded. |
| `customerTransportCharged` | `ysmdm_users.tbl_order.transport_charges` (INT). Also `transport_manage`. |
| `alreadyCredited` | `ysmdm_admin.tbl_sp_passbook` — `order_id`, `actiontype` / `actionTypeCode`, `reason` / `reasonCode`. Also `tbl_sp_tranactions` — `orderid`, `action`, **`subaction`**. The mapping's *"Auto-credit (sub-action: Transport)"* is literally this column; the strings `"TRANSPORT"`, `"Booking Transport"`, `"Transport availed against booking"`, `"Unused transport for "` all appear in the code. |
| `cancellationStatus` | `tbl_order.order_status_code` + **`unassign_status_code`** (`UnAssignStatus`: REJECT=1, CANCEL=2, UNASSIGN=3, UNASSIGN_WITH_REMOVE_SP=4). `tbl_order` also carries `nrTicketCount` and `crTicketCount` — NR and CR exist as first-class concepts. |
| `lastMinCashbackCredited` | `tbl_order.cashback` and `convenienceFeeCashback`; customer-side fees in `ysmdm_users.tbl_order_previous_cancellation` (`cancellation_fee`, `charged_fee`, `CancellationChargeStatus`). |
| `distanceBeyondRadiusKm` | `ysmdm_users.tbl_order` + `ysmdm_admin.tbl_hub` — but see the warning below. |

**WARNING — Rs50/km may not be the real rule.**

`tbl_servicehub_transportation` is a **per-hub distance-slab table**: `start`, `end`,
`charges`, joined from `tbl_hub` by `hub_id`. Transport is priced in *bands*, not per
kilometre, at least for the hub side. Our decision table pays `distance × Rs50`.

Two possibilities: the SP reimbursement genuinely is Rs50/km and the slab table prices
something else, or the mapping's Rs50/km is a simplification of the slab table. **This needs
answering before Transport can pay anyone.** It is the highest-volume concern in the build
(5,615/month) and it is one of only six rules that move money.

`tbl_fifty_metre_radius_log` (`order_id`, `latitude`, `longitude`, `radius`, `source`,
`timestamp`) is the proximity log, and its `radius` is a column rather than a constant — so
"within N metres" is already configurable in the live system.

**Still unresolved: hub centre coordinates.** `ServiceHub` has no latitude/longitude field I
could find. Either hub geography lives elsewhere, or distance is computed from something
other than a hub centre. One question for whoever owns hubs.

### RECHARGE_DEBIT_NO_CREDIT

| Fact | Real source |
|---|---|
| `payuStatus` | `ysmdm_employees.tbl_payu_transaction_details_for_sp` — `status` (String), plus `mihpayid`, `transaction_amount`, `refundStatus`, `refund_amount`. `orderId` here is a **String**. |
| `alreadyCredited` | `tbl_sp_passbook` / `tbl_sp_tranactions` keyed on the PayU txn id — the columns `paytmtrnxid`, `upitxnid` exist, so a gateway reference on a passbook row is an established pattern. |

`WalletRechargeStatus` (PENDING_FROM_ACCOUNT=1, REJECT_FROM_ACCOUNT=2, APPROVED=3) is the
*internal* recharge approval state, **not** the PayU state. The duplicate guard I added is
supported by `tbl_validate_sp_payu_wallet_recharge` and
`tbl_sp_wallet_recharge_checksum_call`, both of which exist for exactly this problem.

**Still needed: the distinct values of `status` in that table.** One query:
`SELECT DISTINCT status FROM ysmdm_employees.tbl_payu_transaction_details_for_sp;`

### VIOL_R5_PERIODS and VIOL_R9_NO_PRODUCT

| Fact | Real source |
|---|---|
| the violation row | `ysmdm_admin.tbl_sp_violation_details` — `sp_id`, `violation_code`, `violation_date`, **`sp_cycle_id`**, `status`, **`appeal_count`**, **`appeal_status`**, `revoked_at`, `revoked_by_user_id` |
| `priorRemovalsThisCycle` | count rows with the same `violation_code` and `sp_cycle_id` where `status = 'REVOKED'` — **the cycle is already on the row**, so no counter of ours is needed for this one |
| the 25-job cycle | `ysmdm_admin.tbl_sp_job_completion_cycle` — `sp_id`, `start_date`, `end_date`, **`order_count`**, `strike_count`, `violation_count` |
| `priorPeriodLeavesThisMonth` | `ysmdm_admin.tbl_sp_leave` — `spid`, `date`, `fromDate`, `toDate`, **`leaveTypeCode`**, `leaveStatusCode`, `fine`. `sp_monthly_job_summary` gives the monthly frame. |

**Two things worth knowing here.**

The appeal machinery **already exists**: `ViolationStatus` is `REGISTERED` / `REVOKED`, and
`AppealStatus` is `NOT_RAISED` / `RAISED` / `RESOLVED` / `REJECTED`. That maps cleanly onto
our vocabulary — a `REMOVE_VIOLATION` outcome is a revoke, an `UPHOLD` is an appeal rejected.
BOTIn should write into these fields rather than inventing its own status.

`ESpLeave` is `FULL_DAY` / `FIRST_HALF` / `SECOND_HALF` — that is leave **duration**, not
reason. The reason lives in the ticketing enum, where `GIRLS_PROBLEM` is code **29**. That is
almost certainly what "period leave" means in the data.

**Needed: which `leaveTypeCode` value marks a period leave.**

### PROD_DELIVERY_DELAY

| Fact | Real source |
|---|---|
| `orderPlaced` | `ysmdm_admin.tbl_sp_order` — `spid`, `order_status`, `order_status_code`, `created_at`, **`delivered_at`**, `awbNo`, `courierDelivery`, `switchToSelfPickUp` |
| delivery progress | `tbl_sp_product_order_track` — `trackStatusCode` via `SpProductOrderTrackEnum`: ORDER_PLACED=1, ORDER_CONFIRMED=2, OUT_FOR_DELIVERY=3, ORDER_DELIVERED=4 |
| Express vs Standard | `tbl_hub.enableExpressProductDelivery`, `tbl_hub.express_product_hub_id`, and **`tbl_settings` key `express_delivery_pincodes`** |

So Express eligibility is decided by **pincode**, from a settings row — not a flag on the
order. The shared TAT service (plan step 43) has to read that list.

`SpUnavailableProductOrderStatus` (TO_BE_ORDERED / ORDER_PLACED / ORDER_DELIVERED) is the
out-of-stock flow, which is `PROD_OUT_OF_STOCK`, not in this build.

### FORGET_MPIN

Needs nothing, and that is confirmed: it is a pure deflection. Worth noting that
`tbl_sp.mpin_hash` and `tbl_sp.dailyMpinAttempts` exist, so a future version could tell a
partner they have attempts remaining rather than only handing them a link.

### Partner identity

`ysmdm_admin.tbl_sp.id` is an **Integer**. It appears as `spid` on most tables, `sp_id` on
the newer ones (`tbl_sp_violation_details`, `tbl_sp_job_completion_cycle`,
`sp_monthly_job_summary`), and `spId` as a **Long** on `tbl_payu_transaction_details_for_sp`.
Our `help_session.sp_id` is `VARCHAR(64)`, which holds all of them, but the fact providers
must cast.

---

## What is still needed

Down from eight questions to five, and four of them are single queries.

1. **Are `ysmdm_admin`, `ysmdm_users` and `ysmdm_employees` on the same MySQL instance in
   UAT?** Decides one datasource or three.
2. ~~`SELECT id, code, description FROM ysmdm_admin.tbl_violation_master;`~~ — **withdrawn.**
   Violations are out of POC scope.
3. `SELECT DISTINCT status FROM ysmdm_employees.tbl_payu_transaction_details_for_sp;`
4. Which `leaveTypeCode` marks a period leave.
5. **Is transport reimbursement really Rs50/km, or the `tbl_servicehub_transportation`
   slabs?** Plus: where do hub centre coordinates live?

Unchanged from before: the `SELECT`-only GRANT, and the Transport path-selector rule.
