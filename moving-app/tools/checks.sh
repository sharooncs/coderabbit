#!/bin/bash
# Runs the automated audit checks (AuditChecksTest) and says where the report is.
#   tools/checks.sh            every check
#   tools/checks.sh speed      only one: phone, smallPhone, narrowTallPhone, largeText or speed
#                              (the report is then rewritten from that check and the parts kept from before)
# The report is app/build/audit/report.md, with the same data in report.json beside it.
# Pictures of the screens as they were checked are under app/build/audit/checks/ and
# app/build/audit/sizes/. Findings do not fail this script; only a broken run does.
cd "$(dirname "$0")/.." || exit 1
out=app/build/audit
filter='app.housemove.AuditChecksTest'
if [ -n "${1:-}" ]; then
  filter="$filter.$1"
else
  rm -rf "$out/checks" "$out/sizes" "$out/report.md" "$out/report.json"
fi
mkdir -p build
start=$(date +%s)
# --rerun: the report is not a declared output of the test task, so Gradle must not skip it as up to date.
tools/test.sh --tests "$filter" --rerun > build/checks-run.txt 2>&1 || { cat build/checks-run.txt; echo "the audit checks failed to run (findings never cause this)"; exit 1; }
tail -n 2 build/checks-run.txt
[ -f "$out/report.md" ] || { echo "the checks ran but wrote no report"; exit 1; }
echo
# The summary: everything up to the first numbered section.
awk '/^## 1\. /{exit} {print}' "$out/report.md"
echo "Took $(( $(date +%s) - start )) s including the build."
echo "Report:  $(pwd)/$out/report.md"
echo "As JSON: $(pwd)/$out/report.json"
