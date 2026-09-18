# CODE MAP

What every file is, how the pieces connect, and the two procedures you will
actually need: **adding a concern** and **changing a rule**.

Read section 1 once. Use sections 2–3 as a lookup. Sections 4–5 are recipes.

---

## 1. The mental model

One partner taps "Help" and one request walks a fixed path. Every concern walks
the same path — what changes between concerns is *configuration*, not code.

```
  app
   │  POST /help/sessions          (start)
   │  POST /help/sessions/{id}/input   (a tap)
   ▼
  api/                 turns HTTP into a call, and a NextStep back into JSON
   ▼
  session/             the state machine: L1_SELECT → L2_SELECT → CONCERN_SELECTED → ENDED
   ▼
  catalogue/           concern_catalogue row: "for this L2 code, use THIS process,
   │                   THIS fact provider, THIS decision table, THIS kill switch"
   ▼
  process/             Flowable starts the BPMN named by process_key
   │
   ├──► facts/         the fact provider reads UAT and returns a map of FACTS
   │                     (what is true — never what to do)
   │
   ├──► decision/      the DMN table named by dmn_key reads those facts and
   │                     returns tier + action + outcomeType
   │                     (what to do — never how to find out)
   │
   ├──► safety/        kill switch checked, money-moving attempts recorded
   │
   └──► SessionStepWriter
             writes nextStep onto the help_session ROW and closes the session
   ▼
  session/ re-reads that row  ──►  api/  ──►  app renders the step
```

### The four sentences that explain most of the design

**1. Facts and decisions are separated on purpose.**
A fact provider may not decide anything. A decision table may not look anything
up. The reason is who owns each: fetching correctly is engineering, concluding
correctly is business — and the business half will be tuned repeatedly, by
someone who should not need a deployment to do it.

**2. The client contract is one object: `NextStep`.**
The app never learns about tiers, DMN, Flowable or tickets. It receives a
`NextStep` — a MENU, DROPDOWN, TEXT, MESSAGE or CSAT — and renders it. Every
path through the system ends by producing one.

**3. `nextStep` lives in the database, never in process variables.**
A T0 process completes inside the same call that started it, and Flowable
*deletes* a completed instance's `ACT_RU_*` rows — variables included. So the
last thing any process does is write `nextStep` onto `help_session`, and the
session service reads it back from there. (ADR-001.)

**4. Gate 1 is the line that must not be crossed by accident.**
Gate 1 = a ticket row exists. A T0 deflection must never cross it: no ticket, no
action, no agent. That is the entire point of the tier model —
**T0** deflect · **T1** auto-resolve · **T2** auto-action (money/state) · **T3** agent.

### The one risk this codebase is built around

> **A name that does not exist behaves exactly like a value that is false.**

Five separate incidents, all the same shape: a Togglz flag that was never
declared, a process variable read after deletion, a DMN fact spelled
`arrivedAt300m` on one side and `arrivedAt300metre` on the other, an *absent*
variable versus a *null* one, and a DMN namespace bumped by a modeller. None
raised an error. Each would have quietly sent partners to agents, or worse.

Everything in `safety/`, the startup validators, and four of the test classes
exist to convert one of those silences into a loud failure. Section 3 lists them.

---

## 2. Every package and file

### `api/` — the edge, and the only thing the app sees

| File | What it is |
|---|---|
| `HelpSessionController` | `POST /help/sessions`, `POST /help/sessions/{id}/input`, `GET /help/sessions/{id}` |
| `ConcernController` | the L1/L2 catalogue as the app needs it |
| `ApiExceptionHandler` | turns the session exceptions into HTTP status codes |
| `dto/NextStep` | **the contract.** Factories: `menu`, `message`, `deeplink`, `text` |
| `dto/StepType` | MENU · DROPDOWN · TEXT · MESSAGE · CSAT |
| `dto/Option`, `dto/L1Group` | menu entries |
| `dto/StartSessionRequest`, `dto/InputRequest`, `dto/SessionView` | request/response shapes |

### `session/` — the state machine

| File | What it is |
|---|---|
| `HelpSession` | the session row: sp, current step, next step payload, status, timestamps |
| `HelpSessionService` | `start` / `input` / `view`. Steps are constants: `STEP_L1_SELECT`, `STEP_L2_SELECT`, `STEP_CONCERN_SELECTED`, `STEP_ENDED` |
| `Ticket`, `TicketRepository` | Gate 1. A row here means a human is involved |
| `TicketService` | **Gate 1's write, and it COMMITS ON ITS OWN.** Reads nothing — every field is passed in |
| `SessionOpener` | commits the session before anything references it. `help_session <- ticket <- ticket_action` |
| `HelpSessionRepository` | |

