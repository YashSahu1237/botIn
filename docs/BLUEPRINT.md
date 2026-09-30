# BOTIn — code blueprint

How the code is organised, what a concern is obliged to provide, and how somebody who has
never spoken to anyone on this project adds one.

**Nothing in here has been executed.** It is the proposal to approve before any file moves.

---

# 1. The problem this solves

Adding one L2 concern today touches **seven places across six packages**:

| # | What | Where today |
|---|---|---|
| 1 | Catalogue row | a Flyway migration |
| 2 | Fact provider bean | `facts/` |
| 3 | Decision table | `resources/dmn/` |
| 4 | Response wording, one per action code | `process/ResponseTemplates.java` — a static map |
| 5 | Action service, if any rule is T2 | `action/` |
| 6 | Kill-switch constant | `safety/BotinFeature.java` |
| 7 | Tests | `src/test/` |

**It is not hard. It is undiscoverable.** Nothing tells you the list is seven items long, so a
new developer finds five, ships, and the sixth fails later.

And some failures are silent. A fact key that does not match its DMN input label is an
**absent variable**, and under strict mode an absent variable takes the entire table down —
catch-all included. The table returns nothing, and the case escalates with no explanation.

Item 4 is also the one file every new concern must edit that every other concern already
edits. It is a merge conflict waiting on volume.

---

# 2. The proposed tree

Three top-level ideas, and every file belongs to exactly one.

```
in/yesmadam/botin/
│
├── BotinApplication.java
│
├── platform/            THE ENGINE. Knows no concern by name.
│   ├── api/  dto/       the client contract
│   ├── session/         conversations, tickets
│   ├── process/         BPMN delegates, process variables, step writer
│   ├── decision/        the DMN bridge
│   ├── catalogue/       the concern registry
│   ├── action/          the ActionService SPI + registry  (no implementations)
│   ├── facts/           the ConcernFactProvider SPI + registry + schema probe
│   ├── safety/          kill switches, ticket actions, Togglz
│   ├── escalation/  csat/  agent/  classifier/  config/
│
├── shared/              USED BY MORE THAN ONE CONCERN.
│   ├── tat/             delivery TAT
│   ├── geo/             haversine, hub radius
│   ├── ledger/          duplicate-credit guard, ledger entry
│   └── counter/         the four counter windows
│
├── concern/             NESTED BY L1, THEN BY L2.
│   ├── _template/       the skeleton you copy — L1-agnostic
│   │
│   ├── amount/          l1_code = AMOUNT_RELATED
│   │   ├── _shared/     used by MORE THAN ONE Amount concern, and nothing else
│   │   ├── transport/
│   │   ├── recharge/
│   │   └── forgetmpin/
│   │
│   ├── product/         l1_code = PRODUCT_ISSUES
│   │   └── deliverydelay/
│   │
│   ├── other/           l1_code = OTHER_ISSUES
│   │   └── freetext/
│   │
│   └── violations/      l1_code = VIOLATIONS
│       └── r4others/
│
├── integration/         EXTERNAL SYSTEMS. One interface per system, and every
│   └── payu/           shape-C fact and every outbound action goes through one.
│
└── surface/             THINGS THAT LOOK AT THE SYSTEM, not part of it.
    ├── console/  demo/  reconcile/
```

Resources mirror it:

```
resources/
├── concern/amount/transport/transport-not-received-decision.dmn
├── concern/amount/transport/templates.properties
├── concern/amount/recharge/recharge-debit-no-credit-decision.dmn
├── concern/amount/forgetmpin/forget-mpin.bpmn20.xml   ← its own process
├── processes/concern-generic.bpmn20.xml             ← platform
└── processes/csat-escalation.bpmn20.xml             ← platform
```

**Two rules keep it honest.**

*Where shared code goes.* A helper lives with the concern that uses it. When a **second concern
in the same L1** needs it, it moves to `concern/<l1>/_shared/`. When a concern in a **different
L1** needs it, it moves to the top-level `shared/`. Not before — speculative sharing is how a
shared folder becomes a junk drawer.

