# BOTIn — plan to production

Built on three sources: the PRD (*PX — Outbound Model — BOT Intervention*), the POC
assessment, and the platform conventions read out of the `empapi` repository.

**One thing is settled:** BOTIn ships as a **new standalone service**. That closes D-1,
which the Decision Brief and the Engineering Design had disagreed on.

---

# PART ONE — three findings to settle before anything is built

These are not risks. They are facts about the gap between what the POC built and what the
PRD launches, and each one changes the plan.

## F1. The POC built Phase 2. The PRD launches Phase 1.

PRD §14 phases the rollout:

| Phase | Scope |
|---|---|
| **1** | Help entry + **Tier 0/1 only** — leave video, status checks, redirects |
| **2** | High-volume backend — Fine refund, Leaves fine, **Transport** (~54%), wallet actions, service-edit + settlement |
| **3** | Realtime + agent connect — cancel/reschedule/IVR/parking, outbound dialer, medal bands |
| **4** | Strike engine + optimise |

What the POC actually built, against those phases:

| Built | PRD tier | PRD phase |
|---|---|---|
| `FORGET_MPIN` | T1 | **Phase 1** |
| `TRANSPORT_NOT_RECEIVED` | T2 | Phase 2 |
| `RECHARGE_DEBIT_NO_CREDIT` | T2 | Phase 2 |
| `PROD_DELIVERY_DELAY` | — | **not in the PRD at all** (see F3) |
| free-text triage, `VIOL_R4_OTHERS` | routing | supports all phases |

**Exactly one built concern belongs to Phase 1.** The two concerns that carry the
engineering value — the ones that move money, that the cap, idempotency, reversal and
kill-switch work was all built for — are Phase 2.

**Decision required.** Either Phase 1 ships as the PRD defines it and the T2 work waits, or
Phase 1 is re-cut to carry Transport and Recharge with it. The second is defensible — the
decision layer for them is built and tested — but it means money moves at launch, and the
PRD deliberately sequenced that second.

## F2. NFR-01 makes Phase 1 far larger than "a few bot concerns"

> **NFR-01** — Help must fully replace Call Expert Care and the Ticket menu **at launch** —
> no parallel legacy entry point left active.

The bot capability is phased. **The entry point is not.** At launch every concern an SP can
raise today must be reachable through Help — including the thirty-odd the bot will not
handle until Phase 2, 3 or 4.

So Phase 1 is not "a handful of bot concerns". It is **a complete front door on which a
handful of concerns are bot-resolved and everything else is a clean T3 handover to a human.**

Most of that work is not decision logic. It is:

- the **full concern catalogue** seeded, active and correctly tiered — all five L1 paths, every L2
- **agent handoff that actually works end to end** — outbound dialer, CTI screen-pop (NFR-04), a real agent queue, not a REST endpoint
- the L1 **audio-note** selection (NFR-08), which does not exist in any form today
- the **order dropdown** (R-01) — top-5 bookings — which every booking-specific concern depends on

This is the single biggest item in the whole plan, and almost none of it was in POC scope.
It should be sized and staffed before a Phase 1 date is given to anyone.

## F3. The PRD's concern taxonomy is not the one that was built

| PRD's five L1 paths | Catalogue's six L1 codes |
|---|---|
| Realtime Job se jude huye issues | *(no equivalent)* |
| Leave sambandidh issues | `LEAVES_RELATED` |
| Amount related | `AMOUNT_RELATED` |
| Fine Related | `FINE_RELATED` |
| Other Related | `OTHER_ISSUES` |
| | `VIOLATIONS` |
| | `PRODUCT_ISSUES` |

Three mismatches:

- **`PRODUCT_ISSUES` does not appear in this PRD at all.** Out-of-stock, delivery delay and
  app-failure have no L1 path. `PROD_DELIVERY_DELAY` is a built, tested, working concern with
  2,306 monthly cases and, on this document, nowhere to live.
- **`VIOLATIONS` is not an L1 path.** Strike appeals sit under *Other Related*; fines under
  *Fine Related*. The R1–R13 sub-reason structure that consumed so much analysis does not
  appear here.
- **"Realtime Job" is an entire L1 path with eleven L2 concerns and nothing built for it** —
  and it is where most of the R-01…R-14 new capabilities live.

**This needs one answer:** is this PRD newer than the concern mapping the catalogue was built
from, or do they describe overlapping but different scopes? The seeded catalogue is the
system's configuration — if it is describing the wrong world, that is corrected once, early,
in a migration, not discovered per concern during build.

---

# PART TWO — the platform this has to fit into

