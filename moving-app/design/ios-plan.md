# HouseMove for iPhone: plan and owner instructions

After three audit rounds (Kotlin side, Apple delivery, what the agent can verify; then technical consistency and an owner walkthrough; then a final blocker check), a spike (iOS Kotlin compiles and shared screens render on Linux) and a probe of GitHub's Xcode 27 runner. Reviewers' notes are not in the repository.

## Decisions built in
- Kotlin Multiplatform + Compose Multiplatform: shared data, sync and screens; thin Swift for what Kotlin cannot call (VisionKit scanner, camera, later CryptoKit and speech), each with a simulator fake.
- Signing and upload happen on the owner's Mac through Remote Control, using the Apple Account signed into Xcode (xcodebuild uses it; Xcode need not be open). An App Manager API key kept only on the Mac (~/.appstoreconnect/private_keys) lets the agent read build status and rejections, manage tester groups, submit for Beta App Review, create the public link and read TestFlight feedback. No Apple secret is stored in GitHub or sent in chat. GitHub CI (free `xcode-27` runners: macOS 27.0.1, Xcode 27.0/27.1/27.2, iOS 27.0–27.2 simulators, 3 cores, 7 GB) builds and tests without secrets. Fallback only if the Mac route fails: an App Manager key + Apple Distribution .p12 in a GitHub environment with the owner as required reviewer.
- First iPhone version (v1): rooms (incl. rename), items, boxes and containers, photos, QR/barcode scanning, label-sheet PDF, roles and joining, Wi-Fi sync with Android 2.x phones, manual backup share. Later: encrypted update files (send/open), daily folder backup, speech button (keyboard dictation meanwhile), signs/reports/Helper-card/Ask-for-decisions PDFs, book title lookup. v1 declares no non-exempt encryption; revisited when update files arrive.
- iPhone only, iOS 26 minimum. TestFlight: internal (owner) first; external group with public link for family later (needs Beta App Review). Builds expire after 90 days: the agent schedules itself a rebuild reminder at ~60 days.

## What the agent does and verifies itself
- Linux: all Android/JVM tests, the seven-phone simulation, golden fixtures, compiling every iPhone-side Kotlin file (klib cross-compilation, proven), rendering every shared screen at 1206×2622 to PNG and looking at it (proven), Android device tests in CI.
- GitHub CI (no secrets): iOS framework link, iOS-simulator unit tests incl. golden fixtures, XCUITest flows, accessibility audit, screenshots (light/dark, largest text) pushed to a `ci-shots` branch the agent fetches, simulator↔JVM-peer sync against the released Android 2.1.1 code. iOS changes skip the Android emulator jobs.
- On the Mac via Remote Control: install a JDK, the Android SDK (platform 37; the Android plugin needs it even for iPhone builds) and XcodeGen into ~/Developer with curl, without admin rights; set DEVELOPER_DIR if needed; check the team and certificate names for non-ASCII letters (Xcode 27 cloud-signing bug, then an Apple Distribution certificate from Xcode → Accounts instead); generate the Xcode project (XcodeGen), build in 1–3 minutes, run the simulator, archive, sign and upload to TestFlight, install on the paired iPhone with `xcrun devicectl` and read its live log during the owner's tests.
- Cannot do: anything in the owner's Apple account in a browser, physical iPhone use (camera, printed labels, real Wi-Fi with an Android phone, look and feel), Beta App Review.

## Work order (agent), sizes in working sessions; Android stays releasable throughout
0a Golden fixtures from 2.1.1 (1): sync byte transcripts (emoji, NUL, long strings), seal values, label ranges and letters (String.hashCode), merge tie-break strings with Doubles (0.1, 1e7, 1e-3, 1e-4, -0.0, 1e21), lineId with İ/Σ/ß, derived move IDs, refused IDs, hex, invite URL encoding, lone surrogates.
0b Toolchain (1–2): Kotlin 2.4.21, AGP 9.3.1, Gradle 9.7.0; fixtures green.
0c Split (2): `:shared` (AGP 9 KMP library) with data/ and sync/ only, verbatim, tests moved; ui/ and pdf/ stay in `:app` until 2b.
0d Compose upgrade on Android alone (1): compileSdk 37, the Compose that CMP 1.12 brings, screenshots re-recorded, audits re-checked.
0e iPhone shell + first internal TestFlight (2–3): SwiftUI App/WindowGroup (UIScene) hosting ComposeUIViewController, static framework, privacy manifest, ITSAppUsesNonExemptEncryption = NO (Sealed.kt and Keys.kt kept out of the iOS build; no library bringing its own crypto), icon, usage strings, 120 Hz key, real icons and one heavy screen linked to measure the runner. Gate G0.
1 Data and sync shared (6–8): locks, blocking socket interface (iOS POSIX sockets, getifaddrs; cellular/VPN interfaces filtered), modified-UTF-8 codec, QR encode, zip reader/writer limited to what Android reads, kotlinx-datetime, secure random, deterministic values; fixtures pass on JVM and iOS simulator. (Update-file crypto deferred.)
4 Cross-platform sync in CI (2): simulator ↔ JVM peer on released 2.1.1 code. Gate G1 (agent-only).
2 Screens shared (8–12): one group at a time; icons as source; Coil 3; JetBrains navigation/lifecycle; fonts via resources; desktop target for local rendering and audits. Gate G2.
3 iPhone features (6–10): scanner, camera, label PDF, share sheet/AirDrop, Keychain (ThisDeviceOnly) with reinstall marker and backup exclusion, local-network denial handling with a Settings link, idle timer off while syncing, background task for transfers; JPEG photos. Gate G3, then real cross-platform sync Gate G4.
5 Family TestFlight (1–2): external group, test information, Beta App Review, public link; platform-aware version-mismatch message and "Share the app" (iPhones get the TestFlight link). Gate G5.
v1 total about 28–35 sessions; the deferred features about 15–25 more.

