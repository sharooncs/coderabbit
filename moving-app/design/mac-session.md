# Instructions for a Claude Code session on the owner's Mac

You are running on the owner's Mac, in `~/Developer`. A cloud session works on the Kotlin side of HouseMove. It cannot reach this Mac, so you do the parts that need it: Xcode signing, the simulator, the paired iPhone and the TestFlight upload. The plan is in `moving-app/design/ios-plan.md`; the build script is `moving-app/tools/ios.sh`. Signing uses the Apple Account already signed into Xcode (team USW8537DGX), with automatic signing.

## Rules
- Never ask for, type, read or store passwords, 2FA codes, or any .p8/.p12 file or its contents. When macOS or Xcode asks for a password, the owner types it into the system dialog.
- No `sudo`. Install tools only under `~/Developer/tools`; `tools/ios.sh setup` does this.
- Let automatic signing create certificates and profiles. Don't revoke anything or change Xcode account settings unless the owner asks.
- Don't push: this Mac has no GitHub sign-in for the repo, and the cloud session works on `claude/intelligent-brown-mk8754`. If a file in the repo must change for the Mac build to work, make the change locally and put the whole diff in your report; the cloud session commits it. Before each run, `git checkout claude/intelligent-brown-mk8754 && git pull` (and drop any local `mac/…` branch whose change the cloud session has taken in).
- In summaries, describe the iPhone by its name, model and iOS version. Leave out its serial number, IMEI or UDID; it's fine to use them in commands.

## Steps
1. Clone and check out:
   `git clone https://github.com/sharooncs/coderabbit.git && cd coderabbit && git checkout claude/intelligent-brown-mk8754`
   If git complains about the Xcode licence, ask the owner to open Xcode once and agree.
2. `moving-app/tools/ios.sh doctor`: report each section. That is: the Xcode version, whether first launch and the licence are done, whether team USW8537DGX is signed in, the certificate names, the paired iPhone with its Developer Mode state, and the Java and Android SDK that Gradle will use. (Detecting the team is a heuristic. If it says "not seen" but Xcode → Settings → Accounts shows the team, carry on.)
3. `moving-app/tools/ios.sh setup`: installs into `~/Developer/tools`, skipping whatever is there already, so it is safe to run again:
   - XcodeGen 2.48.0;
   - a JDK 21 (Temurin, for Apple silicon, checksum-checked) in `tools/jdk`. The app's screens are Kotlin, and every Xcode build runs Gradle first, which needs it. A `java` already on the PATH is left alone; ios.sh and the Xcode build use this JDK unless `JAVA_HOME` names another;
   - the Android command-line tools, then Android platform 37 and build tools 36, in `tools/android-sdk`. The Android Gradle plugin needs them even for the iPhone build. ios.sh and the Xcode build use this SDK unless `ANDROID_HOME` names another.

   Google's SDK licences must be accepted first, by the owner. The first time, setup stops and prints a command. Ask the owner to run it in Terminal on the Mac, read each licence and type `y` to accept it; never answer for them or pipe `yes` into it. Then run setup again.
4. `moving-app/tools/ios.sh sim`: builds the app (Gradle builds the Kotlin screens first: a few minutes the first time, while it downloads the Kotlin/Native toolchain into `~/.konan`) and runs its 4 UI tests in an iPhone 17 Pro simulator. All 4 must pass; screenshots are in `moving-app/iosApp/build/shots`.
   - Optional, when the cloud session asks for it: `moving-app/tools/ios.sh measure` times compiling and linking the Kotlin framework, gives its memory and the Release app's size, and lists the privacy APIs in the app. Paste its output into the report.
5. `moving-app/tools/ios.sh phone`: a development build installed straight onto the paired iPhone, then opened.
   - The first time, automatic signing makes an Apple Development certificate and registers the App ID `app.housemove.ios` and the phone.
   - macOS may ask for the login password so codesign can use the key. The owner chooses "Always Allow".
   - The iPhone must be unlocked.
   - If it says "Untrusted Developer": Settings → General → VPN & Device Management → trust the developer.
   - If Developer Mode is off: Settings → Privacy & Security → Developer Mode → On, then restart.
   - Ask the owner to confirm what the phone shows: the trial screen of the shared Kotlin screens. That is the house mark, "HouseMove" in the app's rounded font, the explanation line and "Test build 0.1 (1)", then a long list of rooms and things that scrolls smoothly, and a grid of icons at the end. Nothing should sit under the Dynamic Island or the home bar.
