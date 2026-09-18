# Scope decision — Violations are out of the POC

**Decided 15 September. This is a scope change, not a blocker and not a failure.**

---

## What was removed

| | |
|---|---|
| `VIOL_R5_PERIODS` | Type 2 R5 — period leave. Decision table, 3 rules, tests green |
| `VIOL_R9_NO_PRODUCT` | Type 2 R9 — no product / equipment. Decision table, 5 rules, tests green |
| Phase 8, steps 62–67 | violation lookup, mock `ViolationService.remove()`, UPHOLD as a first-class outcome, the shared-TAT checkpoint, the monthly counter test |
| The rest of Type 2 (R1–R13) | never built — 11 further concerns in the concern mapping |

**Nothing is deleted.** Migration V4 already set both concerns `active = FALSE` on 14 September,
so the running service, the demo script and the console are byte-for-byte unaffected. The rows,
the decision tables and their tests stay in the repository. This document is the record of the
decision; there is no code change to make.

## Why

The two buildable violation concerns read a violation row and match on a reason. The reason is
the R1–R13 sub-reason under violation type `SP_103`. **Nobody could confirm which column holds
it, or whether it is recorded in the database at all** — the numbering appears in the concern
mapping and nowhere in the schema.

Building on a guess was not an option. A query for a column that does not exist returns zero
rows, and in `viol-r9-no-product-decision` zero prior removals matches
`priorRemovalsThisCycle < 1`, whose outcome is **REMOVE**. The wrong guess would not have failed
safe — it would have auto-removed violations it never checked, silently, in the partner's favour.

Rather than hold the POC open on a question nobody could answer quickly, the concerns leave the
POC.

## What was NOT removed

| Kept | Why it survives |
|---|---|
| `VIOL_R4_OTHERS` | Reads **no violation data at all**. Its four inputs are classifier outputs — `classificationMatched`, `classifierConfidence`, `rerouteTarget`, `riskFlagged`. It is the free-text router to an agent, and it needs nothing from the violations schema |
| `DeliveryTatService` | Built as a shared service for Product delay **and** R9. Product delay still uses it. The sharing was the point and the test that proves it still runs |
| The haversine / hub-radius service | Shared with Transport Path 3, which is where the money is. Unaffected |

---

## Impact on the POC target

### On volume — none

The headline number does not move, in either direction.

**Violations carry no volume figure at all.** The column is empty for every violation row in
the concern catalogue — the June extract never attributed volume to them. The POC's 9,542-case
denominator is built entirely from four non-violation concerns:

| Concern | June cases |
|---|---|
| `TRANSPORT_NOT_RECEIVED` | 5,615 |
| `PROD_DELIVERY_DELAY` | 2,306 |
| `RECHARGE_DEBIT_NO_CREDIT` | 883 |
| `FORGET_MPIN` | 738 |
| **Total** | **9,542** |

So **7.7% resolved-without-a-human stays 7.7%**, and the post-grant figure is unchanged too.

### On the rule-count figure — none

§3 of the assessment counts rules across **the six active concerns**. R5 and R9 were already
outside that set when it was written, because V4 had deactivated them the day before. The
decision changes the status of that exclusion from *parked* to *out of scope*; it does not
change the count.

### On outcome-type coverage — this is the real cost

The stated POC target is *"every tier and outcome type."* Tiers are fine. Outcome types are not.

| Outcome type | Still demonstrated? | By |
|---|---|---|
| BOT | yes | Transport, Recharge, Product delay |
| TICKET | yes | all of them |
| SELF-SERVE | yes | Forget MPIN |
| REROUTE | yes | `VIOL_R4_OTHERS`, free-text triage |
| **UPHOLD** | **no** | was produced only by R5 and R9 |

**UPHOLD is a behaviour class, not a label.** It is the bot refusing an appeal, giving the
reason, and *not* creating a ticket — the one case where the bot says no and means it. Without
it the POC demonstrates the bot helping, deflecting and escalating, but never declining. It also
removes the only **state change that does not move money** (`REMOVE_VIOLATION`, T2); T2 is still
demonstrated, but only as a credit.

Three rows of the 20-row coverage matrix go with it: **UPHOLD**, **state-change-without-money**,
and the **concern-level durable counter**. The counter mechanism itself is built and tested — what
is lost is a concern to attach it to.

### On the blocker list — this is what it buys

**N1 is closed.** Four outstanding answers become three:

| | Status |
|---|---|
| ~~N1 — the R1–R13 sub-reason column~~ | **closed by this decision** |
| N2 — which `unassign_status_code` is NR vs CR | open |
| N3 — which column links a wallet credit to a PayU transaction | open |
| N4 — the `SELECT`-only GRANT | in progress |

Phase 8 moves from *six steps blocked on an unanswerable question* to *six steps out of scope*.
The POC is no longer waiting on anyone for violations.

---

## One decision left open

If UPHOLD needs to be in the demo, there is a cheap way back that needs **no blocked answer**.

`VIOL_T1_SAMEDAY_LEAVE` maps to `SP_102` *Same-Day Leave with Active Job* — a code that
**exists** in `tbl_violation_master`. It is Type 1, so it carries no R-sub-reason and does not
depend on N1. Its four rules produce **BOT, TICKET and UPHOLD**, and they are already specified
in the concern mapping.

| Option | Cost | Result |
|---|---|---|
| **A. Ship without UPHOLD** | nothing | POC covers 4 of 5 outcome types. Stated plainly in the assessment |
| **B. Add `VIOL_T1_SAMEDAY_LEAVE`** | one concern — 4 rules, a fact provider, tests | All 5 outcome types covered. Needs no answer from anybody |

Option B is the only violations work that was ever unblocked. It is recorded here so the choice
is deliberate rather than discovered during the demo.
