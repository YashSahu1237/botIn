# DMN — how the decision tables are implemented

**DMN decides what to do. It reads nothing and orchestrates nothing.** It is handed a map of
facts and returns four values: `tier`, `action`, `outcomeType`, `ratePerKmPaise`.

The governing principle: **Java answers "what is true", DMN answers "what to do about it."**
No threshold, no tier, no outcome and no cap exists anywhere in Java.

---

## 1. What exists

```
src/main/resources/dmn/
  transport-not-received-decision.dmn   11 rules   ← the reference example
  recharge-debit-no-credit-decision.dmn  6 rules
  prod-delivery-delay-decision.dmn       3 rules
  other-freetext-triage-decision.dmn     3 rules
  viol-r4-others-decision.dmn            3 rules
  forget-mpin-decision.dmn               1 rule
  viol-r5-periods-decision.dmn           3 rules   ← out of POC scope
  viol-r9-no-product-decision.dmn        5 rules   ← out of POC scope
```

Which table a concern uses is **`concern_catalogue.dmn_key`** — a database column, not a
line of Java.

---

## 2. Anatomy of a table

Columns are declared once:

```xml
<decisionTable id="dt-transport" hitPolicy="FIRST">
  <input  label="alreadyCredited">       ... typeRef="boolean"
  <input  label="computedAmountPaise">   ... typeRef="number"
  ... 5 more ...
  <output label="tier"           name="tier"           typeRef="string"/>
  <output label="action"         name="action"         typeRef="string"/>
  <output label="outcomeType"    name="outcomeType"    typeRef="string"/>
  <output label="ratePerKmPaise" name="ratePerKmPaise" typeRef="number"/>
```

Every rule then carries **exactly one cell per declared column, matched by POSITION**:

```xml
<rule id="t2">
  <inputEntry><text></text></inputEntry>            <!-- 1: alreadyCredited — don't care -->
  <inputEntry><text>&gt; 30000</text></inputEntry>  <!-- 2: computedAmountPaise -->
  <inputEntry><text></text></inputEntry>            <!-- 3: transportPath -->
  ... 4 more, always ...
  <outputEntry><text>"T3"</text></outputEntry>
  <outputEntry><text>"TICKET_EXCEEDS_CAP"</text></outputEntry>
  <outputEntry><text>"TICKET"</text></outputEntry>
  <outputEntry><text>0</text></outputEntry>
</rule>
```

**The `id` attributes are decoration.** Nothing reads `t2i2`. Delete one `<inputEntry>` and
every cell after it silently shifts a column left — which is why the count must always match,
even when six cells are empty.

---

## 3. How the facts get in

`process/DecideDelegate.java` → `decision/DecisionService.java`:

```java
dmn.createExecuteDecisionBuilder()
   .decisionKey(concern.getDmnKey())   // from the catalogue row
   .variables(facts)                   // the fact map
   .executeWithAuditTrail();
```

**The map keys ARE the column names.** `alreadyCredited` in the map is `alreadyCredited` in
the table. Nothing maps or translates between them, which is why a renamed fact silently
breaks a table.

`executeWithAuditTrail()` rather than `executeWithSingleResult()` because the audit trail is
what says **which rows matched and which fired**. The alternative — re-evaluating conditions
in JavaScript for the console — would be a second expression language beside the engine: a
second source of truth for the one thing that must never have two.

---

## 4. How a row is checked

Per rule, top to bottom:

1. **An empty cell always matches** — the column does not matter for that row.
2. **A non-empty cell is a test** against that column's value:
   - `true` / `false` — equals that boolean
   - `"PATH_1"` — equals that string. **The quotes are required**; without them it reads as a variable name
   - `&gt; 30000` / `&lt;= 0` — comparisons (`&gt;` is `>` escaped for XML)
3. **Every cell in a row is ANDed.** Rule 7 of the Transport table is four conditions.
4. **The first fully-matching row wins and evaluation stops.** Rows below are not "lower
   priority" — they are never reached.
5. That row's outputs are the answer. Strings quoted, numbers bare.

### null vs absent — the distinction that costs a table

A **null** fact evaluates fine, fails any non-empty test, and falls to the catch-all. An
**absent** variable is an evaluation error under strict mode, and **a failed expression takes
the whole table down, catch-all included** — the decision returns nothing at all.

That is why `ConcernFactProvider.emptyFacts()` puts every key in the map with a null value
rather than omitting it.

---

## 5. FIRST hit policy: row order is logic

The single most consequential fact about these files.

**The ₹300 cap is row 2, not row 9.** The concern mapping listed it last. At position 9, a
₹450 Path-1 claim matches row 3, fires, pays ₹450, and the cap row is never reached. Not a
bug any test that merely checks "the cap row exists" would catch.

Worked example, order 7002 — `computedAmountPaise = 45000`, `transportPath = "PATH_1"`:

