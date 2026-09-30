# Decision log

One line per decision, with what it closes and what it costs. Decisions were scattered
across `DEFERRED.md`, `MATRIX-ANSWERS.md` and `SCOPE-VIOLATIONS-OUT.md`; from here they are
recorded once, here, in the order they were taken.

---

## D-1 — BOTIn ships as a new standalone service

**Decided 28 September. Closes open question D-1**, on which the Decision Brief and the
Engineering Design had disagreed.

Not folded into `empapi`, not a library. Its own repository, image, database and pipeline —
matching the platform's conventions rather than inventing new ones.

**Consequence:** BOTIn owns a deployment surface. Everything in Workstream B of
`PRODUCTION-PLAN.md` follows from this.

---

## D-2 — BOTIn's own database is MySQL

**Decided 28 September. Closes Gate 0 item G0.5.**

> *"we use database used in other services"*

`empapi` reads and writes MySQL throughout — `prodsqlwr`, `prodsqlro`, `prodsqluseractivity`,
all `ysmdm_*` on MySQL. So BOTIn's own schema goes to MySQL too. One engine for the people
who operate databases to look after, not two.

### What this costs, concretely

The POC's schema is Postgres. Four things do not port as written:

| | Postgres today | On MySQL |
|---|---|---|
| **Row size** | `escalation_context` holds five `VARCHAR(4000)` columns | **~80,640 bytes under utf8mb4 — over MySQL's 65,535-byte row limit. The table will not create.** Those columns become `TEXT` |
| **`UUID` columns** | native `UUID` type on 5 tables | no such type. `BINARY(16)` (compact, unreadable by hand) or `CHAR(36)` (readable, larger). **This choice changes every query in `DATABASE-ACCESS.md`** |
| **`TIMESTAMP WITH TIME ZONE`** | native | no equivalent. `DATETIME(6)`, with the app's existing `hibernate.jdbc.time_zone: UTC` carrying the contract |
| **`BOOLEAN DEFAULT FALSE`** | native | `TINYINT(1) DEFAULT 0` |

**Recommendation on the UUID question: `CHAR(36)`.** `BINARY(16)` is the efficient answer and
the wrong one here. Every runbook, every reconciliation query and every "why did this partner
get paid" investigation starts with somebody pasting an id into a SQL client. A primary key
nobody can read by hand costs more in an incident than it saves in storage, on a table that
will never be large enough for the difference to matter.

### What else has to be re-proven, not just re-typed

The migrations are the easy half. These were established on Postgres and have to hold on
MySQL:

- **The transaction-boundary work.** `REQUIRES_NEW` isolation, the commit-before-call ordering
  in `PerformActionDelegate`, and the duplicate-credit guard. Money safety was one of the two
  POC stop conditions; it was proved on one engine.
- **Flowable's own schema.** It supports MySQL, but its DDL, its optimistic locking and its
  history tables are engine-specific.
- **The test database.** Tests run on H2 in native mode, chosen because Flowable rejects H2's
  PostgreSQL compatibility mode. H2-standing-in-for-MySQL is a weaker fiction than
  H2-standing-in-for-Postgres was, because the row-size limit above is exactly the class of
  thing H2 will not catch. **Testcontainers MySQL for the migration and repository tests** is
  the honest answer; H2 can stay for the rest.

---

## D-3 — the source database is read through the READ REPLICA

**Decided 28 September, as a consequence of D-2.**

`empapi` already separates them:

```
spring.datasource.primary.jdbc-url = jdbc:mysql://prodsqlwr.yesmadam.com:3306/ysmdm_admin
spring.datasource.reader.jdbc-url  = jdbc:mysql://prodsqlro.yesmadam.com:3306/ysmdm_admin
```

**A read-only endpoint exists and BOTIn's fact providers use it.** This also answers a
question left open in UAT, where the credentials pointed at the `-writer` endpoint and it was
unclear whether a reader existed. It does.

**Consequence:** BOTIn never holds a connection to a write endpoint on any `ysmdm_*` schema.
The `SELECT`-only GRANT stays as well — the grant makes writing impossible, the reader
endpoint makes it impossible to even reach the primary. Belt and braces, and the belt is free.

---

## D-4 — auto-payments get a reversible window

**Direction decided 28 September. Detail with Product.**

> *"we go for this flow only: Auto-payments must have a defined window before they're final"*

