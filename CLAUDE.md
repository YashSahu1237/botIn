# CLAUDE.md — BOTIn Mod

**Read this file first.** It is the continuity record for this project. If a session is
lost, everything needed to resume is here or linked from here. Keep it current: when
something is decided, discovered, or finished, it gets written down here, not left in a
chat transcript.

---

## 1. What this project is

**BOTIn Mod** is a bot-first, tiered outbound support system for Service Partners (SPs).
An SP taps Help, picks an L1 concern, then an L2 sub-concern, and the system resolves it
without a human wherever it safely can.

**The tier model — the single most important idea in the system:**

| Tier | Meaning | Ticket created? |
|------|---------|-----------------|
| T0 | Deflect — answer and stop | **No** |
| T1 | Auto-resolve — an answer the bot owns | Yes |
| T2 | Auto-action — moves money or changes state | Yes |
| T3 | Agent connect — a human takes it | Yes |

**Gate 1** is the moment a ticket row is created. A T0 deflection must never cross it.
Proving that — zero rows in `ticket` after a successful T0 — is an explicit POC goal, not
an implementation detail.

**Agent connect is over chat.** Not voice. The design documents say so explicitly and
note that this needs revisiting if the business later wants voice.

---

## 2. STANDING RULES — do not violate these

These came from the project owner directly. They are not preferences.

1. **No timelines, effort estimates, author names, versions or dates in any BOTIn
   document.** Documents describe the module, not the project management around it. This
   applies to every HTML/doc deliverable. (This file is an internal working record and is
   the one exception, and even here: no effort estimates, no names.)

2. **Nothing BOTIn creates may be written to the UAT database, ever.** UAT is a *second*
   datasource, read-only, `SELECT`-only by GRANT. No JPA, no Flyway, no Flowable on it.
   See §5 for how this is enforced in code.

3. **Use exact production table and column names when reading UAT**, so the SQL does not
   have to change when it points at production later.

4. **No production money moves in the POC.** Every money-adjacent action writes to a mock.

5. **Published artifacts contain internal detail** (`empapi`, `admin`, `app_server`, SP
   fine/wallet/strike rules, medal bands, ticket volumes). They are private by default.
   Do not make one public without being asked.

6. **Explain from the problem first.** When explaining anything: start with the real
   situation, then what the dependency literally is, then the concepts, then the advanced
   behaviour. Leading with mechanics has failed here before.

---

## 3. Where everything is

Everything lives under this project root. Nothing is stored only in a chat.

```
botin/
├── CLAUDE.md                  <- this file
├── pom.xml
├── src/                       <- the POC service (see §5)
└── docs/
    ├── README.md              <- index of the repository
    ├── CODE-MAP.md            <- WHAT EVERY FILE IS, how it connects, how to add a
    │                             concern, how to edit a DMN rule. Start here for code.
    ├── UAT-SCHEMA-MAP.md      <- which UAT catalog/table/column each fact comes from
    ├── SIGNAL-REGISTER.md     <- which facts are REAL today, which are absent, and what
    │                             each absence costs a partner. Read before any demo.
    ├── WHAT-I-NEED.md         <- open items that need someone else
    ├── DEFERRED.md            <- parked decisions, each with the reason and what unblocks it
    ├── design/
    │   ├── 01-decision-brief.html       Part A — the decision-level document
    │   ├── 02-engineering-design.html   Part B — the engineering document (AUTHORITATIVE)
    │   └── 03-decision-engine.html      all 113 decision rules across 38 concerns
    ├── planning/
    │   ├── poc-build-plan.html          the 98-step build plan
    │   ├── poc-tracker.xlsx             live status — Summary / Next up / Steps / Evidence
    │   └── component-notes.html         what each dependency is and why it was chosen
    ├── data/
    │   ├── concern_catalogue.csv        38 concerns
    │   ├── decision_rules.csv           113 rules
    │   ├── decision_facts.csv           9 fact domains, 67 signals
    │   ├── shared_services.csv          10 shared services
    │   └── caps_and_thresholds.csv      18 caps
    ├── spikes/                          the R&D ladder, all green (see §6)
    └── superseded/                      STALE. Kept for history. Do not build from these.
```

**`poc-tracker.xlsx` is the source of truth for status.** The Summary tab is formula-driven
off the Steps tab — change a Status cell and the rest recalculates.

---

## 4. Current state

**R&D ladder: complete, 6 of 6 green.**

**Phase 1 (plan steps 23-31): DONE.** Skeleton, two datasources, 7 tables, the two safety
guards.

**Phase 2 (plan steps 32-37): DONE.** The L1 -> L2 flow answers over HTTP. `mvn test`
passes **21/21**.

What Phase 2 established, beyond the endpoints:

- **The client contract.** Every response carries a `nextStep` descriptor with one of five
  types - MENU, DROPDOWN, TEXT, MESSAGE, CSAT - plus its options or content. The backend
  owns 100% of the flow logic; the client is a thin renderer. Adding a concern is a data
  change. Adding a sixth step type would be a client release. That asymmetry is the point.
- **`nextStep` is persisted as JSON on the `help_session` row**, and the reconnect endpoint
  re-reads it from there. A test asserts the reconnect payload is byte-identical to the
  response that preceded it. From Phase 3 the final Service Task of each process writes the
  same field, for the same reason: process variables are gone the moment an instance ends.
- **No hard-coded menu anywhere.** The L1 list is derived from the distinct `l1_code` values
  across all 38 rows. Switching a concern on is an UPDATE, visible on the next request with
  no deploy.
- **One ending for three causes.** An inactive concern, an unknown code, and a concern
  reached from the wrong L1 path all return `CONCERN_NOT_AVAILABLE` and close the session as
  `CLOSED_NOT_AVAILABLE`. The partner cannot tell them apart because from where they stand
  there is no difference. The reason is on the row rather than in a log line, so "how often
  did someone pick something we have not built" is answerable from the database.
- **Phase 2 creates no ticket, deliberately.** Gate 1 is crossed when a concern needs an
  answer or an action, and which of those it needs is decided by the process and decision
  table. Two tests assert the ticket count is unchanged across the whole flow. This is what
  keeps the Phase 3 checkpoint an assertion rather than a formality.
- **`V3__add_outcome_types.sql`** adds the column the concern mapping needed and the original
  design had no room for, backfilled for all 38 concerns.

**Phase 3 (plan steps 38-40): DONE.** `FORGET_MPIN` runs a real BPMN process end to end.
`mvn test` passes **27/27**. The checkpoint held: a completed T0 files **no ticket**, asserted
three ways - by total count, by SP, and by session.

What Phase 3 established:

- **The shape of a concern.** `forget-mpin.bpmn20.xml` is start event, one Service Task, end
  event. Every concern after it is an ADDITION to a file like this - gateways on tier, on a
  kill switch, a User Task for agent connect - rather than a rewrite of a service class. That
  is the reason a process engine earns its place for three elements.
- **`SessionStepWriter`, and the trap it exists for.** A delegate's obvious move is to set a
  process variable and let the caller read it back after `startProcessInstanceByKey` returns.
  That works until the process completes SYNCHRONOUSLY - which every T0 does - because a
  completed instance has its `ACT_RU_*` rows deleted, variables included. The read returns
  null, and only for the flows that finish fast, which is exactly the set that looks perfect
  in a demo. So every process writes its ending onto the `help_session` row and the API layer
  re-reads it from there. `theEndingSurvivesTheInstanceItCameFrom` is the test that proves it.
- **Deeplinks are configuration** (`botin.deeplinks.*`), not literals in a delegate. The app
  team owns where they point.
- **`ProcessVariables` holds variable names as constants.** A typo in a process variable name
  is not a compile error and not a runtime error - the delegate reads null and behaves as if
  the partner gave nothing. Same silent-failure shape as the Togglz hole, same treatment.
- **`TicketRepository.countByHelpSessionId`** is Gate 1 in its purest form and will be
  asserted in every tier test from here on.

**Phase 5 (plan steps 48-52): DONE**, taken ahead of Phase 4 because the decision tables
need no database. `mvn test` passes **70/70**.

Eight `.dmn` files in `src/main/resources/dmn/`, hit policy FIRST, **32 rows** - 26 from the
concern mapping, 5 catch-alls, 1 added (see section 8). Every threshold is IN the table:
Rs300 as `> 30000` paise, Rs50/km as an output of `5000`, one period leave per month as
`< 1`, one product allowance per cycle as `< 1`, the 0.7 classifier floor in both reroute
tables. Changing any of them is a redeploy of one file.

**TWO ROW-ORDER DEFECTS FOUND IN THE MAPPING'S OWN ORDERING, both in money rules:**

1. **The Rs300 cap never fires.** The mapping lists it LAST, as Transport rule 9. Under
   FIRST, a Path 3 claim computing Rs450 matches rule 8 - credit distance x Rs50 - which
   sits above it. Rule 8 fires, money moves, row 9 is never reached, and nothing is logged.
   The cap is now **row 2**, above every row that can pay. `theCapOutranksThePayingRows` is
   the test that fails if anyone "restores" the mapping's order.
2. **Recharge: the general row swallows the specific one.** The mapping lists "Failed"
   before "Failed and SP not satisfied", so a dissatisfied partner is told to recharge
   again instead of getting a ticket. The specific row now goes first.

**Schema mapping: DONE**, read from the empapi source rather than waiting on a dump. See
`docs/UAT-SCHEMA-MAP.md`. Three findings changed decisions:

1. **Three MySQL catalogs, one instance** - `ysmdm_admin`, `ysmdm_users` (the booking),
   `ysmdm_employees` (SP PayU). One connection, catalog-qualified SQL. **MySQL, not Postgres**
   - our own schema stays Postgres, so the service carries both drivers.
