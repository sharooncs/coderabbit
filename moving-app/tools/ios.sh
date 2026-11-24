#!/bin/bash
# Builds, tests, signs and uploads the iPhone app. Runs on a Mac (the owner's, through Remote Control,
# or GitHub's macOS runner). Signing uses the Apple Account signed into Xcode: nothing secret is read
# from the repo or the environment.
#
#   tools/ios.sh doctor     what the Mac has: Xcode, licence, signed-in team, certificates, iPhone
#   tools/ios.sh setup      install XcodeGen into ~/Developer/tools (no admin rights needed)
#   tools/ios.sh generate   make iosApp/HouseMove.xcodeproj from iosApp/project.yml
#   tools/ios.sh sim        build and run the UI tests in an iPhone simulator; screenshots in iosApp/build/shots
#   tools/ios.sh archive    signed Release archive (the first one also registers the bundle ID app.housemove.ios)
#   tools/ios.sh upload     sign for App Store Connect and upload the archive to TestFlight
#   tools/ios.sh phone      build for the paired iPhone, install and open it (no TestFlight needed)
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$HERE/iosApp"
TOOLS="${HM_TOOLS:-$HOME/Developer/tools}"
export PATH="$TOOLS/bin:$PATH"
PROJECT="$IOS/HouseMove.xcodeproj"
TEAM=USW8537DGX
cd "$IOS"

say() { printf '\n== %s\n' "$*"; }

generate() {
  command -v xcodegen >/dev/null || { echo "XcodeGen missing: run tools/ios.sh setup"; exit 1; }
  xcodegen generate --spec project.yml --quiet
}

sim_id() {
  # One simulator of our own, an iPhone 17 Pro on the newest iOS runtime (or the newest iPhone there is).
  local name="HouseMove ${HM_SIM_TYPE:-iPhone 17 Pro}"
  local id
  id=$(xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["name"] == name:
            print(d["udid"]); sys.exit()
' "$name")
  if [ -z "$id" ]; then
    id=$(xcrun simctl create "$name" "${HM_SIM_TYPE:-iPhone 17 Pro}" 2>/dev/null) || {
      local fallback
      fallback=$(xcrun simctl list devicetypes | grep -o 'iPhone [0-9][0-9]* Pro (' | tail -1 | sed 's/ ($//')
      id=$(xcrun simctl create "HouseMove $fallback" "$fallback")
    }
  fi
  echo "$id"
}

phone_id() {
  [ -n "${HM_PHONE:-}" ] && { echo "$HM_PHONE"; return; }
  local out; out=$(mktemp)
  xcrun devicectl list devices --json-output "$out" >/dev/null
  /usr/bin/python3 -c '
import json, sys
for d in json.load(open(sys.argv[1]))["result"]["devices"]:
    hw = d.get("hardwareProperties", {})
    if hw.get("platform") == "iOS" and hw.get("reality") == "physical" and d.get("connectionProperties", {}).get("pairingState") == "paired":
        print(d["identifier"]); break
' "$out"
  rm -f "$out"
}

case "${1:-}" in
  doctor)
    say "macOS and Xcode"; sw_vers -productVersion; xcodebuild -version
    say "Xcode first launch and licence"
    if xcodebuild -checkFirstLaunchStatus; then echo "done"; else echo "NOT DONE: open Xcode once and agree to the licence"; fi
    say "Team $TEAM signed into Xcode"
    if defaults read com.apple.dt.Xcode IDEProvisioningTeamByIdentifier 2>/dev/null | grep -q "$TEAM"; then echo "yes"
    else echo "not seen: Xcode > Settings > Accounts > + > Apple Account"; fi
    say "Signing certificates in the keychain"
    security find-identity -v -p codesigning | sed 's/[0-9A-F]\{40\}/<hash>/'
    if security find-identity -v -p codesigning | LC_ALL=C grep -q '[^ -~]'; then
      echo "NOTE: a certificate name has non-ASCII letters; if signing fails with an encoding error, rename the Mac user's full name or make the certificate in Xcode again"
    fi
    say "Paired iPhone"; xcrun devicectl list devices 2>/dev/null || echo "none"
    say "Tools"; for t in xcodegen java git; do printf '%s: ' "$t"; command -v "$t" || echo missing; done
    ;;
  setup)
    mkdir -p "$TOOLS/bin"
    if ! command -v xcodegen >/dev/null; then
      say "Installing XcodeGen into $TOOLS"
      tmp=$(mktemp -d)
      curl -fsSL -o "$tmp/xcodegen.zip" https://github.com/yonaskolb/XcodeGen/releases/latest/download/xcodegen.zip
      unzip -q "$tmp/xcodegen.zip" -d "$tmp"
      rm -rf "$TOOLS/xcodegen"; mv "$tmp/xcodegen" "$TOOLS/xcodegen"
      ln -sf "$TOOLS/xcodegen/bin/xcodegen" "$TOOLS/bin/xcodegen"
      rm -rf "$tmp"
    fi
    xcodegen --version
    ;;
  generate) generate ;;
  sim)
    generate
    id=$(sim_id); say "Simulator $id"
    rm -rf build/sim.xcresult build/shots
    status=0
    xcodebuild test -project "$PROJECT" -scheme HouseMove -destination "id=$id" \
      -resultBundlePath build/sim.xcresult CODE_SIGNING_ALLOWED=NO | tail -40 || status=$?
    mkdir -p build/shots
    xcrun xcresulttool export attachments --path build/sim.xcresult --output-path build/shots >/dev/null 2>&1 || true
    # Name each screenshot after the test's own name for it (start.png, start-largest-text.png, ...).
    [ -f build/shots/manifest.json ] && /usr/bin/python3 -c '