`handleL2` is the hinge of the whole service: it saves **and flushes** the
session, starts the process, then **re-reads** `nextStep` from the row. If the
process completed without writing one, the partner is told support is
unavailable rather than shown nothing — and an ERROR is logged naming the
process.

**`TicketService` and `SessionOpener` are `REQUIRES_NEW`, and that is not a
performance choice.** `TicketActionRecorder` commits in its own transaction by
design, and a separate transaction cannot see a row this one has only flushed —
so the ticket, and the session the ticket points at, must be committed before
anything calls a payment gateway on their behalf. Durability travels UP the
reference chain. The corollary is the trap: those methods also cannot READ
anything the caller has only flushed, which is why they take fields rather than
ids to look up.

### `catalogue/` — the taxonomy, and the four pointers

| File | What it is |
|---|---|
| `ConcernCatalogue` | one row per L2 concern |
| `CatalogueService` | L1 groups, active concerns in an L1, lookup by L2 code |
| `ConcernCatalogueRepository` | |

The four columns that make a catalogue row *do* something:

```
process_key    →  src/main/resources/processes/<key>.bpmn20.xml
fact_provider  →  the @Component whose concernCode() returns this value
dmn_key        →  the <decision id="..."> in src/main/resources/dmn/<key>.dmn
togglz_flag    →  a constant in safety/BotinFeature
```

Plus `mandatory_human` (an authoritative override — checked *before* any
decision table), `active` (invisible in the menu when false),
`outcome_types`, and `legacy_concern_code` (the same concern's number in the
existing `SpTicketingConcernEnum`, so counts can be reconciled; null where no
confident mapping exists, because a wrong mapping silently merges two concerns
in a report).

**A catalogue row on its own does nothing.** It is inert until the process, the
provider, the table and the flag all exist. The catalogue removes duplication;
it does not remove the build.

### `process/` — Flowable, and the one rule about it

| File | What it is |
|---|---|
| `ConcernProcessRunner` | `start(processKey, sessionId, spId, l2Concern, selectedReference)` |
| `ProcessVariables` | the variable names, as constants, so they are spelled once |
| `SessionStepWriter` | `writeAndClose(sessionId, step, terminalStatus)` — **the only place a process tells the client what happens next** |
| `ForgetMpinDelegate` | FORGET_MPIN's whole Service Task: build a deeplink step, close as `CLOSED_DEFLECTED` |
| `PreFlightDelegate` | **trigger B.** Mandatory-human concerns stop deciding before anything is fetched |
| `FetchFactsDelegate` | resolves the provider from the catalogue, fetches, writes `factsJson`. Cannot throw at a partner |
| `DecideDelegate` | the concern's DMN table, named by `dmn_key`. The only step that decides |
| `OpenTicketDelegate` | **Gate 1**, and the only place it is crossed |
| `EscalationContextDelegate` | writes the handover before the wait begins |
| `AgentHandoffDelegate` | tells the partner an agent is coming, leaves the session OPEN |
| `AgentCompletionDelegate` | runs when the agent finishes — hours and a restart later if need be |
| `ResolveDelegate` | the bot answered it: message, close ticket, close session |
| `ResponseTemplates` | what the partner reads, keyed by DMN **action** — never by tier |

**`processes/concern-generic.bpmn20.xml` — one process, every concern.** Nothing in
it names a concern; which facts, which table, which kill switch are read from the
catalogue row at runtime. A process per concern would be thirty-eight files that are
95% identical, where a fix to the shared shape has to be applied thirty-eight times
and will not be.

```
start → preFlight → fetchFacts → decide → openTicket → <agentRequired?>
   yes → escalationContext → agentHandoff → [USER TASK] → agentCompletion
   no  → resolve
```

The gateway branches on `agentRequired`, **not on tier** — tier is the concern
table's answer, `agentRequired` is the whole system's, and the pre-flight gate can
have set it before any table ran.

`processes/forget-mpin.bpmn20.xml` is the simplest shape a concern can have:
start → one Service Task → end. The reason a process engine is used for three
elements is that the *next* concerns add a gateway on tier, a gateway on the
kill switch, and a User Task for agent connect — additions to a file, not
rewrites of a service class.

### `facts/` — read UAT, conclude nothing

