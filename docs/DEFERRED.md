# Deferred — picked up after the POC

> **UPDATE — the Decision Matrix closed three of these.** D-B (the transport rate) and D-C
> (the path selector) are **CLOSED**, and D-A's premise was wrong: R1–R13 are not violation
> types, they are sub-reasons under one. See `docs/MATRIX-ANSWERS.md` for what changed, what
> remains, and the four questions still open.

Things deliberately parked, with enough context to resume without re-deriving anything.
Nothing here is lost or abandoned; each item says exactly what unblocks it.

---

## D-A. Violations — REMOVED FROM POC SCOPE

**Status: OUT OF SCOPE by decision, 15 September. Not blocked, not waiting on anybody.**

> This was a blocker (N1 — which column holds the R1–R13 sub-reason). It is no longer a
> blocker, because the concerns it blocked are no longer in the POC. The decision, and
> what it costs, are in `docs/SCOPE-VIOLATIONS-OUT.md`.

> **N1 is closed as a question.** Nobody needs to answer it for the POC to ship.

The rows, decision tables and tests stay in the repository. V4 already sets both concerns
`active = FALSE`, so the running service and the demo are unaffected — this is a scope
record, not a code change.

The history below is kept because it explains *why* the concerns could not be built, and
that reasoning is what a later violations phase would start from.

### Why

Both concerns decide by reading `ysmdm_admin.tbl_sp_violation_details` and matching on
`violation_code`. The full contents of `tbl_violation_master`:

| id | code | description |
|---|---|---|
| 1 | `SP_101` | Job Reject |
| 2 | `SP_102` | Same-Day Leave with Active Job |
| 3 | `SP_103` | Job Reassign / Unassign / SP defaulter |
| 4 | `SP_104` | Customer Cancellation / Denied on OBD |
| 5 | `SP_105` | Training Session Missed |
| 6 | `SP_106` | Job Rejected on Call |
| 7 | `SP_107` | Weekend/SDL Leave Violation |

There is no period-leave violation and no no-product violation. The R1–R13 numbering in the
concern mapping appears nowhere in this system — not in the enum, not in the master table.

### Why parking was safer than leaving them on

A query for a violation code that does not exist returns **zero rows**. In
`viol-r9-no-product-decision`, zero prior removals matches `priorRemovalsThisCycle < 1`,
which is the **REMOVE** row. So an unbuildable concern would not have failed safe — it would
have auto-removed violations it never actually checked.

That is the general shape to watch for: an empty result is not a neutral value. It is a
specific answer, and here it was the generous one.

### A second problem, separate from the codes

`VIOL_R5_PERIODS` needs to know a leave was a **period** leave. The system does not record
leave reasons. `leaveTypeCode` comes from one enum:

```java
ESpLeave { FULL_DAY(1), FIRST_HALF(2), SECOND_HALF(3) }
```

That is duration. The reason is `SPLeave.purpose`, free text typed by the partner, matched
as a category in exactly one place against the literal `"VIOLATION"`.

`SpTicketingConcernEnum.GIRLS_PROBLEM(29)` exists, so the **ticket** records it even though
the **leave** does not. If period leaves arrive through that concern, the count is over
tickets rather than leaves.

### To bring violations back, later

1. Confirm the violation types exist — new rows in `tbl_violation_master`, or a decision
   that R5/R9 map onto existing `SP_1xx` codes.
2. Put the real code into the fact provider's query.
3. `UPDATE concern_catalogue SET active = TRUE WHERE l2_code IN ('VIOL_R5_PERIODS','VIOL_R9_NO_PRODUCT');`
4. For R5 only: decide where the period-leave count comes from — BOTIn's own `sp_counter`,
   or ticket concern 29.

The decision tables, their rules, and their tests are already written and green. Unparking
is data and one query, not a build.

### What the POC loses

Six active concerns instead of eight. Every **tier** is still demonstrated — T0, T1, T2, T3 —
but **UPHOLD is no longer reachable**: it was only produced by these two.

| Outcome type | Still covered? | By |
|---|---|---|
| BOT | yes | Transport, Recharge, Product delay |
| TICKET | yes | all of them |
| SELF-SERVE | yes | Forget MPIN |
| REROUTE | yes | VIOL_R4_OTHERS, Free-text triage |
| **UPHOLD** | **no** | — was R5 and R9 only |

If UPHOLD needs to be in the demo, the cheapest fix is activating
`VIOL_T1_SAMEDAY_LEAVE` (`SP_102`, which exists) — it produces BOT, TICKET **and** UPHOLD,
and its rules are already in the concern mapping.

---

## D-B. Transport reimbursement — Rs50/km or the hub slabs?

`transport-not-received-decision` pays `distanceBeyondRadiusKm × 5000 paise`, from the
concern mapping.

The live system prices transport in **distance bands**: `tbl_servicehub_transportation`
holds `start`, `end`, `charges` per hub, and `JobController` derives the hub radius as
`MAX(end)` across those bands. So the same table is both the geometry and the pricing.

Unresolved: do those bands price the **partner's reimbursement**, or only what the
**customer** is charged? If the former, `× Rs50` is the wrong number on the highest-volume
concern in the build.

**Blocks Phase 6.** Needs whoever owns transport policy, not a query.

---

## D-C. The Transport path selector

Transport rules 2–8 are labelled Path 1 / 2 / 3. Nothing in the mapping, and nothing in the
empapi source, says what assigns a claim to a path. Without a selector the paths overlap: a
claim that was cancelled by an agent **and** lies outside the hub radius satisfies both rule
5 and rule 9, which pay different amounts, and `FIRST` silently takes the higher row.

Currently implemented in `TransportPathSelector` as an assumption, in one method with its own
test. Replacing it is a single edit.

**Blocks Phase 6.** Same owner as D-B.

---

## D-D. `tbl_sp_ticketing` already exists

The live service has `tbl_sp_ticketing`, `tbl_sp_ticketing_executive_comment` and
`SpTicketingStatusEnum`. BOTIn creates its own `ticket` table.

Two ticket stores for the same partners is a reporting problem at best and a reconciliation
problem at worst. V4 adds `legacy_concern_code` so the two taxonomies can at least be joined,
but whether BOTIn should write into the existing table instead is a real question.

**Tied to D-1, hosting.** Should be answered before BOTIn is put in front of partners, not
before the POC is assessed.

---

## D-E. Transport credits can be reversed

`tbl_sp_tranactions` contains both:

```
CREDIT / TRANSPORT
DEBIT  / TRANSPORT
```

and the source carries the string `"Unused transport for "`. So transport is clawed back in
some cases.

`alreadyCredited` therefore cannot be `EXISTS(CREDIT)` — that would tell a partner "already
paid" after the money was taken back. The provider must compute the **net position** per
order.

**Handled in Phase 4, step 46.** Recorded here so it is not lost if that step is rewritten.

---

## D-F. Testing against real Postgres

Eleven columns that should be `TEXT` are `VARCHAR(4000)` so the same DDL runs on H2. The
clean fix is Testcontainers with real Postgres, which adds a Docker dependency.

**Deferred deliberately**, so the POC does not gain infrastructure while it is still
proving itself.
