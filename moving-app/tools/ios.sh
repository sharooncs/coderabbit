#!/bin/bash
# Builds, tests, signs and uploads the iPhone app. Runs on a Mac (the owner's, through Remote Control,
# or GitHub's macOS runner). Signing uses the Apple Account signed into Xcode: nothing secret is read
# from the repo or the environment.
#
#   tools/ios.sh doctor     what the Mac has: Xcode, licence, signed-in team, certificates, iPhone, JDK, Android SDK
#   tools/ios.sh setup      install XcodeGen, a JDK 21 and the Android SDK into ~/Developer/tools (no admin rights)
#   tools/ios.sh generate   make iosApp/HouseMove.xcodeproj from iosApp/project.yml
#   tools/ios.sh measure    time linking the Kotlin framework, its memory, the app's size and the privacy APIs it calls
#   tools/ios.sh sim        build and run the UI tests in an iPhone simulator; screenshots in iosApp/build/shots
#   tools/ios.sh archive    signed Release archive, then a local App Store export (registers app.housemove.ios)
#   tools/ios.sh upload     sign for App Store Connect and upload the archive to TestFlight
#   tools/ios.sh phone      build for the paired iPhone, install and open it (no TestFlight needed)
#   tools/ios.sh asc …      App Store Connect from the command line: TestFlight groups, testers, review (tools/asc.swift)
#
# The screens are Kotlin (sharedUi): every Xcode build runs Gradle first (iosApp/project.yml), which needs a JDK
# (21) and the Android SDK (platform 37, build tools 36), even for the iPhone. `setup` installs both here.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$HERE/iosApp"
TOOLS="${HM_TOOLS:-$HOME/Developer/tools}"
# XcodeGen runs from its own folder: it finds its default build settings next to itself (not through a symlink).
export PATH="$TOOLS/xcodegen/bin:$TOOLS/bin:$PATH"
PROJECT="$IOS/HouseMove.xcodeproj"
TEAM=USW8537DGX
cd "$IOS"

# What `setup` installs, pinned and checked: Temurin JDK 21 for Apple silicon, and Google's Android command-line
# tools (23.0), whose sdkmanager then fetches platform 37 and build tools 36 into $TOOLS/android-sdk.
JDK_TAG="jdk-21.0.13.1+2"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13.1_2.tar.gz"
JDK_SHA256=48067f2b7c958ed104a9d8a5749be7d25220b0a356b4d2d1bcfaab3e16ad0b14
CLT_URL="https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip"
CLT_SHA256=58da8f215781cc0208743e88b65ae259780c55e7eb40e50373d377cf369232d6
SDK_PACKAGES=("platforms;android-37.0" "build-tools;36.0.0")
JDK_HOME="$TOOLS/jdk/Contents/Home"
SDK="$TOOLS/android-sdk"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

# Gradle uses JAVA_HOME and ANDROID_HOME. Unless the shell names others, they are the ones `setup` installs; the
# Xcode build step does the same, since Xcode does not pass on the shell's settings.
if [ -z "${JAVA_HOME:-}" ] && [ -x "$JDK_HOME/bin/java" ]; then export JAVA_HOME="$JDK_HOME"; fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$SDK/platforms" ]; then export ANDROID_HOME="$SDK"; fi

say() { printf '\n== %s\n' "$*"; }

# Downloads $1 to $2 and checks it against the SHA-256 $3.
fetch() {
  curl -fsSL -o "$2" "$1"
  echo "$3  $2" | shasum -a 256 -c - >/dev/null || { echo "The download of $1 does not match its checksum"; exit 1; }
}

install_xcodegen() {
  mkdir -p "$TOOLS/bin"
  rm -f "$TOOLS/bin/xcodegen"
  if ! command -v xcodegen >/dev/null; then
    say "Installing XcodeGen into $TOOLS"
    local tmp; tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/xcodegen.zip" https://github.com/yonaskolb/XcodeGen/releases/download/${XCODEGEN_VERSION:-2.48.0}/xcodegen.zip
    unzip -q "$tmp/xcodegen.zip" -d "$tmp"
    rm -rf "$TOOLS/xcodegen"; mv "$tmp/xcodegen" "$TOOLS/xcodegen"
    rm -rf "$tmp"
  fi
  printf 'XcodeGen '; xcodegen --version
}