2. **The L1/L2 taxonomy already exists** as `SpTicketingConcernEnum` - transport 32, recharge
   31, MPIN 35, and more. V4 adds `legacy_concern_code` so BOTIn's counts can be reconciled
   against the existing ticketing reports. `tbl_sp_ticketing` also already exists (see
   DEFERRED D-D).
3. **The violation codes in the concern mapping do not exist.** `tbl_violation_master` holds
   exactly SP_101-SP_107. Two concerns parked - see DEFERRED D-A.

Real values confirmed by query, and both were about to cause silent failures:

- **PayU status is lower case**, and there is **no "pending"** - `success` 74,796 | NULL
  26,374 (26%) | `failure` 36 | `Not Found` 11. The table compared "SUCCESS"/"FAILED" from the
  mapping's prose, so the row that credits a wallet would never have matched and 883 claims a
  month would have gone silently to agents. `PayuStatusNormaliser` now maps the raw column to a
  closed set in Java, which also avoids testing for null inside a DMN expression.
- **Transport credits are reversible** - both `CREDIT/TRANSPORT` and `DEBIT/TRANSPORT` exist.
  `alreadyCredited` must be a NET position, not `EXISTS(CREDIT)`. See DEFERRED D-E.

**Credentials are environment variables only.** `application.yml` references `${UAT_DB_URL}`,
`${UAT_DB_USER}`, `${UAT_DB_PASSWORD}` with NO fallbacks, so a missing one fails at startup.
`env/botin-uat.env.sample` is committed and holds no values; `env/botin-uat.env` is gitignored
along with `*.env`, `application-local.yml`, `*.pem`, `*.jks`, `credentials*`, `secrets*`.
Tests run with `UAT_ENABLED=false`, so no test can ever need a credential.

**Phase 4 (steps 41-45): DONE.** `mvn test` passes **103/103**. Only step 46 - writing the
fact providers against real columns - is left, and that is now transcription rather than
design.

Four shared services, in `facts/shared/` and `counter/`:

- **`GeoService`** - the haversine formula is COPIED FROM empapi, constant included
  (`EARTH_RADIUS = 6371000`, metres as an int). A better formula here would be a defect: the
  app telling a partner one distance and BOTIn paying for another. Distance is measured
  **from the radius EDGE, not the hub centre** - a job 5.56 km from a hub with a 5 km radius
  is 0.56 km of reimbursable travel; measuring from the centre overpays 10x on every claim.
  `HubGeometry` holds the two things that are not columns: coordinates are STRINGS on
  `tbl_hub`, and the radius is `MAX(end)` of that hub's rows in
  `tbl_servicehub_transportation`, derived exactly as `JobController` derives it.
- **`DeliveryTatService`** - the ONE definition of "late", read by both PROD_DELIVERY_DELAY
  and VIOL_R9_NO_PRODUCT, with a test whose only job is to assert both get the same answer
  from the same call. Express is decided by PINCODE from `tbl_settings`, not by a flag on the
  order. The timezone is stated (`Asia/Kolkata`), not inherited from the server.
- **`DuplicateCreditGuard`** - NET position, never `EXISTS(CREDIT)`. Credit Rs200 then debit
  Rs200 reads as NOT paid, because from the partner's side the money is not there.
- **`SpCounterService`** - `period_key` is part of the PRIMARY KEY, so a new month starts at
  zero by not existing yet, last month stays readable for disputes, and there is no scheduled
  reset job that can fail. The 25-job cycle keys off the live `tbl_sp_job_completion_cycle`
  id so ours and theirs cannot drift. `grant()` is REQUIRES_NEW for the same reason
  `TicketActionRecorder` is: an allowance handed over and then rolled back is a benefit the
  partner keeps with a counter saying they never took it.

**A pattern now consistent across all four: they return NULL, never a default, when they do
not know.** Unknown hub geometry is not zero km (zero means "inside the hub, deny"). An
undated order is not "on time". An unrecognised ledger action contributes nothing rather than
guessing a direction. This is the parked-violations lesson generalised: **an empty result is a
specific answer, and it is usually the generous one.**

**Two TAT assumptions, in `application.yml` and flagged there:** `standard-days: 3` is
evidenced by the live concern being named TEEN_DIN_SE_JYADA_HO_GAYE_PRODUCT_NAHI_AAYA;
**`express-days: 1` is a guess**; and the 11 PM cut-off is read as an ORDERING deadline (at or
after it, the clock starts next day), which the mapping does not actually specify.

**Step 46: DONE.** `mvn test` passes **111/111**. Six fact providers, each declaring the UAT
columns it reads, plus a startup probe that checks those declarations against the live
`information_schema`.

What step 46 established:

- **`ConcernFactProvider` has a stricter contract than it looked.** `fetchFacts()` must return
  EVERY key in `factKeys()` on EVERY path - including when UAT is off and all values are null.
  Strict mode treats an ABSENT variable as an evaluation error, and an evaluation error takes
  the whole table down, catch-all included. A present-but-null value evaluates fine and reaches
  the catch-all, which is a human. `emptyFacts()` makes the map complete by construction rather
  than by remembering.
- **`UatColumn` and `UatSchemaProbe`.** empapi has no persistence config in the repo, so
  camelCase field -> physical column cannot be verified by reading source. Every declared column
  is therefore marked `confirmed(...)` or `inferred(...)`, and the probe checks all of them at
  startup, logging which guesses were right and printing a loud ERROR block naming the misses.
  It deliberately does NOT fail the boot: a wrong column should degrade one concern, not stop
  the service.
- **`ProviderContractTest` is the join the project was missing.** It runs with UAT DISABLED,
  which is the trick - a provider must return its full key set knowing nothing, so the contract
  is testable with no database and no credential. It asserts every active concern has a
  provider, that provider fact names equal the table's inputs, that all-null facts still DECIDE,
  and that they never move money. The expected names are restated there rather than shared with
  `DecisionTableTest`, on purpose: one shared constant renames both sides at once and the tests
  agree with each other while disagreeing with reality.

**THE DMN NAMESPACE INCIDENT - the fifth instance of the same failure shape.** A decision table
was opened in a DMN modeller and re-saved. The modeller exported **DMN 1.5**
(`.../spec/DMN/20230324/MODEL/`), which Flowable 7.0.1 cannot parse. The result was not "that
one concern broke": `dmnEngineConfiguration` failed to start, the Spring context died with it,
and **every** `@SpringBootTest` failed - 109 run, 80 errors, from one attribute in one file. The
error names an XML element and a line number and says nothing about DMN versions.

`DmnNamespaceTest` now guards it: no Spring context, reads the `.dmn` files as TEXT, fails in
milliseconds with a sentence saying what to do. It also fails on any leftover `<dmndi:DMNDI>`
block, which is where modeller-specific namespaces arrive. **Editing tables in a modeller is a
workflow we WANT** - a business owner changing a threshold should not need a developer - so the
fix is a guardrail, not a rule against modellers.

**`docs/CODE-MAP.md`: written.** Orientation for the whole codebase - the one-page mental model
of a request's path, every package and file with one line each, the five guards and the incident
behind each, **how to add a new concern** (eight ordered steps, and where it fails if one is
skipped), and **how to change a rule in a DMN file** (table anatomy, why row order is logic
under FIRST, why the catch-all is not a floor, and the DMN 1.3 rule). This is the file to hand
anyone who asks "what is this package for".

**Step 47: DONE** (`docs/SIGNAL-REGISTER.md`). `mvn test` passes **113/113**. Every fact, per concern: where it comes from,
whether it is populated today, which rules it gates, and what its absence costs.

**THE NUMBER THE REGISTER PRODUCED: of the 26 rules across the 6 active concerns, 15 can fire
today and 11 cannot** — not because anything is broken, but because a signal they read is
never populated. A rule whose input is always null does not error. It simply never fires, and
the table still looks complete.

Two states worth keeping straight:

- **As the service actually runs right now** (`UAT_ENABLED=false`, no SELECT grant yet), only
  **FORGET_MPIN** resolves - 738 of 9,542 known monthly cases, **7.7%**. Everything else
  returns all-null facts and lands on its catch-all, which is an agent.