*What the folder name means.* `concern/<l1>/<l2>/` is **documentation, not identity.** The
catalogue row is the source of truth for which L1 a concern belongs to. If the folder and the
row ever disagree, the row wins — and a guard fails the build, so they cannot disagree for
long. See §5.

---

# 3. The move list

Every file. `→` is the proposed destination; **stays** means the path is unchanged apart from
the new `platform/` or `surface/` prefix.

### 3.1 Becomes `concern/<l1>/<l2>/` — 11 classes

| Today | Proposed | Why |
|---|---|---|
| `facts/TransportFactProvider.java` | `concern/amount/transport/` | transport only |
| `facts/TransportPathSelector.java` | `concern/amount/transport/` | transport only |
| `facts/RechargeFactProvider.java` | `concern/amount/recharge/` | recharge only |
| `facts/PayuStatusNormaliser.java` | `concern/amount/recharge/` | recharge only — rises to `amount/_shared/` if a second Amount concern reads PayU |
| `action/WalletCreditService.java` | `concern/amount/recharge/` | the only registered action; recharge is its only consumer |
| `facts/ForgetMpinFactProvider.java` | `concern/amount/forgetmpin/` | — |
| `process/ForgetMpinDelegate.java` | `concern/amount/forgetmpin/` | a delegate that names one concern does not belong in `process/` |
| `facts/ProductDeliveryFactProvider.java` | `concern/product/deliverydelay/` | — |
| `facts/ClassifierFactProvider.java` | **splits — see below** | one class, two concerns, two L1s |
| `resources/dmn/*.dmn` (8 files) | `resources/concern/<l1>/<l2>/` | the table belongs with its concern |
| `resources/processes/forget-mpin.bpmn20.xml` | `resources/concern/amount/forgetmpin/` | the only concern-specific process |

**The one class that has to split.** `ClassifierFactProvider` is an outer class holding two
nested `@Component` providers — `ViolationOthers` serving `VIOL_R4_OTHERS`, and
`FreeTextTriage` serving `OTHER_FREETEXT_TRIAGE`. Two concerns, in **two different L1s**,
reading identical facts.

Under L1 nesting it has no single home, and that is the structure telling the truth rather
than a problem with the structure:

| Part | Goes to | Because |
|---|---|---|
| the shared classification logic (`factsFrom`, the four fact keys) | `shared/classification/` | crosses an L1 boundary, so by the rule above it is globally shared |
| `ViolationOthers` | `concern/violations/r4others/` | a thin provider that names one concern |
| `FreeTextTriage` | `concern/other/freetext/` | likewise |

Two small classes instead of one nested one, and each sits where a developer looking for that
concern would look.

### 3.2 Becomes `shared/` — 9 classes

| Today | Proposed | Used by |
|---|---|---|
| `facts/shared/DeliveryTatService.java` | `shared/tat/` | product delay · violation R9 |
| `facts/shared/GeoService.java` | `shared/geo/` | transport path 3 · violation R1 |
| `facts/shared/HubGeometry.java` | `shared/geo/` | as above |
| `facts/shared/DuplicateCreditGuard.java` | `shared/ledger/` | **every credit path** |
| `facts/shared/LedgerEntry.java` | `shared/ledger/` | as above |
| `counter/SpCounter.java` + `SpCounterId` + `SpCounterRepository` + `SpCounterService` | `shared/counter/` | four distinct counter windows across concerns |

These match the concern sheet's own **Shared Services** tab, which independently names the
same primitives and says why each must be built once.

### 3.3 Becomes `platform/` — 68 classes

Whole packages move under `platform/` with no internal change:

`api/` (4) · `api/dto/` (8) · `session/` (7) · `decision/` (2) · `catalogue/` (3) ·
`safety/` (9) · `escalation/` (2) · `csat/` (1) · `agent/` (3) · `classifier/` (7) · `config/` (4)