install_jdk() {
  if [ -x "$JDK_HOME/bin/java" ] && "$JDK_HOME/bin/java" -version 2>&1 | grep -q 'version "21\.'; then
    echo "JDK 21: in $TOOLS/jdk already"
  else
    [ "$(uname -m)" = arm64 ] || { echo "The pinned JDK is for Apple-silicon Macs; install a JDK 21 and set JAVA_HOME"; exit 1; }
    say "Installing JDK 21 (Temurin $JDK_TAG) into $TOOLS/jdk"
    local tmp; tmp=$(mktemp -d)
    fetch "$JDK_URL" "$tmp/jdk.tar.gz" "$JDK_SHA256"
    mkdir "$tmp/x"; tar -xzf "$tmp/jdk.tar.gz" -C "$tmp/x"
    rm -rf "$TOOLS/jdk"; mv "$tmp/x"/jdk-* "$TOOLS/jdk"
    rm -rf "$tmp"
  fi
  "$JDK_HOME/bin/java" -version 2>&1 | head -1
}

install_android_sdk() {
  if [ ! -x "$SDKMANAGER" ]; then
    say "Installing the Android command-line tools into $SDK"
    local tmp; tmp=$(mktemp -d)
    fetch "$CLT_URL" "$tmp/clt.zip" "$CLT_SHA256"
    unzip -q "$tmp/clt.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"; rm -rf "$SDK/cmdline-tools/latest"; mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  # The owner reads and accepts Google's licences in Terminal; nothing here answers for them.
  if [ ! -f "$SDK/licenses/android-sdk-license" ]; then
    if [ -t 0 ] && [ -t 1 ]; then
      say "The Android SDK licences: read each one and type y to accept it"
      JAVA_HOME="$JDK_HOME" "$SDKMANAGER" --sdk_root="$SDK" --licenses
    else
      say "The Android SDK licences are not accepted yet"
      echo "The owner runs this in Terminal on this Mac, reads each licence and types y to accept it:"
      echo "  JAVA_HOME=\"$JDK_HOME\" \"$SDKMANAGER\" --sdk_root=\"$SDK\" --licenses"
      echo "Then run tools/ios.sh setup again."
      exit 1
    fi
  fi
  if [ ! -d "$SDK/platforms/android-37.0" ] || [ ! -d "$SDK/build-tools/36.0.0" ]; then
    say "Installing ${SDK_PACKAGES[*]} into $SDK"
    JAVA_HOME="$JDK_HOME" "$SDKMANAGER" --sdk_root="$SDK" "${SDK_PACKAGES[@]}" | grep -v '^\[' || true
  fi
  [ -d "$SDK/platforms/android-37.0" ] && [ -d "$SDK/build-tools/36.0.0" ] || { echo "The Android SDK packages did not install"; exit 1; }
  echo "Android SDK: $SDK (platform 37, build tools 36)"
}

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
  asc)
    # Uses the App Store Connect API key kept in ~/.appstoreconnect on this Mac; see the top of tools/asc.swift.
    shift
    exec xcrun swift "$HERE/tools/asc.swift" "$@"
    ;;
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
    say "Java for Gradle (the Kotlin screens need a JDK 21)"
    if [ -n "${JAVA_HOME:-}" ]; then echo "JAVA_HOME=$JAVA_HOME"; java_bin="$JAVA_HOME/bin/java"; else echo "JAVA_HOME not set: java on PATH"; java_bin=java; fi
    "$java_bin" -version 2>&1 | head -1 || true
    [ -x "$JDK_HOME/bin/java" ] || echo "No JDK in $TOOLS/jdk: run tools/ios.sh setup"
    say "Android SDK for Gradle (the Android plugin needs it even for the iPhone build)"
    echo "ANDROID_HOME=${ANDROID_HOME:-not set: run tools/ios.sh setup}"
    for p in platforms/android-37.0 build-tools/36.0.0 licenses/android-sdk-license; do
      if [ -e "${ANDROID_HOME:-/nonexistent}/$p" ]; then echo "$p: yes"; else echo "$p: MISSING (tools/ios.sh setup)"; fi
    done
    ;;
  setup)
    # Each part is skipped when it is there already. `setup xcodegen` installs only XcodeGen (CI brings its own JDK
    # and Android SDK).
    install_xcodegen
    if [ "${2:-all}" = all ]; then
      install_jdk
      install_android_sdk
    fi
    ;;
  generate) generate ;;
  measure)
    # What the Kotlin side costs on this Mac, for the cloud session to read in the CI log: the time and memory of
    # compiling and linking HouseMoveKit, the size of the Release app for the iPhone, and which of Apple's
    # required-reason APIs end up in it (they must be declared in HouseMove/PrivacyInfo.xcprivacy).
    say "This Mac"
    echo "$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo '?'), $(sysctl -n hw.ncpu) CPUs, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB memory"
    printf 'Java for Gradle: '; "${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1 || true
    mkdir -p build
    for task in :sharedUi:compileKotlinIosSimulatorArm64 :sharedUi:linkDebugFrameworkIosSimulatorArm64 :sharedUi:linkReleaseFrameworkIosArm64; do
      say "$task"
      # --no-daemon: Gradle and the Kotlin/Native compiler it starts are children of `time`, which reports the
      # largest of them; a sampler adds up all Java processes running at once (Gradle and the compiler together).
      rm -f build/measure-peak.txt
      ( peak=0; while sleep 2; do
          now=$(ps -A -o rss=,comm= | awk '/java/ { s += $1 } END { print s + 0 }')
          if [ "$now" -gt "$peak" ]; then peak=$now; echo "$peak" > build/measure-peak.txt; fi
        done ) &
      sampler=$!
      st=0
      (cd "$HERE" && /usr/bin/time -l ./gradlew --no-daemon --console=plain -q "$task") > build/measure.log 2>&1 || st=$?
      kill "$sampler" 2>/dev/null || true
      grep -E "^e: |What went wrong|OutOfMemory" -A6 build/measure.log | head -40 || true
      awk '$2 == "real" { printf "wall time: %.0f s (CPU: user %.0f s, system %.0f s)\n", $1, $3, $5 }
           /maximum resident set size/ { printf "peak memory of the largest process: %.0f MB\n", $1 / 1048576 }' build/measure.log
      [ -f build/measure-peak.txt ] && echo "peak memory of all Java processes together: $(( $(cat build/measure-peak.txt) / 1024 )) MB (sampled every 2 s)"
      [ "$st" = 0 ] || { echo "$task FAILED (exit $st)"; tail -30 build/measure.log; exit "$st"; }
    done
    fw="$HERE/sharedUi/build/bin/iosArm64/releaseFramework/HouseMoveKit.framework/HouseMoveKit"
    echo "HouseMoveKit, Release for the iPhone (a static library, before the app's linker drops what is unused): $(du -h "$fw" | cut -f1)"
    say "The app, Release for the iPhone (unsigned)"
    generate
    start=$(date +%s)
    xcodebuild build -project "$PROJECT" -scheme HouseMove -configuration Release -destination 'generic/platform=iOS' \
      -derivedDataPath build/dd-release CODE_SIGNING_ALLOWED=NO > build/measure-app.log 2>&1 \
      || { grep -E "error:|^e: |What went wrong" -A6 build/measure-app.log | head -60; tail -20 build/measure-app.log; exit 1; }
    app=build/dd-release/Build/Products/Release-iphoneos/HouseMove.app
    echo "Xcode build: $(( $(date +%s) - start )) s"
    echo "HouseMove.app: $(du -sh "$app" | cut -f1); its executable: $(( $(stat -f%z "$app/HouseMove") / 1048576 )) MB; compose-resources: $(ls "$app/compose-resources" 2>/dev/null | tr '\n' ' ')"
    say "Required-reason APIs in the app's executable (Apple's list; declared in PrivacyInfo.xcprivacy)"
    /usr/bin/python3 - "$app/HouseMove" <<'PY'
