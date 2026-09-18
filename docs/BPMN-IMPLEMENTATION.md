# BPMN — how the flow engine is implemented

**BPMN decides the ORDER of steps. It fetches nothing and decides nothing.**

Three engines, three jobs. Confusing them is the most common misreading of this design:

| | Decides | Lives in |
|---|---|---|
| **BPMN** — Flowable process engine | what happens next, what waits, what survives a restart | `.bpmn20.xml` |
| **Fact providers** — plain Java | what is true | `facts/*.java` |
| **DMN** — Flowable decision engine | what to do about it | `.dmn` |

BPMN *calls* the fact provider and *calls* the decision table. It knows nothing about SQL,
orders, wallets, tiers or rupees.

---

## 1. Why a process engine at all

Three reasons, and only the first is non-negotiable.

**1. A conversation can wait for a person, for as long as the queue takes.** `agentConnect`
is a `<userTask>`: the process stops there, **in the database**. No thread is held, no
memory is held, and the wait survives a restart or a deployment. That was the POC's first
stop condition, and it is the one thing a plain service class cannot do without inventing a
scheduler, a state column and a recovery job.

**2. One shape for every concern.** A process per concern reads well in a modeller and rots
in practice: thirty-eight files that are 95% identical, where a fix to the shared shape has
to be applied thirty-eight times and will not be.

**3. It records what it did.** `ACT_HI_ACTINST` holds every step of every instance, in
order, with durations. That is evidence, not narration — the console's "what it touched"
reads it rather than describing what should have happened.

---

## 2. What exists

### Three process files

```
src/main/resources/processes/
  concern-generic.bpmn20.xml    15 elements — runs 5 of the 6 active concerns
  forget-mpin.bpmn20.xml         3 elements — the T0 proof, deliberately separate
  csat-escalation.bpmn20.xml     7 elements — trigger A, started after the first ends
```

### Four element types, and no more

| Element | Count | Used for |
|---|---|---|
| `<startEvent>` / `<endEvent>` | 2 per file | begin, finish |
| `<serviceTask>` | 11 | call one Spring bean |
| `<exclusiveGateway>` | 2 | branch on a variable |
| `<userTask>` | 1 | **wait for a human** |

No timers, no sub-processes, no listeners, no scripts. A reviewer can hold the whole
vocabulary in their head.

### Eleven delegates

| Bean name | Class | Does |
|---|---|---|
| `preFlightDelegate` | `PreFlightDelegate` | trigger B — mandatory-human concerns stop deciding here |
| `fetchFactsDelegate` | `FetchFactsDelegate` | calls the concern's fact provider |
| `decideDelegate` | `DecideDelegate` | calls the concern's decision table |
| `openTicketDelegate` | `OpenTicketDelegate` | **Gate 1** |
| `rerouteDelegate` | `RerouteDelegate` | ends this concern, starts the right one |
| `escalationContextDelegate` | `EscalationContextDelegate` | writes the handover, before the wait |
| `agentHandoffDelegate` | `AgentHandoffDelegate` | tells the partner, leaves the session open |
| `agentCompletionDelegate` | `AgentCompletionDelegate` | closes out the agent's resolution |
| `performActionDelegate` | `PerformActionDelegate` | **the only place money or state moves** |
| `resolveDelegate` | `ResolveDelegate` | writes the answer, closes the ticket and session |
| `forgetMpinDelegate` | `ForgetMpinDelegate` | the whole of the T0 concern |

Three helpers that are not steps: `ProcessVariables` (the variable names as constants),
`ConcernProcessRunner` (starts an instance), `SessionStepWriter` (writes the answer onto the
session row).

---

## 3. The shared shape

```
start
  → preFlight            trigger B. Mandatory-human concerns stop deciding here.
  → fetchFacts           reads UAT. Concludes nothing. Cannot throw at a partner.
  → decide               the concern's own DMN table. The only step that decides.
  → openTicket           GATE 1. A T0 leaves here having created nothing.
  → «agentRequired?»
       yes → escalationContext   the handover, written before the wait
           → agentHandoff        tells the partner, does NOT close the session
           → agentConnect        USER TASK — the process sleeps here
           → agentCompletion     runs whenever the agent finishes, restart or not
       reroute → the target concern's process, against the same conversation
       no  → performAction → «action ok?» → resolve
  → end
```

### Why the gateway branches on `agentRequired` and not on tier

Tier is the concern **table's** answer. `agentRequired` is the whole **system's** — the
pre-flight gate can have set it before any table ran, and a failed action sets it after the
table is long finished. One variable, so there is one thing to read.

### Why the reroute branch is evaluated after the agent branch

A risk-flagged free text must reach a person **even when the model was confident about
where it belonged**.

### Why a failed action rejoins the agent path rather than retrying

The gateway after `performAction` exists because a gateway call may have **done the thing
and failed to say so**. Retrying that pays twice. A failure becomes a person's problem.

---

## 4. How a step finds its Java

```xml
<serviceTask id="fetchFacts" name="Fetch facts"
             flowable:delegateExpression="${fetchFactsDelegate}"/>
```

```java
@Component("fetchFactsDelegate")
public class FetchFactsDelegate implements JavaDelegate {
    @Override public void execute(DelegateExecution execution) { … }
}
```

**Matched by string, resolved at runtime.** Flowable asks the Spring context for a bean with
that exact name. Nothing validates the pair until the process runs — the BPMN form of the
hazard that runs through this codebase: *a name that does not exist behaves exactly like a
value that is false.*

