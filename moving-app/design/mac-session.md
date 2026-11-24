# Instructions for a Claude Code session on the owner's Mac

You are running on the owner's Mac, in `~/Developer`. A cloud session works on the Kotlin side of HouseMove. It cannot reach this Mac, so you do the parts that need it: Xcode signing, the simulator, the paired iPhone and the TestFlight upload. The plan is in `moving-app/design/ios-plan.md`; the build script is `moving-app/tools/ios.sh`. Signing uses the Apple Account already signed into Xcode (team USW8537DGX), with automatic signing.

## Rules
- Never ask for, type, read or store passwords, 2FA codes, or any .p8/.p12 file or its contents. When macOS or Xcode asks for a password, the owner types it into the system dialog.
- No `sudo`. Install tools only under `~/Developer/tools`; `tools/ios.sh setup` does this.
- Let automatic signing create certificates and profiles. Don't revoke anything or change Xcode account settings unless the owner asks.
- Don't push to `claude/intelligent-brown-mk8754`: the cloud session works on it. If a file in the repo must change for the Mac build to work, commit it on a new branch `mac/<short-topic>`, push that branch, and give the owner the branch name.
- In summaries, describe the iPhone by its name, model and iOS version. Leave out its serial number, IMEI or UDID; it's fine to use them in commands.

## Steps
1. Clone and check out:
   `git clone https://github.com/sharooncs/coderabbit.git && cd coderabbit && git checkout claude/intelligent-brown-mk8754`
   If git complains about the Xcode licence, ask the owner to open Xcode once and agree.
2. `moving-app/tools/ios.sh doctor`: report each section. That is: the Xcode version, whether first launch and the licence are done, whether team USW8537DGX is signed in, the certificate names, and the paired iPhone with its Developer Mode state. (Detecting the team is a heuristic. If it says "not seen" but Xcode → Settings → Accounts shows the team, carry on.)
3. `moving-app/tools/ios.sh setup`: installs XcodeGen 2.48.0 into `~/Developer/tools`.
4. `moving-app/tools/ios.sh sim`: builds the app and runs its 3 UI tests in an iPhone 17 Pro simulator. All 3 must pass; screenshots are in `moving-app/iosApp/build/shots`.
5. `moving-app/tools/ios.sh phone`: a development build installed straight onto the paired iPhone, then opened.
   - The first time, automatic signing makes an Apple Development certificate and registers the App ID `app.housemove.ios` and the phone.
   - macOS may ask for the login password so codesign can use the key. The owner chooses "Always Allow".
   - The iPhone must be unlocked.
   - If it says "Untrusted Developer": Settings → General → VPN & Device Management → trust the developer.
   - If Developer Mode is off: Settings → Privacy & Security → Developer Mode → On, then restart.
   - Ask the owner to confirm what the phone shows: the house mark, "HouseMove", the explanation line and "Test build 0.1 (1)".
6. `moving-app/tools/ios.sh archive`: a signed Release archive. Report the `Authority=` and `TeamIdentifier=` lines it prints. Then tell the owner: **"bundle ID registered"**.
7. The owner creates the app record in App Store Connect:
   - Apps → + → New App: iOS, name "HouseMove - Moving Planner", primary language, bundle ID `app.housemove.ios`, SKU `housemove-ios`, Full Access → Create.
   - Then TestFlight → Internal Testing → + → group "Me" → tick "Enable automatic distribution" → Create → invite themselves.

   If you have a browser tool, you may offer to do this with the owner watching. They handle any sign-in.
8. When the owner says the app exists: `moving-app/tools/ios.sh upload` signs for App Store Connect and uploads. Apple processes the build in about 5–30 minutes. It then appears in the TestFlight app on the iPhone (the owner installs TestFlight from the App Store if needed).
9. Finish with a short report the owner can paste back to the cloud session. Include:
   - the doctor summary;
   - each step as pass or fail, with the exact error text;
   - the version and build number uploaded;
   - any `mac/…` branch you pushed.

## If something goes wrong
- "No Account for Team USW8537DGX" or "No signing certificate": in Xcode → Settings → Accounts, check that the account lists the team. Manage Certificates → + → Apple Development; the owner clicks this.
- A signing error about the certificate name or text encoding: the certificate name may have non-ASCII letters. Report the exact error text.
- An upload error saying no app record was found: step 7 isn't done yet.
- `devicectl` can't see the phone: unlock it, connect the cable (or use the same Wi-Fi), and tap Trust on the phone.
- A test failed in `sim`: run it once more. If it fails again, report the test name and its failure text.
