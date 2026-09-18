# BOTIn POC — what can be shown today, and what is pending

Two lists. The first is what a reviewer can be shown right now. The second is what stands
between here and the build plan's exit criteria.

**Position:** 78 steps done · 10 blocked · 5 parked · 6 out of scope · 1 not started.
Both stop conditions passed — process durability and money safety. The architecture question
is answered; what remains is evidence.

**The exit criterion not yet met:** *"All 26 rules reachable and correct through the API."*
**15 of 26 can fire.** Everything in Part 2 is that gap.

---

# PART 1 — READY TO SHOW NOW

## 1.1 The scripted demo run

The 17 cases cover **17 of the 20 rows** in the coverage matrix. They are not a
walkthrough — every case asserts what it expected and **the run reports a failure rather
than hiding it.** A demo that can fail in the room is the only kind worth watching.

They are defined **once**, in `DemoScenarios`, and three surfaces read that one definition:

| Surface | What it is for |
|---|---|
| **The console**, `/console` | A reviewer sees all 17 with a **Run** button each, or **Run every case**. Each row shows its checks — expected beside actual — and a link into the rule trace for the exact conversation that case just had |
| **`tools/demo-run.sh`** | The same run from a terminal. Still exits non-zero on a failure, so it is usable in CI |
| **`DemoScenarioRunTest`** | The same run on every `mvn test`, so the cases cannot quietly rot between demos |

```
Terminal 1:  mvn spring-boot:run -Dspring-boot.run.profiles=demo,console \
                 -Dspring-boot.run.useTestClasspath=true
Terminal 2:  ./tools/demo-run.sh        # or just open http://localhost:8080/console
```

> **CORRECTION, 15 September.** An earlier version of this document said all 17 cases were
> asserted and passing. **Three of them were not.** Cases 1, 8 and 15 read
> `/demo/tickets/{spId}/count` and `/demo/tickets/{spId}/latest` — endpoints that **did not
> exist**. Each returned a 404 body, `jq` read `null`, and the assertion compared `null`
> against a string and failed. The script could never have exited zero. Both endpoints are
> now built. Worse, `DemoIsolationTest` asserted those paths return 404 without the demo
> profile — and passed, because they returned 404 with every profile. **A test that passes
> for the wrong reason is worth less than no test**, because it occupies the place where a
> real one would go.

| # | Case | What a reviewer sees |
|---|---|---|
| 1 | **T0 — a deflection that creates NO TICKET** | Gate 1 held. The ticket count is asserted at **zero** — the tier model's core invariant, as a number |
| 2 | **T1 — the bot answers from data, and says no** | A reasoned denial, no ticket, no human |
| 3 | **T2 — MONEY MOVES** | A wallet credit performed end to end |
| 4 | **T3 — the state we cannot read goes to a person** | Missing signal → human, not a guess |
| 5 | **IDEMPOTENCY — the same claim raised twice pays once** | Second attempt returns `INFORM_ALREADY_CREDITED` |
| 6 | **EXTERNAL FAILURE — the gateway dies mid-session** | Goes to a person, and the **retry is refused** — a gateway may have paid and failed to say so |
| 7 | **THE KILL SWITCH — flipped live, no restart** | Togglz off → the next request routes to T3. Nothing in flight disturbed |
| 8 | **CAP ESCALATION — Rs450 becomes a ticket, not a Rs300 payment** | The cap hands over rather than silently underpaying. The ticket carries **why** |
| 9 | **THE REVERSAL CASE — a clawed-back credit is NOT payment** | Net position, not `EXISTS(CREDIT)`. UAT confirms `DEBIT/TRANSPORT` is real |
| 10 | **ALREADY PAID beats everything, including the cap above it** | Row order is logic, demonstrated |
| 11 | **SHARED SERVICE REUSE — one TAT function, inside and past the deadline** | Two concerns, one definition of "late" |
| 12 | **FREE TEXT AT THE ENTRY POINT — no L1, no L2, just words** | Resolved without a menu |
| 13 | **FREE TEXT INSIDE A CONCERN — reroute** | Partner in the wrong place lands in the right one |
| 14 | **CSAT IS ASKED on every bot resolution** | `csatExpected`, with no process held open waiting |
| 15 | **TRIGGER A — a rejected deflection escalates to a person** | Same ticket, not a new one |
| 16 | **THE BOUNDING RULE — a satisfied partner is NOT offered an agent** | Decided in closure logic, not in the UI |
| 17 | **AGENT CONNECT — claim the task, read the context, complete it** | The process sleeps in the database and resumes |

