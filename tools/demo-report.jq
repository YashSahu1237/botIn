# Renders the report from POST /demo/scenarios/run-all.
#
# Lives in its own file rather than inline in demo-run.sh because the filter needs both
# quote characters and a shell is the wrong place to fight over them.
def colour(ok): if ok then "[32m" else "[31m" end;

.results[] as $r
| "",
  "[1m-- \($r.title)[0m",
  "   [2m\($r.proves)[0m",
  ( $r.checks[]
    | "   " + colour(.passed)
    + ( if .passed
        then "PASS  \(.label): \(.actual)"
        else "FAIL  \(.label): expected [\(.expected)] but got [\(.actual)]"
        end )
    + "[0m" ),
  ( if $r.error then "   [31mFAIL  threw: \($r.error)[0m" else empty end )
