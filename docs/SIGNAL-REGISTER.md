# SIGNAL AVAILABILITY REGISTER

Which facts are **real today**, which are **absent**, and what each absence costs a
partner. Maintained as providers are written — a new fact adds a row here in the same
change that adds it to `factKeys()`, and `SignalRegisterTest` fails if it does not.

This is the document to read before believing a demo. A decision table can look complete
and be half unreachable, because a rule whose input is always null does not error — it
just never fires.

---

## The headline

Across the **6 active concerns**, the decision tables hold **26 rules**.
**15 can fire today. 11 cannot**, because a fact they read is never populated.

| State | What resolves without a human |
|---|---|
| **Today, as the service actually runs** (`UAT_ENABLED=false` — no SELECT grant yet) | **`FORGET_MPIN` only.** 738 of 9,542 known monthly cases, **7.7%**. Every other concern's facts come back all-null and land on its catch-all |
| **With the UAT grant in place, and nothing else changed** | 15 of 26 rules become reachable. Transport and Recharge still send their largest case classes to agents — see below |

Known monthly volume on the active concerns: **9,542**
(Transport 5,615 · Product delivery 2,306 · Recharge 883 · Forget MPIN 738; the two
free-text concerns have no volume recorded in the mapping).

**Nothing here is broken.** Every one of the 11 dead rules is dead because a signal is
missing, and in every case the fallback is a human rather than a wrong answer. That is
the design working. The register exists so the gap is a number rather than a surprise.

---

## Status vocabulary

| Status | Meaning |
|---|---|
| **LIVE** | populated from a confirmed source; the rules reading it can fire |
| **LIVE (name inferred)** | populated, but the physical column name was inferred rather than verified. `UatSchemaProbe` reports the truth at startup |
| **PARTIAL** | populated, but not across its full range — some values it is meant to take never occur |
| **DERIVED** | computed here, not read. Correct only if the rule behind it is correct |
| **ABSENT** | always null. Every rule reading it is unreachable |
| **NOT A UAT FACT** | comes from somewhere other than UAT, and that somewhere does not exist yet |

---

## TRANSPORT_NOT_RECEIVED — 5,615/month · 5 of 11 rules live

The highest-volume concern in the build, and the one with the largest gap.

| Signal | Source | Status | Rules it gates | If absent |
|---|---|---|---|---|
| `alreadyCredited` | `ysmdm_admin.tbl_sp_tranactions` (action, subaction, orderid, amount — all confirmed) | **LIVE** | rule 1 | — |
| `arrivedAt300metre` | `ysmdm_users.tbl_order.arrived_at300_m` | **LIVE (name inferred)** | rules 4, 5a, 5b, 6, 7 | if the name is wrong the query throws. The probe names it at startup, before a partner meets it |
| `lastMinCashbackCredited` | `ysmdm_users.tbl_order.cashback` (confirmed) | **LIVE** | rules 6, 7 | — |
| `transportPath` | derived — `TransportPathSelector` | **DERIVED** | rules 3, 4, 5a, 5b, 6, 7, 8, 9 | never null, which is the risk: it always routes, so a wrong rule is invisible. **The selector is an assumption, not a documented rule** (DEFERRED D-C) |
| `cancellationStatus` | `ysmdm_users.tbl_order.unassign_status_code` (confirmed) | **PARTIAL** | rules 5a, 5b, 6, 7 | only codes 3 and 4 map, both to `CANCELLED_BY_AGENT`. **`CANCELLED_NR` and `CANCELLED_CR` are never produced** — 3 rules unreachable |
| `distanceBeyondRadiusKm` | hub geometry resolves; **the job's own lat/lng does not exist on `tbl_order`** | **ABSENT** | rules 8, 9 | **the entire Path 3 pair is unreachable** — and Path 3 is the selector's fall-through, so most claims land there and reach an agent |
| `computedAmountRupees` | **DERIVED, and the rate is settled.** Paths 1/2 use the customer's transport charge from the order; Path 3 is `(distance − radius) × ₹50`, confirmed by the Decision Matrix. DEFERRED D-B is closed | **DERIVED** | rule 2 (the cap) and every paying row | **the ₹300 cap is live.** One assumption remains, isolated in `kilometresCharged`: partial kilometres are prorated, because the matrix marks rounding as OPEN and prorating invents nothing |

**Dead rules:** 2 (the cap) · 5a (NR) · 6 and 7 (both CR rows) · 8 and 9 (both Path 3 rows).

**What a partner gets today, with UAT on:** already paid → told so. Customer paid transport
that never arrived → credited. Agent-cancelled → credited or denied correctly. **Everything
else → an agent**, including every ordinary-travel claim, because Path 3 cannot be measured.

