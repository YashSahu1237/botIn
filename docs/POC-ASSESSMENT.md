# BOTIn POC — findings and verdict

Plan steps 97 and 98. This is the document the POC existed to produce.

It answers the five questions the design named as expensive to get wrong, reports the
signal-availability position, records where the LLM decision stands, and states the
go / no-go plainly.

**Read the verdict last, not first.** Two of the five answers are qualified and one is not
an answer at all, and a verdict quoted without them is exactly the kind of summary this
document is meant to prevent.

---

## 1. The five answers

| # | Question | Answer | Confidence |
|---|---|---|---|
| **U-1** | Does Flowable really pause at a User Task, survive a JVM restart, and resume? | **YES** | Demonstrated, including the negative case |
| **U-2** | Does the model reach usable accuracy on real Hinglish? | **NOT ASSESSED** | No key, no real ticket text. Not a pass |
| **U-3** | Does `REQUIRES_NEW` really protect the action record through a rollback? | **YES — and it is sharper than expected** | Demonstrated, including the negative case |
| **U-4** | Do the capabilities the violation concerns need exist? | **NO, and not for the reason anticipated** | Established from the source and the live data |
| **U-5** | Can a DMN edit change behaviour with no redeploy? | **YES** | Demonstrated against a running engine |

### U-1 — a paused conversation survives the server dying. **YES.**

A process was started, parked on a User Task, and the JVM destroyed without a graceful
shutdown. A new JVM — one that never saw the process start — found the same task waiting
and ran it to completion.

This is the load-bearing answer. ADR-001 chooses to embed a process engine precisely so
that persisting a half-finished conversation, reloading it and working out which step it
reached is code nobody writes. That choice is worth nothing if the engine does not behave
this way, and "the documentation says so" is not the same claim.

**One consequence was not in the design and changes it.** `ACT_RU_*` rows are deleted the
moment a process completes, so anything the client needs after the conversation ends cannot
live in a process variable. `nextStep` is therefore persisted on the `help_session` row, and
the final Service Task of every process writes it. Without that, a resolved partner who
reconnects would be shown nothing at all — and nothing would have errored.

### U-2 — classifier accuracy. **NOT ASSESSED. This must never be reported as passed.**

There is no API key and no historical ticket text, so there is no number. The fixtures that
exercise routing were written by the same hand that wrote the classifier, and fixtures
written that way always classify well. That is not evidence; it is a mirror.

What **is** built and demonstrable: the model boundary, the hallucination guard, the
confidence floor, the clarification loop, the reroute mechanism and the degradation path.
The stub is a real bean selected at startup when no `CLASSIFIER_URL` is set — so every one
of those behaves today, and a real model is a configuration change rather than a build.

**The guard that matters most is not in Java.** A category counts only if it names a concern
that is ACTIVE right now, checked against the catalogue on every call. That is correct by
construction as concerns are switched on, and a model trained on the old taxonomy cannot
route a partner into a concern nobody has built. The Python service checks the same thing
independently, so the guarantee does not leave if that process is swapped for a vendor's.

**What the absence costs:** the agent-connect ceiling cannot be verified even in principle.
The bounding rule is built and enforced, but whether it holds at 10–20% depends on a number
nobody has.

### U-3 — the payment record survives a rollback. **YES, and the real finding is larger.**

An outer transaction was rolled back and the attempt row was still there. The annotation was
then removed and the same test failed — which is what makes the first result mean something.

**The larger finding, which the original spike could not have seen:** `REQUIRES_NEW` isolates
in **both** directions. It cannot see rows the outer transaction has only flushed, either.
The spike passed because its test committed a ticket first and then exercised the recorder;
in the real flow both happen in one request, and the attempt insert failed on a foreign key
to a ticket that, as far as its own transaction was concerned, did not exist.

The rule that came out of it is worth more than the original answer:

> **If we are about to call an external system on behalf of a row, that row and everything
> it references must be as durable as the call.**

An attempt row pointing at a ticket that was rolled away is a dangling audit record. A
foreign key turned that into a loud failure here; in a schema without one it is a quiet
corruption of the only evidence we keep that we contacted a payment gateway on someone's
behalf.

**A second finding, from the same phase.** The idempotency key must be the THIRD PARTY's
reference, not ours. A partner who opens a second conversation about the same failed recharge
correctly gets a second ticket — it is a second complaint — and a ticket-scoped key would have
paid them twice. Keyed on the gateway's order id it collides across tickets, sessions and
restarts.