| File | What it is |
|---|---|
| `ConcernFactProvider` | the interface. `concernCode()`, `factKeys()`, `requiredColumns()`, `fetchFacts()`, `emptyFacts()` |
| `FactRequest` | ticketId, spId, l2Concern, selectedReference, freeText |
| `FactProviderRegistry` | concern code → provider; **throws at startup if two providers claim one concern** |
| `UatColumn` | `confirmed(...)` / `inferred(...)` — a column name we verified vs one we guessed |
| `UatSchemaProbe` | at startup, checks every declared column against `information_schema.columns` |
| `TransportFactProvider` | `ysmdm_users.tbl_order` + `ysmdm_admin.tbl_sp_tranactions` + hub + slabs |
| `TransportPathSelector` | which of the transport claim paths this claim is |
| `RechargeFactProvider` | PayU rows in `ysmdm_employees` |
| `PayuStatusNormaliser` | raw gateway strings → `SUCCESS` / `FAILED` / `NO_RESPONSE` / `NOT_FOUND` / `UNRECOGNISED` |
| `ProductDeliveryFactProvider` | `tbl_sp_order` + the Express pincode list in `tbl_settings` |
| `ForgetMpinFactProvider` | looks nothing up — the concern needs no facts |
| `ClassifierFactProvider` | two inner providers for the free-text concerns; both currently answer "no confident match" |

**The contract is stricter than it looks:** `fetchFacts()` must return **every**
key in `factKeys()` on **every** path, including when UAT is unreachable and all
values are null. Strict mode makes an *absent* variable an evaluation error, and
an evaluation error takes the **whole table down, catch-all included**. A
present-but-null value evaluates fine and reaches the catch-all, which is a
human. Start from `emptyFacts()` and overwrite what you learn — then the map is
complete by construction rather than by remembering.

#### `facts/shared/` — computations more than one concern needs

| File | What it is |
|---|---|
| `GeoService` | haversine; `kmBeyondRadius` measured from the **radius edge**, not the centre. Returns null on unknown geometry |
| `HubGeometry` | parses the string lat/lng the live tables store; `radiusFromSlabEnds` |
| `DeliveryTatService` | `Asia/Kolkata`; `isExpress(pincode, set)`, `deadline`, `isPastTat` |
| `DuplicateCreditGuard` | `netPaise` over a ledger; `alreadyCredited` = net > 0 |
| `LedgerEntry` | one credit/debit row |

These are shared deliberately. `PROD_DELIVERY_DELAY` and the parked
`VIOL_R9_NO_PRODUCT` both compute "late" from `DeliveryTatService` — if they
computed it separately, the same partner could be told their order is on time
*and* that they should already have had it.

**The rule every one of these follows: return `null`, never a default, when you
don't know.** Unknown hub geometry is not 0 km. An undated order is not "on
time". An unrecognised ledger action contributes nothing.

### `decision/` — conclude, look nothing up

| File | What it is |
|---|---|
| `DecisionService` | wraps Flowable's `DmnDecisionService`; throws `NoMatchingRuleException` on a null result |
| `Decision` | `tier`, `action`, `outcomeType`, plus the raw map. `movesMoney()` = tier is T2 |

`action` is a **stable code**, not a label — the response template and the
action service are both keyed on it, so it is a contract.

### `safety/` — the things that stop a quiet mistake

| File | What it is |
|---|---|
| `BotinFeature` | the Togglz enum. One kill switch per automated concern |
| `FeatureNameValidator` | at startup, every `concern_catalogue.togglz_flag` must be a declared constant, or the boot fails |
| `TicketAction`, `TicketActionRepository` | the attempt log for anything that moves money or state |
| `TicketActionRecorder` | `recordAttempt` / `recordOutcome`, both `REQUIRES_NEW` |
| `KillSwitch` | the one place a flag is read. No flag on a concern = allowed; flag off = do not automate |
| `TogglzStateConfig` | **where a flag's position is kept: the database, no cache.** A flip survives a restart |
| `TogglzConsoleConfig` | mounts the console — and only when a credential exists |
| `TogglzConsoleAuthFilter` | HTTP basic on that one path and nothing else |

**Flag off does not mean fail. It means do not automate — send to a human.** The
partner is still served; only the automation stops.

**The console can stop — or restart — automatic payments to every partner in
the country, so there is no configuration that mounts it unauthenticated.** No
`TOGGLZ_CONSOLE_PASSWORD`, no servlet registration: the URL 404s and there is
nothing behind it. A misconfiguration removes the page rather than exposing it.

The filter is deliberately not Spring Security: that starter secures every
endpoint and enables CSRF on every POST the moment it is on the classpath, which
is a large blast radius for one page. A test asserts what makes the trade
acceptable — the partner API still answers with no credentials. D-7 replaces this
and deletes the class.