Plus the parts of `facts/` and `action/` that are the **SPI rather than an implementation**:

| Today | Proposed |
|---|---|
| `facts/ConcernFactProvider.java` · `FactProviderRegistry` · `FactRequest` | `platform/facts/` |
| `facts/UatColumn.java` · `UatSchemaProbe.java` | `platform/facts/` |
| `action/ActionService.java` · `ActionRegistry` · `ActionRequest` · `ActionResult` | `platform/action/` |

And `process/` (14 remaining, after `ForgetMpinDelegate` leaves) → `platform/process/`.

### 3.4 Becomes `surface/` — 9 classes

`console/` (4) · `demo/` (4) · `reconcile/` (1). These observe the system; they are not part of
the decision path, and keeping them outside `platform/` makes it obvious which code can be
removed from a production image.

### 3.5 Becomes `integration/` — 1 class

`payu/MockPayUGateway.java` → `integration/payu/`. An external system, not a concern.

This package is also where **shape-C fact sources** live (§4, Obligation 2a): a fact whose
answer another team computes is reached through an interface here, never through a client
held by the provider. Today the only member is `PayUGateway`, and it is used by an *action*
rather than a fact — so the pattern is proven and the fact-side platform is not yet built.

### 3.6 The one file that does not simply move

**`process/ResponseTemplates.java`** is a static `Map.ofEntries` of action code → partner
wording, and it is the shared file every concern edits.

Proposed: it becomes a **resolver** in `platform/process/` that reads
`resources/concern/<name>/templates.properties`.

```properties
# resources/concern/transport/templates.properties
INFORM_ALREADY_PAID   = Ye amount pehle hi aapke wallet me aa chuka hai.
DENY_DID_NOT_TRAVEL   = Is booking par aap gaye nahi the, isliye transport amount nahi banta.
TICKET_EXCEEDS_CAP    = Aapka case team ko bhej diya gaya hai.
```

Three things this buys: adding a concern stops editing a file other concerns own; wording
changes stop being Java changes; and the door to per-language files opens without a redesign
(NFR-08 wants Hindi/Hinglish).

### 3.7 Tests

The 36 test classes mirror the same tree. Concern tests move beside their concern:
`DecisionTableTest`'s per-concern nested classes are the one place that needs splitting rather
than moving.

---

# 4. The concern contract

**Seven obligations.** A concern that satisfies all seven works. One that misses any fails the
build, by design — see §5.

### Obligation 1 — a catalogue row

```sql
INSERT INTO concern_catalogue
  (l2_code, l1_code, l1_label, l2_label, display_order, active, mandatory_human,
   process_key, fact_provider, dmn_key, togglz_flag, default_tier, rule_count, june_volume, notes)
VALUES
  ('MAIN_WALLET_TO_BANK', 'AMOUNT_RELATED', 'Amount Related', 'Main Wallet to bank transfer',
   70, TRUE, FALSE, 'concern-generic', 'MAIN_WALLET_TO_BANK',
   'main-wallet-to-bank-decision', 'MAIN_WALLET_AUTO', 'T1', 3, 316, '...');
```

`process_key` is `concern-generic` unless the concern genuinely needs its own flow. **Adding a
concern must not add a BPMN file.** `FORGET_MPIN` is the one deliberate exception, and it
exists to keep the T0 proof in its own three-element file.

### Obligation 2 — a fact provider

```java
@Component
class MainWalletToBankFactProvider implements ConcernFactProvider {
    public String concernCode()   { return "MAIN_WALLET_TO_BANK"; }   // == catalogue.fact_provider
    public Set<String> factKeys() { return Set.of("balanceRupees", "mpinSet", "loanOutstanding"); }
    public List<UatColumn> requiredColumns() { ... }          // shapes A and B
    public List<ExternalDependency> requiredServices() { ... } // shape C — NOT YET BUILT, see 2a
    public Map<String,Object> fetchFacts(FactRequest r) { ... }
}
```

