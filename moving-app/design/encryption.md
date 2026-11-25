# Encryption: update files on the iPhone, and encrypted sync

Two parts. **Part 1 is built** (branch `crypto`): the iPhone has the same update-file crypto as Android, byte for byte,
through a small interface whose Android side is the JCA code Android has run since 2.1 and whose iPhone side is
CryptoKit. **Part 2 is a design**, not built: an encrypted, mutually authenticated channel for phone-to-phone sync from
2.4, which replaces 2.3's Wi-Fi keys and pair keys, and serves the internet mailbox on the owner's always-on Windows
laptop later.

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

Not built. It needs Part 1 (CryptoKit on the iPhone) and changes the sync on both platforms; it should be its own branch
with its own golden transcripts.

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
- **Part 2 is a design.** Pinning and key changes add states the phones must explain in plain words; the first version
  should be tested with the seven-phone simulation (strangers, removed phones, a phone with a new key) before it ships.

## Verify (2026-11-25, on Linux)

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