Read from `empapi`, so this is what the estate actually does rather than what would be nice:

| | Convention |
|---|---|
| Runtime | Java 17, containerised |
| Base images | `098102135019.dkr.ecr.ap-south-1.amazonaws.com/ym-base-images` (build) and `ym-public-images:eclipse-temurin-17-jre` (runtime) |
| Build | multi-stage Dockerfile, `mvn -P${SPRING_PROFILE} clean package` |
| Observability | **Elastic APM agent** baked into the image and attached with `-javaagent` |
| Ports | 8080 (app) and 9090 |
| Environments | **dev · uat · pre-prod · prod** |
| Config | `src/main/config/<env>/application.properties`, selected by the Maven profile |
| Secrets | **AWS Secrets Manager** — `spring.config.import=aws-secretsmanager:<env>/empapi` |
| Region | ap-south-1 |

**BOTIn gets the same shape**, with `botin` in place of `empapi` — `prod/botin`,
`uat/botin`, and so on.

Two consequences worth stating now:

**The `env/botin-uat.env` file is a local-development convenience and must not survive into
any deployed environment.** Secrets come from Secrets Manager. The POC's rule — *no
credential is ever written into a file that is committed* — is already satisfied by the
platform's own mechanism.

**Spring Boot versions differ.** `empapi` is on 2.7.18, BOTIn on 3.2.5. Same Java, and the
runtime base image is a plain JRE, so this is not a blocker — but it does mean BOTIn cannot
copy `empapi`'s dependency choices unexamined.

**One decision this surfaces:** BOTIn's own database is **Postgres** in the POC; the platform
runs **MySQL**. Flowable supports both. Staying on Postgres means a second engine for the
DBA team to operate; moving to MySQL means a Flyway migration rewrite and re-testing the
transaction-boundary work. This should be decided with whoever runs the databases, not by
default.

---

# PART THREE — the plan

## Gate 0 — before a line of production code

Nothing below can be correctly sequenced until these are closed.

| | What | Who |
|---|---|---|
| G0.1 | **Repository location and access.** The project is no longer in the folder this session can read | Project owner |
| G0.2 | **F3 — taxonomy reconciliation.** Which concern set is current | Product |
| G0.3 | **F1 — the Phase 1 cut.** PRD Phase 1 as written, or re-cut to carry the built T2 concerns | Product + Engineering |
| G0.4 | **F2 — front-door ownership.** Who builds dialer, CTI, audio-note L1, order dropdown | Engineering leadership |
| ~~G0.5~~ | ~~Database engine~~ — **CLOSED: MySQL**, matching the platform. See `DECISIONS.md` D-2 | — |
| G0.6 | The PRD's **own six open questions** (§13). The **Agent-2 SLA** (NFR-03) is still blocking; the **reversible window** now has an agreed direction and open detail — `DISCUSSION-POINTERS.md` §1 | Product |
| G0.7 | The outstanding data answers: **N2**, **N3**, **Q6**, the two wrong column names, and paise-vs-rupees on `transport_charges` | Schema owners |

G0.6's reversible window is the one people underestimate. **NFR-07 requires auto-payments to
have a defined window before they are final.** Nothing in the POC implements that — today an
auto-credit is immediate and final. It is a design change to the action layer, not a setting.

## Workstream A — make what is read correct

Everything the decision layer does is worthless if the facts beneath it are wrong. This
workstream is small and it is first.

- **A1.** Fix `arrived_at300_m` and `order_type` — both columns do not exist. A wrong column
  name is MySQL error 1054, which kills the whole `SELECT`: Transport does not degrade on
  real data, it **stops**. Flip both `requiredColumns()` markers from `inferred` to
  `confirmed` in the same edit.
- **A2.** Settle **paise vs rupees** on `transport_charges`. No UAT order exceeds 30000. If
  the column is rupees, the ₹300 cap is comparing against a number 100× too large and **can
  never fire**.
- **A3.** **N2** — the `order_status_code` values for NR / CR / by-agent. Three transport
  rules are unreachable without it, and NR auto-credits where CR denies.
- **A4.** **N3** — what links a wallet credit to a PayU transaction. A wrong "not credited"
  **pays twice**.
- **A5.** **Q6** — wire the job lat/long behind `cs_address`. Two more transport rules are dead
  without it.
- **A6.** Run `UatSchemaProbe` against **production** schema, not only UAT, and make a probe
  failure fail the deployment rather than log a line.

**Exit:** every fact provider reads real columns; `/internal/signals/register` reports no
signal as absent for an in-scope concern; 26 of 26 rules reachable for the concerns in the
Phase 1 cut.