### U-4 — do the capabilities the violation concerns need exist? **No, and the reason is not the one the design expected.**

The design anticipated a binary: either the capability exists as a callable method and Layer 6
is an integration, or it does not and Layer 6 is a build. The audit found a third state.

**The concepts do not exist in the data at all.** `tbl_violation_master` holds exactly seven
rows — `SP_101` through `SP_107`: job reject, same-day leave, reassign, customer cancellation,
training missed, rejected on call, weekend leave. There is no period-leave violation and no
missing-product violation. The R1–R13 numbering used throughout the concern mapping appears
nowhere in the live system.

Separately, the system records leave **duration** — `FULL_DAY`, `FIRST_HALF`, `SECOND_HALF` —
not leave **reason**. "Period leave" is a category the data cannot currently express.

**Both concerns are therefore parked, and parking them was the safe choice rather than the
tidy one.** A query for a violation code that does not exist returns **zero rows**, and zero
prior removals matches `priorRemovalsThisCycle < 1`, which is the REMOVE row. Left active,
the concern would have auto-removed violations it never actually checked.

> **An empty result is not a neutral value. It is a specific answer, and here it was the
> generous one.** This is the same shape as the recurring risk this codebase is built around:
> a name that does not exist behaves exactly like a value that is false.

**What is unaffected:** the read side for every concern that does have data is mapped against
the real source, and every column a provider reads is declared and checked against
`information_schema` at startup. **No production system is written to anywhere in this POC** —
every wallet credit, violation removal and strike recalculation goes to a mock, by design — so
the write-side capability question is open for the production phase rather than answered here.

### U-5 — a DMN edit changes behaviour with no rebuild. **YES.**

A claim for ₹250 is paid under the ₹300 cap. The cap is edited to ₹200 in the XML, that one
file is redeployed into the already-running engine, and the same claim with the same facts is
refused and sent to a person. Nothing was recompiled and nothing was restarted.

A companion check asserts the cap value appears in **no Java source anywhere**. That is what
makes the answer worth anything: a constant in a delegate that agrees with the table today
turns tomorrow's redeploy into a silent disagreement, where the table says one number, the
code says another, and which one wins depends on which is consulted first. That is worse than
having no table at all.

**The consequence is a governance requirement, not a celebration.** If a business owner can
change a payment threshold without an engineer, then the review gate on who may do that, and
what is recorded when they do, becomes urgent rather than theoretical.

---

## 2. Exit criteria, one at a time

These were agreed before the numbers were known, which is the only way a threshold means
anything.

| Criterion | Result | What it means |
|---|---|---|
| **Restart test fails** → stop | **PASSED** | ADR-001 holds. The engine behaves as the architecture assumes |
| **Rollback test fails** → stop | **PASSED** | The money-safety model holds, and is now better understood than when it was written |
| **All 26 rules reachable and correct through the API** | **PARTIAL — 15 of 26** | The decision layer itself is correct. 11 rules are unreachable because a signal is missing, not because a rule is wrong |
| **Shared TAT change propagates to both concerns** | **HALF** | One TAT service is built, tested and read by `PROD_DELIVERY_DELAY`. Its second consumer is a parked concern, so the reuse claim is demonstrated in structure but not across two live concerns |
| **DMN edit changes behaviour with no rebuild** | **PASSED** | Ops-editable rules are real. **The review gate is now urgent** |
| **Signal register: most signals readable** | **NOT MET** | See below. Layer 6 is closer to a build than an integration for several concerns |
| **Satisfied T2 case offers no agent path** | **PASSED** | The bounding rule is enforced in closure logic, on a field the client cannot override |
| **CSAT mechanism chosen and the no-answer path tested** | **PASSED** | Settled with evidence rather than preference — see §4 |
| **LLM decision recorded with measured numbers** | **NOT MET** | No key, no numbers. Procurement cannot proceed on evidence from this POC |

**Neither stop condition triggered.** Both blocking unknowns passed, each with its negative
case demonstrated.

---

## 3. The signal position, in one number

Across the six active concerns the decision tables hold **26 rules. 15 can fire. 11 cannot**,
because a fact they read is never populated.

**As the service actually runs today** — no `SELECT` grant on the source yet — only
`FORGET_MPIN` resolves without a human: **738 of 9,542 known monthly cases, 7.7%.** Every
other concern's facts come back all-null and land on its catch-all.