**Every key in `factKeys()` is present in the returned map on every path**, null when unknown.
`emptyFacts()` does this for you. A null fact evaluates and reaches the catch-all; an *absent*
one is an evaluation error that takes the whole table down.

### Obligation 2a — the three fact shapes

A fact provider is a plain Spring bean. **The SPI fixes what it returns and says nothing about
where the data comes from** — `fetchFacts` is ordinary Java, and the source is a
constructor-injection detail invisible to the delegate, the registry, the decision table and
every test above it. So one provider may use all three shapes at once, and the shape is chosen
**per fact, not per concern**.

| | Shape | Choose it when | Declare with | Live example |
|---|---|---|---|---|
| **A** | One or more columns read over `uatJdbcTemplate` | the fact is **stored** in a table we may read | `requiredColumns()` | `ProductDeliveryFactProvider` — `order_type` → `express` |
| **B** | Rows reduced in a private method, possibly across tables and catalogs | the derivation is **ours** and depends only on rows | `requiredColumns()` for every table touched | `TransportFactProvider` — 4 statements, 2 catalogs, 3 collaborators, 7 facts |
| **C** | A call through an interface in `integration/` | the owner **computes** the answer, or it changes on write, or reading their table would re-implement their business logic inside BOTIn | `requiredServices()` | `PayUGateway` — the seam exists, **no fact provider uses it yet** |

**Rules that hold across all three**

- **Never throw.** Unknown is `null`, not an exception. `FetchFactsDelegate` catches anyway,
  but a provider that leans on that discards the partial answer it had already established.
- **One `try` per source, never one `try` per method.** A shape-C outage must cost the two
  facts that source owned, not the four already read from the database.
- **Never decide.** The test: *could Product change this number without a deploy?* If yes it is
  a rule and it belongs in a DMN row. `computedAmountRupees` is a derivation and lives in the
  provider; the Rs300 cap is a decision and lives in the table, above every paying row, where it
  produces a **ticket** rather than a smaller payment.
- **Name the direction of every degradation, in a comment, at the line.** "Unreadable order
  type → Standard, because Standard is the slower TAT and can only ever tell a partner to wait
  longer than they should — never call an on-time order late." A degradation whose direction
  nobody chose is a guess wearing a fallback's clothes.
- **Unavailable is never the negative value.** Inventory down must set `stockAvailable = null`,
  not `false`. `false` means *out of stock*, which on this concern is a T2 refund — so reading
  an outage as a negative auto-pays on every outage.
- **Shape C gets one attempt and a tight budget.** No retries: this runs inside a partner's live
  chat turn, and the degraded answer (null → a human) is already correct. Retrying multiplies
  latency to improve an answer that has a good failure mode.
- **Shape C always hides behind an interface in `integration/`.** The provider depends on a
  contract, never on a client. *Is this wired to the real thing?* must be answerable by looking
  at which bean is active, not by reading the class.
- **A derivation shared by two concerns moves to `shared/`; a query never does.** Sharing a
  derivation is safe because it is pure. Sharing a query encodes *which rows count*, and is how
  tuning one concern silently changes another concern's decision weeks later with green tests.
  `DeliveryTatService` is the live case — `PROD_DELIVERY_DELAY` and `VIOL_R9_NO_PRODUCT` must
  never disagree about "late".

**What each fact needs before it can be built.** Knowing the fetch logic is necessary and not
sufficient. Four things per fact, and the first two are the ones that silently go wrong:

1. **The key, spelled exactly as the decision table's `inputExpression`.** A name that does not
   exist behaves exactly like a value that is false. The table read `arrivedAt300m` while the
   provider supplied `arrivedAt300metre`, and every Path 2 claim would have reached an agent
   with nothing reporting it. A test asserts `factKeys()` equals the table's inputs.
2. **The physical column name, confirmed or declared inferred.** A wrong name in a `SELECT` is a
   SQL error, which is survivable. The unsurvivable version is a name that exists but is not the
   one meant: the query runs, returns rows, and answers wrongly. `UatSchemaProbe` checks every
   declared column at startup against `information_schema`.