## Workstream B — the service

- **B1.** Repository, branch protection, code review, versioning.
- **B2.** `Dockerfile` matching the platform — ym base images, Elastic APM agent, 8080/9090.
- **B3.** `src/main/config/{dev,uat,pre-prod,prod}/application.properties` and the Maven
  profiles that select them.
- **B4.** Secrets Manager entries `dev|uat|preprod|prod/botin`. **No credential in any file.**
- **B5.** Database provisioned per environment; Flyway runs on deploy; the **read-only grant**
  on the three source catalogues created in prod exactly as it was in UAT — `SELECT` only,
  by GRANT, not by convention, with `readOnlyPropagatesToServer=true` — **and the connection
  pointed at the read replica** (`...sqlro...`), which `empapi` shows already exists. See
  `DECISIONS.md` D-3.
- **B5a.** **Port the schema from Postgres to MySQL** (D-2). Not a retype:
  `escalation_context` holds five `VARCHAR(4000)` columns — about **80,640 bytes under
  utf8mb4, over MySQL's 65,535-byte row limit — so the table will not create as written**.
  Those become `TEXT`. `UUID` has no MySQL type; the recommendation is `CHAR(36)` over
  `BINARY(16)`, because every runbook and every "why did this partner get paid" investigation
  starts with somebody pasting an id into a SQL client. `TIMESTAMP WITH TIME ZONE` becomes
  `DATETIME(6)` with `hibernate.jdbc.time_zone: UTC` carrying the contract.
- **B5b.** **Re-prove, don't re-type.** The transaction-boundary work — `REQUIRES_NEW`
  isolation, commit-before-call ordering, the duplicate-credit guard — was one of the two POC
  stop conditions and it was proved on one engine. Flowable's own schema, locking and history
  tables are engine-specific too. **Testcontainers MySQL for the migration and repository
  tests**: H2-standing-in-for-MySQL would not have caught the row-size limit above, which is
  exactly the class of thing that matters here.
- **B6.** CI: build, full test suite, **pre-flight guards**, image push to ECR.
- **B7.** Deploy pipeline per environment, with a **rollback that is exercised before launch,
  not documented**.
- **B8.** `demo` profile made impossible to activate outside local development, and H2
  removed from any deployed image. The demo profile mounts an unauthenticated SQL console and
  fixture data; it already refuses to start beside a real datasource, and that guard must be
  tested in CI rather than trusted.

## Workstream C — safety, security, compliance

- **C1.** **Authentication and authorisation on every endpoint.** The POC's API is open. The
  partner app, the agent surface and the operations surface are three different callers with
  three different rights.
- **C2.** The **Togglz console** behind real authentication — it exists, with HTTP Basic and a
  constant-time compare, and that is a POC-grade answer for a control that stops the bot
  paying people.
- **C3.** **The console and the demo scenario runner must not exist in production.** They are
  demonstration surfaces.
- **C4.** Rate limiting and abuse protection on the partner-facing endpoints.
- **C5.** **PII** — what is logged, what is stored, and for how long. `escalation_context`
  holds partner free text; `conversation_message` holds the transcript.
- **C6.** **Data retention.** Flowable's history tables grow without bound. `ACT_HI_ACTINST`
  gains a row per step per conversation, forever. A retention and archival policy is required
  before launch, not after the first slow query.
- **C7.** **NFR-05 auditability** — every bot action logged with SP id, timestamp and outcome.
  `ticket_action` does this; confirm it satisfies finance.
- **C8.** **NFR-06 reconciliation, target 100%.** `ReconciliationService` is built and reads
  both directions. It must run on a schedule, alert on any mismatch, and the **unrecorded**
  case — the gateway paid and we have no row — must page someone.
- **C9.** **NFR-07 reversibility window.** Not built. Design required.

## Workstream D — operability

- **D1.** Health and readiness probes distinguishing "up" from "able to read the source
  database".
- **D2.** Elastic APM wired, with **correlation ids** carried from the partner request through
  the process instance to the action row.
- **D3.** Metrics that match the PRD's KPIs directly: automation rate, agent-connect rate,
  auto-settlement accuracy, CSAT, and **per-concern** tier distribution.
- **D4.** Alerts on the things that mean money is wrong: reconciliation mismatch, action
  attempted with no outcome recorded, a kill switch off for longer than a threshold, escalation
  rate moving sharply on any single concern.
- **D5.** Structured logging. The POC logs generously; production needs it parseable.
- **D6.** **Runbooks** — what to do when the source database is unreachable, when the gateway
  is down, when a concern starts escalating everything, and how to turn a concern off.