**Nothing here is broken.** Every one of the 11 dead rules is dead because a signal is
missing, and in every case the fallback is a human rather than a wrong answer. That is the
design working as intended. The register exists so that this is a number rather than a
surprise during a demo.

Two schema questions and the read grant account for most of the gap. A third, N1, was closed on
15 September by taking violations out of scope rather than by an answer. They are
recorded per signal, with what each absence costs a partner, in `docs/SIGNAL-REGISTER.md`.

**One finding from that register changes a rule rather than a number.** The Recharge table
reads `spSatisfied`, which was expected to arrive with CSAT. **It cannot.** CSAT is answered
*after* a resolution; the decision reads `spSatisfied` *during* it, when the partner has not
rated anything. That rule is permanently unreachable as written, and what "SP not satisfied"
means in that context is a question for the concern owner — not a build task.

---

## 4. Two answers the POC settled that were not on the list

**The CSAT mechanism (O-15).** The obvious design holds every resolved session open on a
Receive Task waiting for a rating. That is one live process per resolved conversation,
indefinitely, for an answer that may never come — and a partner handed a self-serve link has
to go and use it before they can say whether it helped. The process completes instead, and
CSAT arrives on its own endpoint against the closed session. Nothing is held open, so there is
no timer and no state waiting. This also deleted a planned timeout step entirely.

**The bounding rule lives in closure logic, not in the UI.** Whether "Talk to an Agent" may be
shown is decided by the backend and carried on the response. A suppression rule the client
owns is one an old app version can ignore — and this is the rule that protects the entire
agent-connect target.

---

## 5. Where the LLM decision stands

**It cannot be recorded, and that is not a shrug.**

The exit criterion was "LLM decision recorded with measured numbers → procurement can proceed
on evidence". There is no key and no labelled real text, so there are no numbers and
procurement cannot proceed on evidence from this POC.

What exists instead, and what it is worth:

- **The task specification is buildable now** and needs nobody: closed-set classification of
  short Hinglish text into the concern taxonomy, with a confidence score and a sentiment
  signal.
- **The harness is the deliverable, not the score.** The scoring shape, the fixture set, the
  prompt as a versioned file, and a boundary that distrusts whatever a model returns are all
  built. A candidate can be measured the day a key exists.
- **The contract is vendor-independent.** Two endpoints, Pydantic-validated, no database
  credentials and no identifiers. Swapping vendors is a configuration change.

**The honest framing for a decision-maker:** this POC de-risked everything about using a model
*except* whether a model is good enough. That question is unanswered and cannot be answered
without the two inputs that were unavailable.

---

## 6. What was built

Six concerns active, one generic BPMN process serving all of them, eight decision tables, and
a full test suite green at every phase boundary.

Every tier is demonstrated end to end: **T0** deflect with no ticket, **T1** answer from data,
**T2** move money, **T3** hand to a person. Free text works at the entry point and inside a
concern. An agent claims a task, reads the facts gathered before the handover, and completes
it. CSAT is captured, a rejected deflection escalates the same ticket, and a satisfied partner
is never offered an agent.

Five guards exist, and **each one exists because something already went wrong in exactly that
way** — an unknown feature flag reading as `false` in silence, a decision table re-saved in a
namespace the engine cannot parse, a fact name spelled differently on the two sides of a
contract, a catalogue row pointing at a process file nobody wrote, and a rollback erasing the
evidence of an external call.

**The demo is a script, not a performance**, and it asserts every case rather than printing
them. A live demo is edited by the performer, and the cases that get skipped when a room is
watching are exactly the ones worth seeing.

**The demo has been run, not just written: 27 of 27 checks pass against a live service.** It
took three rounds of red to get there, and one of those failures was not in the script.

**A feature was built, tested, documented as done — and unreachable.** In-concern free text —
the partner picks "Other", types a reason naming a different concern, and is rerouted — had its
decision table, its delegate and both its guards written and green. Through the API it did
nothing: the selection was taken and the text discarded, so the concern ran with nothing to
classify. No exception, no log line, no failing test. Every test drove reroute from the entry
point, which is a real path that works; nobody had ever driven the path the requirement
describes.

> A test suite proves the components work. Only something driving the real API proves they are
> **wired** — and "built, green, documented, dead" leaves no trace anywhere else.