3. **The unit, where the fact is money.** Three columns in the live schema are still unconfirmed
   as rupees or paise. A unit error is silent in both directions and one of them double-pays.
4. **The degradation direction**, decided and written at the line.

**Shape C is not yet ready, and these four platform pieces come before the first one — not
after, because the first one sets the precedent:**

- a shared HTTP client with **one** timeout policy, the way `uatJdbcTemplate` carries
  `setQueryTimeout(5)` once for every shape-A and shape-B provider
- `ExternalDependency` plus a startup probe, the symmetric twin of `UatSchemaProbe`: loud ERROR
  on an unreachable dependency, boot failure only on a money path
- the no-retry rule, enforced in the client rather than remembered per provider
- a caching decision. Irrelevant against MySQL at 5ms; not irrelevant for a near-static product
  catalogue called on every turn of a high-volume concern.

### Obligation 3 — a decision table

`resources/concern/<name>/<dmn-key>.dmn`, where the decision id equals `catalogue.dmn_key`.

- `hitPolicy="FIRST"` — **row order is logic, not formatting**
- one `<input>` per fact key, label matching exactly
- outputs: `tier`, `action`, `outcomeType`, plus any numbers the action needs
- **the last rule is a catch-all with every input empty**
- an XML comment above each rule saying *why* — the console renders it as the rule's explanation

### Obligation 4 — a response template per action code

One line in `resources/concern/<name>/templates.properties` for every distinct `action` the
table can output.

### Obligation 5 — an action service, if any rule is T2

```java
@Component
class MainWalletTransferService implements ActionService {
    public String actionCode() { return "AUTO_TRANSFER_MAIN"; }   // == the DMN output
    public ActionResult perform(ActionRequest request) { ... }
}
```

No registration — `ActionRegistry` collects every bean and refuses to start if two claim one
code. **Without this the concern still works**: `DecideDelegate` sees no service for the action
and escalates to a human, which is the correct failure.

### Obligation 6 — a kill switch, if it automates

A constant in `BotinFeature` whose name equals `catalogue.togglz_flag`. Off means *do not
automate, send to a person* — never *fail*.

### Obligation 7 — tests

- a decision-table test **per rule**, including the catch-all
- a provider test for the null path — unknown reference, nothing readable
- one acceptance scenario in `DemoScenarios`
- fixtures in `demo/fixtures.json`

---

# 5. The guards — what fails when you forget

A document nobody reads is not a contract. **Each obligation has a check that fails loudly.**

| Obligation | Guard | Where | Exists |
|---|---|---|---|
| provider code == `catalogue.fact_provider` | `check_catalogue_pointers` | `tools/preflight.py` | ✅ |
| no two providers claim one concern | startup exception | `FactProviderRegistry` | ✅ |
| `factKeys()` == DMN input labels | `check_table_contract` | `tools/preflight.py` | ✅ |
| `dmn_key` == the decision id in the file | `check_catalogue_pointers` | `tools/preflight.py` | ✅ |
| togglz flag == enum constant | startup report | `FeatureNameValidator` | ✅ |
| declared columns exist | startup probe | `UatSchemaProbe` | ⚠️ logs only |
| declared **services** reachable | startup probe | `ExternalDependencyProbe` | ❌ **not built** |
| **every table ends in a catch-all** | — | — | ❌ **new** |
| **every action code has a template** | — | — | ❌ **new** |
| **every T2 action code has a service** | escalates at runtime | `DecideDelegate` | ⚠️ runtime only |
| **every active concern has a test** | — | — | ❌ **new** |
| **`concern/<l1>/<l2>/` matches `catalogue.l1_code`** | — | — | ❌ **new** |

**Five new guards, all cheap, all in `preflight.py` except the schema probe:**

