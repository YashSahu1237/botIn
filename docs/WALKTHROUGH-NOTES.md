# Notes for the implementation walkthrough

Things noticed while demonstrating the system that deserve a proper discussion rather than a
one-line answer. Raised, deliberately not resolved. **Nothing here is a bug** — each one is a
design decision, an inconsistency, or a question that only becomes visible once somebody watches
the system explain itself.

Added to as they come up. Keep it; the list is the agenda.

---

## 1. `transportPath` is decided BEFORE the table, and the table can ignore it

Claim 7001 shows `transportPath = PATH_3` even though it is plainly a transport claim that was
already paid. That is the path selector doing what it is written to do: customer-charged AND
not-yet-credited → Path 1; otherwise a cancellation → Path 2; otherwise Path 3.

It does not affect the answer — row 1 ("already paid") fires first and never reads the path — but
a reviewer sees a fact that looks wrong sitting next to a correct decision.

**Worth discussing:** whether a derived fact that the winning row ignores should be shown at all,
and whether `derivePath` belongs before the table or as a first column inside it. The real
path-selector rule is still unanswered (DEFERRED D-C), so this is open anyway.

## 2. The rule numbering in the comments has drifted from the row numbers

The transport table's comments read `1, 2, 3, 4, 5a/5b, 6, 7, 8, 9, 10` while the rows are
`1..11`. The `5a`/`5b` pair is one concept split across two rows — correctly, because a
comma-separated list of values failed to evaluate and took the whole table down with it.

So the numbers a reader sees in the description column do not match the numbers beside them, and
both differ from the concern mapping's own numbering, which is what the comments are quoting.

**Worth discussing:** whether the comments should stop numbering themselves and name the mapping
rule explicitly instead (`[mapping rule 5]`), so three numbering schemes stop competing.

## 3. `FORGET_MPIN` has a `dmn_key` that nothing evaluates

The catalogue row names `forget-mpin-decision`, the file exists and deploys, and the startup guard
is satisfied. But `forget-mpin.bpmn20.xml` is a single service task — it never consults the table.

Consequences: the explanation panel is correctly empty for the one case somebody is most likely to
click first, and the catalogue contains a pointer that implies a relationship that does not exist.

**Worth discussing:** route it through the generic process like everything else, or drop the
`dmn_key` and let the row say what is true.

## 4. The console can show ticket state for any partner id

`/console/tickets/{spId}` takes any id and returns the ticket row. That is fine for a POC
inspection tool behind a profile, and it is not fine anywhere else.

**Worth discussing:** what this becomes in production — an agent-facing view with access control,
or deleted.

## 5. The decision trace is in memory and bounded

Two hundred conversations, oldest evicted, lost on restart. Deliberate: the durable record is
`ticket`, `ticket_action` and Flowable history; this is the explanation, not the audit.

**Worth discussing:** whether "why did the bot decide this" is a production requirement — for
agents, for disputes, for a partner asking twice — and if so, that it needs a table and a
retention policy rather than a map.

## 6. `DecideDelegate` now calls `decideExplained` on every decision

Every real decision, including every one that moves money, goes through Flowable's audit-trail API
rather than the plain one, so the trace can name the row that fired. `DecisionExplainedTest`
asserts both paths return identical answers.

**Worth discussing:** whether explanation should be always-on in production or switchable, and
what the audit trail costs at volume.

---

## 7. PARKED 15 Sept — two wrong column names, found by the UAT probe

**Status: known, located, not yet fixed. Parked at the project owner's request.**

The `SELECT` grant landed (N4 closed) and `tools/uat-probe.sql` ran against UAT. Two
column names that the code had marked `inferred` rather than `confirmed` do not exist:

| Assumed | Reality | Cost while unfixed |
|---|---|---|
| `ysmdm_users.tbl_order.arrived_at300_m` | does not exist | **MySQL 1054 kills the whole SELECT.** Every Transport case escalates to a human — it does not degrade, it stops |
| `ysmdm_users.tbl_order.order_type` | does not exist | Express detection on `PROD_DELIVERY_DELAY` cannot work |

Neither is a reasoning error. Both facts are **pure column reads**, so the column name is
the entire logic — one string constant each, plus flipping the `requiredColumns()` marker
from `inferred` to `confirmed` in the same edit, or the startup schema probe stops being
worth trusting.

**To unpark:** run `tools/uat_probe.py --sql tools/uat-probe-2.sql`. Section A dumps every
column on `tbl_order`; the real names will be in that list. Note the table mixes conventions
(`unassign_status_code` beside `nrTicketCount`), so the name may be snake_case or camelCase.

**One possibility to keep open:** there may be no Express column on the order at all. If so,
Decision Matrix answer Q7 was wrong, and that is a finding to take back to the concern owner
rather than a bug to fix.

**Also open from the same probe run:** zero orders have `transport_charges > 30000`. Either
no claim ever exceeded Rs300, or the column is in RUPEES rather than paise — in which case
the cap row is comparing against a number 100x too large and can never fire. Probe 2 checks
both readings.


---

## 8. WHERE THE ONE-POINT-AT-A-TIME WALKTHROUGH STOPPED — 15 September

The agreed format: one point at a time, explained with what is needed and why, then a
decision, then the next point.

| Point | Status |
|---|---|
| **1 — N4, the read grant** | **CLOSED.** `SELECT` on all three catalogs, verified by `SHOW GRANTS`. Nothing more, nothing less |
| **2 — N1, the violation sub-reason** | **CLOSED by scope decision.** Violations removed from the POC — `docs/SCOPE-VIOLATIONS-OUT.md` |
| **3 — N2, NR vs CR** | **OPEN, and REFRAMED.** See below |
| 4 — N3, the PayU link | not yet raised |
| 5 — partial-kilometre rounding | not yet raised |
| 6 — `cs_address` wiring (Q6) | not yet raised |
| 7 — D-1 hosting | not yet raised |
| 8 — the six items above in this file | not yet raised |

### Point 3 was reframed by the project owner, and the reframing matters

`CANCELLED_BY_AGENT`, `CANCELLED_CR` and `CANCELLED_NR` are **order statuses**, read from
`tbl_order.order_status_code` — **not** from `unassign_status_code`, which is what
`cancellationOf()` maps from today.

`order_status_code` is already SELECTed by the Transport query, already declared in
`requiredColumns()`, **and then discarded** — the result map keeps `transportCharges`,
`arrivedAt300M`, `unassignCode`, `cashback` and `hubId`, and drops the status.

So rules 5a, 6 and 7 were unreachable for a reason that had been misdiagnosed: not "the
code-to-vocabulary mapping is unconfirmed" but **"we are reading the wrong column."**

**What is still needed:** which VALUES of `order_status_code` mean NR, CR and by-agent — and
whether `unassign_status_code` still matters alongside it (it looks like it records *how* the
order ended, where the status records *what* the outcome was). `tools/uat-probe-3.sql`
answers both, and may settle NR vs CR from the data via `nrTicketCount` / `crTicketCount`.

### Probes written and waiting to be run

| File | Answers |
|---|---|
| `tools/uat-probe-2.sql` | the two wrong column names · paise vs rupees on `transport_charges` · N3, the PayU link · `cs_address` for Q6 |
| `tools/uat-probe-3.sql` | N2 — the `order_status_code` values |

Run with `.venv-probe/bin/python tools/uat_probe.py --sql tools/uat-probe-N.sql`.