This is **NFR-07** and nothing in the POC implements it. Today an auto-credit is immediate and
final: `PerformActionDelegate` records the attempt, calls the gateway, records the outcome,
and the money is gone.

**Consequence — this is a design change to the action layer, not a setting.** It introduces a
state the system does not currently have: *paid, but not yet final*. That means a pending
state on `ticket_action`, something that finalises it when the window closes, a path that
reverses it while the window is open, and a decision about what the partner is told in the
meantime — because "credited" and "credited, reversible until tomorrow" are different
sentences.

**Open, and in `DISCUSSION-POINTERS.md`:** the window's duration, whether it varies by amount
or concern, who may reverse within it, and whether the partner sees the money during it.

**Note it interacts with D-2.** Best settled before the schema is ported, so `ticket_action`
is created once on MySQL with the states it will actually need.

---

## D-6 — the ₹300 cap was unreachable, and the Transport guard stays red

**Found by `tools/preflight.py`, not by a test.** In `transport-not-received-decision` the cap
row (`computedAmountPaise > 30000` → `TICKET_EXCEEDS_CAP`) sat **below** the catch-all. Under
`FIRST` the catch-all matches everything, so the cap could never fire: a ₹450 transport claim
fell through to the PATH 1 row and was **paid in full** — the precise outcome the row exists to
prevent.

The file said so itself. The cap's comment read *"moved above every paying row because FIRST
would otherwise never reach it"* while the block was physically last, the comments still
numbered it 2, and every rule's cell ids were off by one from its rule id — the fingerprint of
a block cut from position 2 and pasted at the bottom, with the rules below renumbered and the
block itself left alone.

**Decided: moved back to position 2**, directly under rule 1 (already paid). Rule ids restored
to match their cells, so `rule id == cell prefix` now holds for all eleven rows and a future
move of this kind is visible on sight. No cell contents changed. `TICKET_EXCEEDS_CAP` is
asserted in five tests (`DemoFixturesTest`, `DmnHotRedeployTest` ×2, `DecisionTableTest` ×2,
`DecisionExplainedTest`) plus the `cap-escalation` demo scenario — these were red and should now
pass, which is the confirmation this change needs.

**Decided separately: `check_actions_performable` stays RED for now.** Rules 3, 5, 6 and 10
decide `T2 AUTO_CREDIT_TRANSPORT` / `AUTO_CREDIT_DISTANCE`, but `ActionRegistry` holds only
`AUTO_CREDIT_WALLET`, so every one of those cases becomes a ticket instead of a payment. This is
accepted until Transport's L2 is built, and it is recorded here **because a red check nobody is
expected to fix is how a guard becomes wallpaper.** Pre-flight is expected to exit non-zero on
Transport, and on nothing else. Any *other* failure is new.

---

## D-7 — the restructure, and the two holes it exposed in pre-flight

**The move (BLUEPRINT §3) is applied.** 136 of 140 Java files relocated; `BotinApplication`
stayed, and `ClassifierFactProvider` split three ways as §3.1 proposed. Packages, imports and
the `togglz.feature-enums` / logging FQNs in the YAML were rewritten with it. Verified by
`tools/structure_check.py`, which proves four things without a compiler: every `package` line
equals its directory, every `in.yesmadam.botin` import resolves to a type that exists, every
project type a file uses is reachable from it, and no stale package name survives anywhere —
including in `.yml`, which is where two of the four real breakages were.

**Not a compile.** No JDK or Maven is available to the session that performed the move, so
`mvn test` is still the confirmation this needs.

**Hole 1 — the fact-key reader stopped seeing two concerns.** `declared_fact_keys()` resolved
the shared `classifierKeys()` by searching *the same file*. That held while the helper and its
two providers were one nested class. The split moved them to three files and the check did not
fail — it silently stopped covering `VIOL_R4_OTHERS` and `OTHER_FREETEXT_TRIAGE`. The provider
count in the banner was the only symptom. Now resolved across all sources, and a provider whose
keys cannot be read **fails** instead of being skipped.

**Hole 2 — pre-flight was reading 20 of 38 catalogue rows.** `catalogue_final_state()` counted
columns with `[^,]*`, so any row whose label contains a comma matched nothing. One of them is
`RECHARGE_DEBIT_NO_CREDIT` — **active, T2, money-moving** — which means every catalogue-driven
check had been skipping it. Eighteen rows in total were invisible. Replaced with a quote-aware
field splitter; the catalogue now parses 38 of 38. This predates the restructure and was found
only because the new `check_concern_folders` guard asked the catalogue a question about a
concern the old parser had dropped.