It is fixed and now has the test that would have caught it. It is recorded here rather than
quietly closed because it is the clearest argument in this POC for what the demo is **for**.

---

## 7. What was not built, and why

| Not built | Why | What unparks it |
|---|---|---|
| **Violations — the whole L1** | **OUT OF POC SCOPE by decision, 15 Sept.** The R1-R13 sub-reason could not be located in the schema, and an empty result matched the REMOVE row. `VIOL_R4_OTHERS` stays — it reads classifier output, not violation data | Nothing. It is not waiting on anybody. See `docs/SCOPE-VIOLATIONS-OUT.md` |
| Transport **payment** | The rate question is unanswered, so the computed amount is null on every real read | ₹50/km, or the hub slabs — one answer |
| The reconciliation job | Not reached | Buildable now |
| Classifier accuracy | No key, no real ticket text | Both inputs |
| Agent-connect triggers B, D, E | Out of POC scope by the plan | Production. Trigger D needs the shared trigger table from the design — a column per concern leaves silent holes in the ones that get missed |

**Three coverage-matrix rows cannot be demonstrated at all** — UPHOLD, state-change-without-money,
and the concern-level durable counter. All three came only from the two violation concerns, now out
of scope. The counter mechanism itself is built and tested; what is missing is a concern to attach
it to.

**This costs no volume.** Violations carry no June volume figure at all, so the 9,542-case
denominator and the 7.7% figure are unchanged. What it costs is outcome-type coverage: 4 of 5
instead of 5 of 5. `VIOL_T1_SAMEDAY_LEAVE` (`SP_102`, a code that exists, no blocked answer needed)
would restore UPHOLD for the cost of one concern. That choice is open — see
`docs/SCOPE-VIOLATIONS-OUT.md`.

---

## 8. The verdict

### **GO — with two conditions, and one number that must not be quoted.**

**Neither stop condition triggered.** The two unknowns whose failure would have ended the
project — process durability and money safety — both passed, each with its negative case
demonstrated. The architecture is sound where it was most likely to be wrong, and the codebase
is a production seed rather than a spike: migrations, transaction boundaries, package
structure and tests are built to keep.

**Condition one: the read grant.** Today the service resolves 7.7% of known monthly volume
without a human, because only one concern's facts are readable. The decision layer is correct
and largely idle. Until a `SELECT` grant and the two remaining schema answers land, any deflection figure
from this system measures what it can read, not what it can decide.

**Condition two: the classifier question is still open.** Free text is built end to end and
degrades correctly, but nobody knows whether a model is accurate enough on real Hinglish. If
the answer turns out to be poor, the design's response is already known — raise the threshold
and accept a higher agent-connect rate, or make free text a fallback rather than a front door.
That is a tuning decision, not a rebuild. But it is unmade.

**The number that must not be quoted: there is no classifier accuracy figure.** If this POC is
summarised anywhere as demonstrating that free text works, that summary is wrong. It
demonstrates that free text is *wired* correctly and *fails* correctly.

### What follows from a GO

1. **Get the read grant and the four schema answers.** This is the single highest-value action
   available, and it is not engineering work. It moves 11 rules from dead to live.
2. **Settle the transport rate and the path selector.** Both are one-line changes behind named
   methods, and both gate the highest-volume concern in the build.
3. **Decide whether the two violation types should exist.** If yes, unparking is data and one
   query — the tables, rules and tests are written and green.
4. **Put the review gate on decision-table edits in place before Wave 1.** U-5 passing is what
   makes this urgent: a person who can change a payment threshold without an engineer can
   change it without a reviewer too.
5. **Replace the console gate with the real thing.** The kill-switch console cannot currently
   be mounted without a credential, which is the right failure direction, but the gate in front
   of it is a POC filter and is meant to be deleted.
6. **Treat the signal register as a live document.** It is the most valuable output of this POC
   after the two safety answers, and it is the one most likely to go stale quietly. A test
   fails if a fact is added without a row in it — keep that.

### What a bad outcome would have looked like

Worth stating, because a GO reads differently next to it. Had the restart test failed, the
justification for embedding a process engine would have collapsed and the whole persistence
model would need redesigning. Had the rollback test failed, the money-safety model would need
redesigning before a single rupee could move. Neither happened, and both were tested with the
annotation removed as well as present — so the passes mean something.

---

*Contains internal business rules, thresholds and repository detail. Private until deliberately shared.*