import subprocess, sys
exe = sys.argv[1]
imported = set(subprocess.run(["nm", "-u", "-j", exe], capture_output=True, text=True).stdout.split())
texts = set(subprocess.run(["strings", "-a", exe], capture_output=True, text=True).stdout.splitlines())
# Per category: the C functions and Foundation constants (imported symbols), then the Objective-C method names.
categories = {
    "FileTimestamp": (["stat", "fstat", "lstat", "fstatat", "getattrlist", "fgetattrlist", "getattrlistat",
                       "getattrlistbulk", "NSFileCreationDate", "NSFileModificationDate", "NSURLContentModificationDateKey",
                       "NSURLCreationDateKey"], ["creationDate", "modificationDate", "fileModificationDate"]),
    "SystemBootTime": (["mach_absolute_time"], ["systemUptime"]),
    "DiskSpace": (["statfs", "statvfs", "fstatfs", "fstatvfs", "NSFileSystemFreeSize", "NSFileSystemSize",
                   "NSURLVolumeAvailableCapacityKey", "NSURLVolumeAvailableCapacityForImportantUsageKey",
                   "NSURLVolumeAvailableCapacityForOpportunisticUsageKey", "NSURLVolumeTotalCapacityKey"], []),
    "ActiveKeyboards": ([], ["activeInputModes"]),
    "UserDefaults": (["OBJC_CLASS_$_NSUserDefaults"], []),
}
for name, (symbols, methods) in categories.items():
    hits = [s for s in symbols if "_" + s in imported or "_" + s + "$INODE64" in imported]
    hits += [m + " (method name)" for m in methods if m in texts]
    print(f"NSPrivacyAccessedAPICategory{name}: {', '.join(hits) if hits else 'not used'}")