Both holes are the same shape as D-6 and as the `.dmn` catch-all: **nothing failed, so nothing
looked wrong.** A check that cannot find its subject reports success.

**Hole 3 — the checker agreed with itself.** The first build after the move failed on one
line: `BotinApplication` declared `package in.yesmadam.botin.` with a trailing dot. The
file sits at the package root, its directory part is the empty string, and the special case
written to handle that tested for `"."` instead. `structure_check.py` reported PASS, because
it derived the EXPECTED package with the same function that had produced the wrong one — both
sides were wrong identically, so they matched. A check that computes the expected value the
way the thing under test computes it is not a check. The derivation now comes straight from
the path under `src/{main,test}/java`, which is the rule javac itself uses, with no special
case left to get wrong; re-introducing the bug on purpose confirms the check now fails on it.

**Hole 4 — a capitalised token is not proof of a project type.** The second build failed on
six JPA entities: the import rewriter saw `@Table`, matched it against the project's nested
`DecisionTableReader.Table`, and imported that over `jakarta.persistence.Table`. The same
over-eagerness added imports for names that were already written qualified
(`DecisionService.Explained`), which were harmless but wrong. Eighteen files were corrected by
recomputing, from the pre-move tree, which names actually resolved to a project type in each
file — the only ground truth available without a classpath.

`structure_check.py` had the same blind spot and now states its limits instead of guessing: a
nested type counts as used only when written unqualified, a name never written unqualified needs
no import, and a file carrying a NON-project wildcard import (`jakarta.persistence.*`) could
legitimately take the name from there, which nothing without a classpath can rule out. Those are
COUNTED AND LISTED rather than passed over — currently six, all of them `@Table` in the six
entities above. A guard that cannot check something should say so; silence is what the last three
holes had in common.

**Hole 5 — the demo suite was passing on execution order.** The first build that got as far as
running tests failed only in `DemoScenarioRunTest`, on four scenarios at once. The cause was
`/demo/reset`: it deleted `ticket_action` before `ticket` — correctly — but never deleted
`escalation_context`, which carries the same foreign key. Any reset attempted after something
had escalated threw on `delete from ticket`, cleared NOTHING, and left every scenario after it
running against a dirty world.

It had always been wrong. Surefire happened to run the demo tests before `AgentConnectTest` and
`CsatAndBoundingRuleTest` — the two whose job is escalating to a person — so a reset never met a
row pointing at a ticket. Renaming the packages reshuffled the order and the luck ran out. The
method's own comment said "actions before tickets: the foreign key points that way" and named
one of the two children.

Fixed by deleting `escalation_context` first, so the delete order matches the direction the keys
point. **The restructure did not break this; it removed the coincidence that was hiding it.**
Worth noting for the production suite: nothing currently asserts that the demo run is
order-independent, and this is the second time (after the TRIGGER A ticket-count assertion) that
a demo case has depended on state left by the case before it.

**Hole 6 — and the one the reset fix uncovered underneath it.** With reset working, one scenario
still failed: AGENT CONNECT took `queue.get(0)` from the agent queue — the OLDEST task, usually
one left by an earlier case or an earlier test class in the same context. `/demo/reset` clears
tickets, actions and escalation contexts but **not Flowable's task list**, so those stale tasks
point at rows that no longer exist and "read the context" reads nothing. It had been passing only
because reset was silently failing and leaving the old rows in place — one broken thing holding
another upright.

The case now finds the task whose `helpSessionId` matches the session it just opened. Identity,
not position — the same correction the TRIGGER A case needed.

**Residual, deliberately not fixed:** `/demo/reset` still does not clear Flowable runtime state,
so a task can outlive the ticket it refers to. Every case now matches on identity so none depend
on it, but an agent-queue endpoint listing tasks whose tickets are gone is a real behaviour, and
it should be decided rather than left to the demo to work around.

**Still open:** `docs/CODE-MAP.md` describes the pre-move tree and is now wrong. Resources
(`resources/dmn/`, `resources/processes/`) have NOT moved — see `DISCUSSION-POINTERS.md`,
because Flowable takes a single `process-definition-location-prefix` and the generic process is
not concern-owned, so where concern-specific and shared XML each live is a decision, not a move.

---

## D-8 — response wording moved out of Java, one file per concern