import json, os, re, sys
d = sys.argv[1]
for test in json.load(open(os.path.join(d, "manifest.json"))):
    for a in test.get("attachments", []):
        name = re.sub(r"_[0-9]+_[0-9A-F-]{36}", "", a.get("suggestedHumanReadableName", ""))
        src = os.path.join(d, a["exportedFileName"])
        if name and os.path.exists(src):
            os.replace(src, os.path.join(d, name))
' build/shots
    ls build/shots
    exit $status
    ;;
  archive)
    generate
    build=$(date -u +%Y%m%d%H%M)
    say "Archiving build $build"
    xcodebuild archive -project "$PROJECT" -scheme HouseMove -configuration Release \
      -destination 'generic/platform=iOS' -archivePath build/HouseMove.xcarchive \
      -allowProvisioningUpdates CURRENT_PROJECT_VERSION="$build" | tail -25
    codesign -dv build/HouseMove.xcarchive/Products/Applications/HouseMove.app 2>&1 | grep -E 'Identifier|TeamIdentifier|Authority' || true
    ;;
  upload)
    [ -d build/HouseMove.xcarchive ] || { echo "No archive: run tools/ios.sh archive first"; exit 1; }
    say "Signing for App Store Connect and uploading"
    xcodebuild -exportArchive -archivePath build/HouseMove.xcarchive -exportOptionsPlist ExportOptions.plist \
      -exportPath build/export -allowProvisioningUpdates | tail -25
    ;;
  phone)
    generate
    id=$(phone_id); [ -n "$id" ] || { echo "No paired iPhone: pair it in Xcode (Device Hub) and turn on Developer Mode"; exit 1; }
    say "Building for iPhone $id"
    xcodebuild build -project "$PROJECT" -scheme HouseMove -configuration Debug -destination "id=$id" \
      -derivedDataPath build/dd -allowProvisioningUpdates | tail -15
    xcrun devicectl device install app --device "$id" build/dd/Build/Products/Debug-iphoneos/HouseMove.app
    xcrun devicectl device process launch --device "$id" app.housemove.ios
    ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