1. **Catch-all present and last.** Parse each `.dmn`; fail if the final rule has any non-empty input entry. This is the guard against the worst failure mode in the system — an unmatched case returns null, and a null tier reaching a gateway produced *no error anywhere* in spike 2.
2. **Action code coverage.** Collect every `action` output across all tables; fail if any has no line in its concern's `templates.properties`.
3. **T2 actions have a service, at build time.** Collect every action on a rule whose tier is `T2`; fail if no `ActionService` declares it. Today this is discovered only when a partner hits it.
4. **Every active concern is tested.** Fail if an active catalogue row has no decision-table test and no acceptance scenario.
5. **The folder agrees with the catalogue.** For every concern, assert that the `l1` segment of its package path maps to the `l1_code` on its catalogue row. This is what makes L1 nesting safe: the filesystem cannot drift from the database, and re-homing a concern without moving its folder fails the build instead of quietly misleading the next reader.

Guard 3 would have caught the live gap immediately: **Transport decides T2 correctly and then escalates, because nothing performs `AUTO_CREDIT_TRANSPORT`.**

And `UatSchemaProbe` should **fail the deployment** rather than log an error. It is the check
that would have caught `arrived_at300_m` the day the grant landed.

The service probe is the same guard for shape-C facts and does not exist yet. It is one of the
four platform pieces that must land **before** the first shape-C fact provider, not after —
listed in §4, Obligation 2a. Without it, an endpoint that moved, a contract version that
changed, or a renamed field in a JSON response is discovered by a partner rather than by the
deployment, which is exactly the failure `UatSchemaProbe` exists to prevent on the DB side.

---

# 6. `concern/_template/`

A skeleton that **compiles and passes its tests** as shipped, so a copy is a working starting
point rather than a broken one.

```
concern/_template/                    NOT under an L1 — it belongs to none
├── CHECKLIST.md                      the seven obligations, as ticks
├── TemplateFactProvider.java         every method stubbed, TODO markers
├── TemplateActionService.java        delete if the concern is not T2
└── (resources) concern/_template/
    ├── template-decision.dmn         two rules: one real, one catch-all
    └── templates.properties          one line per action in the table
```

`CHECKLIST.md`, in full:

```
[ ] 1. Catalogue row added in a Flyway migration
       l2_code · l1_code · process_key=concern-generic · fact_provider · dmn_key
       · togglz_flag (only if it automates) · active
[ ] 2. Fact provider: concernCode() matches fact_provider exactly
       factKeys() returns EVERY key, and fetchFacts returns every key on every path
       requiredColumns() declares each column, confirmed or inferred — be honest
[ ] 3. Decision table: id matches dmn_key · hitPolicy FIRST · inputs match factKeys
       · catch-all LAST · a comment above every rule saying why
[ ] 4. templates.properties: one line per action the table can output
[ ] 5. ActionService if any rule is T2 — actionCode() matches the DMN output
[ ] 6. BotinFeature constant if it automates, named exactly as togglz_flag
[ ] 7. Tests: one per rule incl. catch-all · a null-path provider test
       · an acceptance scenario · fixtures

Then: python3 tools/preflight.py && mvn test
If preflight fails it names the obligation you missed. Read the message; it is written for you.
```

---

# 7. Adding a concern, end to end

**A new L2 under an existing L1**

1. `cp -r concern/_template concern/<l1>/<l2>` — **into the L1 folder it belongs to**
2. Same for the resources folder
3. Rename the classes, the package declaration and the `.dmn`
4. Work down `CHECKLIST.md`
5. `python3 tools/preflight.py` until clean
6. `mvn test`

No new BPMN. No edit to any file another concern owns. No conversation required.

**A new L1**

1. `mkdir concern/<l1>` and `resources/concern/<l1>`
2. Catalogue rows carrying the new `l1_code` and `l1_label`
3. Each L2 underneath, exactly as above

The menu needs nothing: the L1 list is built by `SELECT DISTINCT` over the catalogue, so a new
L1 appears in the app with nothing rebuilt. The client has never heard of a concern, a tier or
a rupee — it switches on `nextStep.type` and nothing else.