- **D7.** On-call ownership. This service moves money without a human in the loop.
- **D8.** Replace the in-memory `DecisionTrace` — a 200-entry ring, lost on restart — with
  something durable for the concerns that move money. The facts are already durable in
  `ACT_HI_VARINST`; the rule outcomes are not.

## Workstream E — the complete front door (what F2 requires)

- **E1.** Full catalogue seeded and tiered, all five L1 paths.
- **E2.** Every concern not yet automated routes to an agent **with context** — the escalation
  context is built and works; what is missing is everything after it.
- **E3.** **Outbound dialer + CTI screen-pop** (NFR-04). External integration.
- **E4.** **Agent 2 queue and tooling.** The POC exposes REST endpoints. Agents need a surface.
- **E5.** **Audio-note L1 selection** (NFR-08). Hindi/Hinglish. Not started.
- **E6.** **Order dropdown, top-5 bookings** (R-01). Every booking-specific concern needs it.
- **E7.** **Medal bands (GDPTQ)** and the P1–P4 call-back priorities. `medalBand` is currently a
  known-absent field, in no table this service can read.
- **E8.** **Trigger B** (mandatory-human categories) is built. **C, D and E are not** —
  low-confidence fallback, repeat contact, and negative sentiment. **D needs the shared
  trigger table** rather than a column per concern, or the concerns nobody remembers to add
  will silently never fire it.

## Workstream F — money, for the phase that moves it

- **F1.** The **transport credit service**. `ActionRegistry` holds only `AUTO_CREDIT_WALLET`,
  so Transport decides T2 correctly and then escalates, because nothing can perform the
  payment. Until this exists, Transport is a very well-tested way of creating tickets.
- **F2.** Replace the **mock PayU gateway** with the real integration.
- **F3.** Idempotency proven against the **real** gateway's semantics, not the mock's.
- **F4.** Finance sign-off on the reconciliation report format.
- **F5.** The reversible window from C9, implemented.

## Workstream G — measurement

- **G1.** The PRD's KPI set instrumented from day one — an automation rate nobody can compute
  is not a target.
- **G2.** **Shadow comparison.** Whatever the rollout decision, logging what BOTIn *would* have
  decided beside what an agent actually did is the cheapest way to find a wrong mapping before
  it pays anyone. It needs no user-facing change and it is the strongest argument available at
  the Phase 2 gate.
- **G3.** The **classifier decision** (free-text intent). Still unmeasured: no key, no labelled
  real messages. The spec, prompt and scoring harness exist. If audio-note L1 selection lands
  in Phase 1, this stops being optional.
- **G4.** CSAT response rate and trigger-A rate, from real volume.

---

## Sequencing — what blocks what

```
Gate 0  ──────────────────────────────────────────────► everything

A (reads are correct)   ──► F (money)   ──► Phase 2 launch
B (the service)         ──► C, D        ──► any launch
E (the front door)      ─────────────────► Phase 1 launch   ← the long pole
G (measurement)         ──► the Phase 2 go/no-go decision
```

**A and B are independent of every open decision** and can start immediately — the reads have
to be correct and the service has to be deployable whatever Phase 1 turns out to contain.

**E is the long pole** and it is mostly integration work with other teams: telephony, agent
tooling, the app. It should be started first and tracked hardest, because it is the one no
amount of work inside this codebase can shorten.

## Explicitly out of the first production release

Stated so nobody discovers it late:

- Violations / R1–R13 — out of POC scope by decision; and per F3 the PRD does not have them as an L1 path
- Realtime Job actions (R-02…R-10) — PRD Phase 3
- Strike appeal engine (R-13) — PRD Phase 4
- Medal-differentiated agent connect — PRD Phase 3
- The LLM classifier as a measured, procured component — unless E5 pulls it forward

---

## The honest summary

The POC answered the questions it set out to answer: the architecture holds, a conversation
survives a restart, and the system cannot pay twice. It also produced a decision layer that
is genuinely production-shaped — rules as data, one process for every concern, a kill switch
that works without a restart, and evidence of its own reasoning.

**What it did not do is build the product the PRD describes.** It built four concerns deeply
where the PRD needs thirty-eight broadly, and it built the Phase 2 money path while the PRD
launches with Phase 1 deflection behind a front door that does not exist yet.

That is not a failure of the POC — depth was the right choice for proving an architecture.
But the distance from here to Phase 1 is mostly **integration and breadth**, not the decision
engine, and the plan should be resourced on that basis.