- **With the grant in place and nothing else changed**, 15 rules become reachable, but the two
  money concerns still send their LARGEST case class to a human: Transport cannot measure
  Path 3 (the selector's fall-through, so most claims), and Recharge cannot decide a SUCCESS
  because `alreadyCredited` has no confirmed link column - roughly 68% of that concern's
  gateway rows.

**A GATE, not a note: TRANSPORT_AUTO_CREDIT must not be switched on while
`computedAmountPaise` is absent.** The Rs300 cap is the only row protecting the paying rows
above it, and with the rate question open (DEFERRED D-B) that fact is always null, so the cap
CANNOT FIRE. Auto-credit today would pay with no ceiling.

Four of the nine blockers are one question to whoever owns the schema - the job's own
lat/lng, the wallet-to-PayU link column, the `unassign_status_code` -> NR/CR mapping, and the
order-to-pincode link - and together they unblock 6 of the 11 dead rules.

**`SignalRegisterTest`** asserts every fact in every provider appears in the register, so the
document cannot drift out of agreement with the code. It cannot check that a STATUS is honest
- that is what `UatSchemaProbe` reports at startup - but a fact nobody has written a line
about is a fact nobody has decided about, and that part is now caught.

**Phase 9 (steps 68-72): DONE.** `mvn test` passes **128/128**. Agent connect - the destination
every unresolved case already reaches. Taken ahead of Phase 6 deliberately: the signal
register had just shown that with the current signals almost everything lands on a human, and
the human end was the one rung of the ladder that did not exist. It also needs no UAT, no rate
rule and no classifier, so nothing in it is built on an open question.

**THE DEPENDENCY THAT SHAPED IT.** Reaching T3 at all needs a process that fetches facts,
decides, and branches - and none existed. `forget-mpin` is start -> one task -> end. So the
skeleton was built GENERICALLY rather than for one concern, which means Phase 6 shrinks to
Transport's own specifics.

**`concern-generic.bpmn20.xml` - ONE PROCESS, EVERY CONCERN.** Nothing in the file names a
concern; which facts, which table, which kill switch are all read from the catalogue row at
runtime. A process per concern would have been thirty-eight files that are 95% identical,
where a fix to the shared shape has to be applied thirty-eight times and will not be.

    start -> preFlight -> fetchFacts -> decide -> openTicket -> <agentRequired?>
       yes -> escalationContext -> agentHandoff -> [USER TASK] -> agentCompletion
       no  -> resolve

Nine new delegates and services, and the decisions inside them:

- **`PreFlightDelegate` - trigger B, and the first of the five to exist.** Reads
  `concern_catalogue.mandatory_human` BEFORE facts or decisions, because for those concerns
  there is nothing to decide. B is an OVERRIDE, not a fallback: a row in a table can be
  overruled by row order or by a fact that failed to load; a gate ahead of the table cannot.
- **`DecideDelegate` calls the DMN from Java rather than a BPMN Business Rule Task.** A
  businessRuleTask names its decision key in the process file, which would put the same
  pointer in two places - `dmn_key` and the BPMN - free to disagree. That is the one thing
  ADR-006 exists to prevent. The decision is still configuration; only the lookup moved.
- **EVERY PATH OUT OF `DecideDelegate` IS SAFE.** Facts failed, table matched nothing, dmn_key
  points at nothing - all end with a person. **And a T2 currently escalates**: no action
  service exists until Phase 6, and telling a partner their wallet has been credited when
  nothing has happened is worse than any delay. That is exactly where the kill-switch gateway
  goes later - the semantics are already "do not automate, send to a human".
- **`OpenTicketDelegate` is GATE 1, and the only place it is crossed.** It sits AFTER the
  decision because whether a case needs a ticket is a property of the ANSWER, not the
  question: the same concern can deflect one partner and require an action for the next.
- **`EscalationContextDelegate` writes the handover BEFORE the wait, and never recomputes it.**
  Recomputing when the agent opens the case would show them a different world from the one the
  decision was made in - the wallet may have moved, the order may have arrived - and then "why
  did the bot do that" stops being answerable. The row carries the facts, the tier, the action,
  this partner's recent tickets, and every action already ATTEMPTED for them. That last one is
  there because the most expensive mistake an agent can make is paying somebody twice.
- **A SIXTH `trigger_reason` VALUE: `CONCERN`.** The five triggers A-E are CROSS-CUTTING - they
  belong to the session and fire whatever the concern. A T3 that came out of the concern's own
  decision table is a different thing, and a report that merged the two would overstate how
  often the cross-cutting triggers fire. It is deliberately not pretending to be one of them.
- **`SessionStepWriter.writeAndWait`** - the first ending that does NOT close the session. The
  conversation is still live and the agent's completion has to write the real ending onto the
  same row. A closed session would also refuse the reconnect the partner is most likely to
  make: coming back to check whether anyone replied.
- **THE QUEUE IS FLOWABLE'S USER TASK STORE, not a table of ours.** The work item and the
  process waiting on it are then the same object and cannot drift apart. A separate agent_task
  table has to be kept in step by hand, and its failure mode - a queue entry whose process has
  moved on, or a process waiting on an entry nobody can see - is the silent kind.
- **`AgentTaskService.complete` closes nothing itself.** It would be easy to close the ticket
  and complete the task afterwards, and then a failure between the two leaves a closed ticket
  with a live process still waiting on it. The process owns the ending; the API only hands it
  the agent's words - which reach the partner VERBATIM, because a note written by a person and
  then reworded by the system is a message nobody actually sent.
- **`ResponseTemplates` is keyed on the DMN ACTION, not the tier.** Two T1 outcomes can say
  opposite things - "we already paid you" and "you did not travel" are both T1. And there is
  no generic fallback prose: an action with no template gets an ending that admits we have
  nothing prepared, plus an ERROR naming the code. "Your request has been processed" would
  read as success for an outcome nobody ever designed.

**`V5__route_concerns_through_generic_process.sql`** points the five non-MPIN active concerns
at it. They were previously catalogued, decidable and UNREACHABLE - an active row naming a BPMN
file that does not exist ends at CONCERN_NOT_AVAILABLE. FORGET_MPIN keeps its own process: it
is the T0 proof, and that assertion is worth more running through its own three-element file.

**What V5 actually turns on:** with UAT unreachable those five route to an agent, exactly as the
signal register describes. What changed is that the path is now REAL - a ticket opens, an
escalation context is written, a task appears in the queue - instead of the partner meeting
"not available".

**`AgentConnectTest`** drives it over HTTP using OTHER_FREETEXT_TRIAGE, which needs no UAT and
no credential and lands on its catch-all every time, so it is a genuine T3 from the real
decision path rather than a fixture pretending to be one. Step 71's restart property is
asserted in-process: the task, its variables and the ticket id are all read back from ACT_RU_*
by a caller holding nothing from the request that created them. The JVM-kill evidence itself is
spike 1; what this asserts is that the build KEPT the property.

**AND THE SEVENTH, in the first run of this phase: `--` INSIDE AN XML COMMENT.** A decorative
rule of hyphens in `concern-generic.bpmn20.xml` is illegal XML. The file would not parse,
`springProcessEngineConfiguration` failed to start, the context died, and **126 run, 95 errors**
followed from one line. The output said `ParseError at [row,col]:[91,12]` and nothing else -
identical in shape to the DMN 1.5 namespace incident, and identically out of proportion to the
mistake. `DeployableXmlTest` now parses every `.bpmn20.xml` and `.dmn` with no Spring context
and fails in milliseconds naming the file, the line and the two causes that have actually
happened here. Decorative rules inside comments use `=` now.

**THE SIXTH INSTANCE OF THE SAME FAILURE SHAPE, found while building this.** Five concerns sat
ACTIVE for two phases pointing at BPMN files that had never been written. Nothing failed:
Flowable was never asked for them, the catalogue looked complete, `activePointersAreNotDangling`
passed because the pointer was non-NULL, and a partner selecting one met "we cannot help with
that here". A NON-NULL POINTER IS NOT A WORKING ONE. Two new guards in
`SchemaAndCatalogueTest` now ask the engines directly whether every `process_key` and every
`dmn_key` on an active row resolves to something deployed.

**NOT AUTHENTICATED.** `/agent/tasks` takes whatever agentId the caller claims. Same decision as
the Togglz console (D-7), written down rather than left to be noticed.

**PLAN RE-ORDERED: TRANSPORT MOVES TO LAST.** Phase 6 and Phase 7 (which depends on it) are
parked until the rate question (D-B) and the path-selector rule (D-C) are answered. Everything
else is unblocked today, and building the money path on two guesses would mean building it
twice. New order: **10 -> 11 -> 12 -> 8 -> 6/7 -> 13.**

**Phase 10 (steps 73-81): DONE.** `mvn test` passes **135/135**. CSAT, trigger A, and the
bounding rule.

**STEP 74 - THE DECISION, which settles open question O-15.** Trigger A is the awkward one: the
other four fire while the process runs, A fires AFTER it has completed. Options were (a) hold
the instance alive on a Receive Task, or (b) let it complete and take the answer on its own
endpoint. **(b)**, and the first reason decides it:

1. **(a) charges runtime state to EVERY resolved session to serve the minority who are
   unhappy.** Every T0, T1 and T2 would hold ACT_RU_* rows until answered or timed out. T0 is
   the highest-volume tier and the one that is supposed to cost nothing.
2. **(a) makes the property everything rests on conditional.** "A completed instance has no
   runtime rows" is WHY nextStep lives on the help_session row (ADR-001). A conditional
   invariant is not one.
3. It matches reality. A partner handed a deeplink has to go and USE it before they can say
   whether it helped; asking in the same breath asks them to rate an answer they have not tried.

**AND THAT DELETED STEP 75's TIMER.** With nothing held open there is nothing to time out.
"Never answered" is `csat_result` staying null, which a report can count. One timer, one job and
one class of stuck session, removed by not holding state in the first place.

- **The bounding rule lives on the payload**, as `SessionView.agentOffered`, decided in closure
  logic. A suppression rule the client owns is one an old app version can ignore. Its failure
  mode is the nastiest in the system: nothing errors, nothing logs, and the 10-20% target just
  quietly stops being met months later - so it is asserted on what the client can render.
- **Trigger B is the stated exception:** a mandatory-human concern keeps the agent option
  whatever the partner answered.
- **A DEFLECTION THAT FAILED IS NO LONGER A DEFLECTION.** When a T0 partner answers No, Gate 1
  is crossed at that moment and a ticket is opened. Correct - but it means the T0 no-ticket
  claim is precisely "no ticket IF the deflection worked", and that needs saying before anyone
  quotes the deflection numbers.
- **Trigger A REUSES an existing ticket.** Same complaint, now escalated. A second row would
  inflate every volume number and hide the thing the trigger exists to surface.

**TWO BUGS THE TESTS CAUGHT, both the same shape - a write that lands on nothing looks exactly
like a write that succeeded:**

1. `writeAndWait` never touched the status, so after trigger A the session sat at
   CLOSED_DEFLECTED **while an agent was working it**. A rejected deflection would have gone on
   counting as a SUCCESSFUL one - biasing the single headline number of the project, in the
   flattering direction, in exactly the cases where the bot was told it got the answer wrong.
   `HelpSession.reopen()` now runs whenever a process waits on a closed session.
2. The rating was written to the ticket BEFORE the escalation created it, so a rejected T0's
   ticket carried null. That field is the record that **this ticket exists because the bot's
   answer was refused** - the number that separates concerns automating badly from concerns
   that are merely busy.

**A FINDING THAT CHANGES THE SIGNAL REGISTER.** `spSatisfied` was listed as arriving with
Phase 10. **It does not, and it cannot.** CSAT is answered AFTER a resolution; the Recharge
decision table reads `spSatisfied` DURING the decision, when the partner has not rated anything
yet. Recharge rule 3 is therefore permanently unreachable as written. The mapping's "SP not
satisfied" must mean something collected in-flow - a second turn in the conversation - not a
satisfaction score. **This is a question for the concern owner, not a build task.**

**Phase 11a (steps 82, 83, 84, 86, 88): DONE.** `mvn test` passes **152/152**. The classifier
boundary, the client, the reroute, and the kill-the-service checkpoint. Steps 85 and 87
(entry-point free text, clarification loop) are 11b.

- **The stub is a REAL BEAN, not a test mock.** `StubClassifierClient` is selected at startup
  whenever `CLASSIFIER_URL` is unset — the state this project is actually in. So the reroute
  path, the confidence floor and the agent handoff are all built and demonstrable now, and a
  real model changes one property in one file. It warns loudly at boot, and it answers NO_MATCH
  for anything that is not a fixture phrase, which is most real text. A stub that pretended to
  understand would make every demo a lie.
- **THE HALLUCINATION GUARD READS THE CATALOGUE.** A category counts only if it names a concern
  that is ACTIVE right now. Correct by construction as concerns are switched on, and a model
  trained on the old taxonomy cannot route anyone into ARW_TO_BANK — seeded, real, unbuilt. The
  Python service checks the same thing independently: that process can be redeployed or swapped
  for another vendor's, and the guarantee must not leave with it.
- **TRIGGER E IS A RULE, NOT CODE.** `riskFlagged` is now the FIRST row of both reroute tables.
  Under FIRST a confident match sits below it, so without that row a partner typing "accident ho
  gaya aur mpin bhi bhool gaya" is handed a self-serve deeplink by a model that was 95% sure.
  Inert until a real model supplies the flags, but the ordering is right now rather than later,
  and as a table row it stays tunable without a deployment.
- **Both failure modes return the SAME value as an honest no-match.** A dead classifier, a
  timeout, an open breaker and "I don't recognise this" are one answer, so there is no second
  code path that could behave differently under load — exactly when nobody would notice.
- **`RerouteDelegate` moves the session rather than forking it.** One conversation, one row, and
  the concern on it is the one actually being handled — otherwise every report attributes a
  rerouted case to the triage flow it passed through. Two guards: you cannot reroute INTO a
  triage concern (that is an infinite chain with no error in it), and the target must be active
  and built.

**A REAL BUG THE TESTS CAUGHT: a reroute was crossing Gate 1.** `openTicket` runs before the
gateway, and a reroute carries tier `-`, not `T0` — so the triage concern filed a ticket and
THEN handed the partner on, where the real concern decided for itself. Every rerouted case would
have been counted twice, and a partner whose text was correctly recognised and deflected would
have finished with a ticket no tier ever asked for.

**`tools/preflight.py` — WHY IT EXISTS.** This project cannot be compiled in the environment its
code is written in: Maven Central is refused at the org proxy, and the desktop side has Java 11
and no Maven. Every build runs on the developer's machine, so the feedback loop is a round trip
through a person and a wasted trip is expensive. Seven of the suite's guards are pure text and
structure — XML parses, DMN namespace, every declared fact documented, both statements of the
fact contract agreeing, every catalogue pointer resolving, every `${delegate}` naming a real
bean, and (from 12b) every `@Value` placeholder with no default naming a property that exists.
The last two are not in the Java suite and should be: an unresolvable delegate expression fails
at RUNTIME on the first partner to reach that step, and an unresolvable placeholder kills the
whole context at startup.

    python3 tools/preflight.py

It replays the migrations in order rather than reading the seed, because V4 deactivates two
concerns and V5 repoints five — the naive version reported five failures that were not real, and
**a noisy guard gets ignored, which is worse than no guard.** It cannot compile Java or catch a
logic error; it exists to stop spending real runs on the mistakes it can catch.

**Phase 11b (steps 85, 87): DONE.** `mvn test` passes **159/159**. Entry-point free text and
the clarification loop. **Phase 11 is complete.**

**I BUILT THE WRONG THING FIRST AND CAUGHT IT BEFORE SHIPPING.** The obvious design sends a
confident match straight into the matched concern — and that quietly creates a SECOND routing
mechanism beside `RerouteDelegate`, which already does that job for text typed inside a concern.
Two mechanisms for one behaviour means two places to fix a routing bug, and the one that gets
fixed is the one that gets demoed. The entry point now hands the partner to the TRIAGE CONCERN
and lets its process, its table and `RerouteDelegate` route — the same path in-concern text
takes. `IntentRouter` answers exactly one question: ask again, or go?

- **The confidence floor is still not in Java.** IntentRouter asks the triage table what it makes
  of the text and reads the answer.
- **One model call per turn.** The entry point must classify to decide whether to re-prompt, so
  the classification is carried into the process as `factsJson` and `FetchFactsDelegate` skips
  its own fetch. Optional, not a contract — without it the process simply re-fetches.
- **Risk skips the loop.** Asking someone who just described an accident to "say a bit more" is
  the wrong response to the one case where speed matters most.
- **Every attempt is kept**, joined into `entry_free_text`; only the newest is classified. "They
  told us three times and we never understood" is the thing worth knowing, and the last attempt
  alone hides it.
- **The loop lives on the session, not in BPMN** (V6 `clarification_attempts`). The design puts
  it in the process; at the ENTRY point no process exists, because no concern has been chosen —
  which is the whole situation the loop resolves. The in-concern loop still belongs in BPMN.
- **`clarification_attempts` is also a metric worth reading:** how often a partner has to
  rephrase before we can place them measures how well the taxonomy matches how partners actually
  describe their problems, and it is the one signal that would justify adding a concern.

**THREE BUILD FAILURES, all instructive, all now in §7.** Two beans satisfying conditions that
were only exclusive over two of three states; a stale detached entity; and (11a) a reroute
crossing Gate 1. `tools/preflight.py` gained a rule for the first.

**Phase 12a (steps 89, 90, 92): DONE.** `mvn test` passes **165/165**. The money path end to
end: a switchable mock PayU gateway, the wallet credit service, the kill switch, and the first
T2 that actually moves money.

- **ONE PLACE MONEY MOVES, and the ORDER OF ITS FOUR STEPS IS THE WHOLE SAFETY ARGUMENT.**
  `PerformActionDelegate` records the attempt and COMMITS IT, calls the gateway, records the
  outcome, and never retries. Record-after-call is the intuitive order and it is the one that
  pays twice: the call succeeds, the pod is rescheduled, nothing was written, and the retry
  looks like a first attempt. Committing first means the evidence that we called somebody
  survives the failure that makes the retry happen.
- **THE GUARD IS KEYED ON THE GATEWAY'S REFERENCE, NOT ON THE TICKET** (step 90). A partner
  who opens a second conversation about the same failed recharge gets a second ticket — that
  is correct, it is a second complaint — and the duplicate ticket must not be able to produce
  a duplicate payment. `AUTO_CREDIT_WALLET:ref:<order id>` collides across tickets, sessions
  and restarts; `...:ticket:<id>` would not have. Actions with no external reference keep the
  ticket-scoped key, so the shape degrades to the safe thing rather than to no key at all.
- **A duplicate is not an error and must not read like one.** The second attempt returns
  `INFORM_ALREADY_CREDITED` at T1 and the partner is told, in words they can check against
  their balance, that the money is already there. Silence and a generic success line are both
  worse than the truth.
- **THE KILL SWITCH IS A REFUSAL TO AUTOMATE, NOT AN OUTAGE.** Flag off sends the concern to a
  human; it never returns an error to the partner. A concern with no flag is allowed, so
  adding a concern cannot accidentally ship it disabled. It gates the T2 in `DecideDelegate`,
  before any call is made — not inside the gateway, where a half-executed action would already
  have left the building.
- **A FAILED ACTION MOVES THE TICKET, not just the process variables.** Gate 1 wrote that row
  as a T2 before the external call, correctly. The call then failed and a person picked it up,
  and a row still saying T2 would be counted as automation that worked — the failures would be
  invisible in exactly the metric meant to decide whether automating this concern was a good
  idea. `escalateAfterFailedAction` is a separate committing transaction for the same reason
  the ticket is one.
- **Every money-moving action has its own sentence.** There is still no generic fallback prose,
  and the ERROR that the refusal produces is how the missing `AUTO_CREDIT_WALLET` template was
  found: every successfully credited partner had been reading "we have nothing prepared". Worth
  carrying into the demo — **assert on what the partner reads, not only on what the row says.**

**SIX FIX ITERATIONS, and the two structural ones are in §7.** A `REQUIRES_NEW` insert could not
see the ticket the outer transaction had only flushed (foreign key violation); fixing that by
committing Gate 1 on its own then broke twenty tests, because the committing transaction READ the
session and got its pre-request state and filed every ticket with a null concern. A NOT NULL
column caught that; a nullable one would have hidden it entirely.

**Phase 12b (steps 91, 93): DONE.** `mvn test` passes **181/181**, first run. **Phase 12 is
complete.** A kill switch's position now survives a restart, the console that flips it cannot
exist without a credential, and a threshold can be changed without a developer.

- **A FLAG'S POSITION IS AN OPERATIONAL DECISION, so it belongs in the database.** Until this
  step the flags were in memory, which means a restart put every switch back to its default.
  Consider what that is: somebody turns OFF automatic wallet credits at 2am because the
  gateway is double-paying, and the next deploy — routine, unrelated, by somebody who never
  heard about the incident — turns them back on. The switch that exists to stop money moving
  is undone by a process whose whole purpose is to be uneventful.
- **The `TOGGLZ` table is Togglz's, and Flyway does not own it.** That looks like an exception
  to a standing rule and is not: the rule is about OUR tables. A library creates and migrates
  its own storage exactly as Flowable does with its ~25 `ACT_*` tables, and hand-writing a
  migration to match a schema we do not control means guessing at column widths that the next
  version may change underneath us. `ticket`, `help_session` and `ticket_action` are ours and
  Flyway owns every one.
- **NO CACHE, AND THAT IS THE POINT.** Togglz ships `CachingStateRepository` and a flag is read
  on every automated decision, so the temptation is obvious. Refused, because of what step 92
  actually asserts: flip the switch and the NEXT request stops automating. With a cache the
  next request keeps paying until a TTL expires — precisely the window an operator is trying
  to close.
- **A feature with no row reads as OFF**, so a fresh database automates nothing and pays
  nobody. Correct, and it creates a new way to be confused: "off" and "never configured" look
  identical at runtime. `FeatureNameValidator` now prints every switch's position at startup,
  because the alternative is an engineer checking the catalogue, the table and the delegate and
  finding nothing wrong — since nothing is wrong, the switch is simply off.

**THERE IS NO CONFIGURATION OF THIS SERVICE THAT PRODUCES AN UNAUTHENTICATED CONSOLE.** Not
"secured by default", not "remember to set it in production". With no credential the servlet is
NOT REGISTERED: the URL 404s and there is nothing behind it to attack. A misconfiguration
removes the page instead of exposing it, which is the direction a mistake should fail in when
the page can stop — or restart — automatic payments to every partner in the country.

- **`togglz-console` is a SEPARATE ARTIFACT from the starter.** With it missing,
  `togglz.console.enabled: true` mounted nothing and said nothing about it, which is what the
  configuration had said for three phases. Declared explicitly now, so the page's existence is
  a line in `pom.xml` rather than a transitive accident.
- **The starter's switch is off and the registration is ours**, because the condition that
  matters is not a boolean — it is "only if a credential exists". One owner. And a property
  cannot express it, which is the same shape as the Phase 11a incident: the condition is now an
  `if` inside a method where missing, empty and set are visibly one decision.
- **A FILTER ON ONE PATH RATHER THAN `spring-boot-starter-security`, and it is a trade.**
  Spring Security is the right Wave 1 answer and D-7 says so. It is the wrong POC answer
  because it secures EVERY endpoint the moment it is on the classpath and enables CSRF on every
  POST — the 165 existing tests would then have been passing or failing on how well a
  `SecurityFilterChain` was written blind, which is a large blast radius bought for one page.
  A test asserts the claim that justifies the choice: the partner API still answers with no
  credentials. When Spring Security arrives it replaces the class and deletes it.
- **The console test is the only one in the suite that starts a real server, and it has to.**
  MockMvc calls the Spring MVC dispatcher directly: it never reaches a servlet registered
  beside the dispatcher and never runs the filter chain. A console test on MockMvc would pass
  without ever executing the code that protects the console — the most expensive kind of green
  there is.

**STEP 93 — THE CHECKPOINT THE WHOLE DMN ARGUMENT RESTS ON.** Every decision here is in a table
rather than in Java, and the reason given for that, repeatedly, is that a business owner can
change a threshold without a developer, a rebuild or a restart. That was an assertion in
documents until now. Rs250 is paid under the Rs300 cap; the cap is edited to Rs200 in the XML;
that one file is redeployed into the running engine; the same claim with the same facts is
refused and goes to a person. Nothing recompiled, nothing restarted.

- **A third test asserts `30000` appears in NO Java source anywhere.** The step is worth nothing
  unless the table is the only place the cap lives. A constant in a delegate that agrees with
  the table today turns tomorrow's redeploy into a silent disagreement — the table says Rs200,
  the code says Rs300, and which wins depends on which is consulted first. That is worse than
  having no table at all.
- **The test puts the original back in `@AfterEach`.** A deployment is engine-wide and outlives
  the class, so a lowered cap would silently follow every test that runs afterwards — the same
  shared durable state that burned `MoneyPathTest` in 12a.

**A NEW PRE-FLIGHT RULE, and it was written because this phase added four of the thing it
catches.** Every `@Value("${x}")` with no default must name a property `application.yml`
declares. An unresolvable placeholder is not a warning and not a null — it is an exception while
the context is being built, so the context does not start and EVERY `@SpringBootTest` errors at
once. This project has now had that outcome twice from two different causes and the output looks
identical each time. The rule was checked against a negative control before being trusted: a
guard that passes vacuously is worse than no guard.

**AND THIS PHASE WENT GREEN ON THE FIRST RUN**, which is the first time that has happened on a
phase of this size. The difference was not luck: the two genuinely uncertain library APIs were
each replaced with the version that cannot be wrong — the simple `JDBCStateRepository`
constructor instead of a builder whose method names I could not verify, and a query against
`information_schema` instead of a hard-coded table name out of Togglz's internals. **When the
compiler is on somebody else's machine, prefer the API you are certain of over the one that is
slightly nicer.**

**Phase 13a (steps 94, 95, 96): DONE.** `mvn test` passes **199/199**. The service publishes
its own contract, the demo has a world to decide about, and the demo is an executable file
that checks itself.

- **THE CONTRACT IS PUBLISHED BY THE SERVICE THAT IMPLEMENTS IT** (94). A spec maintained
  beside the code disagrees with it the first time somebody is in a hurry, and nothing
  anywhere reports the disagreement. `OpenApiPublishedTest` names all ten endpoints
  explicitly, because a springdoc that quietly stops scanning a controller does not throw —
  it publishes a SMALLER document, and a smaller document looks exactly like a correct one
  unless something counts. The description carries the one thing an integrator will
  otherwise assume wrongly: the backend owns the whole flow and the client is a renderer.
- **THE DEMO GETS A SYNTHETIC WORLD** (96). Six of the eight concerns decide by reading UAT
  and there is no grant, so with UAT off the demo was eight different ways of saying "a
  person will look at this" — a decision layer fully built, fully tested, and completely
  invisible. `demo/fixtures.json` supplies RAW ROWS: the path selector, the duplicate-credit
  guard and the TAT service all run for real against them. A fixture handing over a finished
  `transportPath` would demonstrate nothing but the fixture file.
- **THREE THINGS STOP A FIXTURE BEING MISTAKEN FOR EVIDENCE, and none is a comment.** The
  bean exists only under the `demo` profile; it FAILS AT STARTUP if a real UAT datasource is
  configured alongside it; and every fixture read logs itself at WARN so a sceptical reviewer
  watching the log can see which answers came from where. Half-real data is worse than either
  kind — afterwards nobody can separate the decisions that were evidence from the ones that
  were invented.
- **FIXTURE TIMES ARE RELATIVE, NEVER DATES.** "Placed six days ago", not a timestamp. A fixed
  date demonstrates one thing this week and a different thing next month, silently, and the
  first person to notice would be standing in front of an audience.
- **THE DEMO IS THE SCRIPT** (95). `tools/demo-run.sh` covers 17 of the 20 coverage-matrix
  rows, and it ASSERTS every one — exit non-zero if any case fails. A live demo is a
  performance, and performances are edited by the performer: the cases that get skipped when
  a room is watching are exactly the ones worth seeing. If it goes red on stage, the right
  thing has happened.
- **`DemoControlController` is `@Profile("demo")`, so it DOES NOT EXIST otherwise.** Three
  matrix rows are about the world misbehaving — the gateway dying mid-call, a kill switch
  pulled, the same claim raised twice — and misbehaviour has to be arranged. Until now it was
  arranged from inside test code, which made the most important behaviour in the system the
  one part nobody could watch. `DemoIsolationTest` asserts 404 and zero beans in an ordinary
  run: ABSENT, not merely refused. "We remembered the annotation" is not a guarantee when the
  annotation is one invisible line whose removal breaks nothing anybody would notice.
- **The demo exposes the ticket COUNT**, because the BRD's central claim is that a deflection
  creates no ticket — not a closed one, not a zero-cost one, none — and a demo where nobody
  can see the count takes on trust the one claim the whole business case rests on. My first
  version of that check read a field `SessionView` does not have, so it would have passed
  forever while asserting nothing.

**WHAT THE DEMO CANNOT SHOW, stated in the script itself rather than left to be noticed.**
UPHOLD, T2-state-change-without-money and the concern-level durable counter all come only from
the two parked violation concerns (D-A). Transport decides correctly but CANNOT PAY, because
D-B is unanswered and `computedAmountPaise` is therefore null on every real read. The DMN
redeploy is proven by a test rather than the script: there is no redeploy endpoint and there
should not be one.

**Phase 13b (steps 97, 98): DONE.** `docs/POC-ASSESSMENT.md` — the five answers, the signal
position, where the LLM decision stands, and the verdict. **THE POC IS COMPLETE: 98 of 98 steps
are Done, Parked or Blocked on somebody else.**

**THE VERDICT IS GO, WITH TWO CONDITIONS.** Neither stop condition triggered: U-1 (a paused
conversation survives the JVM dying) and U-3 (the action record survives a rollback) both
passed, each with its negative case demonstrated — the annotation removed, the test failing.
That is what makes the passes mean something.

- **U-2 IS NOT ASSESSED AND MUST NEVER BE REPORTED AS PASSED.** There is no key and no real
  ticket text, so there is no accuracy number. The fixtures were written by the same hand that
  wrote the classifier, and fixtures written that way always classify well — that is a mirror,
  not evidence. What the POC demonstrates is that free text is WIRED correctly and FAILS
  correctly. If it is ever summarised as showing that free text works, that summary is wrong.
- **U-4's ANSWER IS A THIRD STATE THE DESIGN DID NOT ANTICIPATE.** The question assumed a
  binary — the capability exists and Layer 6 is an integration, or it does not and Layer 6 is a
  build. In fact the CONCEPTS do not exist in the data at all: seven violation codes, none of
  them period leave or missing product, and leave recorded as duration rather than reason.
- **U-3's real finding is larger than the question.** `REQUIRES_NEW` isolates in BOTH
  directions, which the original spike could not have seen because its test committed a ticket
  first. The rule that came out of it: if we are about to call an external system on behalf of
  a row, that row and everything it references must be as durable as the call.
- **U-5 passing is a governance requirement, not a celebration.** A person who can change a
  payment threshold without an engineer can change it without a reviewer too. The review gate
  moves from theoretical to urgent.

**THE HEADLINE NUMBER, AND IT IS NOT FLATTERING: 7.7%.** As the service actually runs today,
only `FORGET_MPIN` resolves without a human — 738 of 9,542 known monthly cases. The decision
layer is correct and largely idle, because 11 of 26 rules read a fact that is never populated.
Nothing is broken; every dead rule falls back to a person rather than to a wrong answer. But
any deflection figure quoted from this system today measures what it can READ, not what it can
DECIDE.

**The highest-value remaining action is not engineering work.** A `SELECT` grant and four
schema answers move 11 rules from dead to live.

**AFTER THE PLAN: the partner console (off-plan).** The 98 steps are finished; this was built
because the person who wrote the system could not narrate it, which is the only honest test of
whether a demo works. `mvn test` passes **206/206**.

**THE PROBLEM IT SOLVES.** Everything here is defensible and almost none of it is VISIBLE. A
partner sees one sentence; a ticket row shows a tier and an action. In between — which facts were
read, which row of which table matched, which rows did not — there was nothing to look at, and
that is the entire interesting part. A reviewer cannot tell a good decision from a lucky one
without it, and neither can an agent asking "why did this reach me".

- **`/console` renders whatever the backend describes and contains NO concern name, NO tier and
  NO rule.** That is the architecture's central claim in a form somebody can disbelieve and then
  check: `DemoConsoleTest` extracts all 38 concern codes from the migrations and fails if one
  appears in the page. The claim only stays true while nobody helps — "transport needs an order
  id, so let us just ask when transport is picked" is one line, and after it, adding a concern is
  a client release again with nothing anywhere reporting the change.
- **`/console/trace/{session}` is the panel that answers the question.** Facts read (nulls shown,
  because a null is why most rows do not fire), the table as a grid with the fired row
  highlighted, and each row's own sentence FROM THE `.dmn` FILE. Flowable can hand back a
  deployed table; it cannot hand back the comments, and in this project the comments are the
  policy.
- **The fired row comes from Flowable's audit trail**, not from working out which row must have
  matched. Working it out means re-implementing the expression language beside the engine — a
  second source of truth for the one thing that must never have two.
- **Nothing is re-run.** The trace is captured BY the decision as it is made. Re-evaluating to
  explain gives a second answer, and the two diverge the moment a fact changes underneath them —
  which for a money-moving action is exactly when somebody is asking.
- **`DecideDelegate` now calls `decideExplained`**, so a money-path call changed for a demo's
  benefit. `DecisionExplainedTest` asserts the two paths return identical answers across five
  fact sets, and that exactly one row fires and it is the first that matched.

**THE PROFILES ARE SPLIT, and the reason is worth keeping.** The console started inside the
`demo` profile — which REFUSES to start beside a real UAT datasource, correctly. The consequence
only surfaced when somebody asked the obvious question: **the console could never show real
data.** By construction, not by accident. It now has its own profile and depends on nothing
synthetic, and the banner is READ FROM THE SERVER (`/console/state`) rather than written into the
page, so it cannot say the wrong thing because somebody edited a string.

    demo,console   fixtures, gateway controls, flag flipping
    console        whatever the service is really reading

Flag FLIPPING stayed in the demo controls. Against real facts the operator surface is the
authenticated Togglz console, and an open endpoint that turns automatic payments on and off would
undo the entire reason for putting a gate in front of that page.

### Three failures from building it, all the same shape

**A feature built, tested, documented as done — and unreachable.** Step 86's in-concern free text
had its table, its delegate and both guards written and green; every test drove reroute from the
ENTRY point, and `handleL2` discarded the text. No exception, no log line. Found by the demo
script driving the real API. *A test suite proves the components work; only something driving the
real API proves they are WIRED.*

**A panel that failed silently.** The explanation card started `hidden` and only appeared on
success, so "not built", "call missing" and "request failed" were one blank space. It now
un-hides FIRST and prints what went wrong. **The same shape as everything else in §7: something
absent behaving exactly like something present.**

**A verification that matched the wrong thing.** The check for "is `explain(view)` called?" found
the function DEFINITION and reported success. Count call sites (`^\s*name(`), never occurrences.

**And one worth keeping for its own sake:** a nested record named `Entry` inside a `LinkedHashMap`
subclass resolves to `Map.Entry`, so `removeEldestEntry` overrode nothing. Only `@Override` caught
it; without the annotation the map would have grown forever.

### Blocked on the project owner

- **Four schema questions, which are one conversation.** The job's own lat/lng (nothing on
  `tbl_order` carries it), which column links a wallet credit to a PayU transaction, the
  `unassign_status_code` -> NR/CR mapping, and which column links an order to a delivery
  pincode. Together they unblock 6 of the 11 rules the register lists as unreachable.
  TABLE names are settled - they came from the empapi source in step 46, and every column a
  provider reads is declared and checked at startup by `UatSchemaProbe`.
