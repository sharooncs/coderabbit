#!/bin/bash
# Runs the JVM tests and prints a short summary. Extra arguments go to Gradle,
# e.g.  tools/test.sh --tests 'app.housemove.FlowsTest'
# The tests are in two modules: shared/ (the data and sync code, on a plain JVM) and app/ (screens and PDFs,
# on simulated Android). Without --tests both run; with it, only the modules holding the tests it names.
cd "$(dirname "$0")/.." || exit 1
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
# The system gradle is used only when it is the version the wrapper names (the Android plugin
# needs a recent one); otherwise the wrapper, which downloads that version once.
GRADLE=./gradlew
want=$(sed -n 's/^distributionUrl=.*gradle-\([0-9.]*\)-.*\.zip$/\1/p' gradle/wrapper/gradle-wrapper.properties)
command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -qx "Gradle $want" && GRADLE=gradle
# A --tests pattern names a module when one of its dot-separated parts, wildcards and all, is the name of a
# test file there ('app.housemove.SimulationTest.seven*' and '*FlowsTest' each name one). Gradle gives --tests
# to the task before it, and fails a test task whose filter matches nothing, so each module gets its own.
shared=0 app=0 named=0 value=0
for a in "$@"; do
  if [ $value = 1 ]; then pattern=$a; value=0
  elif [ "$a" = --tests ]; then value=1; continue
  elif [[ $a == --tests=* ]]; then pattern=${a#--tests=}
  else continue; fi
  named=1 found=0
  IFS=. read -ra parts <<< "$pattern"
  for part in "${parts[@]}"; do
    compgen -G "shared/src/jvmTest/kotlin/app/housemove/$part.kt" > /dev/null && shared=1 found=1
    compgen -G "app/src/test/java/app/housemove/$part.kt" > /dev/null && app=1 found=1
  done
  [ $found = 0 ] && shared=1 app=1
done
[ $named = 0 ] && shared=1 app=1
tasks=() results=()
[ $shared = 1 ] && tasks+=(:shared:jvmTest "$@") results+=(shared/build/test-results/jvmTest)
[ $app = 1 ] && tasks+=(:app:testDebugUnitTest "$@") results+=(app/build/test-results/testDebugUnitTest)
mkdir -p build
for i in 1 2 3 4; do
  # --continue: failing tests in one module do not stop the other module's tests.
  $GRADLE "${tasks[@]}" --continue -q > build/test.log 2>&1; rc=$?
  # The package mirror sometimes rate-limits; that is not a test failure.
  grep -qE "status code 429|status code 403|Failed to fetch maven" build/test.log && { sleep 20; continue; }
  break
done
grep -E "^e: " build/test.log | cut -c1-300 | head -30
python3 - "${results[@]}" <<'PY'
import glob, re, html, sys
total = bad = 0
modules = []
for d in sys.argv[1:]:
    mt = mb = 0
    for f in sorted(glob.glob(d + '/*.xml')):
        s = open(f).read()
        m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', s)
        name = f.split('TEST-app.housemove.')[-1][:-4]
        mt += int(m.group(1)); mb += int(m.group(3)) + int(m.group(4))
        print(f"{name}: {m.group(1)} tests, {int(m.group(3)) + int(m.group(4))} failed")
        for fm in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(?:failure|error) message="([^"]{0,400})"[^>]*>(.{0,900})', s, re.S):
            lines = [l for l in html.unescape(fm.group(3)).split('\n') if 'housemove' in l][:4]
            print("  FAIL", fm.group(1)); print("   ", html.unescape(fm.group(2))[:400].replace('\n', ' ')); print("   ", ' | '.join(l.strip() for l in lines))
    modules.append((d.split('/')[0], mt, mb))
    total += mt; bad += mb
if len(modules) > 1:
    for name, mt, mb in modules:
        print(f"{name}: {mt} tests, {mb} failed")
print("total", total, "failed", bad)
PY
exit $rc