**A flag with no row reads as OFF.** A fresh database automates nothing and pays
nobody, which is right — and it means "off" and "never configured" look identical
at runtime, so `FeatureNameValidator` prints every switch's position at startup.

`TicketActionRecorder` is ADR-005, and the `REQUIRES_NEW` is the whole
mechanism: the ATTEMPTED row commits in its **own** transaction before the
external call happens. If the surrounding transaction then rolls back — or the
service dies mid-call — the row survives, `idempotency_key` is taken, and a
retry short-circuits instead of paying twice.

### `action/` — the only code that moves money or state

| File | What it is |
|---|---|
| `ActionService` | the interface every automated action implements: does it handle this code, what is its external reference, do it |
| `ActionRegistry` | action code → service. `DecideDelegate` asks it before it is allowed to emit a T2 |
| `ActionRequest`, `ActionResult` | what goes in, what came back. Both serialised into the attempt log verbatim |
| `WalletCreditService` | the first real one: credit a failed recharge back to the partner's wallet |

**`externalReferenceFor` is the most important method here**, and it is on the
service rather than in the delegate because only the service knows what makes two
requests the same thing. For a recharge that is the PayU order id. The value it
returns becomes the idempotency key, so a service that returns null has silently
downgraded its own duplicate protection to ticket scope — which is why it is a
declared part of the interface and not something inferred from the facts map.

**A T2 with no service behind it cannot be emitted.** `DecideDelegate` checks the
registry, and `PerformActionDelegate` checks again and escalates loudly if it finds
nothing. The second check is not redundant: the first can only be true at decision
time, and these two drifting apart is precisely the failure that would otherwise
look like a successful resolution that did nothing.

### `payu/` — the gateway, mocked

| File | What it is |
|---|---|
| `MockPayUGateway` | switchable status and amounts, `failNextCalls(n)`, `creditedFor(orderId)`, `reset()` |

No PayU credential exists for this POC, and the money path is the part that most
needs proving. The mock is a real bean on the real interface, so the four-step
sequence in `PerformActionDelegate`, the idempotency key and the escalation on
failure are all exercised for real; only the HTTP call at the end is not. `failNextCalls`
exists so the FAILURE path is demonstrable on demand rather than only in theory —
a gateway that always succeeds tests half of what matters.

### `classifier/` — the model boundary

| File | What it is |
|---|---|
| `ClassifierClient` | the two questions a model is ever asked. Two implementations |
| `StubClassifierClient` | deterministic, keyword-matched, chosen when no `CLASSIFIER_URL` is set |
| `HttpClassifierClient` | 3s timeout, Resilience4j breaker, both failure modes → no-match |
| `ClassifierGateway` | **the boundary.** Distrusts the answer: hallucinated category, out-of-bounds confidence, unknown sentiment, any exception |
| `Classification` | what the model said, after checking. `NO_MATCH` is the only fallback |
| `ClassifierConfig` | **which client is wired in — one if statement.** Replaced two conditions that both matched on an empty property |
| `IntentRouter` | entry-point free text: ask again, or go? The clarification loop, and nothing else |

`classifier-service/` holds the Python side — FastAPI + Pydantic, two endpoints, no
database and no identifiers. No model is wired in; `classify_text` is a placeholder and
the contract around it is what the Java client is built against.

### `csat/` — satisfaction, trigger A, and the bounding rule

| File | What it is |
|---|---|
| `CsatService` | `isExpected`, `offersAgentAfter` (the bounding rule), `escalate` (trigger A) |

The answer arrives on a **closed** session, at its own endpoint, because a partner has
to go and use an answer before they can rate it — and because holding every resolved
session open would charge runtime state to the whole T0 volume to serve the unhappy few.
There is no timer anywhere: nothing is held, so nothing can get stuck.

### `escalation/` — what the agent opens first

| File | What it is |
|---|---|
| `EscalationContext` | one row per ticket: facts, tier, action, recent history, prior actions, trigger |
| `EscalationContextRepository` | |

Written once, at escalation, and **never recomputed**. Recomputing when the agent
opens the case would show them a different world from the one the decision was made
in, and then "why did the bot do that" stops being answerable. `trigger_reason` is
A–E for the five cross-cutting triggers, or **`CONCERN`** when the concern's own
table returned T3 — which is not one of the five and does not pretend to be.

### `agent/` — the queue side of the handover

