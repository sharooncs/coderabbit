#!/bin/bash
# Walks every screen as an Organiser, a Decider, a Helper and someone alone (RoleWalkTest),
# says what it found, and adds a contact sheet per role to app/build/audit/sheets/.
#   tools/roles.sh
# Under app/build/audit/roles/<who>/: a picture of every screen as that person sees it,
# controls.txt (everything that can be tapped on each screen) and walk.md (what was found).
# It fails when a screen crashes, or when a core screen shows a control its person may not use.
cd "$(dirname "$0")/.." || exit 1
mkdir -p build
tools/test.sh --tests 'app.housemove.RoleWalkTest' > build/roles-run.txt 2>&1
rc=$?
tail -n 12 build/roles-run.txt | grep -v "^Picked up"
for who in organiser decider helper alone; do
  f=app/build/audit/roles/$who/walk.md
  [ -f "$f" ] && { echo; head -n 1 "$f"; sed -n '/^## /,$p' "$f"; }
done
python3 tools/sheets.py > /dev/null && echo && echo "Sheets: $(pwd)/app/build/audit/sheets/roles-*.png"
exit $rc