- **Confirmation the UAT user is `SELECT`-only by GRANT.** The code cannot write; a GRANT
  means nothing can.

### Blocked on procurement

- **The LLM track (plan steps 10–11, 15–22).** No API key, no model decision. Steps 7–9
  (task spec, NFRs, PII position) need nobody and can be done at any time.

### Unresolved, raised more than once, never answered

- **D-1, hosting.** The Decision Brief still lists hosting as an open decision while the
  Engineering Design states `empapi` as settled. One of the two is wrong. Ask.

---

## 5. The service — what exists and why

Spring Boot 3.2.5, Java 17, Postgres in production, H2 for tests.

```
in.yesmadam.botin
├── BotinApplication.java
├── api/        controllers + the client contract (dto/)
├── config/     datasource wiring — the UAT isolation lives here
├── session/    HelpSession, Ticket, HelpSessionService — the flow
├── catalogue/  ConcernCatalogue, CatalogueService — the L1/L2 taxonomy
├── facts/      how a concern fetches the data it needs to decide
└── safety/     the two guards
```

Packages `process/`, `decision/`, `action/` and `agent/` are named in the plan and arrive in
later phases. They do not exist yet. `api/` arrived in Phase 2.

### UAT isolation — four structural protections

Hibernate creates tables. Flyway creates tables. Flowable creates about 25 of its own. All
of them do it by default, against whatever datasource they find. So the isolation is not a
setting — it is four facts, all in `config/`:

