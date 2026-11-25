# Encryption: update files on the iPhone, and encrypted sync

Two parts. **Part 1 is built** (branch `crypto`): the iPhone has the same update-file crypto as Android, byte for byte,
through a small interface whose Android side is the JCA code Android has run since 2.1 and whose iPhone side is
CryptoKit. **Part 2 is built** (branch `enc-sync`, Android 2.4, versionCode 12; the iPhone build stays 0.1): an
encrypted, mutually authenticated channel for phone-to-phone sync, which replaces 2.3's Wi-Fi keys and pair keys. **The
mailbox is built** (branch `mailbox`, Android 2.5, versionCode 13; the iPhone build stays 0.1): phones that are not on the
same Wi-Fi keep each other up to date through sealed messages (HPKE) left in a small program on the owner's always-on
Windows laptop, and update files are sealed the same way (format 2); "Mailbox as built" says what was built.

## Part 1: crypto on the iPhone, byte-compatible with Android

### In short

- The update files (`Sealed.kt`, `Update.kt`) are common Kotlin now, in `shared/src/commonMain`. What they need of the
  platform is one interface, `PlatformCrypto` in `data/Crypto.kt`.
- Android and the JVM: the interface is today's JCA calls (`Crypto.jvm.kt`, the former `Keys.kt` and the JCA half of
  `Sealed.kt`), with `java.util.Base64` and `java.util.zip` as before. Android's update files are the same bytes: the
  golden fixtures `crypto.json` and `update.json` pass untouched, and so does everything else in `golden/`.
- The iPhone: `iosApp/HouseMove/CryptoKitCrypto.swift`, about 120 lines of Swift over CryptoKit (`P256.KeyAgreement`,
  `AES.GCM`) and the Keychain. The app hands it to the shared code at start-up. The phone's private key is in the
  Keychain, readable after the first unlock and on this device only; a reinstall marker makes a reinstalled app start
  with a new key.
- So the shared code on the iPhone can open an update file an Android phone sends it, write one an Android phone opens,
  and the iPhone's phone entry carries a real public key (`Phone.keys`), which Android phones seal updates to.
- What is not built: the iPhone's screens have no "Send update" or "Open update" yet (the iPhone build is still the
  read-only joining build). The capability is in the shared code and checked; the buttons, the share sheet and the
  document type come with the iPhone's screens (plan step 2).

### The interface

`data/Crypto.kt` (common):

