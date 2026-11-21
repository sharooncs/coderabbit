#!/bin/bash
# Runs the JVM tests and prints a short summary. Extra arguments go to Gradle,
# e.g.  tools/test.sh --tests 'app.housemove.FlowsTest'
cd "$(dirname "$0")/.." || exit 1
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE=./gradlew; command -v gradle >/dev/null 2>&1 && GRADLE=gradle
mkdir -p build
for i in 1 2 3 4; do
  $GRADLE :app:testDebugUnitTest "$@" -q > build/test.log 2>&1; rc=$?
  # The package mirror sometimes rate-limits; that is not a test failure.
  grep -qE "status code 429|status code 403|Failed to fetch maven" build/test.log && { sleep 20; continue; }
  break
done
grep -E "^e: " build/test.log | cut -c1-300 | head -30
python3 - <<'PY'
import glob, re, html
total = bad = 0
for f in sorted(glob.glob('app/build/test-results/testDebugUnitTest/*.xml')):
    s = open(f).read()
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', s)
    name = f.split('TEST-app.housemove.')[-1][:-4]
    total += int(m.group(1)); bad += int(m.group(3)) + int(m.group(4))
    print(f"{name}: {m.group(1)} tests, {int(m.group(3)) + int(m.group(4))} failed")
    for fm in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(?:failure|error) message="([^"]{0,400})"[^>]*>(.{0,900})', s, re.S):
        lines = [l for l in html.unescape(fm.group(3)).split('\n') if 'housemove' in l][:4]
        print("  FAIL", fm.group(1)); print("   ", html.unescape(fm.group(2))[:400].replace('\n', ' ')); print("   ", ' | '.join(l.strip() for l in lines))
print("total", total, "failed", bad)
PY
exit $rc