| File | What it is |
|---|---|
| `AgentTaskService` | list, claim, complete; `contextFor(ticketId)` |
| `AgentTaskView` | one row in the queue — enough to triage, no more |
| `AgentController` | `GET /agent/tasks`, `POST /agent/tasks/{id}/claim`, `.../complete`, `GET /tickets/{id}/escalation-context` |

**The queue is Flowable's User Task store, not a table of ours.** The work item and
the process waiting on it are then the same object and cannot drift apart. Not
authenticated yet — same decision as the Togglz console (D-7).

### `counter/` — caps that reset without a job

| File | What it is |
|---|---|
| `SpCounterId` | `@Embeddable` composite key: sp + counter + **period** |
| `SpCounter`, `SpCounterRepository` | |
| `SpCounterService` | `PERIOD_LEAVE`, `POOLED_EMERGENCY`, `CYCLE_REMOVAL`; `monthKey`, `cycleKey`, `count`, `grant`, `grantIfWithinCap` |

`period_key` is part of the **identity**, not a column beside it. A new month is
a new key, which starts at zero on its own. Nothing resets anything; there is no
scheduled job to fail silently at a month boundary.

### `demo/` — the synthetic world, and the wall around it

| File | What it is |
|---|---|
| `DemoFixtures` | loads `demo/fixtures.json`. **Fails at startup if UAT is also configured** |
| `DemoControlController` | the handles the script needs: gateway status, fail-next, flip a flag, ticket count, reset |
| `resources/demo/fixtures.json` | 5 recharges, 8 transport orders, 2 product orders. Every row says what it demonstrates |
| `tools/demo-run.sh` | the run itself. 17 of 20 coverage-matrix rows, each asserted |

**Both classes are `@Profile("demo")` — they DO NOT EXIST otherwise.** Not
disabled, not secured: absent, and `DemoIsolationTest` asserts 404 plus zero
beans in an ordinary run. The control surface flips kill switches and changes
what a payment gateway reports, so "we remembered the annotation" is not a good
enough guarantee for one invisible line whose removal breaks nothing visible.

**The fixtures supply RAW ROWS, not conclusions.** The path selector, the
duplicate-credit guard and the TAT service run for real against them — a fixture
handing over a finished `transportPath` would demonstrate the fixture file and
nothing else. The one invented figure is `computedAmountPaise`, because D-B is
unanswered; it is labelled as invented, and it exists so the CAP is demonstrable
without the RATE being right. Those are two different questions.

**Times are relative** — "placed six days ago", never a date. A fixed timestamp
means one thing this week and another next month, silently.

### `console/` — making the backend visible

| File | What it is |
|---|---|
| `ConsoleController` | `@Profile("console")`. The page, `/state`, `/trace/{session}`, ticket and flag reads |
| `DecisionTrace` | why the bot did that, captured as it decided. Bounded, in memory, never on the deciding path |
| `DecisionTableReader` | parses a `.dmn` into a grid — **and keeps each rule's comment**, which is the policy |
| `resources/console/console.html` | the renderer. No concern name, no tier, no rule — asserted |

**The trace is captured BY the decision, not re-run afterwards.** A second
evaluation is a second answer, and the two diverge the moment a fact changes
underneath them — which for a money-moving action is exactly when somebody is
asking why.

**The fired row comes from Flowable's own audit trail.** Deducing which row must
have matched means re-implementing the expression language beside the engine.

**The grid is read from the file on disk, so it can go stale after a hot
redeploy** (step 93 changes the running table without touching the file). It is a
reading aid: the answer and the fired row always come from the engine. If the two
disagree, the engine is right.

**Separate from `demo/` on purpose.** The demo profile refuses to start beside a
real UAT datasource, so a console living inside it could never show real data.
`console` alone runs against whatever the service is really reading; `demo,console`
adds the synthetic world. The banner comes from `/console/state`, not from the page.

### `config/` — the two datasources

| File | What it is |
|---|---|
| `DataSourceConfig` | primary (`@Primary`, Postgres, ours) and `uatJdbcTemplate` (MySQL, `setReadOnly(true)`, 5-second query timeout) |
| `UatDataSourceProperties` | binds `botin.uat.*` |
| `ConditionalOnUatEnabled` | everything UAT disappears when `UAT_ENABLED=false` |
| `FlowableConfig` | Flowable against the **primary** datasource only |

The UAT datasource has **no** EntityManager, **no** JPA, **no** Flyway and
**no** Flowable attached. There is no mechanism in the service capable of
emitting DDL against it. Belt and braces: the UAT user must be SELECT-only *by
GRANT*, not by convention.

### `db/migration/` — Flyway owns the schema