| Member | What | Android / JVM (`Crypto.jvm.kt`) | iPhone (`Crypto.ios.kt` + Swift) |
| --- | --- | --- | --- |
| `AgreementKey.publicDer` | a P-256 public key as Android sends it: X.509 SubjectPublicKeyInfo, DER (91 bytes) | `PublicKey.encoded` | the 26-byte X.509 prefix + CryptoKit's `x963Representation` (`P256Der`) |
| `AgreementKey.agree(peerDer)` | ECDH: the x-coordinate, 32 bytes; null when the peer is not a P-256 key | `KeyAgreement("ECDH")` | `sharedSecretFromKeyAgreement`, raw bytes |
| `PlatformCrypto.generateKey()` | a one-use key pair (each recipient's wrapped key) | `KeyPairGenerator("EC", secp256r1)` | `P256.KeyAgreement.PrivateKey()` |
| `isPublicKey(der)` | can this platform agree a key with it? | `KeyFactory("EC").generatePublic(X509EncodedKeySpec)` | the X.509 prefix, then `PublicKey(x963Representation:)` |
| `sealGcm(key, nonce, aad, plain)` | AES-256-GCM, 128-bit tag; ciphertext then tag | `Cipher("AES/GCM/NoPadding")` | `AES.GCM.seal(_:using:nonce:authenticating:)`, `ciphertext + tag` |
| `openGcm(key, nonce, aad, sealed)` | the opposite; null when it does not open | the same, `AEADBadTagException` → null | `AES.GCM.SealedBox(nonce:ciphertext:tag:)`, `AES.GCM.open` |
| `phoneKeys(phoneId, file)` | this phone's key pair, kept by the platform (`UpdateKeyPair`: public text, when it was made, `agree`) | `PhoneKeys.inFile(file)`: X.509 and PKCS#8 in `no_backup/phone-key.json`, as since 2.1 | private key in the Keychain under the phone ID; `phone-key.json` holds only the public key and its time |

Common, on top of it: HKDF-SHA256 (one block, RFC 5869) and HMAC-SHA256 (`Hmac.kt`, since 2.3), SHA-256 (the
platform's digest), the update-file format, the chunk nonce (8-byte prefix + chunk number, big-endian) and additional
data (SHA-256 of the header + chunk number + last-chunk flag), the header JSON, Base64 (`java.util.Base64` on the JVM,
`JavaBase64` on the iPhone: the same rules, checked against Java on random and spoilt text), and the streams the zip of
the contents goes through.

The private key never crosses the interface: Kotlin sees public keys, shared secrets, content keys and sealed bytes.
(One way in, not out: the crypto check gives CryptoKit the golden fixtures' test keys, which are public in this
repository, through `importKey(raw:)`; the app's own keys are only ever made inside CryptoKit.)
On the iPhone the Swift side carries `Data`; `sharedUi`'s `AppleCrypto.kt` declares the protocols Swift implements
(`AppleCrypto`, `AppleKey`, `AppleKeychainItem`) and `SwiftCrypto` turns `NSData` into the byte arrays of
`shared`'s `AppleCryptoKit`; `Crypto.ios.kt` adds the X.509 form and the Keychain rules (`IosCrypto`).

**Registration.** `StartView.swift` calls `MainViewController(buildLabel:crypto: CryptoKitCrypto())`;
`MainViewController` calls `useAppleCrypto(…)` before any move is opened: the real app with Keychain service
`app.housemove.phone-key` and the no-backup folder `AppFolders.noBackup`; demo mode (`-HMDemoHost YES`) with
`app.housemove.demo-key` and its own folders, which are emptied at every start. Until then `platformCrypto` is null on
the iPhone: no key, and an update says it cannot be opened (`Update.DAMAGED`) or written (`Update.COULD_NOT_WRITE`).

### Can CryptoKit reproduce Android's nonces?

Yes. Android's chunk nonce is the 8-byte random prefix in the header followed by the chunk number as 4 big-endian bytes,
12 bytes in all; the wrapped content key has a random 12-byte nonce. `AES.GCM.Nonce(data:)` takes any caller-chosen
nonce of 12 bytes (or more), and with exactly 12 bytes GCM uses it directly as the IV, as Java's `GCMParameterSpec`
does. CryptoKit's sealed box keeps ciphertext and tag apart; Android's `doFinal` writes the ciphertext, then the 16-byte
tag; `CryptoKitCrypto` joins and splits them the same way. The additional data is passed as `authenticating:`.
Nothing here relies on CryptoKit choosing a nonce. Checked in CI: the app seals the known-answer file byte for byte
(below), which only works if every chunk's nonce, additional data and tag layout are Android's.

One difference, on purpose: Java's GCM takes nonces of any length (it hashes a longer one); the iPhone takes only 12
bytes. Android never writes another length, so this only refuses hand-made files.

### The phone's key on the iPhone (Keychain)

- **Where.** A generic-password item: `kSecAttrService` = `app.housemove.phone-key`, `kSecAttrAccount` = the phone ID,
  `kSecValueData` = the 32-byte private scalar (`rawRepresentation`), `kSecAttrAccessible` =
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: readable by HouseMove after the first unlock since the phone
  started, never synced to iCloud Keychain, never restored to another device. No file holds it, so nothing that backs up
  does. `phone-key.json` in the no-backup folder (beside `phone-id`, kept out of iCloud backups) holds the public key and
  when it was made, which the phone's entry in the move carries (`PhoneEntry.key`, `keyAt`).
- **Found or made.** The first time `Phone.keys` is used: the Keychain is asked for the item; if there is none
  (`errSecItemNotFound`), a new key is made and added. If the Keychain cannot be read now (`errSecInteractionNotAllowed`
  before the first unlock, or any other status), the phone has no key *for now*, replaces nothing, and asks again the next
  time the key is used. If the item is there but `phone-key.json` was lost, the key is kept and the file written again.
- **Reinstall marker.** The Keychain outlives the app: deleting HouseMove leaves its items. Each item is under its own
  phone ID, and a reinstalled app has a new phone ID (it is in the app's container), so a stale key can never be found.
  On top of that, `keychain-marker` in the no-backup folder marks "this install has cleared the Keychain": at start-up,
  when it is missing (first start after installing, or after a restore, since the folder is not backed up), every item
  under the service is deleted, then the marker is written. If the Keychain cannot be used at that moment, the marker
  waits for a start when it can. Demo mode and the crypto check use services of their own, so they never touch the
  phone's key.
- **Why not the Secure Enclave.** `SecureEnclave.P256.KeyAgreement` would keep the private key in hardware, but it does
  not exist in the simulator (where CI runs) and needs a separate code path; the Keychain item with
  `ThisDeviceOnly` already keeps the key off backups and other devices. Worth revisiting with Part 2.

### How it is tested, and how CI runs the real CryptoKit

Kotlin tests on the iPhone simulator (`:shared:iosSimulatorArm64Test`) cannot call Swift, so CryptoKit cannot be
inside them. Both options of the brief are used, for different things:

1. **(a) A reference P-256 and AES-256-GCM in plain Kotlin, in `commonTest` only** (`ReferenceCrypto.kt`: Montgomery
   field arithmetic, Jacobian points, AES-256 and GHASH as written in FIPS 197 and SP 800-38D; about 500 lines). It is
   never compiled into an app (`commonTest` is not part of any framework or APK). `ReferenceCryptoJvmTest` checks it
   against the JCA: AES-GCM on 120 random keys, nonces, additional data and lengths up to 64 KB (sealing equal, tampering
   refused), ECDH and public keys on 24 random JCA key pairs, points off the curve refused, and the golden phones' keys.
   With it, the simulator runs the common update-file code on the golden vectors: `UpdateCommonTest` (every HKDF and
   chunk case of `crypto.json`, the 150,000-byte stream, Android's `update.json` sample opened step by step and through
   `Update.open` on Asha's phone, the known-answer file sealed byte for byte, an update written and opened), and the
   golden syncs now carry real keys. This is the reliable part: it runs in every `tools/test.sh` on the JVM (with the
   JCA) and on the simulator, and a failure names the step.
2. **(b) The real CryptoKit path, in the app**, once per CI run. Launched with `-HMCryptoCheck YES` (only Xcode and the
   UI tests can pass launch arguments), the app runs `CryptoCheck` (shared, `iosMain`) with the `CryptoKitCrypto` it was
   given and shows one line per check; `LaunchTests.testCryptoKitOpensAndSealsAsAndroidDoes` reads them and puts all of
   them in its failure message. The checks:
   - CryptoKit takes Asha's golden private key and gives Android's public key;
   - ECDH, HKDF, the content key and the chunks of Android's update file (`golden/update.json`) are Android's values;
   - `Update.open` on Asha's phone, started from her folder in the fixture, says "Taken in from Priya 🏠: 1 change.";
   - **the known-answer file** (`shared/src/jvmTest/resources/sealing/update-sealed.json`) is sealed byte for byte: the
     inner zip of Android's update, sealed again with a fixed one-use key, content key, nonce prefix, salt and IV, through
     `Update.wrappedFor` and `Update.writeSealed` (what `Update.write` uses). The JVM made and committed it
     (`SealingFixtureTest`), and the JVM opens it on Asha's phone as it opens Android's own; so the iPhone writing the
     same bytes is the iPhone writing a file the JVM, and Android, open. The app then opens it too;
   - the Keychain: a key kept, read back and agreeing; the phone key found again with the same public key and time; the
     reinstall marker clearing old keys once.
   The same `CryptoCheck` runs in the simulator's Kotlin tests with the reference crypto and a Keychain in memory
   (`CryptoCheckIosTest`), so a check that passes there and fails in the app is CryptoKit's or the Keychain's.

   CI builds the app with code signing off (`CODE_SIGNING_ALLOWED=NO`). The simulator may then refuse the Keychain with
   `errSecMissingEntitlement` (-34018). The check then says "Check skipped: the Keychain …" and the UI test prints a note
   instead of failing; every other Keychain status fails. If CI shows the skip, the Keychain is checked by the Kotlin test
   with the in-memory stand-in and on the owner's iPhone (the next test build: an update sent from an Android phone to
   the iPhone opens; that needs the iPhone's screens, plan step 2).

Why both: (a) alone never runs CryptoKit; (b) alone runs it, but a failure in a UI test says less, and the common code
on the simulator would otherwise be tested only through the app. Generating the check data: Gradle's
`cryptoCheckSources` turns `golden/update.json` and the known-answer file into a Kotlin object for `iosMain`; the golden
files are only read.

### Android, unchanged

- The golden fixtures are byte for byte as recorded; none was recorded again. `GoldenFixturesTest` makes `crypto.json`
  again through the common sealing on the JCA, opens the recorded `update.json` sample, and checks a new update's
  deterministic parts.
- Android's update files: the same header JSON, the same Base64 (`java.util.Base64`), the same zip
  (`java.util.zip.ZipOutputStream` through `ZipWriter`, which wraps it in a `BufferedOutputStream`: the bytes are the
  same), the same chunks. Opening: the same order of checks and the same words. The random bytes and one-use keys come
  from one `SecureRandom`, now in `Crypto.jvm.kt` (it was `Sealing`'s); the phone's key pair from `keyRandom`, as before.
  One edge moved: a phone entry with an EC key on another curve (no HouseMove makes one) made `Update.write` throw the
  JCA's `InvalidKeyException`, and now an `IllegalArgumentException`; the screen says "could not be written" for both.
- The release APK: see "Verify" at the end.

### Export compliance (`ITSAppUsesNonExemptEncryption`)

**Not changed.** `iosApp/HouseMove/Info.plist` still says `ITSAppUsesNonExemptEncryption` = `NO`. That is the owner's
declaration; below is what applies and what the owner answers.

What Apple says (fetched 2026-11-25):

- *Complying with Encryption Export Regulations*
  (developer.apple.com/documentation/security/complying-with-encryption-export-regulations): set the key to `NO` "if
  your app — including any third-party libraries it links against — doesn't use encryption, or if it only uses forms of
  encryption that are exempt from export compliance documentation requirements". "Typically, the use of encryption
  that's built into the operating system … is exempt from export documentation upload requirements, whereas the use of
  proprietary encryption is not." And: "If your app uses exempt forms of encryption, you might alternatively be required
  to submit a year-end self-classification report to the U.S. government."
- *ITSAppUsesNonExemptEncryption* (Information Property List reference): `NO` = "either uses no encryption, or only uses
  encryption that's exempt from export compliance requirements"; `YES` comes with `ITSEncryptionExportComplianceCode`,
  the code Apple gives after reviewing uploaded documentation. Without the key, App Store Connect asks the questions for
  every build.
- App Store Connect Help, *Export compliance documentation for encryption*
  (developer.apple.com/help/app-store-connect/reference/app-information/export-compliance-documentation-for-encryption):
  "Your app uses encryption limited to that within the Apple operating system: No documentation required in App Store
  Connect." An industry-standard algorithm *not* provided by Apple's OS: the French encryption declaration, only when
  distributed on the App Store in France. Proprietary algorithms: a CCATS from the U.S. BIS and the French declaration.
- App Store Connect Help, *Overview of export compliance*: apps using "crypto functionality within Apple's operating
  system" still need a determination (the questions); the developer is responsible for reading the EAR; France controls
  "Secure Storage, Secure Communications" apps (ANSSI).
- The App Store Connect API's export declaration names the questions exactly (`AppEncryptionDeclaration` attributes):
  `usesEncryption` ("uses, contains, or incorporates cryptography"), `containsProprietaryCryptography`,
  `containsThirdPartyCryptography` ("implements any standard encryption algorithms instead of, or in addition to, using or
  accessing the encryption in Apple's operating systems"), `availableOnFrenchStore`, and the result `exempt`. The
  questionnaire in App Store Connect asks the same as one question, "What type of encryption algorithms does your app
  implement?", whose last answer is "None of the algorithms mentioned above" (wording as developers quote it; the
  dialog itself needs the owner's login).
- U.S. rules (EAR): since the rule of 26 March 2021 (86 FR 16482, in force 29 March 2021), mass-market encryption items
  self-classified under § 740.17(b)(1) (ECCN 5D992.c for software) need no annual self-classification report, except
  mass-market *components* and their "executable software"; items with non-standard cryptography still need BIS review.
  (Law-firm summaries of the rule found by search: Sidley, Goodwin, Venable, Arnold & Porter. bis.gov and ecfr.gov could
  not be fetched from this session; the owner or a reviewer should confirm there.)
- Unofficial, for expectations only: developers report App Store Connect sometimes asking the questions for a build even
  with the key set to `NO` (Apple Developer Forums thread 802746, October 2025; no Apple answer).

What the iPhone app does with this branch:

- **Encryption:** AES-256-GCM and P-256 ECDH, both CryptoKit (Apple's OS), to keep the family's own move data
  confidential in update files between their phones. Nothing else encrypts.
- **The app's own code:** HKDF-SHA256 and HMAC-SHA256 (key derivation and authentication, common Kotlin) and the update
  file's layout (which nonce and additional data each CryptoKit call gets). SHA-256 is CommonCrypto's (the OS). No cipher,
  no key agreement, no other primitive is implemented in the app or in a library it links (`kotlinx`, Compose; no
  OpenSSL, no Bouncy Castle). The reference P-256 and AES in `commonTest` are test code only and are never in the app.
- 2.3's HMAC for syncing by itself is authentication only; the sync connection itself is not encrypted (Part 2 changes
  that, still with CryptoKit's primitives).

**What the owner answers** (for the first build with update files on the iPhone, in App Store Connect, or by keeping the
key):

1. Does the app use encryption? **Yes**: CryptoKit's AES-GCM and P-256, for the family's own data.
2. Proprietary or non-standard algorithms? **No.**
3. Standard algorithms instead of, or in addition to, Apple's operating system's encryption? **No**: all of it is
   CryptoKit; HKDF and HMAC are key derivation and authentication, not encryption.
4. Available on the App Store in France? The owner's plan. TestFlight is not the App Store; with only Apple's encryption
   no French declaration is needed either way, per Apple's table.

Those answers make the app exempt from documentation: **keep `ITSAppUsesNonExemptEncryption` = `NO`**, which says the
same for every build. No CCATS, no French declaration and, under the 2021 rule, no year-end self-classification report
are expected for a free mass-market app using only the OS's standard encryption. This rests on reading "encryption within
Apple's operating system" as including CryptoKit, Apple's own framework; Apple's pages do not name CryptoKit, and App
Store Connect's examples ("crypto functionality within Apple's operating system") fit it. If the owner wants certainty,
Apple's export compliance team (appstore.ec@apple.com, the address Apple's help points to) answers such questions.

**The prepared change**, for the owner to apply (or not):

- Keeping `NO` (recommended): only a comment, so the next person knows why.

  ```xml
  <!-- Encryption: only Apple's (CryptoKit AES-GCM and P-256 for update files, design/encryption.md). No proprietary or
       non-standard algorithms, none implemented by the app or a library. Exempt from documentation: NO. Owner's
       declaration of <date>. -->
  <key>ITSAppUsesNonExemptEncryption</key>
  <false/>
  ```
- If the owner answers question 3 with Yes (for example after a later version adds its own cipher or a crypto library)
  and App Store Connect then asks for documentation and gives a code:

  ```xml
  <key>ITSAppUsesNonExemptEncryption</key>
  <true/>
  <key>ITSEncryptionExportComplianceCode</key>
  <string>(the code App Store Connect gives)</string>
  ```

Android (the APK on GitHub) uses Android's own JCA provider in the same way; Apple's questions do not apply to it.

## Part 2: design for encrypted sync (2.4 and later)

Built on branch `enc-sync` (Android 2.4). The design is kept as it was reviewed; what was built, the wire formats, the
choices the design left open and where the build differs are in "Part 2 as built", below it.

### Goals

- Phones of a move sync (by themselves and by code) over a channel that is encrypted and mutually authenticated with the
  phones' P-256 keys, the ones on their phone entries.
- A well-known pattern, no new primitives: ECDH P-256, HKDF-SHA256 and AES-256-GCM, the three Part 1 already has on both
  platforms.
- Long-lived keys never in the clear. The phones' keys replace 2.3's Wi-Fi keys and pair keys as the trust root.
- 2.1.1, 2.2 and 2.3 phones keep working by code sync, with plain-words warnings. The golden fixtures stay valid.
- The same identities and sealing serve the internet mailbox.

### What can happen today (2.3), in plain words

| Who | What they can do today |
| --- | --- |
| Someone who can read the Wi-Fi's traffic (an open or shared network, a guest with the password recording packets) | Read everything two phones sync: the whole move, prices and serial numbers included, and photos. Read the move's **Wi-Fi keys and pair keys** as phones hand them over after a sync by themselves; with them, later, connect to any phone of the move as any phone of the move, take a full copy and push changes, until someone is removed (the Wi-Fi key changes) and, with a pair key, even after. Read a code sync's code as the hello sends it. |
| Someone who can also change traffic (a hostile router, ARP spoofing) | All of the above, and change what a code sync carries in either direction while it happens. |
| A stranger on the Wi-Fi without any key | See that HouseMove runs (Bonjour) and the hourly tag. Nothing of the move: the 2.3 handshake refuses them before anything is sent. |
| Someone removed from the move | Keep their copy of the data. Their phone is refused at sync. But: **craft an update file that claims to come from another phone of the move** (the recipients' public keys are in the data; update files are sealed to recipients but say nothing provable about the sender), and the recipient takes it in under the claimed phone's person's rights. |
| A current member acting badly | What their role allows; and, as data is not signed, write records that claim another person made them (the merge checks the claimed author's rights, not who really wrote them). |
| Whoever holds a backup file | Become the phone that made it ("Keep backup files to yourself"). |

### The options compared

| | Noise XX | Noise KK | Noise IK | TLS 1.3, pinned keys |
| --- | --- | --- | --- | --- |
| Needs to know the other's key first | neither | both | the dialler knows the answerer's | both pinned; certificates |
| Round trips before data | 1.5 | 1 | 1 | 1 (+ certificates) |
| A stranger who connects learns | the answerer's static key (a stable identifier, linkable across networks) | nothing (but the answerer must try every member's key: trial decryption) | nothing: the answerer says nothing unless the dialler's first message opens with its own key, and only the answerer's private key can read who is dialling | the server's certificate (identifier) |
| Mutual authentication, forward secrecy | yes | yes | yes | yes |
| Fits HouseMove | dialler need not know whom it dials | not as specified: the answerer does not know who dials | yes, once discovery tells which phone advertises (per-phone tag, below) | two different TLS stacks (Conscrypt; Network.framework, while the iPhone's sync is POSIX sockets in Kotlin), X.509 certificates to make and sign on the iPhone (no API to create one), client-certificate pinning on both, interop testable only between real stacks |
| Code | one common Kotlin implementation (handshake state machine about 300 lines) on Part 1's interface, the same bytes on both phones, golden transcripts as for 2.1.1 | same | same | per platform |

P-256 is not one of the Noise specification's named DH functions (it names 25519 and 448); "Noise-style" here means the
Noise framework (revision 34) with these choices written down: DH = P-256 ECDH, the 32-byte x-coordinate; public keys
sent as 65-byte uncompressed points (so encrypted static keys are 65 + 16 bytes); cipher AESGCM with Noise's nonce
(4 zero bytes and a 64-bit big-endian counter) and 256-bit keys; hash SHA-256 with Noise's HKDF. Protocol names
`Noise_IK_P256_AESGCM_SHA256` and `Noise_XXpsk0_P256_AESGCM_SHA256`. Curve25519 would match the specification exactly
but Android's JCA does not offer X25519 on every Android version HouseMove supports (from 8.0), and the phones' keys are P-256 already.

**Recommendation: Noise.** `Noise_IK_P256_AESGCM_SHA256` for syncing by itself, `Noise_XXpsk0_P256_AESGCM_SHA256` for
code syncs and invite joins between 2.4 phones, and HPKE (RFC 9180) in authenticated mode for sealed messages (update
files from 2.4, and the mailbox). One implementation in common Kotlin on the `PlatformCrypto` of Part 1, the same
bytes on Android and the iPhone, checked with recorded transcripts like the 2.1.1 golden syncs. TLS would bring two
platform stacks, certificates and pinning code on each, and tests only between real devices.

### The design

**Identities.** Each phone's P-256 key pair from Part 1 (`Phone.keys`; Android's since 2.1, the iPhone's from this
branch) is its static key. The private half never leaves the phone (Android: `no_backup/phone-key.json`, as since 2.1;
it could move to the Android Keystore later; iPhone: the Keychain). The same key is used for update files and for the
handshakes; both only ever use it in ECDH with a fresh key from the other side and feed the result to HKDF with
different labels, and both platforms check points are on the curve, so neither gives an attacker anything to use against
the other. (A separate sync key on the phone entry is possible if that is preferred; it changes nothing below.)

**The trust root: pins.** A small `pins.json` per move in the no-backup folder replaces `ring.json`. It maps phone IDs
to the static key this phone accepts for them, and how it learned it:

- *met*: in a code sync or invite join with that phone (the code was seen on its screen, in person), or from a phone it
  met that vouched for it in that meeting;
- *from the move*: first seen on the phone's entry in the move's data (trust on first use).

A pin is never replaced silently. When a phone presents another key than its pin (an Android phone whose key file was
lost makes a new one), syncing by itself with it stops and both phones say: "Asha's phone has a new key. Sync once with
a code to go on syncing by itself." A code sync with any phone that has met it pins the new key there; others take it
when that meeting's record reaches them from an Organiser's or Decider's phone. 2.4's merge also refuses a change of an
existing entry's key unless it comes from such a meeting, as other unauthorised changes are refused today. Who is in the
move is still the move's rules: a pin is used only for a phone that is on someone's list, not retired, whose person is
not removed.

**Discovery.** 2.4 advertises `HN-<phoneTag>-<random>` with TXT `v=2` on the same `_housemove._tcp` type (so the
iPhone's `NSBonjourServices` does not change). 2.3 phones look only for `HM-<moveTag>-…` and ignore it.
`phoneTag = hex(HMAC-SHA256("housemove-discovery-2:" + moveId, "phone:" + hour + ":" + phoneId))[0..16]`: a phone of the
move can tell which phone advertises (it works the tag out for every phone of the move), a stranger cannot, and tags
change every hour. The move ID is not a strong secret (it is in every invite and every copy of the move), so the tag only
says which phone to dial; the handshake is what keeps people out.

**Syncing by itself: `Noise_IK`.** The dialler knows the answerer's static key from its pin.

```
prologue: "HouseMove sync 2" | move ID
D → A   e, es, s, ss   payload: D's phone ID
A → D   e, ee, se      payload: A's phone ID, "OK" or a refusal word
then both have two cipher states; today's sync runs inside them, unchanged
```

A checks that D's static key is its pin for the phone ID in the payload and that the phone is a member; anything else is
closed without an answer. D checks A's answer the same way. After that, `WifiSync`'s exchange runs as now (hello,
`admission`, Helper filtering, merge, photos), with the hello's phone ID required to be the authenticated one, as 2.3
requires it to be the proved one. The wire inside is frames of a 2-byte length and up to 65,535 bytes of AES-GCM
ciphertext, nonces counting up per direction; photos go in many frames. Busy, back-off and who dials are 2.3's.

**Code syncs and joining: `Noise_XXpsk0`** between 2.4 phones. The pre-shared key is
`HKDF(code, salt = "HouseMove code 2", info = move ID)`, from the code in the QR (an 8-character code, about 40 bits,
used once and for ten minutes): only the phone that scanned it gets past the first message, and a passive listener who
later guesses the code learns nothing, since the session keys also need the ephemeral keys. The answering phone tries
each code it is showing (its plain code and any invites: a handful) on the first message's tag, as 2.3 tries its keys
on a proof. Both static keys travel
encrypted, and each phone pins the other's key as *met*. An invite join is the same with the invite's code; the joining
phone's key goes on its new entry, as today.

How a 2.4 phone knows the code's phone speaks 2.4: the code itself. A 2.4 phone shows codes of 10 characters beginning
with `E2` (old phones take any token: `Invite.parse` does not look at it). Scanning an `E2` code, a 2.4 phone speaks
only Noise and never falls back, so an attacker in between cannot talk it down to plain; scanning an old code, it speaks
2.3's `HMSYNC3` byte for byte and warns. A 2.4 phone showing its code to an old phone answers that phone's plain hello
as 2.3 does (the old phone sends the code it scanned), and warns on its own screen.

**Old phones (2.1.1, 2.2, 2.3).** Code sync only, exactly as today (the golden transcripts stay valid). A 2.4 phone
does not speak `HMAUTO1` and does not advertise `HM-…`, so 2.3's Wi-Fi keys and pair keys stop being used the moment a
phone updates; it deletes `ring.json`. Plain words on the 2.4 phone:

- before a code sync with an older phone: "Priya's phone has an older HouseMove, so this sync is not encrypted. Do it on
  a Wi-Fi you trust, or update HouseMove on Priya's phone first.";
- on Sync and backup: "Syncing by itself needs HouseMove 2.4 on Priya's phone too.";
- an update file from an older phone: "This update comes from an older HouseMove, so it cannot show it came from Priya's
  phone. Open it only if Priya sent it to you." (asked once, then opened as today).

**Migration.**

1. 2.4 on Android and the iPhone together. Entries already carry keys (Android since 2.1; the iPhone from Part 1's build,
   which puts its key on its entry at its next sync).
2. First start of 2.4: delete `ring.json`; seed `pins.json` *from the move* with every member phone's key; start
   advertising `HN-…`.
3. Phones on 2.4 sync by themselves with each other at once, with no code: their keys are already in the move's data.
   Each later code sync turns pins into *met*.
4. Phones still on 2.3: code sync with the warnings; once the last one updates, nothing of 2.3's keys is accepted
   anywhere, and a recording of 2.3's traffic opens no door.
5. A phone without a key on its entry (a 2.0 phone that never synced since, an iPhone before Part 1): code sync once.

### The mailbox

The always-on laptop stores and forwards sealed messages; it never holds a key that opens them.

- **Sealing**: HPKE (RFC 9180), `mode_auth`, DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-256-GCM: per recipient phone,
  to its static key, authenticated with the sender's static key, with `info` = "HouseMove mailbox 1" | move ID |
  recipient phone ID. Built from the same three primitives (HPKE's own test vectors include DHKEM(P-256)); CryptoKit has
  HPKE built in from iOS 17, which can serve as a cross-check. The recipient knows from the seal itself which phone sent
  it, with no signature key besides; a removed person can no longer forge a sender (today's update-file weakness).
- **What is sent**: what an update file holds (the move's data as the sender may share it, small photos), merged with
  the rules of a sync, as `Update.open` does. Merges are idempotent and commutative, so a message replayed, duplicated,
  late or out of order changes nothing wrong.
- **Envelopes**: each phone has a mailbox ID (random, registered with the laptop once) and polls it. An envelope is the
  recipient's mailbox ID, a size and the sealed bytes. The laptop sees mailbox IDs, sizes, times and IP addresses; not
  the move, not names, not which phones belong to one move beyond what timing shows. It can drop, delay or replay, but
  not read or change.
- **The connection to the laptop**: HTTPS with the laptop's key pinned in the app, or the same Noise `IK` with the
  laptop as a known party; either way only for the metadata, since the content is sealed end to end.
- Update files from 2.4 can be the same HPKE envelopes in a file (format 2), and 2.4 still opens format 1.

### What changes, before and after (2.4, all phones updated)

| Who | Before (2.3) | After (2.4) |
| --- | --- | --- |
| Reads the Wi-Fi's traffic | the whole move, photos, the Wi-Fi and pair keys (then can pull and push later) | that two phones talked, when, how much; nothing of the move, no key; sessions have forward secrecy |
| Changes the Wi-Fi's traffic | can change a code sync on the way | can only stop syncs; any change is refused by AES-GCM; cannot pose as a phone without its private key |
| A stranger on the Wi-Fi | sees HouseMove runs and the hourly tag | the same, with hourly per-phone tags; a dial is closed unanswered |
| Removed from the move | keeps their copy; can forge update files as another phone | keeps their copy (nothing can take it back); their key is refused everywhere; cannot forge a sender |
| A member acting badly | their role's rights; records claimed as another's | the same: the channel protects against people outside the move, not inside it. Signing records is a later step |
| The mailbox laptop, or whoever breaks into it | (no mailbox) | envelopes and their metadata; never the move |
| A lost or stolen phone | the phone's own lock and encryption; an Organiser removes it | the same; its key is refused once removed; the iPhone's key is in the Keychain, this device only |
| A backup file | becomes its phone | the same; backup encryption is a separate decision |

While any phone of the move is still on 2.3, the "before" column holds for the syncs that phone takes part in.

## Part 2 as built (2.4)

### In short

- **Where.** All common Kotlin in `shared` (`app.housemove.sync`), on Part 1's `PlatformCrypto` and nothing else:
  `Noise.kt` (Noise's CipherState, SymmetricState and HandshakeState for IK and XXpsk0, about 300 lines),
  `SecureChannel.kt` (the encrypted frames), `Pins.kt` (`pins.json`), `AutoSession.kt` (IK for syncing by itself),
  `WifiSync.kt` (XXpsk0 for E2 codes; the plain path untouched) and `AutoSync.kt` (per-phone tags, the new hints). No
  primitive was added to the interface and no Swift was needed: ECDH is `AgreementKey.agree`, one-use keys are
  `generateKey`, AES-GCM is `sealGcm`/`openGcm`, HMAC, HKDF and SHA-256 are common. The iPhone therefore encrypts only
  through CryptoKit, and `ITSAppUsesNonExemptEncryption` stays `NO` (the answers in Part 1 hold: Noise is a way of
  using CryptoKit's ECDH and AES-GCM, not another cipher).
- **Gone from the app:** 2.3's `ring.json` (deleted when a move is opened), its meetings and its handshake (HMAUTO1).
  A 2.4 phone neither advertises `HM-…` nor answers HMAUTO1. The tests keep a faithful copy of a 2.3 phone (`Old23.kt`,
  jvmTest) to play beside 2.4 phones.
- **Unchanged:** the sync's own exchange (`WifiSync`'s hello, admission, Helper filtering, merge, photos) runs inside
  the channel byte for byte as before; the hello has no new field; the merge, the data layout (`SCHEMA` 5) and update
  files (format 1) are as in 2.3. The golden fixtures are untouched and pass.

### Wire formats

Strings are `writeUTF` (two-byte length, modified UTF-8). `[a, b]` is a list of parts, each a four-byte big-endian
length and its bytes (as 2.3's `|`). A *frame* is a two-byte big-endian length and that many bytes (at most 65,535).

**Discovery.** `_housemove._tcp`, instance name `HN-<tag>-<four random letters and digits>`, TXT `v=2`. `tag` = the first 16 hex
digits of HMAC-SHA256(key `"housemove-discovery-2:" + moveId`, `"phone:" + hour + ":" + phoneId`), `hour` = whole hours
since 1970 (UTC), `moveId` = the lowest ID the move is known by. A phone works out every pinned phone's tag for every ID
of the move, an hour either way, so it knows which phone advertises (`AutoSession.whoAdvertises`); a tag of 2.3's form
(`HM-<move tag>-…`) of this move only tells it a 2.3 phone is on the Wi-Fi (the hint below).

**Syncing by itself: `Noise_IK_P256_AESGCM_SHA256`.** The dialler D knows the answerer A's key from its pin.

```
prologue  ["HouseMove sync 2", moveId]   (moveId: the one A's tag was made with; A tries each ID it knows)
D → A     writeUTF "HMAUTO2", then frame( e (65) | AEAD(s) (65+16) | AEAD([D's phone ID]) )        e, es, s, ss
A → D     frame( e (65) | AEAD([A's phone ID, "OK" | "BUSY"]) )                                        e, ee, se
then frames of the channel: WifiSync's exchange (D's hello, with AUTO for the code), D's vouches, A's vouches
```

A answers only a phone of the move (on someone's list, not retired, person not removed) whose key is A's pin for the
phone ID in the payload; anything else is closed without a word. A treats D as proved only once D's first frame opens
(it needs D's private key: key-compromise impersonation with A's stolen key gets nothing). BUSY: one sync by itself at
a time, as 2.3.

**Code syncs and invite joins: `Noise_XXpsk0_P256_AESGCM_SHA256`.**

```
psk       HKDF-SHA256(ikm = code, salt = "HouseMove code 2", info = moveId), one block (as Sealing.hkdf)
prologue  ["HouseMove code 2", moveId]   (the invite's move ID; the hosting phone uses its own)
J → H     writeUTF "HMSYNC4", then frame( e (65) | tag (16) )                                    psk, e
H → J     frame( e (65) | AEAD(s) (65+16) | AEAD([H's phone ID]) )                               e, ee, s, es
J → H     frame( AEAD(s) (65+16) | AEAD([J's phone ID]) ), then frames: WifiSync's exchange      s, se
          (J's hello with the code), J's vouches, H's vouches
```

The hosting phone tries each code it shows (the plain code, its invites and Helper codes, a handful) on the first
message's tag; none opens it: closed, and its screen says a wrong code was tried. The hello's code must be the one whose
key opened the handshake, and each hello's phone ID and key (`Hello.key`) must be the ones the handshake proved (so
the joining phone's key, which goes on its new entry, is the one it proved). **Codes:** a 2.4 phone shows `E2` and eight
of `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (ten characters; older phones' are eight, so never mistaken). Scanning an E2 code
a 2.4 phone speaks only HMSYNC4: a failed handshake is "The sync code was not accepted", never a plain hello (which
would send the code in the clear).

**Old phones.** A hosting 2.4 phone answers a plain `HMSYNC3` hello (any of its codes) exactly as 2.3 does. A 2.4 phone
scanning an eight-character code says the plain hello exactly as 2.3 does. Both say the sync was not encrypted
(`WifiSync.Result.plain`).

**The channel (`SecureChannel`).** Each direction its own key (Noise's Split), the nonce Noise's (four zero bytes and the
frame count, big-endian), empty additional data. A frame holds at most 65,519 bytes of the sync (65,535 with the tag);
one goes on each flush or when full, so photos go in many. A frame that is changed, repeated, reordered, cut or
dropped does not open and the channel ends (the sync then says what a failed connection says). Every 2^20 frames in a
direction (64 GB) both sides apply Noise's Rekey; after 2^40 frames a channel refuses to go on, far before a nonce
could repeat. A sync is a few frames; neither limit is ever reached in practice, and the tests check both with small
values.

**Vouches.** An `int` length and UTF-8 JSON `{"keys":[{"phone":"p…","key":"MFkw…"}]}`: the pins the sender has *met*,
of phones of the move other than the two (at most 500).

**`pins.json`** (the move's no-backup folder, beside the codes):
`{"move":"m…","pins":[{"phone":"p…","key":"MFkw…","how":"met"|"move","at":…,"v2":true}],"older":{"p…":at},"mine":"MFkw…","newKey":false}`.

### Trust, as built

- **Seeding.** Every phone of the move with a usable key on its entry is pinned *from the move* the first time it is
  seen (the first start of 2.4 included). Joining pins the inviting phone and the joining phone each other as *met*.
- **Met.** A code sync or invite join over XXpsk0 pins the other phone's proved key as met, in place of any other (the
  code was seen on its screen). The phone met vouches for the keys it has met: taken as met in that meeting; in a sync
  by itself, only from an Organiser's or a Decider's phone. A vouch is taken only when the key is the one on the
  phone's entry in the data just merged.
- **Never silently.** When an entry's key differs from its pin, the pin stays; the phone is not synced with by itself,
  and Sync and backup says "Asha's phone has a new key. Sync once with a code to go on syncing by itself." The phone
  that made a new key knows it (`pins.json`'s `mine`), and says "This phone has a new key. …" until a phone takes it.
- **Removed or retired.** Unpinned as soon as this phone's data says so; every handshake checks membership against the
  data of that moment, so the refusal is immediate.
- **Downgrade.** E2 codes are Noise only (above). And a hosting 2.4 phone refuses a plain hello carrying one of its E2
  codes from a phone that has synced encrypted with it before (`v2`): HouseMove 2.4 never does that, so it is someone
  who saw the code, posing as that phone.

### Where the build differs from the design, and why

1. **The merge is unchanged.** The design adds "2.4's merge also refuses a change of an existing entry's key unless it
   comes from such a meeting". Changing what a merge keeps would make 2.4 phones and 2.3 phones (which keep the newest
   key) end up with different data and never agree. The same protection is in the pins instead: a key on an entry
   counts for syncing by itself only through a met pin, a vouch from an Organiser's or Decider's phone, or first use;
   it is never taken silently in place of a pin. (Update files still seal to the entry's key, as in 2.3; their format 2
   is the mailbox step.)
2. **No warning for update files from older phones yet.** Update files are still format 1 on every version (HPKE format
   2 belongs to the mailbox step, out of scope), so no update file can show its sender yet, from any phone; the warning
   comes with format 2. The hook: `WifiSync.Result.plain` and `Pins.olderPhones` say which phones are older. (2.5 has
   format 2 and the warning: "Mailbox as built".)
3. **"Both phones say" a new key without a word on the wire.** The design wants both phones to say so; the answering
   phone closes without an answer (as the design also says), so each phone says it from what it knows: the others
   from the data (entry key ≠ pin), the phone itself from its own key having changed.
4. **One rule added:** the downgrade refusal on the hosting phone (above).
5. **Demo mode's keys are in memory** (`useAppleCryptoInMemory`), made by CryptoKit, not kept in the Keychain: an unsigned
   build in CI may be refused the Keychain (-34018), which would leave the demo's phones without keys and unable to
   sync encrypted. The Keychain is still checked by the crypto check.
6. **What the design left open** is fixed as above: the magic words, XXpsk0's prologue, the payloads, BUSY, the
   vouches, the rekey interval and the frame limits, and that the answering phone tries every ID the move is known by.
7. **A 2.4 phone without a usable key** (an iPhone whose Keychain refuses it) cannot sync encrypted: it shows
   eight-character codes and speaks plain, and the other phone then says it has "an older HouseMove". Android always has
   its key.

### Ready for the mailbox

The identities (each phone's static key on its entry), the pins and the vouches are the trust the mailbox needs; HPKE's
`mode_auth` is ECDH, HKDF and AES-GCM, all on `PlatformCrypto`. `Pins.olderPhones` and `Result.plain` name the phones
that would still need format 1. Nothing is sent anywhere but the local network.

### How it is checked

- **Known answers** (`noise/handshakes.json`, recorded by `NoiseFixtureTest` with the JCA): an IK and an XXpsk0 handshake
  with fixed keys, every message, the handshake hash, both directions' keys, and the channel's frames both ways with a
  rekey. `NoiseCommonTest` makes them again byte for byte on the JVM and, with the reference crypto, on the simulator;
  the iPhone app's `CryptoCheck` with CryptoKit (UI test `testCryptoKitOpensAndSealsAsAndroidDoes`); and
  `tools/noise_check.py` with Python's `cryptography` and a Noise written apart (it agrees on every byte).
- **Common tests (JVM and simulator):** `NoiseCommonTest` (both patterns with fresh keys; a changed, cut or longer
  message; another prologue, pre-shared key or responder key; a point off the curve; a replayed first message opens no
  channel; frames changed, repeated, reordered, dropped or cut; the frame and rekey limits; message sizes),
  `AutoHandshakeCommonTest` (a pinned phone let in; a stranger, a removed phone, a retired phone, a phone with a new
  key, another move, 2.3's handshake and a code hello not answered; BUSY; the proved phone must say it is that phone;
  key-compromise impersonation gets nothing; the tags, checked against Python's hmac), `CodeSyncCommonTest` (join and
  sync encrypted with pins met and the joining key on the entry; a wrong code; an E2 code never falls back, whatever
  answers; the downgrade refusal; vouches), `AutoSyncCommonTest` (two and four phones by themselves; a new key taken only
  from a meeting or an Organiser's or Decider's vouch; a removed phone left out; an older phone named; the words).
- **JVM:** `NoiseFixtureTest`; `MigrationTest` (2.3 phones on their keys, one updates: ring.json gone, pins from the move,
  HMAUTO1 unanswered, plain code sync with both saying so; the other updates: by themselves at once, encrypted);
  `AutoSyncOldPhonesTest` (code sync with a 2.2 or 2.3 phone both ways is 2.1.1's eight messages, plain, said);
  `SniffingTest` (below); the seven-phone simulation with phones on 2.2, 2.3 and 2.4 and the invariant "between two
  phones with 2.4 nothing goes in the clear".
- **Sniffing.** A relay between two 2.4 phones records every byte of an invite join, a code sync and a sync by itself,
  and searches it for the move's ID and name, things' names, a serial number in a note, a photo's bytes, people's
  names, both phone IDs, the hello's words, `HMSYNC3`, each phone's public key (point, X.509, Base64) and private key,
  the code and its pre-shared key, and all six session keys: none is there. The same search over a sync with a 2.3
  phone finds the move, the code and the public keys (private keys never travel), so the search works.

## Mailbox as built (2.5)

Built on branch `mailbox` (Android 2.5, versionCode 13; the iPhone build stays 0.1). The design is "The mailbox" above;
what was built, the formats, the choices it left open and where the build differs are here.

### In short

- **The laptop.** `mailbox/` is a small Go program (standard library only) for the owner's always-on Windows laptop. It
  listens on 127.0.0.1 only; Tailscale Funnel gives it a fixed `https://<machine>.<tailnet>.ts.net/` address with a normal
  Let's Encrypt certificate. It stores and forwards sealed envelopes, by mailbox, and never holds a key that opens one.
  Its API, limits, files and log are in `mailbox/README.md`; `design/asus-session.md` is how a Claude Code session on the
  laptop sets it up. The .exe is committed, built reproducibly, with its SHA-256 in the README.
- **HPKE** (RFC 9180), `sync/Hpke.kt`: mode_auth with DHKEM(P-256, HKDF-SHA256), HKDF-SHA256 and AES-256-GCM, in common
  Kotlin on Part 1's `PlatformCrypto` and nothing else (ECDH through the phone's keys, AES-GCM through `sealGcm`/`openGcm`,
  HKDF over the common HMAC). No primitive was added to the interface, commonMain or iosMain. Checked against the CFRG's
  test vectors for exactly this suite, against an HPKE written apart in Python, and in the iPhone app with CryptoKit and
  against CryptoKit's own HPKE.
- **The phones** (`sync/MailboxSync.kt`, shared, so Android and the iPhone): an Organiser scans the laptop's code; the
  address goes into the move's data, the house token to the other phones only inside encrypted syncs and sealed messages.
  Each phone registers a mailbox of its own and puts its address on its entry. A little after a change, and whenever
  another phone lacks something, it seals for each other phone of the move with a pinned key and a mailbox, and posts. At
  the start and every minute while HouseMove is open it reads its mailbox, opens what a pinned phone of the move sealed,
  merges it as `Update.open` does, and deletes it. Beside syncing on the Wi-Fi, never instead of it.
- **Update files of format 2**: the same HPKE, in a file. 2.5 writes format 2 when every phone the file is for is known
  to open it, and opens format 1 and 2. The phone opening one sees which phone made it ("Update from Priya's phone").
- **Unchanged:** the Wi-Fi sync's handshakes, channel and exchange, byte for byte; the Noise code; the golden fixtures; the
  data layout's version (`SCHEMA` 5), so 2.4 and 2.5 phones sync as before. Nothing in the data changes while a move has
  no mailbox.
- **Export compliance:** unchanged, `ITSAppUsesNonExemptEncryption` = `NO`. HPKE is a way of using CryptoKit's ECDH and
  AES-GCM, as Noise is; CryptoKit's own HPKE is called only by the crypto check.

### Wire formats

`[a, b, c]` is a list of parts, each a four-byte big-endian length and its bytes (`AutoSession.joined`).

**The setup code** (the laptop's QR code, `data/setup-code.png`): `HMBOX:1:<house token>:<address>`, the token 64
lower-case hex digits (32 random bytes), the address `https://…` with no slash at the end. The phones also take the
address and the token pasted apart, in either order. An iPhone's Camera opens it in HouseMove (`hmbox` in Info.plist).

**The API** (`mailbox/README.md` has the answers): `PUT /v1/mailboxes/{id}` `{"secret": …}` with
`Authorization: Bearer <house token>`; `POST /v1/mailboxes/{id}/envelopes` (the bytes; no credential: the ID is the
capability to post); `GET …/envelopes`, `GET …/envelopes/{e}`, `DELETE …/envelopes/{e}` and `DELETE /v1/mailboxes/{id}`
with `Authorization: Bearer <read secret>`; `GET /v1/health`. A mailbox ID is 32 hex digits (128 random bits) and the read
secret 64 (256 bits), both chosen by the phone; the laptop keeps only the secret's SHA-256 and compares in constant time.

**The envelope** (what the laptop holds):

```
"HMB1" | enc (65) | head length (4, big-endian) | Seal(aad "HouseMove mailbox 1 head", head) | Seal(aad "HouseMove mailbox 1 body", body)
info = ["HouseMove mailbox 1", move ID, recipient's phone ID]          (the move ID the sender advertises with: the lowest)
SetupAuthS(recipient's pinned point, info, sender's static key): the head is the context's message 0, the body message 1
```

The head is JSON `{"from":"p…","seq":12,"at":<ms>,"ack":7,"schema":5,"access":{"url":…,"token":…,"at":…}}`: the sender's
phone ID (which must be the phone whose pinned key opened it), the message's number from that sender to this recipient,
when it was sealed, the newest of the recipient's messages the sender has opened, the sender's data layout, and the
mailbox's access when the sender has it. The body is a zip of `data.json` (the move as the recipient's person may see it,
`AppData.sentTo`) and `photos/<file>` (small copies). The recipient does not know who sealed it until it opens: it tries
each phone of the move it has a pin for, opening the small head first; the envelope says nothing of its sender.

**An update file of format 2:**

```
"HMUPDATE" | 2 (4) | header length (4) | header JSON
chunks as format 1: (length (4) | AES-GCM(content key, prefix | n, SHA-256(header) | n | last, ≤ 64 KB)) …
0 (4)                                                                   the end of the chunks
trailer length (4) | AES-GCM(content key, prefix | FFFFFFFF, "HouseMove update 2 trailer" | h, trailer JSON)
```

The header is format 1's (`format`, `schema`, `moveId`, `aliases`, `derived`, `from`, `at`, `nonce`) with `to` entries
`{"phone":…,"key":…,"enc":…}`: per phone, `SetupAuthS(its pinned point, ["HouseMove update 2", move ID, phone ID], this
phone's key)`, `enc` the encapsulated key and `key` the context's message 0, the content key sealed with aad the nonce
prefix. `h` is SHA-256(header), then for each sealed chunk `h = SHA-256(h | chunk)`. The trailer is
`{"sums":[{"phone":…,"sum":…}]}`, each `sum` that phone's context's message 1, `h` sealed with aad
"HouseMove update 2 sum". A phone opens its envelope only with the pinned key of the phone the header says it is from (a
phone no longer of the move: the key its entry carries, so an Organiser can still take in a removed person's last
work), checks every chunk, opens the trailer, and takes in nothing unless its own sum is the `h` of what came: another
phone the file was for, which holds the same content key, can make other chunks and seal the trailer again, but cannot
seal another phone's sum.

**Which format.** Format 2 when every phone the file is for is pinned with the key its entry carries and known to open
HPKE (`Pin.hpke`); otherwise format 1, exactly as 2.4 writes it, and the phone writing it says whose phones made it so.

**The vouches after an encrypted sync** gain three fields, which 2.4 reads past: `{"keys":[…],"hpke":1,
"hpkePhones":["p…"],"access":{"url":…,"token":…,"at":…}}`: this phone opens HPKE; the phones of the move it knows open it
(taken with the rule of key vouches: in a meeting from any phone, by itself only from an Organiser's or a Decider's); the
mailbox's access.

**In the move's data** (absent while unset, so a move without a mailbox writes what 2.4 writes):
`"mailbox":{"url":…,"setBy":{"by":…,"at":…,"sum":…}}` (an Organiser's, with a check value over the address, as the move's
settings have; the newest good one wins at a merge) and on each phone entry `"mailbox":{"url":…,"id":…,"at":…}` (the newest
wins; an empty one, with its time, once the phone has stopped using it). `PhoneEntry` writes its text (which merges compare)
as before while it has no mailbox, so the golden fixtures' records.json stands.

**On the phone** (`mailbox.json` in the move's no-backup folder, beside `pins.json`; never in a backup, an update or a
sync): the access (address and house token), this phone's mailbox ID and read secret, and per other phone what was sent and
heard (message numbers, the data's stamp last sent, photos sent and not yet acknowledged, photos it has).

### Trust, as built

- **Who sets it up.** Only an Organiser sets the move's mailbox (the merge refuses anyone else's, as for the move's
  settings). Anyone may scan the same mailbox's code to get the token.
- **The house token travels, but never in the data.** Each phone needs it to register its own mailbox, so it goes to every
  phone of the move, Helpers included: inside the encrypted channel after a sync (the vouches) and inside sealed messages.
  It is never in the move's data, because the data also goes in the clear to phones on 2.3 and older, into backup files
  (not encrypted) and into update files. What the token allows is registering mailboxes on the laptop, within its limits;
  it opens nothing. Someone removed keeps it; the owner can make a new one (`--new-token`) and an Organiser scans it.
- **Sending.** Only to phones of the move as this phone's data has them now (on someone's list, not retired, the person
  not removed), only to a pinned key, never to a key that is not pinned. A phone whose entry shows another key than its pin
  is sealed to its pin (so a key changed in the data does not redirect anything).
- **Receiving.** Only what a phone of the move sealed with its pinned key opens; the head must name that phone. So a
  removed person is shut out at once on the phone that removed them, both ways, and everywhere after the next sync; a
  stranger who knows a mailbox ID can post, but nothing it posts opens.
- **Replays, duplicates, late and out-of-order messages.** Each message carries the whole of what the recipient may see,
  and merging is idempotent and does not care about order, so a message played back late, twice or out of turn changes
  nothing wrong (a test plays back an old message after a deletion: nothing comes back). Message numbers and
  acknowledgements only decide which photos go again.
- **What the laptop sees:** mailbox IDs, sizes, times and addresses (through Funnel), and who registers. Not the move, not
  a name, not a phone ID, not which mailboxes belong to one move beyond what timing shows. It can delete, delay or replay
  envelopes, not read or change them.
- **Echoes.** After taking in a message, a phone sends nothing back unless the sender lacks something of its own (merging
  what it has into what came changes it), so a change costs about one message per other phone, plus relays.

### Where the build differs from the design, and why

1. **HTTPS without pinning the laptop's key.** The design offered pinning or Noise IK to the laptop. Funnel's certificate is
   a normal Let's Encrypt one that Tailscale renews every few months, so a pin would break the mailbox at each renewal,
   and Noise to the laptop would need a key and code on it. The content is sealed end to end either way; someone who could
   pose as the laptop would learn the metadata and a phone's read secret, which lets them read sealed envelopes and delete
   them, nothing more.
2. **An envelope is two messages of one HPKE context**, a small head and the body, so that the recipient, which has to try
   each pinned phone (the envelope does not say who sealed it, so the laptop cannot learn who writes to whom), tries on the
   head and opens the body once.
3. **Update files of format 2 seal the content key per phone, not the whole contents per phone**, with a trailer of sealed
   hashes: the design said "the same HPKE envelopes in a file"; sealing everything once per phone would make a file N
   times larger. The trailer gives the same guarantee for each phone (no other phone the file was for can change it).
   Info is `["HouseMove update 2", move ID, phone ID]`, apart from the mailbox's, so one cannot pass for the other.
4. **One format per file.** A file goes to several phones; with one that does not open HPKE it is format 1 for all (a
   format 1 file cannot carry HPKE envelopes 2.4 would read past without a second pass). The design's warning, asked once
   before opening, is on every 2.5 phone that opens a format 1 file: "This update was made the older way, so it cannot show
   it came from Priya's phone. Open it only if Priya sent it to you." The wording says "made the older way" rather than "from
   an older HouseMove", since a 2.5 phone writes format 1 too when one of its recipients is older; the writing phone says
   which ("…because Omar's phone has not shown it has HouseMove 2.5 yet").
5. **Which phones open HPKE is learnt, not written in the data:** a field on every phone entry would change the golden sync
   transcripts. Each phone keeps it with its pins, from what a phone said inside an encrypted sync, vouches, and what opened.
6. **Messages carry the whole shareable data**, not only what changed since the last acknowledgement: simple and safe
   (nothing lost when a message is), at the cost of the data's size per message (zipped, usually tens of kilobytes).
   Photos are the part that goes once: small copies, until acknowledged, again after 30 minutes otherwise, at most about
   6 MB a message, and only the photos this phone's person took (so a photo is not sent once by every phone that has it).
7. **The read secret and the mailbox ID are chosen by the phone**, so registering again after a lost answer changes
   nothing; the laptop keeps only the secret's hash.
8. **The tools' simulation is not extended to the mailbox.** Its per-merge checks follow the Wi-Fi sync's exchange; a
   mailbox message is taken in through `Update`'s path, which they do not know. The mailbox has its own multi-phone tests
   instead (below); the simulation still runs every Wi-Fi invariant on the 2.5 code.
9. **Demo mode on the iPhone has no mailbox**, so the UI tests see nothing of the internet; the iPhone's mailbox is
   checked by the shared tests, the desktop tests of its model, and on the owner's phone (`design/ios-plan.md`, 1f).

### How it is checked

- **HPKE:** `HpkeCommonTest` (the CFRG's vectors for this suite, mode_auth and mode_base: keys, shared secret, key
  schedule, seven messages each, exports; mode_auth with fresh keys and what it refuses), on the JVM and the simulator;
  `HpkeFixtureTest` and `hpke/housemove.json` (an envelope and a file of format 2 with fixed keys, made again byte for byte
  on the JVM, the simulator and, through CryptoKit, in the app); `tools/hpke_check.py` (Python's `cryptography` and an HPKE
  written apart: the vectors, the envelope sealed and opened, the file written byte for byte and opened); the app's crypto
  check against CryptoKit's own `HPKE.Sender` and `HPKE.Recipient` (UI test `testCryptoKitOpensAndSealsAsAndroidDoes`).
- **Update files:** `UpdateFormat2CommonTest` (format 2 between 2.5 phones, naming the sender; format 1 for a 2.4 phone,
  said; a 2.5 phone asking before format 1; a 2.4 phone refusing format 2; a changed header, chunk or trailer; another
  phone the file was for re-sealing its chunks and trailer; a key that is not the pin; a removed sender's last work);
  the existing update tests and golden fixtures, unchanged.
- **The phones:** `MailboxCommonTest` (on the JVM and the simulator, against an in-process stand-in with the program's
  API): two and three phones, a removal (shut out at once on the remover, then everywhere), a Helper sent no costs, an
  unpinned key and a phone saying it is another, a changed envelope, replays, duplicates and out of order, a laptop off
  and back, a full mailbox, a wiped laptop, photos and their acknowledgements, the switch, who may set it up, the words,
  and a search of every request, answer and envelope for the move. `MailboxGoServerTest` (JVM): three phones over HTTP
  (HttpURLConnection) against the real Go program, built for the test, and its files, request and answer bodies and log
  searched for the move, the read secrets and the token. Screens: `MailboxScreenTest` and `ApartFlowsTest` (Android),
  `MailboxModelTest` (the iPhone's model).
- **The laptop:** `go test` in `mailbox/` (the API, limits, retention, rate limits, a log and files with nothing secret,
  crash-safe files, settings, the setup code, many posts at once, and QR codes module for module ZXing's).

## Risks

- **CryptoKit in CI is checked only in the UI test.** The Kotlin tests on the simulator use the reference crypto. If the
  UI test does not run (the Xcode steps fail earlier), CryptoKit is not exercised; the CI log's "Failed tests" step says.
- **The Swift was not compiled here** (no Xcode on Linux). The Kotlin it implements compiles for both iPhone targets;
  the Swift follows the names Kotlin/Native gives (`generateKey()`, `importKey(raw:)`, `isPublicKey(x963:)`,
  `seal(key:nonce:aad:plain:)`, `open(key:nonce:aad:sealed:)`, `keychainKey(service:account:create:)`,
  `keychainForget(service:)`, `agree(peerX963:)`, `publicX963`, `status`, `key`), with `Data` for `NSData` and `Int32` for
  `Int`. A naming slip shows as a Swift compile error in CI's first Xcode step.
- **The Keychain may not be checkable in CI** (unsigned builds, -34018). Then only the in-memory stand-in and the owner's
  iPhone check it.
- **No iPhone buttons yet** for sending or opening update files; the capability waits for the iPhone's screens.
- **Update files do not authenticate their sender** (today, on Android too): anyone with the move's data can make one
  that claims to be from any phone. Part 2's HPKE `mode_auth` fixes it; until then an update file is as trustworthy as
  whoever sent it through the messaging app.
- **The reference crypto is test code.** It is not constant-time and must never move into `commonMain`; Gradle keeps
  `commonTest` out of every app.
- **Export compliance** rests on CryptoKit counting as "encryption within Apple's operating system" (Apple's pages do
  not name it) and on the 2021 BIS rule as summarised by law firms (bis.gov could not be fetched here). The owner
  declares.
- **Part 2 (2.4): Noise over P-256 is not one of Noise's named suites.** The choices are written down above and the
  recorded transcripts agree with a separate implementation (Python's `cryptography`), but no third party has
  interoperated with it. Both ends are always HouseMove's own code, so that matters only for review.
- **Part 2: the real CryptoKit runs the handshakes only in the crypto check** (the UI test), as for update files; the
  demo's sync (UI test) runs them too, between two CryptoKit keys. A failure there that the simulator's tests do not show
  is CryptoKit's.
- **Part 2: only real phones show** NSD and Bonjour across a router with the new `HN-…` names and `v=2`, a phone that
  loses its key file (Android: Clear storage) and the new-key words, and a 2.3 phone beside 2.4 phones on one Wi-Fi.
  The owner's list is in `design/ios-plan.md` (1e).
- **Part 2: a phone's static key is in a file on Android** (`no_backup/phone-key.json`, as since 2.1, out of backups):
  whoever can read the app's private files (a rooted phone) can pose as that phone until it is removed. Moving it to the
  Android Keystore is a later step (the iPhone's is in the Keychain).
- **Part 2: a member acting badly** can still change another phone's entry key in the data (as any member can change
  records today); the pins do not follow it silently, so syncing by itself with that phone stops (and says so) rather
  than trusting the forged key; a code sync puts it right.
- **Part 2: a plain hello with an E2 code from a phone not known to speak 2.4** is still taken (the older phone it says
  it is might be one): someone who saw the code on the screen can join or sync as a new phone that way, as anyone with
  the code could in 2.3. The code is shown in person, lasts ten minutes, and an invite works once.
- **Mailbox: Funnel's sender address.** The rate limits go by the last `X-Forwarded-For` entry of a connection from the
  laptop itself, which Tailscale's Funnel proxy adds. Should a Tailscale version not add it, every request counts as
  127.0.0.1 and the limits hold for all senders together, which a family's phones stay well within; the laptop's log
  cannot say which (it keeps no addresses), and only the real Funnel shows it.
- **Mailbox: the laptop.** Whoever runs or breaks into it sees mailbox IDs, sizes, times and addresses, can delete, delay
  or replay envelopes, and can register mailboxes; it cannot read or change one. A phone's read secret travels to it over
  HTTPS without a pin (see above): someone who could pose as the laptop learns the same, and no more.
- **Mailbox: a member acting badly** can put another mailbox on another phone's entry (its messages then go where they
  cannot be read, until that phone puts its own back at its next round), say a 2.4 phone opens HPKE (update files of
  format 2 it then cannot open, and says so), or keep the house token after being removed (registering mailboxes within the
  laptop's limits, until the owner makes a new token). None of it reads or changes the move.
- **Mailbox: always-on.** The laptop must not sleep, Tailscale must run unattended and the program start without a
  login; `design/asus-session.md` sets that up, and only the laptop itself shows that it holds (a restart, the lid shut).
- **Mailbox: what only real phones and CI show.** NSURLSession on an iPhone (the Kotlin compiles here, and the simulator's
  test uses the in-process stand-in), CryptoKit's own HPKE against HouseMove's (the crypto check in CI; the Swift was not
  compiled here), Funnel's certificate and its X-Forwarded-For, mobile networks, a phone woken from sleep, and the
  battery and data a poll a minute while HouseMove is open costs.

## Verify, the mailbox (2026-11-25, on Linux, branch `mailbox`)

- `tools/test.sh`: 583 tests, 0 failed (shared 401, app 166, sharedUi 16). New: `HpkeCommonTest` (5), `HpkeFixtureTest`
  (1), `UpdateFormat2CommonTest` (5), `MailboxCommonTest` (9) (the common ones also on the simulator),
  `MailboxGoServerTest` (1, against the real Go program, built for the test), `MailboxScreenTest` (4),
  `MailboxModelTest` (1), and one more in `ApartFlowsTest`. Two existing tests changed what they expect, for what 2.5
  does: `CodeSyncCommonTest` (the pin now records that the phone opens HPKE) and `ApartFlowsTest` (an update from a 2.5
  phone says "Update from Dee's phone").
- `go test` in `mailbox/`: 14 tests, all pass (`go vet` and `gofmt` clean). `mailbox/build.sh check`: the committed
  `dist/housemove-mailbox.exe` (Windows amd64, 6,496,256 bytes, SHA-256
  `8c00e9bab2ca1dc6a622da3d415b2b184f29ccda4dd653a2e0f7772505b3d85b`) is what the source builds, also from another folder.
- `tools/hpke_check.py`: the CFRG's vectors (14 messages, keys, schedules, exports) and HouseMove's envelope and update
  file of format 2 agree with Python's `cryptography` on every byte. `tools/noise_check.py` still agrees with the Noise
  transcripts.
- The golden fixtures are byte for byte as at 0c9c089 (`git diff 0c9c089 -- shared/src/jvmTest/resources/golden` is empty);
  none was recorded again. `noise/handshakes.json` and `sealing/update-sealed.json` are unchanged too.
- `tools/simulate.sh 50`: 0 seeds broke an invariant (phones on 2.2, 2.3 and the current code; 3,809 syncs, 801
  encrypted code syncs and 224 by themselves); the 5 seeds with phones working while they sync held every invariant.
- `tools/checks.sh` (0 findings but the one text field on search, as before) and `tools/roles.sh` (nothing found on core
  screens; the same "alone" notes as before) pass with the new section on Sync and backup.
- `:app:assembleRelease`: versionCode 13, versionName 2.5, the same signing certificate (SHA-256 afc48c1a…ab00a3);
  `aapt dump badging` differs from 2.4's only in the version (the same permissions: camera and internet). `classes.dex`
  and `classes2.dex` are byte for byte 2.4's; `classes3.dex` is 162 KB larger (HPKE, the envelopes, the engine, the HTTP
  client, format 2, the screens); no test class (no `MailboxStandIn`, `HpkeKat`, `ReferenceCrypto`). The APK is 17,320,764
  bytes (2.4: 17,255,228), SHA-256 `799fc4facbfd87c804593efc39d7b9c1c57dd9529d7f05d6f6f96c495007bfd2`, in
  `releases/HouseMove-2.5.apk` and `HouseMove-latest.apk`.
- iOS: `:shared:compileKotlinIosArm64`, `:shared:compileKotlinIosSimulatorArm64`, `:shared:compileTestKotlinIosSimulatorArm64`,
  `:sharedUi:compileKotlinIosArm64` and `:sharedUi:compileKotlinIosSimulatorArm64` succeed (NSURLSession included). The
  Swift (CryptoKit's HPKE for the crypto check), the simulator tests and the UI tests run in CI only.
- **The iPhone encrypts only through `PlatformCrypto`:** HPKE in `sync/Hpke.kt` calls `agree` (through the phones'
  `NoiseKey`), `generateKey`, `sealGcm` and `openGcm`; HKDF and HMAC are common; no cipher, curve or hash is written in
  commonMain or iosMain. CryptoKit's own HPKE is called only by the crypto check.

## Verify, Part 2 (2026-11-25, on Linux, branch `enc-sync`)

- `tools/test.sh`: 556 tests, 0 failed (shared 380, app 161, sharedUi 15). New or rewritten: `NoiseCommonTest` (11),
  `CodeSyncCommonTest` (5), `AutoHandshakeCommonTest` (10), `AutoSyncCommonTest` (7) (all four also on the simulator),
  `NoiseFixtureTest` (1), `MigrationTest` (1), `SniffingTest` (2), `AutoSyncOldPhonesTest` (2), `AutoSyncScreenTest`
  (5, two new); on the simulator also `CryptoCheckIosTest`, which now plays the Noise transcripts.
  `tools/noise_check.py`: the recorded transcripts agree with Python's `cryptography` on every message, hash, key and
  frame.
- The golden fixtures are byte for byte as at a0fa6ee and ecf69e2 (`git diff ecf69e2 -- shared/src/jvmTest/resources/golden`
  is empty); none was recorded again. 2.1.1's code sync between a 2.4 phone and an older one is still its eight
  messages with 2.1's hello fields, both ways round (`AutoSyncOldPhonesTest`).
- `tools/simulate.sh 100`: 0 seeds broke an invariant, the new one included ("between two phones with 2.4 nothing goes
  in the clear"): phones on 2.2 (123), 2.3 (223) and 2.4 (354); 7,657 code syncs (1,598 encrypted, 6,059 plain with an
  older phone) and 618 by themselves (422 encrypted, 196 between two 2.3 phones on 2.3's keys); the relay read every
  encrypted sync with the session keys, so each merge was checked as before. The 10 seeds with phones working while
  they sync, and 50 more such seeds (`HM_SIM_LOOSE=50 HM_SIM_LOOSE_JOBS=2 tools/simulate.sh 0`), held every invariant.
- `tools/checks.sh` (0 findings but the one text field on search, as before) and `tools/roles.sh` (nothing found on
  core screens; the same "alone" notes as before) pass.
- `:app:assembleRelease`: versionCode 12, versionName 2.4, the same signing certificate (SHA-256 afc48c1a…ab00a3);
  `aapt dump badging` differs from 2.3's only in the version (the same permissions: camera and internet).
  `classes.dex` and `classes2.dex` are byte for byte 2.3's; `classes3.dex` is 46 KB larger: `Noise`, `NoiseKey`,
  `CipherState`, `SymmetricState`, `Handshake`, `SecureChannel`, `Pins`, `Pin`, `PinsSaved`, `Vouch` and their
  serializers and lambdas in, `Ring`, `RingSaved`, `WifiKey`, `Meetings` and HMAUTO1's credentials out; no test class
  (no `NoiseKat`, `ReferenceCrypto`, `Old23`, `SessionKeys`) and no `HMAUTO1`. The APK is 17,255,228 bytes (2.3:
  17,238,844), in `releases/HouseMove-2.4.apk` and `HouseMove-latest.apk`.
- iOS: `:shared:compileKotlinIosArm64`, `:shared:compileKotlinIosSimulatorArm64`, `:shared:compileTestKotlinIosSimulatorArm64`,
  `:sharedUi:compileKotlinIosArm64` and `:sharedUi:compileKotlinIosSimulatorArm64` succeed. The Swift (unchanged but for
  the UI tests' new assertions), the simulator tests and the UI tests run in CI only.
- **The iPhone encrypts only through `PlatformCrypto`:** in `shared/src/commonMain`, `shared/src/iosMain` and
  `sharedUi/src/{commonMain,iosMain}` there is no `javax.crypto`, `java.security` (one comment), `KeyAgreement` (comments
  naming CryptoKit's types), CommonCrypto cipher call, S-box, GHASH or other block-cipher code; every `sealGcm`,
  `openGcm`, `agree` and `generateKey` call is on the interface (`Sealed.kt`, `Update.kt`, `Noise.kt`, the checks). The
  reference P-256 and AES-GCM (`ReferenceCrypto.kt`) are in `commonTest` only, used by tests in commonTest, iosTest
  and jvmTest.

## Verify, Part 1 (2026-11-25, on Linux)

- `tools/test.sh`: 534 tests, 0 failed (shared 360, app 159, sharedUi 15). New: `UpdateCommonTest` (4, also on the
  simulator), `ReferenceCryptoJvmTest` (4), `SealingFixtureTest` (1); on the simulator also `CryptoCheckIosTest` (4).
- The golden fixtures are byte for byte as at a0fa6ee (`git diff a0fa6ee -- shared/src/jvmTest/resources/golden` is
  empty); none was recorded again.
- `tools/simulate.sh 100`: 0 seeds broke an invariant (6,803 syncs, 1,839 of them by themselves; update files opened
  only where sent); the 10 seeds with phones working while they sync held every invariant too.
- `:app:assembleRelease`: the same signing certificate (SHA-256 afc48c1a…ab00a3) and the same `aapt dump badging`.
  `classes.dex` and `classes2.dex` (the libraries) are byte for byte the same; `classes3.dex` (the app and the shared
  code) is 16 KB larger, 17 classes more: the interface (`PlatformCrypto`, `AgreementKey`, `CryptoKt`), the JCA side
  (`JcaCrypto`, `JcaAgreementKey`, `Crypto_jvmKt` in place of `KeysKt` and `UpdateCrypto`), `SealedKt` (the stream
  helpers), the small stream adapters (`Files_jvmKt$inputStreamOf$1`, `$outputStreamOf$1`, `SealedOut$stream$…`,
  `SealedIn$stream$1`), lambdas the compiler made (`Update$$ExternalSyntheticLambda0..2`, `Sealing$random$1`), and
  `JavaBase64` and `P256Der`, which only the iPhone calls (R8 is off, so they ship unused). No test class is in it (no
  `ReferenceCrypto`, `RefP256`, `CryptoCheck`). `assets/dexopt/baseline.prof` differs with the class list. The APK is
  the same size to the byte: its stored entries are aligned, and the padding took the 5 KB the compressed dex grew.
- `:shared:compileKotlinIosArm64`, `:shared:compileKotlinIosSimulatorArm64`, `:shared:compileTestKotlinIosSimulatorArm64`,
  `:sharedUi:compileKotlinIosArm64` and `:sharedUi:compileKotlinIosSimulatorArm64` succeed. The Swift and the simulator
  tests run in CI only.