1. The UAT datasource is **not `@Primary`** — nothing auto-wires to it by accident.
2. It has **no `EntityManagerFactory`** — no JPA at all, so `ddl-auto` cannot see it.
3. **Flyway is bound to the primary datasource** explicitly.
4. **`FlowableConfig` hands Flowable the primary `DataSource` by name**, rather than
   letting the engine choose one.

What is exposed from UAT is a single `JdbcTemplate`, `setReadOnly(true)`, 5-second query
timeout. `ConditionalOnUatEnabled` switches the whole thing off, which is why `mvn test`
needs no UAT connection.

### The database — 7 tables

`concern_catalogue`, `help_session`, `ticket`, `ticket_action`, `conversation_message`,
`escalation_context`, `sp_counter`.

- **`concern_catalogue`** is the taxonomy, and four of its columns are *pointers*:
  `process_key` → a BPMN process, `dmn_key` → a decision table, `fact_provider` → a Java
  bean, `togglz_flag` → a kill switch. **Adding a row does not add a concern.** 38 seeded,
  8 active.
- **`help_session`** is created the instant Help is tapped — *before* any ticket exists.
  That ordering is Gate 1 made concrete.
- **`sp_counter`** was not in the original design. The concern mapping forces it: the
  pooled emergency cap, the monthly period-leave count and the 25-job cycle allowances are
  **state this engine owns**, not facts it can read from anywhere else.

