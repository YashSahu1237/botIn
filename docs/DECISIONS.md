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

## Earlier decisions, recorded elsewhere

| | Where |
|---|---|
| Violations removed from POC scope | `SCOPE-VIOLATIONS-OUT.md` |
| Transport rate — ₹50/km from the radius edge (D-B) | `MATRIX-ANSWERS.md` |
| Path selector confirmed (D-C) | `MATRIX-ANSWERS.md` |
| Recharge rule 3 deleted — `spSatisfied` unreachable | `MATRIX-ANSWERS.md` |
| CSAT is not a wait state | `POC-ASSESSMENT.md` §4 |
| The bounding rule lives in closure logic, not the UI | `POC-ASSESSMENT.md` §4 |