## Owner instructions
Never send in chat: passwords, 2FA codes, any .p8/.p12 file or its text. Fine to send: "done", Team ID, app name, Apple's emails, screenshots.

### Now (one sitting, about 45 minutes)
1. Mac: install or update Xcode 27 from the App Store, open it once, agree to the licence, let it install the iOS 27 platform. (Before the licence is agreed, even `git` refuses to run.) I'll tell you if a particular 27.x build has upload problems.
2. developer.apple.com/account: accept any agreement banner; note the Team ID (Membership details). App Store Connect → Business: accept anything pending (New App is blocked otherwise). Send me the Team ID.
3. Approve the bundle ID `app.housemove.ios` and the App Store name "HouseMove" with a fallback such as "HouseMove – Moving Planner" (names are unique across the store; the home-screen name comes from the app itself).
4. Register the bundle ID: developer.apple.com/account → Certificates, IDs & Profiles → Identifiers → + → App IDs → App → Explicit, Bundle ID `app.housemove.ios`, description "HouseMove", no extra capabilities → Register.
5. App Store Connect → Apps → + → New App: iOS, name, language, the bundle ID from the list, SKU `housemove-ios`, Full Access → Create. Name taken: use the fallback.
6. In that app: TestFlight → Internal Testing → + → name "Me" → tick "Enable automatic distribution" → Create → Invite Testers → tick yourself → Add. (There is no default group; without this no build reaches you.)
7. App Store Connect → Users and Access → Integrations → App Store Connect API. If it shows "Request Access", request it (Account Holder only) and tell me if it is pending. Then Team Keys → + → name "HouseMove Mac", Access **App Manager** → Generate → Download API Key (one chance; missed it: revoke and make another). On the Mac move the downloaded AuthKey_….p8 into a folder named `private_keys` inside a folder `.appstoreconnect` in your home folder (Terminal: `mkdir -p ~/.appstoreconnect/private_keys && mv ~/Downloads/AuthKey_*.p8 ~/.appstoreconnect/private_keys/`). Send me the Key ID and the Issuer ID (these two are identifiers, not secrets); never send the file.
8. Xcode → Settings → Accounts → + → Apple Account: sign in with the developer account.
9. Terminal: `mkdir -p ~/Developer && cd ~/Developer && git clone https://github.com/sharooncs/coderabbit.git` (not Documents, Desktop, a hidden folder or ~/Library).
10. Connect the iPhone with a cable, tap Trust. Xcode → Open Developer Tool → Device Hub → the iPhone → Pair. On the iPhone: Settings → Privacy & Security → Developer Mode → On → restart → Enable (the switch appears only once pairing has started). Pair again after each iOS update.
11. Claude desktop app on the Mac (update it first): Settings → Claude Code → Remote Control: turn it on, add only the folder ~/Developer/coderabbit, keep the Mac awake while we work. On the Windows PC, remove System32 and bin from its Remote Control list (they are not usable from here and should not be shared). Send "Mac ready"; when a card asks to let this session work in coderabbit on your Mac, approve it.
12. iPhone: install TestFlight from the App Store.

### First iPhone build (step 0e; I'll tell you the time)
13. Be at the Mac when I start the first archive: macOS asks once for your login password so the build can use your signing key; choose "Always Allow". If Xcode ever says the session expired, sign in again under Xcode → Settings → Accounts.
14. The build arrives in TestFlight automatically (your "Me" group): open TestFlight on the iPhone → Install. Nothing within 2 hours: tell me (I can install a development build directly from the Mac instead).
15. Gate G0 checklist: app opens; the HouseMove font shows; nothing hides under the Dynamic Island or the home bar; Home looks like the picture I send. Reply pass/fail per line.

### Gates later (I send the checklist each time; reply pass/fail per line, screenshots welcome)
- G2 screens: every tab, scrolling, back swipe, typing, largest text size, VoiceOver.
- G3 features: camera and torch; a printed label sheet at 100 %; an ISBN, an EAN and a serial barcode in dim light; backup by AirDrop; delete and reinstall, then "Lost your phone?".
- G4 real sync: iPhone + an Android phone on home Wi-Fi, an Android hotspot and an iPhone hotspot; refuse then allow local network; 200 photos with auto-lock on; a Helper joining.
- G5 family: in the app's TestFlight → Test Information you type your feedback email and the review contact yourself (I prepare the description); I create the external group, submit for Beta App Review and make the public link through the App Manager key; you send relatives the link; a relative's oldest supported iPhone installs it.

### At the end of the move
Turn Developer Mode off, remove the Remote Control folder, and revoke the App Manager key (Team Keys → Revoke) and any key made for the fallback.