| File | What it does |
|---|---|
| `V1__core_schema.sql` | `concern_catalogue`, `help_session`, `ticket`, `ticket_action`, `conversation_message`, `escalation_context`, `sp_counter` |
| `V2__seed_concern_catalogue.sql` | the full L1/L2 taxonomy |
| `V3__add_outcome_types.sql` | `outcome_types` |
| `console/JourneyService.java` | **what a conversation touched, step by step.** The shape comes from the DEPLOYED BPMN model, not a list in the code — a hardcoded shape rendered a concern with its own process as "2 of 15 steps taken". Sequence flows are filtered out; Flowable records them as activity instances and they inflated the step numbers |
| `GET /console/journey/{sessionId}` | the endpoint behind it. The client's own API calls, then every step of every process run, taken or not, with what each step actually did |
| `demo/DemoScenarios.java` | **the 17 acceptance cases, as data, in one place.** Drives the service over HTTP on purpose — the claim is "reachable through the API", and a runner that reached past the controllers would be a weaker test that looked stronger |
| `demo/DemoScenarioController.java` | `/demo/scenarios`, `/{id}/run`, `/run-all`. run-all answers 200 when every case passed and 409 when any did not, so a caller learns the verdict from the status code |
| `tools/demo-report.jq` | renders the run-all report for the terminal. Its own file because the filter needs both quote characters and a shell is the wrong place to fight over them |
| `V4__park_unbuildable_violations_and_map_legacy_codes.sql` | deactivates two violation concerns that read codes which do not exist; adds `legacy_concern_code` |

Hibernate is `ddl-auto: validate` — it checks the entities match and creates
nothing.

### Credentials

No credential is written in any file. `application.yml` references environment
variables with **no fallback**, so a missing one fails at startup rather than
connecting somewhere unintended. `.gitignore` excludes `env/*.env`, `*.env`,
`application-local.yml`, `application-secret*.yml` and key material;
`env/botin-uat.env.sample` is committed and holds no values. Tests run with
`UAT_ENABLED=false`, so **no test can ever need a credential.**

---

## 3. The five guards, and the incident behind each

Each of these exists because something already went wrong in exactly this way.
None of them is defensive tidiness.

| Guard | Catches | What happened without it |
|---|---|---|
| `FeatureNameValidator` (startup) | a catalogue row naming a Togglz flag no enum declares | Togglz resolves an unknown name to **false**, silently. With fail-to-human semantics that concern routes every partner to an agent, with no error anywhere — just a rising queue |
| `TicketActionRecorder` (`REQUIRES_NEW`) | paying twice | a rollback after an external call would erase the evidence that the call happened; the retry pays again |
| `UatSchemaProbe` (startup) | a column name we guessed wrong | empapi has no persistence config in the repo, so camelCase field → physical column cannot be verified by reading. The probe checks every declared column against `information_schema` and prints a loud block naming the misses. It does **not** fail the boot — a wrong column should degrade one concern, not stop the service |
| `DmnNamespaceTest` | a decision table re-saved as DMN 1.5 | a modeller exported the newer namespace; Flowable 7.0.1 could not parse it; `dmnEngineConfiguration` failed to start; the Spring context died; **every** `@SpringBootTest` failed — 80 errors from one attribute in one file. The test reads the files as text, with no context, and fails in milliseconds with a sentence saying what to do |
| `ProviderContractTest` | a fact name spelled differently on the two sides | `arrivedAt300m` vs `arrivedAt300metre`. Not a compile error, not a runtime error, not a log line — the table reads null, never matches, and every claim of that kind quietly reaches an agent |

Two details in `ProviderContractTest` that look like mistakes and are not:

- The expected fact names are **restated** there rather than shared with
  `DecisionTableTest`. If both read one constant, renaming the constant renames
  "the contract" on both sides at once and the test agrees with itself while the
  table disagrees with reality. Two independent statements is what makes the
  check real.
- It runs with **UAT disabled**. That is the trick: a provider must return its
  full key set even when it can look nothing up, so the contract is testable
  without a database and without a credential.

---

## 4. How to add a concern

Do these in order. The order matters: it puts the startup validators in front of
the mistakes they can catch, so a wrong name fails at boot instead of in front of
a partner.

**Step 1 — catalogue row (a migration, `V5__...sql`).**
Insert with `active = false`. Fill `l1_code`, `l2_code`, labels, `display_order`,
`default_tier`, `outcome_types`, and `legacy_concern_code` if a confident mapping
to `SpTicketingConcernEnum` exists (null if not — a wrong one is worse than none).
Leave the four pointers null for now.
Set `mandatory_human = true` if this concern must always reach an agent — that is
checked before any decision table and overrides it.

