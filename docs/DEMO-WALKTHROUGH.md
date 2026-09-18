# The demo, case by case — a presenter's script

How to run the POC in front of people, what to click, what each case proves, and the
sentence to say out loud. Written to be read beside the console, not instead of it.

---

## Starting it

```bash
cd <project root>
mvn spring-boot:run -Dspring-boot.run.profiles=demo,console \
    -Dspring-boot.run.useTestClasspath=true
```

Then open **http://localhost:8080/console**

**Both profiles are needed.** `console` renders the page; `demo` supplies the cases and the
controls that arrange misbehaviour — a gateway that dies, a kill switch that flips. Without
`demo` the acceptance-run card stays hidden, which is correct: those controls must not exist
anywhere a real partner can reach.

`useTestClasspath` is needed because H2 is test-scoped on purpose — the POC database is
Postgres, and the demo borrows H2 rather than promoting it. Set `BOTIN_DB_URL` to demo
against a real Postgres instead.

### The first thing to point at

An amber banner: *"Demo profile — every fact behind these answers is SYNTHETIC. Nothing is
read from a live system."*

**That banner is read from the server, not written into the page.** A demo where the audience
cannot tell evidence from invention is worse than no demo, because it produces confident
wrong beliefs. Say that the banner is authoritative and that if it ever disagrees with the
presenter, believe the banner.

### The three surfaces, and why they are the same thing

The seventeen cases are defined **once**, in `DemoScenarios`. Three things read that one
definition:

| Surface | For |
|---|---|
| The console | A reviewer, with a **Run** button per case |
| `tools/demo-run.sh` | The same run from a terminal; exits non-zero on failure, so it works in CI |
| `DemoScenarioRunTest` | The same run on every `mvn test`, so the cases cannot rot between demos |

If asked whether the demo is special-cased: two definitions would drift, and the first anyone
would know is a demo that passes in the terminal and fails on screen. There is one.

### Two buttons under every case

- **"why it answered that"** — the facts read, every rule marked matched / not matched, and
  the row that fired.
- **"what it touched"** — the client's own API calls, then every step of the process, in the
  order the engine recorded them, with the steps that did **not** run struck through.

Both are read back from what the run actually wrote. Nothing is re-derived: a second
evaluation would be a second answer, and the two can differ the moment a fact changes
underneath them — which is exactly when somebody is asking.

---

# CASE 1 — T0: a deflection that creates NO TICKET

**Concern:** `FORGET_MPIN` · **Partner:** `SP-DEMO-T0` · **Volume:** 738/month

### The checks

```
outcome: MESSAGE
status:  CLOSED_DEFLECTED
TICKET ROWS: 0
```

### What it proves

The BRD's central claim, made literal. The business case rests on *deflected volume
disappears from ticket counts*, and the only way that is true is if a T0 never creates a
ticket row at all. Not a closed one. Not a zero-touch one. **None.**

### What "what it touched" shows

**`forget-mpin · 3 of 3 steps taken`** — and a note explaining why this concern has its own
process:

> Three elements, no facts, no decision table and no Gate 1. It is the T0 proof, and that
> claim is worth more running through its own file than folded into the shared one. It is
> also the only concern whose answer is a deeplink rather than a message.

**There is no Gate 1 step in this process to strike through.** That is the strongest form of
the claim: not a ticket created and then closed, not one created with zero touches — no step
in this flow is capable of creating one. The claim is structural, not conditional.

### The full path, for when somebody asks "but what actually happens"

One HTTP call — `POST /help/sessions/{id}/input {"selection":"FORGET_MPIN"}` — and all of
this happens inside it, synchronously, before the response returns.

| # | What happens | Where |
|---|---|---|
| 1 | The `help_session` row is loaded. Not `OPEN` → refused | `HelpSessionService.input` |
| 2 | The row says `current_step = L2_SELECT`, so the input is read as an L2 choice. **The server decides what your input means, from where the conversation actually is** — not from what the client claims | `handleL2` |
| 3 | The `concern_catalogue` **table** is read: is it active, does it belong to the L1 chosen, what is its `process_key` | `CatalogueService` |
| 4 | Three checks — exists, active, belongs to that L1. Any failure gives a defined "not available" ending, never a 500 or an empty menu | `handleL2` |
| 5 | The session row is updated and **flushed to disk**. It must be committed before the engine starts, because the delegate will load it by id | `enterConcern` |
| 6 | The engine is started with `process_key` **read from the table** — nothing in Java says this concern runs this process | `ConcernProcessRunner` |
| 7 | The process runs `start → finaliseStep → end`. **Synchronously, inside this request** | Flowable |
| 8 | `finaliseStep` resolves the Spring bean named `forgetMpinDelegate` **by string** and calls it | BPMN `delegateExpression` |
| 9 | The delegate reads the session id from a process variable and builds the answer: a code, a Hinglish sentence, and a link **that comes from configuration** so the app team can move it without a backend release | `ForgetMpinDelegate` |
| 10 | The answer is written onto the session row and the conversation is closed as `CLOSED_DEFLECTED` | `SessionStepWriter.writeAndClose` |
| 11 | The process completes — and Flowable **deletes its runtime rows, variables included.** This is why step 10 exists and runs last: the answer cannot live in the engine, because the engine is about to forget it. The history rows survive, and are what "what it touched" reads | Flowable |
| 12 | The answer is read back **from the database row**, because the engine no longer has it | `readNextStep` |
| 13 | The response returns. The app switches on `nextStep.type` — `DEEPLINK` → a sentence and a tappable link. The client has never heard of this concern, of tiers, or of deflection | `SessionView` |

**What is in the database afterwards:** one updated `help_session` row · **zero** `ticket`
rows · **zero** `ticket_action` rows · the engine's runtime rows deleted · the engine's
history rows kept.