> **This concern must not be switched on for auto-credit while `computedAmountRupees` is
> absent.** The cap is the only row protecting the paying rows, and it cannot fire. This is
> a gate on Phase 6, not a note.

---

## RECHARGE_DEBIT_NO_CREDIT — 883/month · 4 of 7 rules live

| Signal | Source | Status | Rules it gates | If absent |
|---|---|---|---|---|
| `payuStatus` | `ysmdm_employees.tbl_payu_transaction_details_for_sp.status` (confirmed), normalised by `PayuStatusNormaliser` | **LIVE (link names inferred)** | rules 1, 2, 3, 5, 6 | the two lookup columns (`sp_id`, `order_id`) were inferred from camelCase fields. The probe checks them |
| `alreadyCredited` | **no confirmed column links a wallet credit to a PayU transaction**, and the recharge sub-action value has never been observed | **ABSENT** | rules 4, 5 | **the only money-moving row in this concern is unreachable**, and so is its duplicate guard |
| `amountRupees` | the gateway — `tbl_payu...` when UAT is on, the **mock gateway** when it is off | **PARTIAL** — real only with UAT; the mock supplies it for the money-path tests | declared by the table, branched on by no rule; read by `WalletCreditService` | a credit of zero is REFUSED rather than treated as nothing to pay, and the case goes to a person. Paying zero silently would close the ticket having done nothing |
| ~~`spSatisfied`~~ | **REMOVED, with the rule that read it.** The Decision Matrix confirms the behaviour — *"PayU = Failed and SP not satisfied → create ticket on SP request"* — and the system already delivers it through CSAT and trigger A. A table row as well would be two mechanisms for one behaviour, and it could only ever have read null: the table is evaluated *before* satisfaction is asked | — | nothing. The partner who says the answer did not help still gets a person, on the same ticket |

**Dead rules:** 3 (failed + dissatisfied) · 4 (already credited) · 5 (**auto-credit wallet**).

**What a partner gets today, with UAT on:** no gateway response → ticket. Gateway has no
record → ticket. Failed → told to recharge again. **Successful → an agent**, because the
row that credits them needs `alreadyCredited` and null matches neither `true` nor `false`.

On the observed gateway data that is **68% of rows** (`success` 74,796 of ~111,000). The
26% carrying no status at all (26,374 rows) are the ones that *do* resolve, via rule 1.

---

## PROD_DELIVERY_DELAY — 2,306/month · 3 of 3 rules live

The only UAT-reading concern with no unreachable rule. Its gap is **accuracy**, not
reachability, which is the more dangerous of the two because nothing about it looks wrong.

| Signal | Source | Status | Rules it gates | If absent |
|---|---|---|---|---|
| `pastElevenPmDeadline` | `ysmdm_admin.tbl_sp_order` (spid, created_at, delivered_at, order_status_code — confirmed) via `DeliveryTatService` | **PARTIAL** | rules 1, 2 | null when no order row is found, which reaches the catch-all — correct, since telling a partner with no order to keep waiting is worse than admitting we do not know |
| — Express/Standard | **`ysmdm_admin.tbl_sp_order.order_type`** (name inferred). The Decision Matrix lists *"order type (Express/Standard)"* as a backend check — it is an attribute of the ORDER, not a pincode lookup, which is what this provider used to do | **LIVE (name inferred)** | the TAT calculation | an unreadable value degrades to STANDARD — the slower clock, so a partner can only ever be told to wait longer than they should, never that an on-time order is late |

**The cost:** an Express partner is judged against a 3-day clock instead of a 1-day one, so
they are told to keep waiting for up to two days after they should have been escalated. The
direction is deliberate — the gap can only make the service *slower* to call something late,
never faster — but it is still a partner waiting on a promise that has already lapsed.

**THE TAT RULES ARE NO LONGER ASSUMPTIONS.** The Decision Matrix states them: *"Express:
same day if before 3PM, next day if after. Standard: ~3 days. Deadline 11 PM."* Two earlier
readings were wrong and both erred the same way — Express as a flat one day, and 11 PM as an
*ordering* cut-off rather than the hour an order becomes late. Both made orders look more
on-time than they were, which is the forgiving direction: nothing would have looked broken,
it would simply have deflected complaints that deserved a ticket.

---

## FORGET_MPIN — 738/month · 1 of 1 rule live

| Signal | Source | Status |
|---|---|---|
| `l2Concern` | the request itself | **LIVE** |