**The three rows it cannot reach:** UPHOLD, state-change-without-money, and the
concern-level durable counter. All three came only from the violation concerns, now out of
scope. The counter mechanism itself is built and tested; it has no concern to attach to.

**One row is proven by a test rather than by the script:** editing a DMN threshold and
redeploying that one file. `DmnHotRedeployTest` does it against a running engine — there is
no redeploy endpoint and there should not be one.

## 1.2a The console — what the case touched, step by step

Beside every case, **"what it touched"** renders the whole path:

- **The client's own API calls**, with bodies, and which one starts the engine.
- **Every step of the process**, in the order the engine recorded it, with how long each
  took — and the steps that did **NOT** run, struck through.

The shape is read from the **deployed BPMN model**, so a concern with its own process shows
its own steps rather than a shared frame it never used. Each step carries a design note
saying *why the step exists*, beside evidence of *what this run did there* — the facts read,
the rule that fired, whether a ticket was created, whether money moved.

**The struck-through steps are the argument.** "Went to resolve" says little; "went to
resolve and therefore never touched escalationContext, agentHandoff or agentConnect" is the
tier model, visible. `JourneyTest` asserts exactly that, and asserts that some steps report
as not-taken — a view where everything looked taken would seem informative and prove nothing.

A reroute appears as **two process runs under one journey**, because the process is found by
business key rather than by a stored id.

## 1.2 The console — why the bot answered that

`/console` renders, for any session:

- the **facts that were read**, named
- **every rule** in the table, marked matched / not matched
- **which row actually fired**, and the comment above that row as its explanation
- the ticket, its tier and its action

This is what makes the difference between "the bot said no" and "the bot said no **because
row 4 matched and here is row 4**." It also carries a banner, read from the server, saying
whether the data behind it is real or fixtures — so nobody in the room has to take anyone's
word for which they are looking at.

## 1.3 The two questions the POC was built to answer

| Stop condition | Result |
|---|---|
| **Process durability** — does a conversation survive a restart? | **PASSED.** A process asleep at agent-connect survives a restart and a redeploy. No thread held, no memory held |
| **Money safety** — can the system pay twice? | **PASSED.** Attempt recorded and committed *before* the external call, outcome after, each in its own transaction. A failure becomes a person's problem, never a retry |

Each was demonstrated with its **negative case**, not just its happy path.

## 1.4 The engineering artefacts

- **223 tests green.**
- **The decision layer is data, not code.** Thresholds, tiers, outcomes and the cap live in
  `.dmn` files. `theCapLivesOnlyInTheTable` scans the Java source and fails if the cap value
  appears anywhere in it.
- **One BPMN process for all 38 concerns.** Adding a concern is a catalogue row, a fact
  provider and a `.dmn` file — never a new flow file.
- **`/internal/signals/register`** reports, per signal, whether a fact provider is reading
  real data or nothing, and why. The most valuable POC output is queryable rather than
  buried in a document that goes stale.
- **A read-only UAT probe** (`tools/uat-probe.sql`) that found two wrong column names before
  they ever reached a partner.
- **The UAT grant is `SELECT`-only by GRANT**, verified, not by convention.

## 1.5 What must be said out loud when showing it

