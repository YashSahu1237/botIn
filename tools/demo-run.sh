#!/usr/bin/env bash
# =============================================================================
# PLAN STEP 95 — THE DEMO, AS AN EXECUTABLE FILE.
#
# =============================================================================
# WHY THIS IS A SCRIPT AND NOT A LIVE WALKTHROUGH
# =============================================================================
#
# A live demo is a performance, and performances are edited by the performer. The
# question a reviewer actually wants answered is not "can you make it work" but "does it
# work" — and those differ by exactly the cases somebody would skip when a room is
# watching. So the run is fixed, the whole run happens every time, and IT CHECKS ITSELF:
# every case asserts what it expected, and this script exits non-zero if any case fails.
#
# That makes this an acceptance test that happens to be readable out loud. If it goes red
# on stage, the right thing has happened.
#
# =============================================================================
# WHY THIS FILE IS NOW THIN
# =============================================================================
#
# The seventeen cases used to live here, in bash. That meant they could only be seen by
# somebody with a terminal open — a reviewer watching the console had to take it on trust
# that seventeen assertions existed somewhere else.
#
# The cases now live in ONE place, DemoScenarios on the server, and both surfaces read it:
# the console renders them with a Run button, and this script calls /demo/scenarios/run-all.
# Two definitions would drift, and the first anyone would know is a demo that passes in the
# terminal and fails on the screen.
#
# Nothing was lost in the move. The cases still drive the service over HTTP, still assert
# the same things, and the run still fails loudly. What changed is that they also run on
# every `mvn test` — see DemoScenarioRunTest — so they cannot quietly rot between demos.
#
# =============================================================================
# WHAT IT COVERS, AND WHAT IT CANNOT
# =============================================================================
#
# 17 of the 20 rows in the build plan's coverage matrix. The three it cannot reach are
# UPHOLD, T2-state-change-without-money, and the concern-level durable counter — all three
# came only from the violation concerns, which are OUT OF POC SCOPE (docs/SCOPE-VIOLATIONS-OUT.md).
# The counter mechanism itself is built and has nine passing tests; what is missing is a
# concern to attach it to.
#
# One row is proven by a test rather than here: editing a DMN threshold and redeploying
# that file alone. There is no redeploy endpoint and there should not be one — see
# DmnHotRedeployTest, which does it against a running engine.
#
# =============================================================================
# RUNNING IT
# =============================================================================
#
#   Terminal 1:  mvn spring-boot:run -Dspring-boot.run.profiles=demo,console \
#                    -Dspring-boot.run.useTestClasspath=true
#   Terminal 2:  ./tools/demo-run.sh
#
# The `console` profile is optional for this script and the reason to add it is the page at
# http://localhost:8080/console, which shows the same seventeen cases with a Run button.
#
# The demo runs on an in-memory H2 by default, so it needs nothing installed. That is why
# the test classpath is added: H2 is test-scoped in pom.xml and stays that way, because
# `H2 is for tests only, the POC database is Postgres` is a deliberate rule. Set
# BOTIN_DB_URL to demo against a real Postgres instead.
#
# The demo profile loads demo/fixtures.json and REFUSES TO START beside a real UAT
# datasource, so nothing in this run can be mistaken for evidence about real partners.
# =============================================================================

set -u
BASE="${BASE:-http://localhost:8080}"

command -v jq >/dev/null 2>&1 || { echo "This script needs jq.  brew install jq"; exit 2; }

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
dim()   { printf '\033[2m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }

bold "BOTIn POC — the scripted demo run"
dim  "$BASE"

if ! curl -s "$BASE/demo/scenarios" | jq -e 'type == "array"' >/dev/null 2>&1; then
  red "The demo control endpoints are not there."
  echo
  echo "  Either the service is not running, or it was not started with the demo profile."
  echo
  echo "  Start it in another terminal, from this folder:"
  echo
  echo "    mvn spring-boot:run -Dspring-boot.run.profiles=demo,console \\"
  echo "        -Dspring-boot.run.useTestClasspath=true"
  echo
  echo "  Wait for 'Started BotinApplication', then run this script again."
  echo "  (useTestClasspath is needed because H2 is test-scoped on purpose — the POC"
  echo "   database is Postgres, and the demo borrows H2 rather than promoting it.)"
  exit 2
fi

# The server reseeds the fixtures and the kill switches before the run, so a second run of
# a durable-guard case does not find the first run's ledger rows. That used to live here
# and belongs with the cases.
report=$(curl -s -X POST "$BASE/demo/scenarios/run-all" -H 'Content-Type: application/json' -d '{}')

echo "$report" | jq -r -f "$(dirname "$0")/demo-report.jq"

echo
bold "── Not covered here, and why"
dim "   UPHOLD, T2-state-change, the concern-level durable counter — all three came only"
dim "   from the violation concerns, now OUT OF POC SCOPE. The counter itself is built,"
dim "   with nine tests. See docs/SCOPE-VIOLATIONS-OUT.md."
dim "   Editing a DMN threshold and redeploying that file alone: DmnHotRedeployTest."
dim "   Transport cannot PAY: no service performs AUTO_CREDIT_TRANSPORT yet (step 55), so"
dim "   every transport T2 escalates instead of crediting."

echo
passed=$(echo "$report" | jq -r '.passed')
failed=$(echo "$report" | jq -r '.failed')
total=$(echo  "$report" | jq -r '.total')

if [ "$failed" = "0" ]; then
  green "══ $passed of $total cases passed, 0 failed."
  exit 0
else
  red   "══ $failed of $total cases FAILED."
  exit 1
fi
