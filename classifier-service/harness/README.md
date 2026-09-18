# Scoring harness — plan step 12

**The harness is the deliverable of the LLM track, not the score.**

There is no API key and no labelled real partner text, so there is no accuracy number and
there will not be one until both arrive. What exists now is a fixed way of measuring, agreed
before anybody has a result to defend — which is the only way a threshold means anything.

```bash
python3 harness/score.py --fixtures harness/fixtures.jsonl --dry-run      # validate, call nothing
python3 harness/score.py --fixtures harness/fixtures.jsonl                # score a running service
python3 harness/score.py --fixtures harness/fixtures.jsonl --cost-per-1k 0.40
```

## Rules for a fair comparison

1. **Every candidate uses `prompts/classify-v1.txt`, unchanged.** A prompt tuned per vendor
   turns a model comparison into a prompt-engineering comparison, and the winner becomes
   whoever was tuned last.
2. **Every candidate uses the same fixture file.** Adding fixtures invalidates earlier runs.
3. **Record the runner-up and its numbers.** A choice with no recorded alternative cannot be
   revisited when the winner's price changes.
4. **Run the compliance filter first** (`docs/LLM-TASK-SPEC.md` §3). Measuring first creates a
   number that makes the wrong answer hard to refuse.

## Why these numbers

- **False match** is worse than **missed match** and they are reported separately. A missed
  match hands the partner to a person, which is the system working. A false match sends them
  confidently into a flow that asks about something else.
- **Risk recall is never averaged into accuracy.** A missed accident is not one error among
  many.
- **Cost is per 1,000 calls, not per token.** Tokenisers differ between vendors, and Hinglish
  tokenises worse than English, so a per-token quote understates the real cost.
- **A call failure scores as a no-match**, because that is exactly what the service does with
  one. Degradation is part of the score, not an excluded outlier.

## The fixtures are not evidence

They are hand-written from the concern names and the mapping's own phrasing. Fixtures written
by the person building the classifier always classify well.

**U-2 — "does the model reach usable accuracy on real Hinglish" — is answered only by 150–200
hand-labelled real partner messages. Until those exist it must be reported as NOT ASSESSED,
never as passed.**