PY
    ;;
  sim)
    generate
    id=$(sim_id); say "Simulator $id"
    # A new simulator's first start takes minutes on a small machine: wait for it before testing,
    # or the first test times out launching the app. A test that still fails is run once more.
    xcrun simctl boot "$id" 2>/dev/null || true
    xcrun simctl bootstatus "$id" -b >/dev/null
    rm -rf build/sim.xcresult build/shots
    # Build first (Gradle builds the Kotlin screens), then stop Gradle so its memory goes to the simulator.
    say "Building for the simulator"
    xcodebuild build-for-testing -project "$PROJECT" -scheme HouseMove -destination "id=$id" \
      CODE_SIGNING_ALLOWED=NO > build/sim-build.log 2>&1 \
      || { grep -E "error:|^e: |What went wrong" -A6 build/sim-build.log | head -80; tail -25 build/sim-build.log; exit 1; }
    tail -3 build/sim-build.log
    (cd "$HERE" && ./gradlew --stop -q) >/dev/null 2>&1 || true
    say "Testing"
    status=0
    xcodebuild test-without-building -project "$PROJECT" -scheme HouseMove -destination "id=$id" \
      -retry-tests-on-failure -test-iterations 2 \
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
    codesign -dvv build/HouseMove.xcarchive/Products/Applications/HouseMove.app 2>&1 | grep -E 'Identifier|TeamIdentifier|Authority' || true
    # The archive itself may be signed with Xcode's wildcard team profile, which registers nothing. Signing it for
    # App Store Connect (kept on this Mac, nothing uploaded) registers the bundle ID app.housemove.ios and makes the
    # distribution certificate, so the app can be created in App Store Connect and `upload` works.
    say "Registering app.housemove.ios and preparing App Store signing (local export, nothing is uploaded)"
    cp ExportOptions.plist build/ExportOptions-local.plist
    plutil -replace destination -string export build/ExportOptions-local.plist
    rm -rf build/export-local
    xcodebuild -exportArchive -archivePath build/HouseMove.xcarchive -exportOptionsPlist build/ExportOptions-local.plist \
      -exportPath build/export-local -allowProvisioningUpdates | tail -15
    ls build/export-local/*.ipa
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
  *) sed -n '2,18p' "$0"; exit 1 ;;
esac
