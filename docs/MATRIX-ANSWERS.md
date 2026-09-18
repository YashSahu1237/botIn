# What the Decision Matrix answered

The concern mapping ("PX Outbound — YM Sathi — Decision Matrix") settled six open questions,
corrected two live defects, and overturned the premise behind the largest parked item.

`mvn test` passes **218/218** with all of it implemented.

---

## Answered, and implemented

| # | Question | Answer | What changed |
|---|---|---|---|
| **Q2** | Transport rate — ₹50/km or the hub slabs? | **`(distance − radius) × ₹50`**, measured from the radius EDGE, capped at ₹300 | `computedAmountPaise` is now computed. **The ₹300 cap is live against real data.** DEFERRED **D-B closed** |
| **Q3** | What assigns a claim to Path 1/2/3? | Path 1 = customer paid, not in wallet · Path 2 = cancellation · Path 3 = distance | `derivePath` already did exactly this. **D-C closed**, no code change |
| **Q6** | Where is the job's lat/long? | **`cs_address`** — the customer address, not `tbl_order` | A place to look. Not yet wired |
| **Q7** | How is Express decided? | **An attribute on the order**, not a pincode lookup | The `tbl_settings` pincode mechanism is **deleted** |
| **Q9** | What does "SP not satisfied" mean? | *"Create ticket on SP request"* — a conversational turn, not a score | **Recharge rule 3 deleted**, `spSatisfied` removed from the fact contract |
| **Q10** | TAT rules | *"Express: same day if before 3 PM, next day if after. Standard: ~3 days. Deadline 11 PM."* | `DeliveryTatService` rewritten |

### Two live defects, both erring the same way

Express was a flat **one day**, and 11 PM was read as an **ordering cut-off** that pushed the
clock to the next day. Both made an order look **more on time than it was**.

That is the forgiving direction, which is why it would never have looked broken. It would
simply have told partners to keep waiting on orders that were already late — **quietly
deflecting complaints that deserved a ticket**, in the concern with the second-highest volume
in the build.

### One rule deleted rather than implemented

Recharge rule 3 read `payuStatus = FAILED AND spSatisfied = false`. The matrix confirms the
behaviour — but **the system already delivers it**: the bot answers, the partner says it did
not help, and trigger A escalates that same ticket. Keeping both would be two mechanisms for
one behaviour. It was also permanently dead: the table is evaluated *before* satisfaction is
ever asked, so the input could only ever have been null.

### One cost taken on deliberately

The transport rate now lives in **configuration and in the decision table**. It has to: the
₹300 cap is a ROW in that table, so the amount is one of its inputs, and a rate cannot come out
of the thing it is an input to. `TransportRateConsistencyTest` asks configuration and the
running engine separately and fails if they differ — it does not share a constant, because two
values from one source agree with each other while disagreeing with reality.

---

## The premise behind D-A was wrong

**R1–R13 are not violation types. They are sub-reasons under Type 2.**

| Decision Matrix | `tbl_violation_master` |
|---|---|
| Type 1 — Same-Day Leave with Active Job | `SP_102` Same-Day Leave with Active Job |
| **Type 2 — R1…R13** | `SP_103` Job Reassign / Unassign / SP defaulter |
| Type 3 — Customer Cancellation / Denied on OBD | `SP_104` Customer Cancellation / Denied on OBD |
| Type 4 — Training Session Missed | `SP_105` Training Session Missed |
| Type 5 — Job Reject | `SP_101` Job Reject |
| Type 6 — Job Rejected over call | `SP_106` Job Rejected on Call |

Five are near-exact name matches. There was never meant to be an `SP_1xx` code for "period
leave" — **R5 is a reason recorded against an `SP_103` violation**. The matrix also confirms a
reason field exists: *"R4 Others — agent marked 'Others', no structured reason captured"*
implies the other twelve are structured.

**Phase 8 was buildable in principle.** It needed one thing: which column holds the sub-reason.
That column could not be located, and on 15 September violations were taken out of POC scope
rather than held open. `docs/SCOPE-VIOLATIONS-OUT.md` records the decision and its cost.

---

## Still blocked — four things, none of them engineering

| # | What is needed | What it unblocks | Who |
|---|---|---|---|
| ~~**N1**~~ | ~~Which column on `tbl_sp_violation_details` holds the R1–R13 sub-reason~~ **CLOSED 15 Sept — violations removed from POC scope.** See `docs/SCOPE-VIOLATIONS-OUT.md` | — | — |
| **N2** | Which `unassign_status_code` value is NR, and which is CR | Transport rules 5a/5b, 6, 7. NR **auto-credits** and CR **denies**, so the wrong mapping pays the wrong partners | Schema |
| **N3** | Which column links a wallet credit to a PayU transaction | `alreadyCredited` for recharge. A wrong "not yet credited" **pays twice** | Schema |
| **N4** | The `SELECT`-only GRANT on all three catalogs | 15 of 26 rules. **The highest-value item on the list** | DBA |

Plus two carried forward: **D-1 hosting** (the Decision Brief and the Engineering Design
disagree), and **partial-kilometre rounding** — the matrix marks it OPEN, and the code prorates
because that is the only option of the three that invents nothing.

---

## Still parked, and why

| What | Why | What unparks it |
|---|---|---|
| **Phase 8 — violations** | **OUT OF SCOPE**, not parked. Removed 15 Sept | Nothing — it is not waiting on anybody |
| **Phase 6 — transport payment** | The rate and the path selector are settled. What remains is N2, the job lat/long behind `cs_address` (Q6), and a mock credit service (step 55) | N2 + wiring |
| **Step 61 — reconciliation** | Not reached. Buildable now | Nothing |
| **The LLM track** | No key, no labelled text | Procurement |
| **Agent triggers B, D, E** | Out of POC scope by the plan | Production |
