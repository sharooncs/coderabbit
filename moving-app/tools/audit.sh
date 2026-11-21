#!/bin/bash
# Renders every screen with realistic data and builds contact sheets to look at.
#   tools/audit.sh                 all screens
#   tools/audit.sh boxes,box_box1  only these (names are routes with / replaced by _)
# Sheets are written to app/build/audit/sheets/ as tall-N.png (whole screen, top to
# bottom), fold-N.png (what a phone shows on opening) and empty-N.png (no data yet).
cd "$(dirname "$0")/.." || exit 1
mkdir -p build
tools/test.sh --tests 'app.housemove.AuditShotsTest' > build/audit-run.txt 2>&1 || { cat build/audit-run.txt; echo "audit run failed"; exit 1; }
python3 tools/sheets.py "${1:-}"