6. `moving-app/tools/ios.sh archive`: a signed Release archive, then a local App Store export that registers the bundle ID and makes the distribution certificate (the archive alone may be signed with Xcode's wildcard profile, which registers nothing). Report the `Authority=` and `TeamIdentifier=` lines it prints. Then tell the owner: **"bundle ID registered"**.
7. Done once already (2026-11-24: app record "HouseMove - Moving Planner", Apple ID 6838661243, internal group "Me" with automatic distribution). Skip this step from now on. For the record, the owner created the app record in App Store Connect (after accepting the App Store Connect Terms of Service):
   - Apps → + → New App: iOS, name "HouseMove - Moving Planner", primary language, bundle ID `app.housemove.ios`, SKU `housemove-ios`, Full Access → Create.
   - Then TestFlight → Internal Testing → + → group "Me" → tick "Enable automatic distribution" → Create → invite themselves.

   If you have a browser tool, you may offer to do this with the owner watching. They handle any sign-in.
8. When the owner says the app exists: `moving-app/tools/ios.sh upload` signs for App Store Connect and uploads. Apple processes the build in about 5–30 minutes. It then appears in the TestFlight app on the iPhone (the owner installs TestFlight from the App Store if needed).
9. Finish with a short report the owner can paste back to the cloud session. Include:
   - the doctor summary;
   - each step as pass or fail, with the exact error text;
   - the version and build number uploaded;
   - the output of `tools/ios.sh measure`, if the cloud session asked for it;
   - the whole diff of any change you made to the repo (nothing is pushed from this Mac).

## App Store Connect from the command line
The owner made an App Store Connect API key (2026-11-25). Its file is in `~/.appstoreconnect/private_keys/` and is never opened, printed or copied. `~/.appstoreconnect/housemove.conf` holds two lines, `key_id=…` and `issuer_id=…` (identifiers, not secrets; kept off GitHub because the repo is public). With them, `moving-app/tools/ios.sh asc` does on the command line what the TestFlight pages do:
- `asc status`: the latest builds with their processing and Beta App Review state, and the tester groups.
- `asc test-info`: the beta description, feedback email and review contact as saved.
- `asc create-group NAME`, `asc add-tester GROUP EMAIL [FIRST LAST]`, `asc add-build GROUP BUILD` (a build number or `latest`), `asc what-to-test BUILD TEXT`, `asc submit BUILD`, `asc public-link GROUP on|off`.
Ask the owner before `submit`, `add-tester` and `public-link`: they send something to Apple or to people.

## If something goes wrong
- "No Account for Team USW8537DGX" or "No signing certificate": in Xcode → Settings → Accounts, check that the account lists the team. Manage Certificates → + → Apple Development; the owner clicks this.
- A signing error about the certificate name or text encoding: the certificate name may have non-ASCII letters. Report the exact error text.
- An upload error saying no app record was found: step 7 isn't done yet.
- "Invalid bundle … orientations … iPad multitasking": `UISupportedInterfaceOrientations~ipad` must list all four orientations (it does since 2026-11-24).
- `devicectl` can't see the phone: unlock it, connect the cable (or use the same Wi-Fi), and tap Trust on the phone.
- A test failed in `sim`: run it once more. If it fails again, report the test name and its failure text. For the accessibility audit, also report the "Accessibility issue:" lines it prints.
- The Xcode build fails in "Build the Kotlin screens (HouseMoveKit)": report the lines after "What went wrong" or starting with `e:`. "Unable to locate a Java Runtime", or Gradle saying the SDK location is not found: run `tools/ios.sh setup` (and `doctor`) again. If Xcode itself was opened from the Dock, it uses `~/Developer/tools` too.
- Gradle stuck or out of memory after many builds: `cd moving-app && ./gradlew --stop`, then try again.