**BLUEPRINT §3.6 applied.** `ResponseTemplates` was a static `Map.ofEntries` of twelve action
codes — and the one file every concern had to edit. It is now a resolver that reads
`resources/concern/<l1>/<l2>/templates.properties` from the classpath at startup:

| file | action codes |
|---|---|
| `concern/amount/transport/` | INFORM_ALREADY_PAID · DENY_DID_NOT_TRAVEL · DENY_CASHBACK_COMPENSATES · DENY_WITHIN_HUB · AUTO_CREDIT_TRANSPORT · AUTO_CREDIT_DISTANCE |
| `concern/amount/recharge/` | INFORM_ALREADY_CREDITED · ASK_RECHARGE_AGAIN · AUTO_CREDIT_WALLET |
| `concern/product/deliverydelay/` | SHOW_EXPECTED_DELIVERY |
| `platform/` | AGENT_CONNECTING · INFORM_ALREADY_DONE |

Ownership was derived from the decision tables, not guessed: every code is assigned to the table
that emits it. The two in `platform/` come from no table at all — `AGENT_CONNECTING` is what every
T3 says whichever concern produced it, and `INFORM_ALREADY_DONE` is the duplicate guard's answer
for any action that is not `AUTO_CREDIT_WALLET` (`PerformActionDelegate`).

**Two files claiming one action code is now a startup failure**, not a merge. Silently merging
would pick a winner by classpath order, so the sentence a partner reads could differ between a
developer's machine and production. Pre-flight catches the same thing without a JVM, and both
that check and the missing-template check were confirmed by deliberately breaking each one.

**Deliberately unchanged:** no fallback prose. An action with no template still gets a message
admitting nothing is prepared, with an ERROR naming the code. A generic "your request has been
processed" is the worst available failure here — it reads as success for an outcome nobody wrote.

**Noted for whoever builds the next action service:** `INFORM_ALREADY_DONE` is generic because it
has to cover actions that do not exist yet. The first concern to add one should decide whether its
partners deserve a more specific sentence when the duplicate guard fires.

---

## D-9 — the add-a-concern path, and the Flowable move that was reverted

**Step 4 of BLUEPRINT §8 is done.** `CHECKLIST.md` states the seven obligations, each paired
with the guard that catches you if you skip it; `concern-template/` holds the four files to copy;
`src/main/java/in/yesmadam/botin/concern/README.md` points a developer at both from inside the
tree. `CODE-MAP.md` was updated rather than rewritten — its prose was still accurate, its paths
and grouping were not — and it now defers to the checklist for the procedure so the two cannot
drift.

**Templates are `.txt` on purpose.** A template that compiles registers a bean for a concern with
no catalogue row, and `check_concern_folders` would fail the build on the template itself.

**The Flowable resource move was attempted and reverted.** `classpath*:/flowable/` worked for
BPMN; the DMN engine deployed nothing from it and said nothing about it — the first symptom was
`No decision found for key` at runtime. Two attempts at the property semantics, two identical red
builds, then a revert to the last green commit. Parked with the one command that would have
answered it, in `DISCUSSION-POINTERS.md`.

**Worth naming plainly: I guessed twice at a framework default instead of reading it, and cost
two build cycles.** The checklist now tells the next person the opposite — run the guard, read
the metadata, do not infer behaviour from a property's name.

**Consequence for the checklist:** response templates live beside their concern,
`resources/dmn/` and `resources/processes/` stay flat. Two conventions, documented as such
rather than smoothed over.

---

## D-10 — FORGET_MPIN, and what the first concern taught the checklist

FORGET_MPIN passed all seven obligations and all guards. The audit still found five things,
which is the useful result: **the guards check that pieces exist, not that they do anything.**

**Its decision table was deployed, pointed at, and never evaluated.** Its process is
`startEvent -> finaliseStep -> endEvent` — no decide step. Editing the tier or action in that
file changed nothing at all. **Decided: the table is deleted and `dmn_key` set to NULL** (V8).
A concern that makes no decision should not claim to. Pre-flight now fails if a concern running
`concern-generic` has a null `dmn_key`, because that process always reaches `DecideDelegate`.

**Its fact provider is never invoked either** — same missing step. Left in place: the registry
requires one for every active concern, and exempting a concern from that rule is a hole in it.
Recorded here so the next reader is not misled into thinking it does something.

