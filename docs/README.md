# BOTIn Mod — document repository

Every document produced for this project. Nothing is stored only in a chat transcript.

Start with **`../CLAUDE.md`** — it is the continuity record and explains the current state,
the standing rules, and what is blocked on whom.

The HTML documents open in any browser. Double-click them.

---

## CODE-MAP.md — start here for the codebase

**`CODE-MAP.md`** is the orientation document for the service itself: the one-page mental
model of how a request travels, every package and file with one line each, the five guards
and the incident behind each, **how to add a new concern**, and **how to change a rule in a
DMN file**. Read it before opening `src/`.

The other loose files in this folder:

| File | What it is |
|------|-----------|
| `SIGNAL-REGISTER.md` | **Which facts are real today and which are always null**, per concern, with what each absence costs a partner and which decision rules it leaves unreachable. Read this before believing a demo. |
| `UAT-SCHEMA-MAP.md` | which UAT catalog, table and column each fact is read from, and which column names are confirmed versus inferred. |
| `WHAT-I-NEED.md` | the open items that need someone other than the build to resolve. |
| `DEFERRED.md` | parked decisions. Each entry states what was deferred, why, and what unblocks it. |

---

## design/ — the documents that describe the system

| File | What it is |
|------|-----------|
| `01-decision-brief.html` | **Part A.** The decision-level document — what is being built and why, for a reader who will not read code. |
| `02-engineering-design.html` | **Part B.** The engineering document. **This is the authoritative technical description**, including the architecture decision records. |
| `03-decision-engine.html` | All **113 decision rules** across **38 concerns**, with the outcome vocabulary (BOT, TICKET, UPHOLD, REROUTE, SELF-SERVE, V2, DEPRECATED). |

## planning/ — how the build is sequenced and where it stands

| File | What it is |
|------|-----------|
| `poc-tracker.xlsx` | **The source of truth for status.** Four tabs: Summary (formula-driven), Next up, Steps (98 rows), Evidence. Change a Status cell on Steps and Summary recalculates. |
| `poc-build-plan.html` | The 98-step build plan in full, with the reasoning behind the sequencing. |
| `component-notes.html` | What each dependency actually is, why it was chosen, and what the R&D ladder proved about it. Read this before touching Flowable, DMN, Togglz or Resilience4j. |

## data/ — the concern mapping, normalised

Generated from the L1/L2 concern mapping. These are the inputs the decision engine is
built from.

| File | Contents |
|------|----------|
| `concern_catalogue.csv` | 38 concerns |
| `decision_rules.csv` | 113 rules |
| `decision_facts.csv` | 9 fact domains, 67 signals |
| `shared_services.csv` | 10 shared services |
| `caps_and_thresholds.csv` | 18 caps and thresholds |

## spikes/ — the R&D ladder

Throwaway projects, each answering one question that could have invalidated the
architecture. All green. They are kept as evidence, not as code to build on.

| File | Question it answered |
|------|---------------------|
| `spike-1-3-flowable.zip` | Does a Flowable process survive the JVM dying mid-flight? Does `REQUIRES_NEW` survive a caller rollback? |
| `spike-2-dmn.zip` | How does DMN `FIRST` hit policy behave with a deliberately wrong row order, and with no catch-all row? |
| `spike-4-togglz-resilience-flyway.zip` | Do Togglz, Resilience4j and Flyway behave as assumed? **This one found the Togglz silent-false hole.** |
| `spike-4-fix.zip` | The correction to spike 4's own test SQL (H2 quotes Flyway's history table in lower case). |
| `spike-5-classifier.zip` | Does the FastAPI + Pydantic classifier contract hold, including closed enums and bounded confidence? |

## superseded/ — history only

**Do not build from anything in this folder.** These were replaced by the documents in
`design/` and `planning/`. They are kept so a decision can be traced back to where it was
first made.

| File | Replaced by |
|------|------------|
| `tech-design-original.html` | Split into Part A and Part B |
| `combined-tdd.html` | Split into Part A and Part B |
| `layer-reference.html` | Folded into the Engineering Design |
| `flow-map.html` | Folded into the Engineering Design |
| `poc-scope-lock.html` | The 98-step build plan |
| `poc-checklist-56-step.html` | The 98-step build plan |

## Added 15 September

| File | What it is |
|---|---|
| `DEMO-WALKTHROUGH.md` | **The presenter's script.** How to start the console, what to click, what each of the 17 cases proves, and the sentence to say out loud. Case 1 is written out end to end, including the full request path for one deflection |
| `POC-READINESS.md` | What can be shown today, and what stands between here and the exit criteria |
| `SCOPE-VIOLATIONS-OUT.md` | Violations removed from the POC: what went, what it costs, and the one decision it leaves open |
| `WALKTHROUGH-NOTES.md` §8 | Where the one-point-at-a-time walkthrough stopped, and the two probes waiting to be run |

## Added 16 September — the implementation references

| File | What it is |
|---|---|
| `BPMN-IMPLEMENTATION.md` | The flow engine: why a process engine at all, the three files, the eleven delegates, process variables and the rules that govern them, deployment and versioning, what is not in the BPMN yet and where it goes |
| `DMN-IMPLEMENTATION.md` | The decision tables: anatomy, how a row is checked, FIRST hit policy, the three scars, derived facts, seeing and changing tables, and what is currently unreachable |
| `DATABASE-ACCESS.md` | **How to see the data live.** The H2 browser console for the demo, psql for Postgres, what each of the 7 tables and the Flowable `ACT_*` tables hold, and six queries that show something |

