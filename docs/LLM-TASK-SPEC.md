# The classifier — task specification, requirements, and how a candidate is chosen

Plan steps 7, 8, 9 and 14. Written without a key, a vendor or a budget, because none of them
are needed to write it — and a conversation with a vendor stalls without it.

**This document does not choose a model.** It defines the job precisely enough that two people
comparing candidates are comparing the same thing, and states what would make an answer
unacceptable regardless of how good the numbers look.

---

## 1. The task, stated as narrowly as it actually is

**Closed-set classification of short Hinglish text into a taxonomy the service already owns.**

Given a partner's message, return:

| Field | Type | Meaning |
|---|---|---|
| `category` | one of the ACTIVE concern codes, or null | which concern this is about |
| `confidence` | 0.0 – 1.0 | how sure. **Never thresholded by the model** |
| `extracted_reason` | short string, or null | the partner's own words for why, kept for the agent |
| `sentiment` | `POSITIVE` · `NEUTRAL` · `NEGATIVE` | one closed set, no free text |
| `risk_flagged` | boolean | accident, hospital, police — a person, now, whatever the topic |

**WHAT THIS IS NOT, and every one of these has been mistaken for it:**

- **Not open-ended understanding.** The model picks from a list that exists in a database
  table. A category outside that list is rejected at the boundary, not acted on.
- **Not a decision.** It never decides a tier, an amount, or whether to pay. It says what the
  partner is talking about; the decision tables do the rest, from backend facts.
- **Not a threshold.** The confidence floor lives in the decision tables so a business owner
  can tune it without a deployment. A model that refuses to answer below its own internal
  threshold has taken that decision away.
- **Not a conversation.** One message in, one structured answer out. No memory, no
  personality, no generated prose shown to a partner. **Every sentence a partner reads comes
  from a template**, and that is not a limitation to be relaxed later — it is the reason a
  wrong classification costs a reroute rather than a wrong promise about their money.

### The language is the hard part, and it is worth being explicit

Partners write Roman-script Hinglish with no fixed spelling: *"transport ka paisa nahi mila"*,
*"recharge kiya pese cut gaye"*, *"mpin bhool gaya"*. The same word appears as *pese*, *paise*,
*paisa*. English, Hindi and Devanagari-free transliteration mix inside one sentence.

A model that scores well on English support tickets tells you nothing about this. **Any
candidate evaluated on anything other than real partner text is being evaluated on the wrong
task.**

---

## 2. Non-functional requirements

| # | Requirement | Why this number |
|---|---|---|
| **Latency** | p95 **under 3 seconds**, end to end | The client already enforces a 3s timeout and a circuit breaker. A model slower than that degrades to "a person will look at this" — correct, but it means paying for a model that is not used |
| **Availability** | Degradation must be **indistinguishable from a no-match** | Already built: a dead service, a timeout and an open breaker all return the same answer as "I don't recognise this". There is no second code path that could behave differently under load |
| **Throughput** | Sized against **9,542 known monthly cases** on the active concerns, plus retries and the clarification loop | Roughly 2× message volume, because a partner who is not understood is asked to rephrase twice |
| **Cost** | Must be stated **per 1,000 classifications**, not per token | Token pricing is not comparable across vendors with different tokenisers. Hinglish tokenises worse than English, so a per-token quote understates the real cost |
| **Determinism** | The same input must give the same output within a run | Otherwise no accuracy number means anything, and a demo cannot be repeated |
| **Structured output** | Must return parseable JSON against a fixed schema | Already enforced at the boundary — prose where a field belongs is rejected. A model needing heavy prompt coaxing to emit JSON is paying that cost on every call |
| **Data residency** | To be confirmed against §3 before any candidate is sent real text | — |

### What is explicitly NOT required

- **Not multi-turn.** One message, one answer.
- **Not reasoning.** The decision tables reason. The model recognises.
- **Not the newest model.** The cheap tier is evaluated first and on merit (§4).

---

