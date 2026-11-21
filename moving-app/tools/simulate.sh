#!/bin/bash
# Runs the seven-phone simulation over many random seeds and writes
# app/build/audit/simulation.md: seeds, actions, syncs, refusals by rule, and
# every invariant with pass or the seeds that broke it.
#   tools/simulate.sh              50 seeds, starting at seed 1
#   tools/simulate.sh 300          300 seeds
#   tools/simulate.sh 1 4711       seed 4711 alone, to look at a failure again
# A failing seed prints what led to the failure. With HM_SIM_TRACE=1 the whole
# trace of every seed is written to build/simulation-trace.txt as well.
cd "$(dirname "$0")/.." || exit 1
export HM_SIM_SEEDS="${1:-50}"
export HM_SIM_FIRST="${2:-1}"
export HM_SIM_REPORT=1
export HM_SIM_JOBS="${HM_SIM_JOBS:-3}"
# One seed in ten more is run with the phones working while they sync (see SimulationTest).
export HM_SIM_LOOSE="${HM_SIM_LOOSE:-$(( (HM_SIM_SEEDS + 9) / 10 ))}"
[ -n "$HM_SIM_TRACE" ] && export HM_SIM_TRACE="$PWD/build/simulation-trace.txt"
mkdir -p build app/build/audit
# Gradle skips a test task whose inputs have not changed; removing its last result makes it run.
rm -f app/build/test-results/testDebugUnitTest/TEST-app.housemove.SimulationTest.xml app/build/audit/simulation.md app/build/audit/simulation-loose.md
tools/test.sh --tests 'app.housemove.SimulationTest.sevenPhonesDoRandomThingsAndEndUpAgreeing' --tests 'app.housemove.SimulationTest.phonesThatKeepWorkingWhileTheySyncStillEndUpAgreeing' > build/simulate.txt 2>&1
rc=$?
if [ -f app/build/audit/simulation.md ]; then
  [ -f app/build/audit/simulation-loose.md ] && cat app/build/audit/simulation-loose.md >> app/build/audit/simulation.md && rm app/build/audit/simulation-loose.md
  echo "report: app/build/audit/simulation.md"
  sed -n '/^| Seeds run/,/^| Syncs/p;/^## Invariants/,/^## Refusals/p' app/build/audit/simulation.md | grep -v '^## '
  grep -A2 '^## Phones that keep working' app/build/audit/simulation.md | tail -1
else
  cat build/simulate.txt
  echo "no report was written; see build/test.log"
fi
exit $rc