**Step 2 — the decision table.**
Create `src/main/resources/dmn/<key>.dmn`. Section 5 is the anatomy. Write the
rules first and the provider second: the table defines the fact names, and the
provider's job is to supply exactly those.

**Step 3 — the fact provider.**
A `@Component` implementing `ConcernFactProvider`:

```java
@Override public String concernCode() { return "MY_CONCERN"; }

@Override public Set<String> factKeys() {
    return Set.of("someFact", "anotherFact");   // EXACTLY the table's inputExpressions
}

@Override public List<UatColumn> requiredColumns() {
    return List.of(UatColumn.confirmed("ysmdm_admin", "tbl_x", "col_a"),
                   UatColumn.inferred ("ysmdm_admin", "tbl_x", "col_b"));  // a guess, marked
}

@Override public Map<String, Object> fetchFacts(FactRequest request) {
    Map<String, Object> facts = emptyFacts();     // ALWAYS start here
    if (uat == null) return facts;                // UAT off: all keys, all null
    ...
    return facts;
}
```

Inject the UAT template as `@Autowired(required = false) @Qualifier("uatJdbcTemplate")`
so the provider still constructs when UAT is disabled. Declare every column you
query, and mark anything unverified as `inferred` — the probe can only check what
is declared, and a column marked `confirmed` that is actually a guess defeats it.

**Step 4 — the BPMN process.**
`src/main/resources/processes/<key>.bpmn20.xml`. Start from `forget-mpin` and add
only what the shape needs. Whatever the last path does, it must end by calling
`SessionStepWriter.writeAndClose(...)` — a process that completes without writing
a `nextStep` produces a partner staring at nothing (the service logs an ERROR and
falls back, but the concern is broken).

**Step 5 — the kill switch, if the concern automates anything.**
Add a constant to `BotinFeature`. Any concern that moves money or state needs
one. Flag off = do not automate = send to a human.

**Step 6 — wire the four pointers.**
In the same migration, `UPDATE concern_catalogue SET process_key, fact_provider,
dmn_key, togglz_flag WHERE l2_code = ...`. `fact_provider` must equal the
provider's `concernCode()`; `dmn_key` must equal the `<decision id="...">`;
`togglz_flag` must equal the enum constant's name.

**Step 7 — tests.**
Add the concern's fact names to `TABLE_INPUTS` in **both** `ProviderContractTest`
and `DecisionTableTest`. Add a nested class in `DecisionTableTest` covering every
rule, including the catch-all and the all-nulls case.

**Step 8 — activate.**
`active = true`, only once steps 2–7 are green. An active concern with a null
`process_key` shows the partner "not available here" and logs an ERROR.

### Where it fails if you skip a step

| Skipped | Symptom |
|---|---|
| provider bean | `FactProviderRegistry` throws at startup naming the missing concern |
| Togglz constant | **boot fails** with the catalogue row and flag name |
| declared column wrong | loud ERROR block at startup from `UatSchemaProbe`; service still runs |
| fact name mismatch | `ProviderContractTest` fails naming both spellings |
| catch-all missing | `DecisionService` throws `NoMatchingRuleException` with the facts |
| `writeAndClose` missing | partner sees "not available"; ERROR names the process |

---

## 5. How to change a rule in a DMN file

### Anatomy

```xml
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" ...>
  <decision id="recharge-debit-no-credit-decision" ...>   <!-- = catalogue dmn_key -->
    <decisionTable hitPolicy="FIRST">

      <input label="payuStatus">
        <inputExpression typeRef="string"><text>payuStatus</text></inputExpression>
      </input>                          <!-- must match a provider factKeys() entry -->

      <output name="tier"        typeRef="string"/>
      <output name="action"      typeRef="string"/>
      <output name="outcomeType" typeRef="string"/>

      <rule>
        <inputEntry><text>"SUCCESS"</text></inputEntry>   <!-- must be true -->
        <inputEntry><text>false</text></inputEntry>
        <inputEntry><text></text></inputEntry>            <!-- EMPTY = don't care -->
        <outputEntry><text>"T2"</text></outputEntry>
        <outputEntry><text>"AUTO_CREDIT_WALLET"</text></outputEntry>
        <outputEntry><text>"BOT"</text></outputEntry>
      </rule>
      ...
    </decisionTable>
  </decision>
</definitions>
```

Three outputs are mandatory on every table: `tier`, `action`, `outcomeType`.
Some tables add more (a per-km rate, for one); those come back in
`Decision.raw()` and are read with `numeric(...)`.

