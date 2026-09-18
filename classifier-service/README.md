# BOTIn classifier service

Closed-set classification of short Hinglish text. Two endpoints, no database, no
identifiers, no decisions.

```
pip install -r requirements.txt
uvicorn app.main:app --port 8099
```

Then point BOTIn at it:

```
export CLASSIFIER_URL=http://localhost:8099
```

With `CLASSIFIER_URL` unset, BOTIn uses its own deterministic stub instead and says so
loudly at startup. Nothing in the service depends on this being up: every failure mode —
timeout, connection refused, malformed response, an unknown category — is treated as
"no confident match", which routes the partner to a person.

## What it is given, and what it is not

It receives a sentence and, on `/classify`, the concern code the partner is currently in.
It is never given an SP id, an order id, a ticket id, or any database credential. That is
what keeps it a pure function: it can be logged, replayed, load-tested and swapped for
another vendor's service without a privacy review each time.

## No model is wired in yet

`classify_text` is a deterministic placeholder. The contract, the Pydantic validation and
the failure shape are real and are what the Java client is built against. When a model is
chosen, one function changes and nothing else does.

Two properties must survive that change:

* it never raises — every failure returns `Classification.no_match()`
* it never returns a category outside the live taxonomy

The Java side independently rejects a category that is not an active concern, so a model
that starts hallucinating still cannot route a partner anywhere. That redundancy is
deliberate: this check protects the service's callers, the other protects the partner.