**Its partner-facing sentence was hardcoded in the delegate** — the last one still living in
Java after D-8. **Decided: moved** to `concern/amount/forgetmpin/templates.properties`. A new
guard, `check_named_templates`, now fails when any `promptFor("CODE")` in Java — literal or via
a constant — resolves to no template. It covers the two codes no decision table emits:
`AGENT_CONNECTING` and `FORGET_MPIN_DEFLECT`, each the only sentence its path ever produces.
Both were confirmed by deliberately deleting them.

**Open — the kill switch.** Agreed, not built: turning a T0 deflection off is not the same
operation as turning automation off, and the existing `togglz_flag` means the latter. See
`DISCUSSION-POINTERS.md`.

**Open — the deeplink.** `${DEEPLINK_FORGET_MPIN:yesmadam://account/security/reset-mpin}` is an
unconfirmed default, and this repo has no per-environment config yet. 738 partners a month
follow it. CSAT plus Trigger A means a wrong link is recoverable rather than silent, which is
the design working — but it is not a substitute for the app team confirming the value.

**The readiness bar in `CHECKLIST.md` was written from this concern.** Four of its eight items
came from gaps here.

---

## D-11 — money is held in rupees

**Decided by the product owner, against my recommendation, and implemented.** Amounts were
`long` paise; they are now `BigDecimal` at scale 2 in rupees. My objection is recorded because
it is the reason the implementation looks the way it does, not to relitigate it: paise-as-long
is the standard precisely because it cannot acquire a fraction, and rupees force a scale and a
rounding mode onto every amount. `BigDecimal` at a fixed scale gives rupees without giving up
exactness — a `long` of rupees would have silently truncated Rs68.50 to Rs68.

**One place holds the rules.** `platform/money/Rupees` owns `SCALE = 2` and
`ROUNDING = HALF_UP`. HALF_UP matches what `Math.round(km * 5000)` did before, so no behaviour
changed; it is marked **PROVISIONAL** because the concern matrix still records partial-kilometre
handling as OPEN (prorate / round up / down). When that is answered, one line changes.

**What moved:** `ActionRequest.numericFact` became `amountFact` returning `BigDecimal`;
`ActionResult.amountPaise` became `amountRupees`; `ticket_action.amount_paise BIGINT` became
`amount_rupees DECIMAL(12,2)` in V9; `LedgerEntry`, `DuplicateCreditGuard.netRupees`, the
gateway, reconciliation, the console and the fixtures followed. Both decision tables now read
rupees: the cap is `> 300` rather than `> 30000`, and the rate output is `50` rather than `5000`,
so a business owner editing a row writes the number they would say out loud.

**DECIMAL and not DOUBLE in the schema**, and `compareTo` and not `equals` in reconciliation —
`BigDecimal.equals` is false for `250.0` vs `250.00`, which would have reported every credit as
a mismatch on a difference of nothing.

**The assumption I refused to bury.** Paths 1 and 2 pay what UAT's `tbl_order.transport_charges`
says, and this code has always read that column AS PAISE. Whether it really is paise is **not
confirmed** — probe 1 found no order above 30000, weak evidence it may already hold rupees. The
conversion goes through `Rupees.fromPaise` at exactly one call site, with a comment saying so.
Today's behaviour is preserved rather than changed on a guess. **If probe 2 shows the column is
rupees, that one call goes away — and every transport credit computed until then was 100x too
small.** Renaming the variables without this would have made the bug harder to see, not easier.

**Not verified by a build yet.** Pre-flight and structure_check pass; `mvn clean test` is the
real check, and the DMN comparison of a `BigDecimal` fact against an integer threshold is the
part no static check reaches.

---

## D-12 — a seam for the real payment gateway

`WalletCreditService` — the only code in the system that moves money — named `MockPayUGateway`
in its constructor. The concern that credits a partner depended on the mock **by type**, so
there was no place to put a real gateway: adding one meant editing the concern, and "is this
wired to the real thing?" could only be answered by reading the class.

`PayUGateway` is now the contract, and it carries only what a real gateway can honour:
`creditWallet`, `statusOf`, `amountFor`, `allCredits`, `creditedFor`. `setRecharge`,
`failNextCalls` and `reset` stay on the mock — they arrange a world, they do not talk to a
payment provider, and an interface that included them would be the mock's shape wearing an
interface's name. The demo surface and the tests keep depending on the concrete mock, which is
correct: they are the things that arrange the world.