### The two safety guards

**`TicketActionRecorder`** — `@Transactional(propagation = REQUIRES_NEW)`. This is ADR-005
and it is the money-safety mechanism. An attempt row written inside it **survives the
caller's transaction rolling back**, so a retry cannot pay twice with no record of the
first attempt. It is the only place allowed to write a money-adjacent attempt row, so
there is one thing to audit rather than many. The rollback test is in the suite
specifically to fail if someone deletes the annotation.

**`FeatureNameValidator`** — runs on `ApplicationReadyEvent` and refuses to boot if any
`concern_catalogue.togglz_flag` is not a declared `BotinFeature`. It exists because a spike
proved Togglz resolves an unknown flag name to `false` **silently — no exception, no log
line**. With fail-to-human semantics, one typo routes an entire concern to agents and
nothing anywhere reports it.

### Tests — 6, and what each is for

`SchemaAndCatalogueTest` (3): all seven of our tables exist after Flyway · more than 15
`ACT_*` tables exist, proving our schema and Flowable's coexist in one database · the
catalogue returns only active concerns.

`TicketActionRecorderTest` (3): **the attempt row survives caller rollback** · a second
attempt with the same idempotency key short-circuits · the outcome write lands.

---

## 6. The R&D ladder — what was proven

| # | Spike | Result |
|---|-------|--------|
| 1 | Flowable pause / JVM death / resume | PASSED — ADR-001 holds |
| 2 | DMN hit policy and row order | PASSED — row order **is** logic; no catch-all returns `null` |
| 3 | `REQUIRES_NEW` through rollback | PASSED **both** directions |
| 4 | Togglz / Resilience4j / Flyway | 16/16 — and found the Togglz hole |
| 5 | FastAPI + Pydantic classifier contract | 12/12 |
| 6 | Component notes write-up | Delivered |