The demo runs on **fixtures**, not UAT. The fixture supplies the raw row; the path selector,
the duplicate-credit guard and the decision engine then run for real against it. What is
faked is where the row came from and nothing else — and the demo profile **refuses to start**
beside a real UAT datasource, so nothing in a demo run can be mistaken for evidence about a
real partner.

**Today the service resolves 7.7% of known monthly volume without a human** — 738 of 9,542
cases, `FORGET_MPIN` only, because it is the one concern whose facts need no UAT read. That
number is the read-grant gap, not a measure of what the design can do. It should be quoted
with that sentence attached or not quoted at all.

---

# PART 2 — PENDING

## 2.1 Blocks a credible demo on real data

| # | What | Why it blocks | Unblocked by |
|---|---|---|---|
| 1 | **Two wrong column names** — `arrived_at300_m`, `order_type` | MySQL 1054 kills the whole Transport `SELECT`. On real data Transport does not degrade, it **stops** | probe 2 §A |
| 2 | **`transport_charges` — paise or rupees?** | No order exceeds 30000. If the column is rupees, the Rs300 cap compares against a number 100x too large and **can never fire** | probe 2 §E |
| 3 | **N2 — which `order_status_code` values mean NR / CR / by-agent** | Rules 5a, 6, 7 unreachable. **NR auto-credits and CR denies**, so a wrong mapping pays the wrong partners | probe 3, or the concern owner |
| 4 | **N3 — what links a wallet credit to a PayU transaction** | `alreadyCredited` for Recharge. A wrong "not credited" **pays twice** | probe 2 §C |
| 5 | **The transport credit service** (step 55, Phase 6) | `ActionRegistry` holds only `AUTO_CREDIT_WALLET`. Transport decides T2 correctly and then escalates, because **nothing can perform the payment** | build — nobody is blocking it |
| 6 | **Job lat/long behind `cs_address`** (Q6) | Path 3 distance is null, so rules 8 and 9 are dead | the column list, then build |

Items 1-4 are answered by probe output. Items 5 and 6 are build work with no dependency on
anyone.

## 2.2 Then, to close it out

- Rebuild and run the full suite. The fixes above will add tests.
- Re-run `tools/demo-run.sh` against **UAT** rather than fixtures, so the coverage matrix is
  asserted on real rows.
- **Step 81** — the only step never started: the CSAT response rate and the trigger-A rate,
  read from a run with real volume. Genuinely last, because it needs the run to exist.
- Confirm `/internal/signals/register` reports every signal as real rather than absent.

## 2.3 Decisions only — no code, but they gate the write-up

| Decision | Options |
|---|---|
| **UPHOLD in the demo?** | Add `VIOL_T1_SAMEDAY_LEAVE` (`SP_102` exists, needs no blocked answer) — or ship 4 of 5 outcome types and state it |
| **Partial-kilometre rounding** | Prorate (what the code does, and the only option that invents nothing), round up, or round down |
| **D-1 hosting** | The Decision Brief lists it open; the Engineering Design states it settled |

## 2.4 Explicitly not in the POC

| | Why |
|---|---|
| **The LLM measurement track** — 10 steps | No key and no labelled real text. Reported as **NOT ASSESSED**, never as a pass |
| **Violations** — 6 steps | Out of scope by decision. See `docs/SCOPE-VIOLATIONS-OUT.md` |
| **Agent triggers B, D, E** | Production work by the plan |

---

## The critical path, in order

1. Run **probe 2** and **probe 3** — items 1 to 4 collapse in one pass.
2. Apply the column and mapping fixes; flip every `inferred` marker to `confirmed`.
3. Build the transport credit service and wire `cs_address`.
4. Rebuild, full suite, then re-run the demo against UAT.
5. Step 81 off that run.
6. Settle the three decisions and update the assessment.

Item 5 in §2.1 is the one to flag hardest. Everything else makes a rule *correct*. That one
is the difference between a demo where the bot explains what it would do, and one where the
bot moves money in front of the room.