### The five things to know before editing

**1. Hit policy is `FIRST`. Row order IS logic.**
The first matching row wins and evaluation stops. Two real defects in this
project were pure ordering:

- the ₹300 cap was listed *last*, so the paying row above it always won and the
  cap never fired;
- recharge's general "failed" row sat above the specific "failed **and** not
  satisfied" row, so the general one swallowed it and a dissatisfied partner was
  told to recharge again instead of getting a ticket.

**Specific rows go above general rows. Caps and guards go above the rows they
limit.**

**2. Every table ends with a catch-all, and the catch-all is a human.**
All input entries empty, `tier = "T3"`, `outcomeType = "TICKET"`. Without one,
an unmatched fact set produces a null result and `DecisionService` throws.

**3. The catch-all is not a floor.**
A failed *input expression* takes the **whole table** down — catch-all included.
That is how a comma-separated entry (`"A","B"`) broke a table that had a perfectly
good catch-all underneath it. **One value per row**, or a proper FEEL list. The
protection is "unknown facts reach a human", and it only holds while every input
expression can evaluate.

**4. Absent and null are different.**
A variable *missing from the map* is an evaluation error (see 3). A variable
*present with a null value* evaluates fine and falls through to the catch-all.
This is why providers must always return their complete key set.

**5. DMN 1.3, and nothing else.**
`https://www.omg.org/spec/DMN/20191111/MODEL/` is the only model namespace
Flowable 7.0.1 parses. If you open a table in a modeller, check the namespace
before committing — many export DMN 1.5 (`20230324`), which stops the DMN engine
and takes the whole application context with it. Strip any `<dmndi:DMNDI>` block
too: nothing here renders diagrams, and that block is where modeller-specific
namespaces arrive. `DmnNamespaceTest` enforces both.

Editing tables in a modeller is a workflow **we want** — a business owner
changing a threshold should not need a developer. The namespace rule is a
guardrail on that, not an argument against it.

### Common edits

**Change a threshold.** Edit the `<inputEntry>` value. If the number also exists
in Java, it is in the wrong place — thresholds belong in the table.
Exception: values that are *operational configuration* rather than business rules
(TAT days, the cut-off hour) live in `application.yml` under `botin.*`, because
they are read by more than one concern and must move together.

**Add a case.** Insert the `<rule>` **above** the more general row it refines,
and below anything meant to beat it. Give it a unique `id`. Then add the case to
that concern's nested class in `DecisionTableTest` — a rule with no test is a
rule nobody will dare reorder later.

**Add a new input.** Three places, one name: the `<inputExpression>` text, the
provider's `factKeys()`, and `TABLE_INPUTS` in both test classes. Miss one and
`ProviderContractTest` says which.

**Change what an action does.** Don't — not here. `action` is a code, and the
table only chooses it. What the code *does* lives in the process and the action
service. Changing the string here without changing them there produces a decision
nothing acts on.

---

## Before you ask for a build

```
python3 tools/preflight.py
```

Runs everything the suite checks that needs no JVM. It does not compile and it does not
catch logic errors — it exists because this project can only be built on one machine, so
a wasted build round trip is expensive.

Seven rules: deployable XML parses, the DMN namespace is the one Flowable reads,
every declared fact is documented in the register, both statements of the fact
contract agree, every catalogue pointer resolves, every `${delegate}` names a
real `@Component`, and every `@Value` placeholder with no default names a
property `application.yml` declares.

Two of those are not in the Java suite at all, and both fail LATE without it: an
unresolvable delegate expression fails at runtime on the first partner to reach
that step, and an unresolvable placeholder kills the entire context at startup,
which shows up as every `@SpringBootTest` erroring at once and says nothing about
the one character responsible.

**When you add a rule, check it against a negative control** — break something on
purpose and confirm it reports. A guard that passes vacuously is worse than no
guard, because it is trusted.

## Where the rest of the documentation is

| Path | What it holds |
|---|---|
| `CLAUDE.md` | project state, standing rules, landmines, assumptions, working agreement |
| `docs/README.md` | index of everything in `docs/` |
| `docs/design/` | decision brief, engineering design, decision engine |
| `docs/planning/` | build plan, tracker, component notes |
| `docs/data/` | catalogue, rules, facts, caps, shared services as CSV |
| `docs/UAT-SCHEMA-MAP.md` | which UAT table and column each fact comes from |
| `docs/WHAT-I-NEED.md` | open items that need someone else |
| `docs/DEFERRED.md` | parked decisions, each with the reason and what unblocks it |