Reads no database and cannot be wrong. **The only concern that is fully operational today**,
and the only one that works with UAT disabled. 100% deflected, no ticket, no agent.

---

## VIOL_R4_OTHERS and OTHER_FREETEXT_TRIAGE — 2 of 3 rules live each

| Signal | Source | Status | Rules it gates | If absent |
|---|---|---|---|---|
| `classificationMatched` | the classifier — **does not exist.** Phase 11, blocked on procurement | **ABSENT** (set to an explicit `false`) | rule 1 in both tables | every free-text case reaches a human |
| `classifierConfidence` | as above | **ABSENT** with a real model; the stub answers `0.95` on a fixture phrase and `0.0` otherwise | rule 1 in both tables | as above |
| `rerouteTarget` | the classifier's category, **checked against the live catalogue** before it is ever used | **PARTIAL** — only fixture phrases resolve | rule 1 (declared in every row; read by `RerouteDelegate`) | no target means no reroute, and the case reaches a human. A target that is not an ACTIVE concern is discarded at the gateway — a model naming a seeded-but-unbuilt concern must never move a partner into it |
| `riskFlagged` | `abuseFlag` or `riskFlag` from the classifier — **trigger E** | **ABSENT** with a real model; the stub sets it on three fixture words | **rule 0, the first row in both tables** | nothing is flagged, so risky text is judged on its category alone. Note the direction: this signal being absent makes the system LESS cautious, which is the opposite of every other gap in this register |

**Dead rule:** 1 (reroute) in each table.

These two are absent **by explicit decision rather than by accident**, and the difference
matters. The values are *set* to `false` and `0.0` rather than left null. Null would reach
the same catch-all, but by accident — and the day a classifier is wired in badly, "no
opinion" and "no confident match" behave identically right up until they don't. A wrong
reroute costs the partner an entire second journey through the wrong flow, so the absence of
a classifier has to read as a definite no.

---

## The blockers, ranked by what they cost

| # | Blocker | Costs | Who |
|---|---|---|---|
| 1 | **UAT user is not confirmed `SELECT`-only by GRANT** | everything. 8,804 of 9,542 monthly cases. Until this lands the service runs with UAT off and only Forget MPIN resolves | project owner |
| 2 | **The job's own latitude/longitude** — `tbl_order` carries none, and `tbl_fifty_metre_radius_log` records where the *partner* was, not where the *job* is | Transport rules 8 and 9, which is the fall-through path for most claims | schema |
| 3 | ~~The ₹50/km vs hub-slab rate~~ | **ANSWERED** by the Decision Matrix: `(distance − radius) × ₹50`, measured from the radius edge, capped at ₹300. Partial-km rounding is still marked OPEN in the matrix itself | — |
| 4 | **Which column links a wallet credit to a PayU transaction, and the recharge sub-action value** | Recharge rules 4 and 5 — the only money row and its duplicate guard. ~68% of 883/month | schema |
| 5 | **The `unassign_status_code` → NR / CR mapping** (`nrTicketCount` and `crTicketCount` on `tbl_order` say both concepts exist somewhere) | Transport rules 5a, 6 and 7 | schema |
| 6 | **The Transport path-selector rule** (DEFERRED D-C) | nothing is blocked — which is the problem. The assumption always produces a path, so a wrong one is silent | project owner |
| 7 | **Confirm `tbl_sp_order.order_type`** — the mechanism is now right, the column name is still inferred | Express detection. Until confirmed, every order is judged on the slower Standard clock | schema |
| 8 | **The classifier** | both free-text concerns beyond fixture phrases, and trigger E entirely. The contract, the client, the breaker, the validation and the reroute are all built and tested against a deterministic stub — only the model is missing | procurement |
| 9 | ~~CSAT (`spSatisfied`)~~ | **CLOSED.** The rule was deleted; CSAT and trigger A already do this | — |

Items 2, 4, 5 and 7 are all one question to whoever owns the schema, and together they
unblock 6 of the 11 dead rules.

---

## How this register is kept honest

- **`SignalRegisterTest`** asserts every key in every provider's `factKeys()` appears in
  this file. A new fact cannot be added without a line here saying whether it is real.
- **`UatSchemaProbe`** reports at startup which inferred column names were right and which
  were not. Its output is the evidence behind every "LIVE (name inferred)" above — promote
  a row to plain LIVE only after the probe has confirmed it against a real database.
- **`ProviderContractTest`** asserts that facts which could not be looked up still decide,
  and still never move money. That is what makes an ABSENT signal safe rather than fatal.

When a blocker clears: update the row, move the rule out of the dead list, add the case to
that concern's nested class in `DecisionTableTest`, and correct the headline count.