| Row | Test | |
|---|---|---|
| 1 | `alreadyCredited == true` → false | ✗ |
| **2** | `computedAmountPaise > 30000` → 45000 | **FIRES** → T3 / TICKET_EXCEEDS_CAP |
| 3 | *would have matched and paid ₹450* | never reached |

**A cap that pays a smaller amount is a silent underpayment nobody ever sees.** This one
produces a ticket — a handover to a person, not a clamp. The amount is deliberately **not**
clamped in Java either; `amountFor()` returns the full figure and the table decides.

---

## 6. The three scars in the Transport table

Each is a comment in the file, and each cost time once.

**The cap at row 2.** Above every paying row, because FIRST would otherwise never reach it.

**Rules 5a and 5b as two rows.** The obvious form is one row with
`"CANCELLED_NR","CANCELLED_BY_AGENT"`. That is written up as valid FEEL and **did not
evaluate here**: with a non-null `cancellationStatus` the expression failed, and a failed
expression takes the whole table down. Two rows cost two lines and cannot fail that way.

**The catch-all last, and it must stay.** Without it an unmatched case returns `null`, and a
null tier reaching a BPMN gateway produced **no error anywhere** in spike 2. It did not
crash — it stopped, silently. `DecisionService` throws `NoMatchingRuleException` if a table
ever returns nothing, because failing loudly is the whole point.

---

## 7. Derived facts — what PATH_1 / 2 / 3 are

`transportPath` is in no database column. It is computed in
`facts/TransportPathSelector.java` before the table runs:

```java
if (customerTransportCharged && !alreadyCredited)  return PATH_1;
if (cancellationStatus != null)                    return PATH_2;
return PATH_3;
```

| Path | The situation | Decided by |
|---|---|---|
| **PATH_1** | the customer paid a transport charge and it never reached the wallet | nothing to judge — pay what they paid (1 row) |
| **PATH_2** | the booking was cancelled | did they travel, and who cancelled (5 rows) |
| **PATH_3** | ordinary travel | distance beyond the hub **radius edge** (2 rows) |

**Why a selector exists at all:** the mapping labelled rules Path 1/2/3 but never said what
assigns a claim to a path. Without one the paths are not mutually exclusive — and under FIRST
that is not a visible conflict, it is a silent one. A claim cancelled by an agent *and*
outside the radius matches two rows that pay different amounts, and the table quietly takes
whichever sits higher.

So the assignment is made once, in one named method, with its own test.

---

## 8. Seeing and changing the tables

### Seeing

**The console renders them.** `/console` → *"The decision tables"* — every row, every cell,
with the XML comment above each rule shown as its explanation. No case has to be run first.
`GET /console/tables` and `GET /console/table/{dmnKey}` are the endpoints.

**The IDE cannot.** These files have no `DMNDI` layout section — they were written as XML,
not drawn — so a graphical modeller shows "Empty Diagram". **Do not click Auto-generate
Diagram**: it rewrites the file and a modeller round-trip will drop the XML comments, which
are where the reasoning lives. To read the file, open it as XML (in IntelliJ: right-click →
Override File Type → XML).

### Changing

| Change | How |
|---|---|
| a threshold (cap ₹300 → ₹500) | edit `&gt; 30000` → `&gt; 50000` in rule `t2` |
| priority | move the `<rule>` block |
| a new condition | add a `<rule>` **above the catch-all** |
| outcome or tier | edit its `<outputEntry>` |
| which table a concern uses | `concern_catalogue.dmn_key` — no file at all |

`DmnHotRedeployTest` proves a table can be swapped **against a running engine**: it edits the
cap to 20000, redeploys that one file, asserts the behaviour changed, and restores it.

**There is no redeploy endpoint and there should not be one.** An HTTP call that changes how
money is decided is not a feature.

### The guard that keeps it honest

`theCapLivesOnlyInTheTable` scans `src/main/java` and **fails if `30000` appears anywhere in
it**. It once failed on my own javadoc quoting the number; the fix was to remove the number,
not to weaken the guard — *a guard with no exceptions is worth more than a guard with one
reasonable-looking exception.*

---

## 9. The limit, stated plainly

Editing a table changes **when** a fact matters, what it is worth, which tier it lands in,
and in what order rules are tried. It cannot make a rule read something new: if a rule needs
a fact the provider does not fetch, that is Java and a rebuild.

**The policy is configuration; the vocabulary is code.**

---

## 10. What is currently unreachable, and why

Of the Transport table's 11 rows:

| Rows | Status |
|---|---|
| 1, 2, 3, 4, 5b, catch-all | fire today |
| **5a, 6, 7** | dead — nothing produces `CANCELLED_NR` or `CANCELLED_CR` (**N2**) |
| **8, 9** | dead on real data — `distanceBeyondRadiusKm` is null, because `tbl_order` carries no job lat/long (**Q6**) |

Across all six active concerns: **15 of 26 rules can fire.** Every dead rule is dead because
a signal is missing, and in every case the fallback is a human rather than a wrong answer.
`docs/SIGNAL-REGISTER.md` records each one with what its absence costs a partner.
