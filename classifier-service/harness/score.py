#!/usr/bin/env python3
"""PLAN STEP 12 — THE SCORER.

=============================================================================
THE HARNESS IS THE DELIVERABLE OF THIS TRACK, NOT THE SCORE
=============================================================================

There is no API key and no labelled real partner text, so there is no accuracy
number and there will not be one until both arrive. What CAN be built now is the
thing that makes a number mean something the day it exists: a fixed way of
measuring, agreed before anybody has a result to defend.

RUN IT AGAINST THE STUB TODAY. It scores the deterministic stub exactly as it
would score a vendor, which proves the harness works and produces a baseline
that is honest about being a baseline.

=============================================================================
WHAT IT REPORTS, AND WHY EACH ONE
=============================================================================

ACCURACY                 the headline, and the least useful number on its own
PER-CLASS CONFUSION      which concerns are mistaken for which. An 85% that is
                         wrong uniformly and an 85% that sends every transport
                         claim into recharge are the same number and completely
                         different problems
FALSE-MATCH RATE         labelled null, answered with a category. THE WORST
                         ERROR: the partner is confidently sent into a flow that
                         asks about something else
MISSED-MATCH RATE        labelled a category, answered null. Costs a handover,
                         which is the system working as designed
RISK RECALL              risk words missed. Reported separately and never
                         averaged into accuracy — a missed accident is not
                         one error among many
p95 LATENCY              against the 3s client timeout
COST per 1,000 calls     not per token: tokenisers differ, and Hinglish
                         tokenises worse than English

    python3 harness/score.py --fixtures harness/fixtures.jsonl --endpoint http://localhost:8000
    python3 harness/score.py --fixtures harness/fixtures.jsonl --dry-run
"""

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                sys.exit(f"{path}:{n} is not valid JSON — {e}")
    if not rows:
        sys.exit(f"{path} has no fixtures")
    return rows


def classify(endpoint, text, timeout_s):
    """One call. A failure is a RESULT, not a crash — degradation is part of the score."""
    body = json.dumps({"text": text}).encode()
    request = urllib.request.Request(
        f"{endpoint}/classify", data=body, headers={"Content-Type": "application/json"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_s) as response:
            answer = json.loads(response.read())
        return answer, (time.perf_counter() - started) * 1000, None
    except Exception as e:                      # noqa: BLE001 - every failure is a data point
        return None, (time.perf_counter() - started) * 1000, str(e)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--fixtures", required=True)
    p.add_argument("--endpoint", default="http://localhost:8000")
    p.add_argument("--timeout", type=float, default=3.0,
                   help="the client's real timeout. Slower than this degrades to a human")
    p.add_argument("--cost-per-1k", type=float, default=0.0,
                   help="vendor cost per 1,000 calls, for the comparison table")
    p.add_argument("--dry-run", action="store_true", help="validate fixtures, call nothing")
    args = p.parse_args()

    fixtures = load(args.fixtures)
    labelled = sum(1 for f in fixtures if f.get("expected_category"))
    print(f"{len(fixtures)} fixtures · {labelled} labelled with a category · "
          f"{len(fixtures) - labelled} labelled as no-match\n")

    if args.dry_run:
        missing = [f for f in fixtures if "text" not in f or "expected_category" not in f]
        if missing:
            sys.exit(f"{len(missing)} fixture(s) missing 'text' or 'expected_category'")
        print("fixtures are well-formed. No calls made.")
        return 0

    confusion = defaultdict(Counter)
    latencies, failures = [], 0
    correct = false_match = missed_match = 0
    risk_expected = risk_found = 0

    for f in fixtures:
        answer, ms, error = classify(args.endpoint, f["text"], args.timeout)
        latencies.append(ms)

        if error is not None:
            failures += 1
            answer = {"category": None, "risk_flagged": False}

        expected = f.get("expected_category")
        got = answer.get("category")
        confusion[expected or "NO_MATCH"][got or "NO_MATCH"] += 1

        if expected == got:
            correct += 1
        elif expected is None and got is not None:
            false_match += 1
        elif expected is not None and got is None:
            missed_match += 1

        if f.get("expected_risk"):
            risk_expected += 1
            if answer.get("risk_flagged"):
                risk_found += 1

    total = len(fixtures)
    pct = lambda n: f"{100.0 * n / total:5.1f}%"

    print("=" * 72)
    print(f"ACCURACY           {pct(correct)}  ({correct}/{total})")
    print(f"FALSE MATCH        {pct(false_match)}  answered a category where the truth is no-match")
    print("                          ^ the worst error: a confident wrong flow")
    print(f"MISSED MATCH       {pct(missed_match)}  answered no-match where a category was right")
    print("                          ^ costs a handover. The system working as designed")
    if risk_expected:
        print(f"RISK RECALL        {100.0 * risk_found / risk_expected:5.1f}%  "
              f"({risk_found}/{risk_expected}) — NEVER averaged into accuracy")
    if failures:
        print(f"CALL FAILURES      {failures} — each scored as a no-match, which is what the service does")

    ordered = sorted(latencies)
    p95 = ordered[min(len(ordered) - 1, int(0.95 * len(ordered)))]
    print(f"\nLATENCY            p50 {statistics.median(latencies):.0f} ms · "
          f"p95 {p95:.0f} ms · budget {args.timeout * 1000:.0f} ms"
          f"{'   *** OVER BUDGET ***' if p95 > args.timeout * 1000 else ''}")
    if args.cost_per_1k:
        print(f"COST               {args.cost_per_1k} per 1,000 calls")

    print("\nCONFUSION — rows are the truth, columns are the answer")
    print("  an 85% that is wrong uniformly and an 85% that sends every transport")
    print("  claim into recharge are the same number and different problems\n")
    for truth in sorted(confusion):
        for got, n in confusion[truth].most_common():
            mark = " " if truth == got else "<"
            print(f"  {mark} {truth:<28} -> {got:<28} {n}")

    print("\n" + "=" * 72)
    print("A SCORE FROM FIXTURES WRITTEN BY WHOEVER BUILT THE CLASSIFIER IS NOT EVIDENCE.")
    print("U-2 is answered only by real, hand-labelled partner messages. Until then this")
    print("number describes the harness, not the model.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