### The sentence to say out loud

> "738 cases a month. Today this is the **only** concern that resolves without a human,
> because it is the only one whose facts need nothing from the database. That is the 7.7%
> figure — it measures the read grant, not the design."

Say it before anyone else does the arithmetic. It is the number most likely to be quoted back
out of context.

### If asked "so it always says the same thing?"

Yes — a single-rule concern, which is why it is first. Every case after this has a table that
branches.

---

# CASES 2 TO 17 — the running order

Each is one row in the console with its own **Run** button. The grouping matters when
explaining what the seventeen are: they are **properties of the system**, not seventeen
different conversations.

## Distinct partner-facing outcomes

| # | Case | Fixture | What it proves |
|---|---|---|---|
| 2 | **T1 — the bot answers from data, and says no** | order `7004` | The partner never reached the job, so there is nothing to pay. **A correct refusal is as valuable as a payment** |
| 3 | **T2 — MONEY MOVES** | `DEMO-RCH-PAID` | PayU took the money, the wallet never saw it. The bot credits it and says so in words the partner can check against their balance |
| 4 | **T3 — the state we cannot read goes to a person** | `DEMO-RCH-UNKNOWN` | 26% of real rows have no gateway response. Nothing moves on a state we cannot read: nobody is paid and nobody is refused |
| 8 | **CAP ESCALATION — Rs450 becomes a ticket, not a Rs300 payment** | order `7002` | A cap is a **handover**, not a clamp. Clamping would be a silent underpayment nobody ever sees. Check the ticket: the partner hears the ordinary agent sentence, and the **cause** is on the row |
| 10 | **ALREADY PAID beats everything, including the cap above it** | order `7001` | Row order is logic. This row sits above the cap deliberately |
| 11 | **SHARED SERVICE REUSE — one TAT function** | `SP-DEMO-20` / `21` | Two concerns, one definition of "late". If they ever differed, a partner would be told their order is on time **and** that it is late |

## The same conversation, different world state — the four that matter most

These run the identical partner journey four ways. **The partner does the same thing every
time; what changes is what is true behind them.** This is where systems are normally wrong.

| # | Case | What it proves |
|---|---|---|
| 5 | **IDEMPOTENCY — the same claim raised twice pays once** | Two conversations a week apart are two complaints and correctly two tickets. The guard is keyed on **PayU's** order id, not ours, so it collides anyway |
| 6 | **EXTERNAL FAILURE — the gateway dies mid-session** | The call may have succeeded and failed to tell us. So: record, escalate, and **never retry**. A retry on an unknown outcome is a second payment |
| 7 | **THE KILL SWITCH — flipped live, no restart** | Off does not mean fail. It means *do not automate* — the partner is still served, by a person. That is the difference between a kill switch and an outage |
| 9 | **THE REVERSAL — a clawed-back credit is NOT payment** | `EXISTS(CREDIT)` is the obvious implementation and it refuses this partner money they never kept. Net position reads it correctly. **UAT confirms `DEBIT/TRANSPORT` is real, not hypothetical** |

## How a partner arrives

| # | Case | What it proves |
|---|---|---|
| 12 | **FREE TEXT AT THE ENTRY POINT** | No L1, no L2, just words. The partner is handed to the **triage** concern and its table routes them — the same path in-concern text takes. Two routing mechanisms would mean two places to fix a routing bug |
| 13 | **FREE TEXT INSIDE A CONCERN — reroute** | The partner picked one thing and described another. The session **moves** rather than forking: one conversation, one row. "What it touched" shows this as **two process runs under one journey** |

## After the answer

| # | Case | What it proves |
|---|---|---|
| 14 | **CSAT IS ASKED on every bot resolution** | The only number that says whether automating a concern was a good idea rather than merely a busy one. `csatExpected` — **nothing is held open waiting for a rating** |
| 15 | **TRIGGER A — a rejected deflection escalates** | Not a new complaint; the **same ticket**, marked as one automation got wrong. Asserted before/after, and by ticket **id** — same count is not the same row |
| 16 | **THE BOUNDING RULE — a satisfied partner is NOT offered an agent** | The rule that protects the whole target. Offering an agent to somebody the bot already helped is how a deflection rate quietly becomes a handover rate. It lives in **closure logic, not the UI** — a suppression rule the client owns is one an old app version can ignore |

## The agent side

| # | Case | What it proves |
|---|---|---|
| 17 | **AGENT CONNECT — claim, read the context, complete** | The agent opens the **computed facts**, not the transcript: what we already know, so the partner is not asked to repeat themselves. The process **sleeps in the database** until the task is claimed |

---

## What these 17 do NOT cover — say it before you are asked

| Not shown | Why |
|---|---|
| **UPHOLD** — the bot refusing an appeal with a reason and no ticket | Produced only by the violation concerns, now out of POC scope |
| **State change without money** | Same |
| **The concern-level durable counter** | Same. The mechanism is built with nine passing tests; it has no concern to attach to |
| **Editing a DMN threshold and redeploying that file alone** | Proven by `DmnHotRedeployTest` against a running engine. There is no redeploy endpoint and there should not be one |
| **All 26 rules** | Only **15 can fire** today, and these cases reach a subset. For Transport: rules 1, 2 and 4 plus the reversal path — not 5a/5b (blocked on N2), not 8/9 (Path 3 distance is null) |
| **Transport actually paying** | `ActionRegistry` holds only `AUTO_CREDIT_WALLET`. Transport decides T2 correctly and then escalates, because nothing performs the payment. That is step 55 |

**Coverage in one line:** all 6 active concerns, all four tiers, 4 of 5 outcome types,
17 of the 20 coverage-matrix rows, 15 of 26 rules reachable.