**Why the folder exists at all, given that.** An L1 is a label on rows, and the runtime never
branches on it. The folder is there to teach: it makes "add an L2" and "add an L1" visibly
different operations, gives each L1 a home for code shared across its own concerns
(`_shared/`), and means a developer holding the PRD finds the same shape on disk that the
document describes. Guard 5 is what keeps that teaching honest.

---

# 8. Risks in executing this

**A large diff.** ~100 files move, every import changes, `git blame` gets a layer of
indirection. Cheaper now than after thirty more concerns. Do it as **one commit that only
moves files**, with no behaviour change, so the diff is reviewable as a rename.

**Flowable will stop finding the resources.** It scans `classpath*:/dmn/` and
`classpath*:/processes/` by default. Moving tables under `concern/` **requires** updating the
resource-location properties, and if that is missed, every decision fails and every case
escalates. Loud, but only at runtime. **The exact property names must be verified against
Flowable 7 before the move, not assumed** — this is the single highest-risk step in the
refactor. A first-boot assertion that counts deployed definitions would close it permanently.

**`ResponseTemplates` changes behaviour, not just location.** It should be a separate commit
from the moves, with its own tests.

**Re-homing a concern becomes a package rename.** In Java the folder is the package, so moving
a concern between L1s touches every file in it and its tests. This is not hypothetical: the
reconciliation leaves four L1 assignments genuinely open — whether Fine Related exists or folds
into Violations, whether Product Issues exists at all, whether *Expert issue with customer
rating* is Fine or Other, and whether *Booking banwani hai* is Amount or Other.

Two things make it tolerable. It is a mechanical IDE refactor rather than a rewrite. And guard
5 turns a forgotten move from a silent inconsistency into a failing build. **But it is a real
argument for closing the taxonomy decisions (G2) before, not after, the concern folders are
created.**

**Test fixtures reference paths.** `DemoConsoleTest` and `DeployableXmlTest` read resource
paths from disk and will need updating in the same commit.

Suggested order: (1) add the four guards to the current layout, so the safety net exists
*before* anything moves · (2) move files, no behaviour change · (3) split `ResponseTemplates` ·
(4) add `_template/` and the checklist.

Guards first is deliberate: they are what tells us the move did not break anything.

---

# 9. First L1 — Amount Related

11,283 cases a month, 80.4% deflection — the strongest L1 in the sheet, and it holds the only
complete money path in the codebase.

Its L2s, in the order I would take them:

| # | L2 | June vol | State today | Why this position |
|---|---|---|---|---|
| 1 | `FORGET_MPIN` | 738 | built | Simplest possible concern. Proves the template and the guards with nothing else moving. |
| 2 | `RECHARGE_DEBIT_NO_CREDIT` | 883 | built | The only concern whose money path is complete. Proves the action layer, idempotency and the kill switch. |
| 3 | `TRANSPORT_NOT_RECEIVED` | 5,615 | built | Highest volume and the hardest table. Needs the credit service that does not exist yet. |
| 4 | `MAIN_WALLET_TO_BANK` | 316 | seeded | First concern built **new** under the blueprint — the real test of whether it works. |
| 5 | `ARW_TO_BANK` | 650 | seeded | Shares the Cashfree health check with #4. First chance to exercise the `shared/` rule. |
| 6 | `CASHBACK_NOT_RECEIVED` | 176 | seeded | Fully bot-resolvable on all four branches — no human path at all. |
| 7 | `REFERRAL_NOT_RECEIVED` | 192 | seeded | — |
| 8 | `ONLINE_BOOKING_MONEY` | 1,868 | seeded | Only ~15% genuine; most reroutes out. Best done once triage is trusted. |

The first three are deliberately concerns that already work. **They exercise the new structure
without also inventing new logic** — so if something breaks, it is the structure, and we know
it immediately.

`SAVING_UNDER_5K` (1,042) is excluded: the sheet deprecates it and the PRD keeps it live. That
is decision #5 on the reconciliation, and it should be answered before anyone builds it.