**`allCredits()` is in the interface deliberately.** It looks like a test convenience and is
not: reconciliation's whole value is comparing what we recorded against what the gateway
believes, and the second half has to come from the gateway. A real implementation answers it
from a settlement report.

**`PayUUnavailableException` moved out of the mock** to a top-level class in the same package.
A real implementation would otherwise have had to throw an exception belonging to the mock —
the sort of detail that quietly decides an interface is not really one. Its comment states the
rule that matters: a timeout on a credit is **"we do not know", never "it did not happen"**.

**What this does NOT do.** There is still no real gateway, and `creditWallet` is still an
in-memory map. RECHARGE still cannot move money. This removes the obstacle to fixing that; it
does not fix it. OI-2 in the readiness sheet stays open, with its blocker narrowed from
"there is no seam" to "implement `PayUGateway` against the real provider".

---

## D-13 — a fact may come from a service, and the platform for that comes first

**Decided.** A fact provider may read a column (shape A), derive a value from rows across
tables (shape B), or call another service (shape C). The SPI already allowed all three — it
constrains only what `fetchFacts` returns, never where the data came from — but only A and B
had any platform behind them. C had a pattern (`PayUGateway`) proven on the action side and
nothing on the fact side.

**Why it could not wait for the first shape-C concern.** The first provider to call a service
would have chosen a timeout, a retry policy and a failure semantic, and every provider after it
would have copied whatever that was. That is exactly how three money columns ended up with
unconfirmed and mutually inconsistent units: the first instance set a convention nobody wrote
down, and the second inherited it without knowing it was a choice.

**What was built**

- `ExternalDependency` — the shape-C twin of `UatColumn`. Names the dependency, the **property**
  holding its base URL (a literal URL is refused at construction: a URL in the jar is the same
  URL in every environment, which is how a UAT build calls production), an optional health path,
  a timeout, and whether it feeds a money path.
- `ExternalServiceClientFactory` — the only supported way to reach a service while answering a
  partner. One connect budget, a read budget capped at `botin.external.max-timeout` (1500ms),
  base URL resolved from the property so a missing environment variable fails at startup with
  the property name in the message, and every non-2xx converted to
  `ExternalServiceUnavailableException`.
- `ExternalDependencyProbe` — the symmetric twin of `UatSchemaProbe`. Configuration is always
  checked; reachability only where a safe health path is declared, and a dependency without one
  is logged as *configured only* rather than reported as a pass it did not earn. Loud ERROR for
  an advisory dependency, **boot failure on a money path** — the same policy, for the same
  reason: a partner is better served by a service that escalates than by none, but deciding
  whether to pay on facts we have silently stopped reading is worse than not starting.

**No retry, anywhere.** Deliberately absent rather than configured off. The call sits inside a
partner's live chat turn and the degraded answer — null fact, catch-all, a human — is already
correct. A retry doubles the worst case to improve an answer that already has a good failure
mode. Outbound **actions** are a different question with a different answer, and they keep going
through their own interface in `integration/`, the way `PayUGateway` does.

**Unavailable is never the negative value.** `ExternalServiceUnavailableException` exists as a
type so this cannot be forgotten. `stockAvailable = false` is a refund; `alreadyRefunded = false`
is a second refund. An outage read as a negative automates on every outage.

**No cache, and that is the decision rather than an omission.** A cache here would be a second
source of truth with its own staleness window, and nothing in the system reads a service yet, so
the call rate it would be sized against does not exist. Revisit when the first shape-C fact
lands, with a measured rate. Recorded so the next reader knows the question was asked.

**What is deliberately still missing.** No preflight guard cross-checks `movesMoney` against the
tiers in the concern's decision table — a dependency feeding a T2 rule but declared `advisory`
would not be caught today. That guard belongs in `preflight.py` and should land with the first
shape-C provider, when there is something for it to check.

---

## Earlier decisions, recorded elsewhere

| | Where |
|---|---|
| Violations removed from POC scope | `SCOPE-VIOLATIONS-OUT.md` |
| Transport rate — ₹50/km from the radius edge (D-B) | `MATRIX-ANSWERS.md` |
| Path selector confirmed (D-C) | `MATRIX-ANSWERS.md` |
| Recharge rule 3 deleted — `spSatisfied` unreachable | `MATRIX-ANSWERS.md` |
| CSAT is not a wait state | `POC-ASSESSMENT.md` §4 |
| The bounding rule lives in closure logic, not the UI | `POC-ASSESSMENT.md` §4 |