A delegate receives **no arguments**. Everything it knows travels in process variables, which
is what allows one process to serve every concern.

---

## 5. Process variables — the only channel between steps

Declared as constants in `process/ProcessVariables.java` so a typo is a compile error rather
than a silent null:

| Variable | Set by | Read by |
|---|---|---|
| `helpSessionId`, `spId`, `l2Concern`, `selectedReference` | `ConcernProcessRunner` at start | every delegate |
| `factsJson` | `fetchFactsDelegate` | `decideDelegate`, `escalationContextDelegate` |
| `factsFailed` | `fetchFactsDelegate` | `decideDelegate` |
| `tier`, `action`, `outcomeType` | `decideDelegate` | `openTicket`, `resolve` |
| `agentRequired`, `rerouteRequired`, `rerouteTarget`, `triggerReason` | `decideDelegate`, `preFlight`, `performAction` | the gateways |
| `ticketId` | `openTicketDelegate` | `performAction`, `escalationContext` |

### The rule that governs all of them

**Every variable a gateway reads must be set on every path that reaches it.** An absent
variable in a condition expression is an **evaluation error**, not a `false`. `DecideDelegate`
sets `rerouteRequired` even on its escalation paths for exactly this reason.

### And the rule that governs the end of a process

```
finaliseStep is the LAST thing to run. It must not leave anything the client needs in
process variables: the instant this instance completes, its ACT_RU_* rows are deleted and
those variables are gone.
```

The answer the partner reads is written to the **`help_session` row**, and read back from
there — never from the instance. That is why `enterConcern` calls `readNextStep(session)`
after `processRunner.start(...)` returns.

---

## 6. Starting an instance

`process/ConcernProcessRunner.java`:

```java
runtimeService.startProcessInstanceByKey(
        processKey,                 // from concern_catalogue.process_key
        helpSessionId.toString(),   // THE BUSINESS KEY
        variables);
```

Two things worth noticing.

**`processKey` comes from the database**, not from Java. Change that column and a different
flow runs, with nothing rebuilt.

**The business key is the help-session id.** That is what makes a reroute legible: one
conversation, two process instances, both findable by the same key. A stored
process-instance id on the session would have shown only the second — and the reroute is
precisely the case somebody asks about.

**It runs synchronously.** `start(...)` does not return until the process reaches a wait
state or completes. For a T0 the whole thing happens inside one HTTP request.

---

## 7. Why FORGET_MPIN has its own process

Migration `V5` routed five concerns onto `concern-generic` and deliberately left this one
alone:

> FORGET_MPIN keeps its own process. It is the T0 proof — the assertion that a deflection
> creates no ticket — and that assertion is worth more running through its own three-element
> file than folded into a shared one. It is also the only concern whose outcome is a
> deeplink rather than a message.

The consequence is the strongest form of the claim: **there is no Gate 1 step in that
process to skip.** No step in it is capable of creating a ticket. `JourneyTest` asserts
that the shape does not contain `openTicket`, `performAction`, `escalationContext`,
`agentHandoff` or `agentConnect` — structural, not conditional.

---

## 8. Deployment, versions and history

Flowable scans `classpath:/processes/**` at startup and deploys anything whose content has
changed, as a **new version**. Running instances stay on the version they started with;
new instances get the latest. Nothing has to be migrated, and nothing in flight is disturbed
by a deploy.

| Table | Holds |
|---|---|
| `ACT_RE_DEPLOYMENT`, `ACT_RE_PROCDEF` | the deployed definitions and their versions |
| `ACT_RU_EXECUTION`, `ACT_RU_TASK`, `ACT_RU_VARIABLE` | what is running **now** |
| `ACT_HI_PROCINST`, `ACT_HI_ACTINST`, `ACT_HI_VARINST` | what ever ran — durable |

`ACT_RU_*` rows for an instance vanish when it completes. `ACT_HI_*` rows stay.

---

## 9. Changing the flow

| Change | Where | Needs |
|---|---|---|
| which process a concern runs | `concern_catalogue.process_key` | a database update |
| add a step to the shared shape | `concern-generic.bpmn20.xml` + a new delegate | file + Java + restart |
| change a branch condition | the `<conditionExpression>` in the file | file + restart |
| what a step does | its delegate class | Java + restart |

**Adding a concern adds no BPMN at all** — a catalogue row, a fact provider bean, and a
`.dmn` file. That is the point of the shared process, and it is the claim to make when
somebody asks how this scales to thirty-eight concerns.

---

## 10. What is not in the BPMN yet, and where it goes

| Missing | Where it belongs |
|---|---|
| the order-ID capture step | before `fetchFacts` (plan step 54). Transport cannot look anything up without it |
| a gateway on the Togglz kill switch | between `decide` and `openTicket`. **Today `DecideDelegate` escalates every T2 instead** — the semantics are already "do not automate, send to a human", so the gateway replaces a condition rather than adding a concept |
| the shared agent-connect-triggers table (trigger D) | between `fetchFacts` and `decide`. Blocked on open question O-14 |
| the CSAT step | after `resolve` — and it is deliberately **not** a wait state. See below |

### The CSAT decision, because it shaped the process

The obvious design holds every resolved session open on a Receive Task waiting for a rating:
one live process per resolved conversation, indefinitely, for an answer that may never come.
Instead **the process completes**, and CSAT arrives on its own endpoint against the closed
session. Nothing is held open, so there is no timer and no state waiting — and this deleted
a planned timeout step entirely.