## 3. The PII position — settle this before a vendor sees anything

**A partner's free text is the most sensitive thing this system handles.** It is unstructured,
written by someone in difficulty, and may contain anything: a phone number, an address, a
medical situation, an accusation about a named colleague.

### What is sent, and what is not

| Sent | Never sent |
|---|---|
| The message text | Partner id, name, phone, email |
| The active concern code, when inside a concern | Order ids, booking ids, transaction ids |
| — | Wallet balances or any amount |
| — | Anything from the UAT database |

**The Python service already enforces this structurally**: it holds no database credentials and
its request schema has no identifier field. It cannot leak what it was never given. That is a
property of the design rather than a promise about behaviour, and it is the form this
commitment should keep.

### The three questions that need an answer, and who should answer them

1. **Does partner free text leave the country?** Most vendors process outside India by
   default. This is a legal and policy answer, not an engineering one.
2. **Is the text retained by the vendor, and for how long?** Zero-retention is offered by some
   vendors on request and is not the default anywhere. **Ask explicitly; do not assume.**
3. **Is it used for training?** Same answer required in writing.

### The position this document recommends

> **Zero retention, no training on our data, and processing region stated in the contract.**
> If a vendor cannot offer all three, that is a reason to exclude them **before** measuring
> accuracy — otherwise the best-scoring candidate becomes the one everybody argues to accept.

**Sequence matters here.** Run the compliance filter first and measure what survives. Measuring
first creates a number that makes the wrong answer hard to refuse.

---

## 4. How a candidate is chosen — three categories, not three brands

Shortlist across **categories of approach**, because they fail differently and cost differently:

| Category | Why it is in the comparison | The risk it carries |
|---|---|---|
| **A hosted frontier model** | Best Hinglish handling with no training data of ours | Cost per call, data leaves our control, vendor lock-in on a prompt |
| **A hosted small/cheap model** | Often sufficient for closed-set classification, an order of magnitude cheaper | May collapse on code-mixed text; needs measuring, not assuming |
| **A self-hosted open model** | Data never leaves; cost is infrastructure, not per call | Needs someone to run it, and quality on Hinglish is the open question |

**Evaluate the cheap tier first.** This is closed-set classification into fewer than forty
categories, not open-ended reasoning. If a small model reaches the bar, the frontier model is
paying for capability this task does not use — on every single call, forever.

### The exit criteria, agreed before the numbers exist

Stated up front, which is the only way a threshold means anything:

| Accuracy on real labelled text | Verdict |
|---|---|
| **≥ 90%** | The design holds as written |
| **75 – 90%** | Proceed with a raised confidence floor, and accept a higher agent-connect rate. A tuning change, not a rebuild |
| **< 75%** | Rethink. Free text becomes a fallback rather than a front door — the menu stays primary |

**The decision record must state the runner-up and its numbers**, not only the winner. A choice
with no recorded alternative cannot be revisited when the winner's price changes.

---

## 5. What is already built, and what is still missing

**Built and demonstrable now:** the two-endpoint contract, the Pydantic-validated schema, the
boundary that distrusts every answer (hallucinated category checked against the live catalogue,
confidence bounds, sentiment normalisation, never throws), the 3-second timeout, the circuit
breaker, the clarification loop, the reroute mechanism, and a deterministic stub selected
automatically when no key is configured.

**A real model is a configuration change.** One property, one file.

**Missing, and not obtainable by writing more code:**

1. **An API key** — nothing can be measured without one.
2. **150–200 hand-labelled real partner messages** — and this is the one that gets dropped.

> Without labelled real text there is no accuracy number, and **U-2 must be reported as NOT
> ASSESSED — never as passed.** Fixtures written by the person building the classifier always
> classify well. That is a mirror, not evidence.

The scoring harness (`classifier-service/harness/`) and the versioned prompt
(`classifier-service/prompts/`) are built and ready. **The harness output is the deliverable
of this track, not the score** — a candidate can be measured the day a key exists.