Spike numbering follows the R&D ladder steps, not the order they were delivered.

---

## 7. Landmines — things that cost time once and must not cost it twice

- **Togglz resolves an unknown flag name to `false` silently.** No error anywhere. This is
  why `FeatureNameValidator` exists and why it is mandatory, not advisory.
- **`ACT_RU_*` rows are deleted when a process instance completes.** `nextStep` must
  therefore be written to `help_session` by the final Service Task, never read back from
  process variables.
- **Spring self-invocation silently disables `@Transactional`.** Calling an annotated
  method from another method of the same bean bypasses the proxy. The annotation appears
  to be there and does nothing.
- **DMN `FIRST` hit policy means row order is logic.** A table with no catch-all row
  returns `null`, not a default.
- **H2 cannot run `MODE=PostgreSQL` in this project.** Flowable detects H2 and emits H2
  DDL containing the `IDENTITY` type, which H2 rejects while in PostgreSQL mode. The core
  schema is therefore written in types both engines accept natively — no `TIMESTAMPTZ`
  (use `TIMESTAMP WITH TIME ZONE`), no `TEXT` (use `VARCHAR(n)`; `TEXT` is an alias for
  `CLOB` in native H2 and Hibernate's `validate` rejects it for a `String` field).
- **`flyway-database-postgresql` is a Flyway 10 module.** Spring Boot 3.2.x manages Flyway
  9.x, where Postgres support is inside `flyway-core`. Declaring it breaks the build with a
  missing-version error.
- **A DMN input expression that FAILS takes the whole decision down - catch-all included.**
  A catch-all protects against no row matching. It protects against nothing if evaluation
  itself dies. The floor is one level lower than it looks. Found via a comma-separated list
  (`"A","B"`) in an inputEntry: documented as valid, did not evaluate here, and returned
  NOTHING from the entire table. **Write one row per value instead.** The tell was that only
  the tests with a non-null value for that input failed - with null the expression never had
  to resolve.
- **An ABSENT fact is not the same as a NULL fact.** In strict mode (the default, and the
  right default) an input naming a variable that is not in the map at all is an evaluation
  error, which kills the decision. A key present with a null value evaluates fine. So a fact
  provider must put EVERY key the table reads into the map, null included. `TABLE_INPUTS` in
  `DecisionTableTest` is that contract, written out by name.
- **A name that does not exist behaves exactly like a value that is false.** Four instances
  now: an unknown Togglz flag resolves to `false`; process variables vanish on completion; a
  DMN input naming a fact the provider spells differently silently never matches
  (`arrivedAt300m` vs `arrivedAt300metre` - it would have sent every Path 2 claim to an
  agent); and absent-vs-null above. Treat every cross-component name as a contract with a
  test behind it.
- **Maven Central is blocked from the Claude cloud container** (403 at the egress proxy).
  Java cannot be compiled there. Builds happen on the project owner's machine. The Linux
  shell Claude gets on that machine is a *separate sandboxed VM*, not the Mac — it has no
  Maven and cannot see the real `~/.m2`.

---


### An assigned @Id with no @Version means `save()` MERGES, and your object goes stale

`HelpSession` and `Ticket` carry an assigned UUID. Spring Data's `isNew()` is therefore
FALSE, so `save()`/`saveAndFlush()` call `merge()` — which returns a MANAGED COPY and
leaves the object you passed in DETACHED.

Cost an hour in Phase 11b. `start()` created a session, flushed it, and kept the original.
The process then ran perfectly — classified, rerouted, wrote the deflection, closed the
session — onto the managed copy. The detached object still read null for `nextStep`, so the
partner got "we cannot help with that" after a completely successful resolution. The ticket
count was right, the agent queue was right, nothing errored.

**Rule: assign the result.** `session = sessions.saveAndFlush(session);` whenever the row is
about to be touched by anything else — a process, a delegate, another service. The menu path
never hit this because there the session comes from `findById` and is already managed; only a
path that CREATES a row and immediately hands it to a process is exposed.

### Two @ConditionalOnProperty beans can both match: missing, empty and set are THREE states

    HttpClassifierClient  @ConditionalOnProperty(name = "botin.classifier.url")
    StubClassifierClient  @ConditionalOnProperty(..., havingValue = "", matchIfMissing = true)

Exclusive over "missing" vs "present", and correct for two phases — until
`url: ${CLASSIFIER_URL:}` was added to application.yml. That makes the property PRESENT BUT
EMPTY, which satisfies BOTH. Two beans, one injection point, **the entire context failed to
start: 159 run, 126 errors.**

Note the shape: a line of configuration in a different file changed which beans exist, and
nothing about either annotation hints the other exists. Replaced with `ClassifierConfig` — one
`@Bean` factory with an if statement, where missing, empty and set are visibly one decision.
`tools/preflight.py` now reports any property guarding more than one bean.

### REQUIRES_NEW is isolated in BOTH directions, and the obligation travels UP the reference chain

A separate transaction cannot see rows the outer one has only flushed. That is the half everyone
knows. The other half cost most of Phase 12a: **it also cannot see the outer transaction's
CHANGES to rows that already exist.**

    Referential integrity constraint violation:
    PUBLIC.TICKET_ACTION FOREIGN KEY(TICKET_ID) REFERENCES PUBLIC.TICKET(ID)

`TicketActionRecorder` is `REQUIRES_NEW` by design (ADR-005) so an attempt row survives a
rollback. Its insert therefore could not see the ticket Gate 1 had flushed in the request's own
transaction. The ADR-005 spike never met this because its test committed a ticket first and then
exercised the recorder; in the real flow both happen in one request, which is the case that
matters.

Making `TicketService` commit on its own fixed the FK and broke twenty tests, all the same way:
the new transaction LOADED the session to read the chosen concern, got the row as it was BEFORE
this request, and filed every ticket with a null `l1_concern`. The fix is that the committing
method reads nothing — every field is passed in — and `SessionOpener` commits the session first.

**The rule worth keeping: if we are about to call an external system on behalf of a row, that row
and everything it references must be AS DURABLE AS THE CALL.** An attempt row pointing at a
ticket that was rolled away is a dangling audit record. The foreign key turned that into a loud
failure; in a schema without one it is a quiet corruption of the only evidence we keep.

### One writer per row per request, or a committed write gets silently reverted

`CsatService.escalate` read the ticket, then started the escalation process, which committed
`trigger_reason = A` from its own transaction. The outer transaction then flushed ITS copy — read
before that commit, and the one Hibernate's first-level cache believes in — and the trigger reason
went back to `CONCERN`. No error, no conflict, no `@Version` to notice.

Every rejected deflection would have been filed as an ordinary concern escalation: the number that
separates "this concern automates badly" from "this concern is busy" would have been zero forever.

**Rule: decide which layer owns the row and let only that layer write it.** The CSAT result is now
carried into the process as a variable and written by `TicketService` alongside everything else,
rather than by the caller afterwards.

### A durable guard means tests cannot share fixtures

`MoneyPathTest` used one order id across its cases, and the duplicate-payment guard did exactly
what it was built to do: refused every payment after the first. The failures looked like broken
code and were a working guard.

Any state deliberately built to OUTLIVE a request — an idempotency key, a committed audit row, a
feature flag's persisted state from step 91 — makes shared test data a bug. Per-test references,
and read the failure as evidence the guard works before reading it as a defect.

### A catch-all @ExceptionHandler(Exception) turns every mistyped URL into a 500

`ApiExceptionHandler` ends with a handler on `Exception`, which is right: a partner-facing
surface must never leak an internal message. It was also catching SPRING'S OWN way of saying
"that is not a real address", so every wrong URL in the service answered **500**.

The damage is not to the caller:

- An app team with a typo is told the backend is broken, and files a bug against us.
- Every scan, probe and stale bookmark writes a full stack trace to the error log.
- **Real 500s sit in that noise.** An alert on 5xx rate is worthless when the baseline is
  "somebody browsed a wrong path", and the first instinct during a genuine incident is to
  assume it is more of the same.

Nothing failed and nothing was logged as wrong — the service was lying about whose fault it
was, in the direction that costs an on-call engineer the most. Two siblings were in the same
state: a wrong verb and malformed JSON were both 500s, now 405 and 400. `ErrorMappingTest`
locks all three. **Any handler for a Spring-thrown exception must sit ABOVE the catch-all.**

It surfaced from a test that asserted a SPECIFIC status code rather than "not a success" —
`DemoIsolationTest` wanted a 404 from an endpoint that should not exist. A test asserting
"not 2xx" would have passed on the 500 and this would have shipped.

### MockMvc decodes a response as ISO-8859-1 unless the response names a charset

`getContentAsString()` with no argument uses the RESPONSE's encoding, and falls back to
ISO-8859-1 — which springdoc's `application/json` triggers, because it does not name one. An
em dash came back as `â` plus two control characters, and the failure read as though the
value were wrong rather than the way the test had read it.

Pass `StandardCharsets.UTF_8` for any assertion on non-ASCII text through MockMvc. The
Hinglish templates are Roman-script ASCII today so they do not hit this; the first one
carrying a rupee sign or a Devanagari character will, and it will look like a bug in the
message rather than in the test.

### A feature can be built, tested, documented as done — and unreachable through the API

Plan step 86 is in-concern free text: the partner picks `VIOL_R4_OTHERS`, types a reason that
names a different concern, and is rerouted. The decision table was written, `RerouteDelegate`
was written, both reroute guards were written, and `RerouteTest` was green.

**Through the API it did not work.** `handleL2` took the selection and DISCARDED the text, so
the concern ran with nothing to classify and the partner sat exactly where they started. No
exception, no log line, no failing test.

The reason it hid so well is that every test drove reroute from the ENTRY point, where text
arrives before any concern exists. That path is real and it works. Nobody ever drove the path
the step actually describes, and the two share every component except the one line that was
missing.

**It was found by `tools/demo-run.sh` driving the real HTTP API, on its third run.** That is the
argument for having a demo that asserts rather than prints, and for driving the surface a client
would use rather than the one the tests find convenient:

> A test suite proves the components work. Only something driving the real API proves they are
> WIRED — and "built, green, documented, dead" leaves no trace anywhere else.

The same shape as the rest of §7: a value that is silently absent behaves exactly like a value
that is wrong. Here the absent thing was a method argument.

### A durable guard makes a demo runnable exactly once, unless the demo asks for a clean world

The second run of the demo script found the FIRST run's `ticket_action` rows and correctly
refused to pay again — so the case meant to show a gateway failure showed a duplicate credit
instead, and a partner who should have had one ticket had two. The guard was right; the script
was wrong to assume a clean world without asking for one.

`/demo/reset` now clears the ledger as well as the gateway. Deleting rows is a serious control,
so it is confined: `@Profile("demo")` only, and that profile refuses to start beside a real UAT
datasource. What it erases is a synthetic ledger in a database that cannot contain anything
else. The alternative — a demo runnable once per restart — is the kind of friction that ends
with nobody running it.

### An off-by-one in an EXPLANATION is worse than a missing explanation

Flowable numbers the rules in its audit trail from **1**. `DecisionTableReader`, which parses the
`.dmn` file for the console, numbers them from **0**. The console joins the two by index.

Every outcome therefore landed on the row BELOW the one it belonged to, and the last row's outcome
matched nothing at all.

**How it presented, and why that was luck.** The catch-all fired, its outcome fell off the end, and
the panel showed *no row highlighted* — visibly odd, so it got investigated. Had the CAP fired, the
highlight would have moved one row down and the panel would have said, confidently and in a room
full of people, that a ₹450 claim was paid by the PATH_1 rule — when the cap had actually stopped
it. **A wrong explanation is worse than no explanation: the audience has no way to tell.**

A second, latent bug came out with it. "The first match wins" is only true if the entries are
walked in rule order, and a `Map` promises no such order. It happened to work. Both are fixed by
sorting the audit keys and re-indexing from zero, so the join is defined rather than lucky.

**The test is what caught it, and only because it was specific.** It asserted *exactly one row
fired*. "The panel rendered" would have passed. So would "at least one row is highlighted", once
the cap case moved the highlight rather than losing it. **When something explains a decision,
assert WHICH decision it named — not that it said something.**

### Verify a call site, not an occurrence

The check for "is `explain(view)` actually called?" found the function DEFINITION and reported
success. The panel was never invoked and the verification said it was.

Count call sites (`^\s*name\(`), never occurrences. A definition and a call look identical to
`grep`, and this is the same family as everything else in this section: the absent thing behaved
exactly like the present one.

### A column nothing ever writes is invisible until something tries to read it

`ticket_action.amount_paise` existed from the first migration. `TicketAction.attempt(...)`
took it, `ActionResult` carried it, and `PerformActionDelegate` passed **null**. Every row in
the payment ledger recorded that a credit succeeded and none recorded how much.

Nothing failed. No test noticed, because no test summed the column. It surfaced the moment
step 61 tried to reconcile — and then it was total, because you cannot add up a column of
nulls. **A payment log that records the fact of a payment but not its size is a receipt with
the number torn off.**

The amount is now written with the OUTCOME, not the attempt: before the call we only know
what we intend to pay, and a crash mid-call would leave a row asserting a movement nobody can
confirm. Null on an ATTEMPTED row is the honest state.

**The general shape:** a field that is declared, plumbed and never populated looks exactly
like a field that works, from every angle except the one nobody is looking from.

### A global count in a test is a shared fixture wearing a different hat

`TicketActionRecorderTest` asserted `actions.count() == 1` — every row in the database. That
was correct for as long as it was the only class writing any. `ReconciliationTest` arrived and
the assertion silently became "how many payments has the whole suite made".

Count what the test owns. This is the third variation on one theme in this project:
**durable state makes test isolation an explicit job.** The idempotency key, the committed
audit row, and now the reconciliation table are each deliberately built to outlive a request,
which is precisely what breaks the assumption that a test starts from nothing.

### The demo could not have been green, and nobody could tell

`tools/demo-run.sh` called `/demo/tickets/{spId}/count` and `/demo/tickets/{spId}/latest`
from the day it was written. **Neither endpoint existed.** Each returned a 404 body, `jq`
read `null`, and three assertions compared `null` against a string. The script could never
have exited zero, and because it was only ever run by hand, nobody found out.

Worse: `DemoIsolationTest` asserted those exact paths return 404 without the demo profile —
and passed, because they returned 404 under **every** profile. **A test that passes for the
wrong reason is worth less than no test**, because it occupies the place where a real one
would go. When a test asserts an absence, assert the presence somewhere too, or the test is
measuring nothing.

### An assertion that only passed because of what ran before it

`TRIGGER A` asserted `ticketCount == 1`. That was only ever true because the case before it
left exactly one ticket on that partner and this case reused its session. The moment the
cases became individually runnable — which they had to be, because a reviewer clicks
whichever row they ask about — the count was 2 and a correct system reported a failure.

**The count was never the property.** The property is that rejecting a deflection REUSES the
ticket rather than opening another, and a before/after comparison says that no matter what
else has run. An absolute count in an assertion is almost always a hidden dependency on
execution order.

### A journey view that hardcoded one process shape

`JourneyService` first hardcoded the fourteen steps of `concern-generic`, on the assumption
every concern runs it. **`FORGET_MPIN` deliberately does not** — V5 left it on its own
three-element process, because the T0 proof is worth more in its own file. The hardcoded
list rendered that concern as *"2 of 15 steps taken"* with thirteen steps struck through
that had never been part of its process, which reads as a flow that did almost nothing —
the exact opposite of the truth.

The shape now comes from the deployed BPMN model (`RepositoryService.getBpmnModel`). **When
a view claims to show what the system did, derive its frame from the system too.** A frame
written by hand is a second description that drifts, and it drifts silently because it still
renders.

Also from the same screenshot: Flowable records **sequence flows as activity instances**. A
three-element process reported its end event as step 4 until they were filtered out.

---

## 8. Known assumptions and compromises

- **`derivePath()` in `TransportFactProvider` is an assumption.** Transport rules 2–8 are
  labelled Path 1/2/3 but nothing in the concern mapping says what assigns a claim to a
  path. It is derived from `customerCharged` / `alreadyCredited` / `cancellationStatus` in
  one named method, deliberately in one place, so the real rule is a single edit.
- **One decision row added beyond the concern mapping.** Recharge, `SUCCESS` + already
  credited -> `INFORM_ALREADY_CREDITED`, no money. The mapping defines only "Success and NOT
  already credited", leaving a re-raise after a successful credit undefined; it would fall to
  the catch-all and reach an agent. The concern's own note calls for a duplicate guard on the
  PayU transaction id, so the row states it. **NEEDS CONFIRMATION before that concern goes
  live.**
- **`VARCHAR(4000)` caps.** Eleven columns that should be `TEXT` in Postgres are capped so
  the same DDL runs on H2. `facts_snapshot` and `request_payload` are the two where 4000 is
  a real ceiling. The clean fix is testing against real Postgres via Testcontainers, which
  adds a Docker dependency and was deferred to Phase 4.

---

## 9. Findings from the concern mapping that change scope

- **Fine Related is DEPRECATED** — 12,781 volume. The POC's original subject no longer
  exists.
- **13,823 tickets (37%) leave the build** once DEPRECATED and REROUTE are removed.
- **Violations has no volume figure at all** — 17 of 38 concerns, 54 of 113 rules.
- **Only 6 of 113 rules move money.** The money-safety machinery guards a small, findable
  surface.
- **14 rules are REROUTE**, an outcome type with no equivalent anywhere in the design.
- **13 concerns have no catch-all rule.** With DMN `FIRST`, they return `null`.
- **12 rules are not machine-evaluable** — "substantial emergency" appears five times.

---

## 10. Build and run

```bash
cd "/Users/admin/Documents/BOTIn Mod/Claude outputs/botin-phase1/botin"

mvn test                  # H2, no database or UAT needed. Should be 6/6.

createdb botin
export BOTIN_DB_URL=jdbc:postgresql://localhost:5432/botin
export UAT_DB_URL=... UAT_DB_USER=... UAT_DB_PASSWORD=...
mvn spring-boot:run       # or UAT_ENABLED=false to boot without UAT
```

Expect on a clean boot: **7** tables from Flyway, **~25** `ACT_*` from Flowable, **38**
concerns seeded with **8** active.

---

## 11. Working agreement

- Every document produced goes into `docs/` in this repository. Nothing lives only in a
  chat.
- Status changes go into `poc-tracker.xlsx`, not into prose.
- Anything learned the hard way goes into §7 of this file.
- When a standing rule in §2 is at risk, stop and say so rather than working around it.
