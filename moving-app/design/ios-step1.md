# Step 1: the data and the sync in common Kotlin (checklist)

Step 1 of `design/ios-plan.md` is in two parts. **1a** (done in the repo, branch `ios-1a`) moves the code into
`commonMain` behind small seams, with the JVM side of every seam being the code Android ran before, and gives the
iPhone the small things and the text rules. **1b** gives the iPhone files, zips, sockets, threads and QR codes, and
moves the remaining tests into `commonTest` so they run on the simulator too.

## Where the code is now

| Source set | What | Lines |
|---|---|---|
| `shared/src/commonMain` | every file of `app.housemove.data` and `app.housemove.sync` that was in `jvmCommonMain`, moved with `git mv` and small edits (same packages, same class and file names), except those below; the label QR text from `Qr.kt` (now `QrCodes.kt`); the seams and the text rules | 10,230 |
| `shared/src/jvmCommonMain` | the update-file crypto (`Keys.kt`, `Sealed.kt`, `Update.kt` with `Moves.openUpdate`), zxing (`Qr.kt`), and the JVM side of each seam (`*.jvm.kt`) | 1,075 |
| `shared/src/iosMain` | the iPhone side of each seam (`*.ios.kt`) | 381 |

So 90 % of the shared module's lines are common. Of the 9,983 lines that were in `jvmCommonMain`, 92 % moved; what
stays is the crypto, the update files and zxing (790 lines).

`app/compose-stability.conf` still lists the same class names: nothing was renamed or moved to another package.
The app changed by no line: it sees `java.io.File`, `java.io.InputStream`, `Moves.openUpdate` and the rest as before.

## Survey: what the code took from the JVM, and where it went

The JVM side is today's code in every row; the iPhone column says whether its side is real in 1a or waits for 1b.

| File | JVM API used | Seam now (common → JVM) | iPhone in 1a |
|---|---|---|---|
| Store, Phone, Moves, Backup, DailyBackup, Shrink, WifiSync | `java.io.File`: `File(dir, name)`, `exists`, `isFile`, `isDirectory`, `name`, `parentFile`, `absoluteFile`, `mkdirs`, `listFiles`, `delete`, `renameTo`, `length`; kotlin.io `readText`, `writeText`, `readBytes`, `copyTo`, `deleteRecursively`, `inputStream`, `outputStream` | `Files.kt`: `expect class File` → `typealias java.io.File`; each call an extension whose JVM body is that call | stub (1b) |
| Backup, DailyBackup (`BackupPlace`), Moves | `java.io.InputStream`, `OutputStream`, `IOException`, `EOFException` | `Files.kt`: typealiases | real classes; files and sockets behind them in 1b |
| Backup | `java.util.zip` `ZipOutputStream(out.buffered())`, `ZipInputStream(input.buffered())`, `ZipEntry` | `Files.kt`: `ZipWriter`, `ZipReader` (JVM: those streams, same calls in the same order) | stub (1b) |
| Store | `Executors.newSingleThreadScheduledExecutor` (daemon "store-io"): `execute`, `schedule`, `submit().get(10 s)`, `shutdownNow`, `awaitTermination(5 s)` | `Threads.kt`: `SerialWorker` (JVM: that executor) | stub (1b) |
| Store, Phone, Moves, WifiSync | `synchronized(lock)` on `Any()` (Store, Moves ×2), on the Phone itself, on WifiSync's `personal` and `answers` maps | `Threads.kt`: `Lock` (JVM: `typealias Any`), `synchronized` (JVM: `kotlin.synchronized`, the monitor); the Phone and the two maps got a lock object of their own | real: `NSRecursiveLock` (reentrant, as monitors are) |
| Store, WifiSync | `AtomicBoolean` (dirty), `AtomicLong.updateAndGet` (stamps), `AtomicInteger` (peers served) | `Threads.kt`: typealiases to java.util.concurrent.atomic | real: `kotlin.concurrent.atomics` |
| WifiSync | `CountDownLatch(1)`, `await(ms)`, `countDown`; `kotlin.concurrent.thread(name, isDaemon = true)` | `Threads.kt`: `CountDownLatch` (typealias), `awaitMillis`; `startThread` | stub (1b) |
| Store, Moves, Shrink, WifiSync, Keys | `@kotlin.jvm.Volatile` | `kotlin.concurrent.Volatile` (on the JVM a typealias of the same annotation) | real |
| Store, Moves, Backup, WifiSync | `System.currentTimeMillis` | `Platform.kt`: `currentTimeMillis` | real: `NSDate` |
| Logic, Store, Moves | `LocalDate.now().toEpochDay()` | `localToday` (behind `today()`) | real: `NSDate` + `NSTimeZone.localTimeZone` |
| Logic | `DateTimeFormatter.ofPattern("EEE d MMM yyyy")`, `("d MMM")` | `dayText`, `dayShortText` | real: `NSDateFormatter`, Gregorian, ASCII digits, as java.time does (not yet looked at on a screen) |
| DailyBackup | `LocalDate.ofEpochDay(d).toString()`, `LocalDate.parse` | `isoDate`, `parseIsoDate` | real: `JavaText` |
| Store | `UUID.randomUUID()` | `randomUuid` | real: `NSUUID`, lower case |
| Store | `Math.random()` | `mathRandom` | real: `kotlin.random` |
| Phone, WifiSync | `SecureRandom().nextInt(n)` | `secureRandom()` | real: `SecRandomCopyBytes`, drawn on as `java.util.Random.nextInt` does |
| Phone | `MessageDigest.getInstance("SHA-256")`, `"%02x".format` | `sha256`; `toHex` (common, all 256 bytes checked against `"%02x"`) | real: CommonCrypto `CC_SHA256` (a digest, not encryption) |
| Phone, Backup, WifiSync | `String.toByteArray()` (a lone surrogate as '?') | `toUtf8` | real: `JavaText.utf8` |
| Backup, WifiSync | `ByteArray.decodeToString()` | `decodeUtf8` | real: `JavaText.decodeUtf8` |
| WifiSync | `URLEncoder.encode`, `URLDecoder.decode` (UTF-8) | `urlEncode`, `urlDecode` | real: `JavaText` |
| Logic (`fmtNum`) | `Double.toString` | `javaText` | real: `JavaText.doubleText` |
| WifiSync | `ServerSocket(0)`, `accept`, `localPort`, `close`; `Socket()`, `connect(InetSocketAddress, timeout)`, `soTimeout`, `tcpNoDelay`; `SocketException`, `SocketTimeoutException` | `sync/Net.kt`: typealiases to java.net, extensions | stub (1b); the exception classes are real |
| WifiSync | `DataInputStream(s.getInputStream().buffered())`: `readUTF`, `readInt`, `readLong`, `readUnsignedShort`, `readFully`, `read`; `DataOutputStream(...buffered())`: `writeUTF`, `writeInt`, `writeLong`, `write`, `flush`; `File.inputStream().copyTo(out)` | `sync/Net.kt`: typealiases, `dataIn`, `dataInUnbuffered` (1.3's hello), `dataOut`, `writeFile` | real: `sync/Wire.kt` over the stream |
| WifiSync | `NetworkInterface`, `Inet4Address.isSiteLocalAddress` | `siteLocalAddresses` | stub (1b: `getifaddrs`, without cellular and VPN interfaces) |
| BookLookup | `HttpURLConnection` | `fetchCatalogue` | stub (book lookup is not in v1) |
| Qr | zxing `QRCodeWriter`, `MultiFormatReader` | label text in common (`QrCodes.kt`); `qrPixels` (JVM: `Qr.matrix`); the camera decoder stays JVM | stub (1b: an encoder with zxing's modules) |
| Rules | `java.lang.Double.doubleToLongBits`, `java.lang.Long.toString(x, 36)` | `toBits()`, `toString(36)`: the same JVM calls | real (stdlib) |
| People, Phone | `Math.floorMod` | `mod` (the same results for every Int) | real (stdlib) |
| People, Merge, Records | `Map.merge`, `putIfAbsent` (Java default methods) | written out (`mergeValue`, `getOrPut`): the same results | real (stdlib) |
| Deleting | `java.util.TreeSet<String>` | a set, then `sorted()`: the same list | real (stdlib) |
| Phone, Store, WifiSync | `PhoneKeys` (`keys.publicText`, `keys.madeAt`, `PhoneKeys.publicFrom`) | `UpdateKeys.kt`: `UpdateKeyPair`, `updateCrypto` (JVM: `Keys.kt`'s) | `updateCrypto` is null: no key, no crypto |
| Keys, Sealed, Update | JCA EC P-256, ECDH, HKDF, AES-GCM, Base64, zip | stay in `jvmCommonMain` | not built for the iPhone |

Proof that nothing of the crypto reaches the iPhone: `grep -rnE "javax\.crypto|java\.security|PhoneKeys|Sealing|KeyAgreement|Cipher" shared/src/commonMain shared/src/iosMain`
finds one comment (Platform.kt names java.security.SecureRandom as the JVM's random source); `Keys.kt`, `Sealed.kt` and
`Update.kt` are only in `jvmCommonMain`; and the iPhone klibs (`shared/build/classes/kotlin/ios*/main`) hold none of
those names. The iPhone's only Security-framework call is `SecRandomCopyBytes`, and CommonCrypto only `CC_SHA256`.

## Text: what the iPhone writes, and how it is checked

`JavaText.kt` (common) writes and reads text as the JVM does: UTF-8 with '?' for a lone surrogate, UTF-8 decoding
with the JVM's U+FFFD pieces, modified UTF-8 (`writeUTF`/`readUTF`, with their errors), `Double.toString` (Java 19 and
later: shortest, closest, one or two digits when the shortest has one), `URLEncoder`/`URLDecoder` and ISO dates.
`sync/Wire.kt` reads and writes the sync wire on top of it.

- **Modified UTF-8: the JVM keeps DataOutputStream/DataInputStream; the iPhone uses `JavaText`.** The two are
  byte-identical: the golden fixtures (`modified-utf8.json`, `sync-refusals.json`'s words, and every field of the two
  recorded syncs read and written again), plus `JavaTextTest` against `DataOutputStream`/`DataInputStream` on
  500,000 random strings and byte strings (cut short and spoiled ones included; 10 million once, with
  `HM_JAVATEXT_TIMES=20`). Android was still left on DataOutputStream: the brief asks for today's code on the JVM, and
  it would gain nothing, as the same checks run on the JVM and the simulator. Switching is one line per function in
  `sync/Net.jvm.kt`.
- **Double: the JVM keeps `Double.toString`; the iPhone uses `JavaText.doubleText` where the code asks for it
  (`fmtNum`).** `doubleText` matches the JVM on every fixture double and on about 170,000 more in each test run (random
  bits, all powers of two and ten and their neighbours, the first subnormals, around 10^-3 and 10^7, short decimals;
  3 million once). It still stays off Android, because Android's `Double.toString` is ART's, not the JDK 21 one the
  fixtures and tests run on: the two agree on ordinary values, but no JVM test can prove them the same on all of them,
  and an Android phone must keep writing what 2.1.1 phones write.
- **Record text and JSON on the iPhone use Kotlin/Native's own `Double.toString`** (data-class `toString`, which
  merges compare, and kotlinx.serialization). `GoldenCommonTest.thePlatformWritesDoublesAsTheJvmDoes` and the record and
  JSON checks test exactly that on the simulator. If Kotlin/Native differs, 1b has to put `doubleText` there.

## Tests

- `shared/src/commonTest` runs on the JVM (in `:shared:jvmTest`, so in every `tools/test.sh`) and on the iPhone
  simulator (`:shared:iosSimulatorArm64Test`, in CI). 26 tests:
  - `JavaTextCommonTest` (3): ISO dates, hex and Doubles at the edges of plain text, through the iPhone's rules.
  - `GoldenCommonTest` (12): the fixtures through the shared code: modified UTF-8 and
    the wire integers, Doubles, seals and every check sum, record text, `ownPart` and tie-breaks, `String.hashCode`,
    label blocks and letters, derived move IDs, tags, digests, line ids, `lowercase`/`uppercase`/`trim`,
    `compareTo`, refused-change ids, invite text and URL coding, AppJson both ways (and `move-data.json` byte for
    byte), label QR text, and both recorded syncs read and written back byte for byte.
  - `MergeCommonTest` (3) and `LogicCommonTest` (8): tests moved from `DataTest`, `EdgeCaseTest`, `NoTypingTest`,
    `RoomNamesTest` and `BarcodeTest` that need only common code.
- The fixtures reach `commonTest` as Kotlin: the Gradle task `:shared:goldenSources` writes
  `build/generated/golden/kotlin/app/housemove/GoldenFiles.kt` from `src/jvmTest/resources/golden/*.json`, with the
  SHA-256 of each file, which `theFixturesComeThroughWhole` checks. The files are only read.
- `jvmTest/JavaTextTest` (10): the iPhone's text rules and wire against the JVM's own functions (above).
- Still in `jvmTest` until 1b: everything that needs a Store on disk, sockets, zips, QR modules or update files,
  `GoldenFixturesTest` among them.

## CI

`housemove-ios.yml` runs `./gradlew :shared:iosSimulatorArm64Test` on the simulator `tools/ios.sh sim` uses
(`tools/ios.sh sim-id`, given to Gradle as `HM_SIMULATOR`), before the Xcode steps, and keeps the reports
(artifact `ios-shared-tests`). A failure lets the Xcode steps run and fails the job at the end. The iPhone workflow
runs for `moving-app/shared/**` (and sharedUi, iosApp, the Gradle setup), not for the Android app alone; the APK
workflow runs for everything in `moving-app` but the iPhone app.

**Not yet seen:** the first CI run is the first time these tests run under Kotlin/Native. What they test there that
the JVM could not: Kotlin/Native's `Double.toString`, `lowercase()`/`uppercase()` special casing (İ, final Σ, ß),
`trim()`, `String.hashCode`, `toDoubleOrNull` (the number fields of step 2), its Regex (contents lines), and the iPhone's
SHA-256. Whatever fails there is 1b's first job.

## 1a, checked (2026-11-25, on Linux)

- `tools/test.sh`: 421 tests, 0 failed (shared 260, of them 26 common tests; app 155; sharedUi 6). Before: 396.
- The golden fixtures are byte for byte as recorded (sha256 of every file in `golden/`); `GoldenFixturesTest` passes.
- `tools/simulate.sh 100`: 0 seeds broke an invariant (8,286 syncs); `HM_SIM_LOOSE=50 HM_SIM_LOOSE_JOBS=2
  tools/simulate.sh 0`: all invariants held. `tools/checks.sh` and `tools/roles.sh` pass, with the same findings
  as before the move.
- `:app:assembleRelease`: the same signing certificate (SHA-256 afc48c1a…ab00a3) and the same `aapt dump badging`.
  The APK is 16 KB larger: `classes.dex` and `classes2.dex` (the libraries) are byte for byte the same, and
  `classes3.dex` (the app and the shared code) is 44 KB larger. It holds 31 more classes: the seams' JVM side
  (`Files_jvmKt`, `Threads_jvmKt`, `Platform_jvmKt`, `Net_jvmKt`, `SerialWorker`, `ZipWriter`, `ZipReader`), the
  common text rules and wire that only the iPhone calls (`JavaText`, `WireReader`, `WireWriter`; R8 is off, so they
  ship unused), the interfaces (`UpdateKeyPair`, `UpdateCrypto`, `RandomInts`, `QrPixels`), and small classes the
  compiler made differently (a `LongUnaryOperator` per `updateAndGet` call instead of a desugared lambda; none for
  `Map.merge` and `"%02x"`, now written out). The seams' bytecode calls the java.io, java.net and java.util.concurrent
  methods the code called before.
- `:shared:compileKotlinIosArm64`, `:shared:compileKotlinIosSimulatorArm64`, `:shared:compileTestKotlinIosSimulatorArm64`
  and `:sharedUi`'s two iPhone compiles succeed.

## What 1b must do on the iPhone

- [ ] **Files** (`Files.ios.kt`): `File` as a path (NSFileManager or POSIX) under Application Support, the
  no-backup folder excluded from iCloud backup; `renameTo` replacing the target as POSIX `rename` does;
  `readText`/`writeText` through `JavaText` UTF-8; `copyTo` and `deleteRecursively` as kotlin.io's; file streams
  (subclasses of the iOS `InputStream`/`OutputStream`).
- [ ] **Zip** (`ZipWriter`, `ZipReader`): DEFLATE through the iOS SDK's zlib; entries as Java writes them (version 20,
  flags 0x0808 with a data descriptor after the data, UTF-8 names, no extra fields); a reader that takes data
  descriptors; what `backup.json`'s `deterministic` part says.
- [ ] **The store's writer** (`SerialWorker`), **`CountDownLatch`**, **`startThread`**: a serial GCD queue or a
  thread; `NSCondition`; `NSThread`.
- [ ] **Sockets** (`sync/Net.ios.kt`): POSIX, a connect with a timeout, `SO_RCVTIMEO` as `SocketTimeoutException`,
  `TCP_NODELAY`, `SO_NOSIGPIPE`, an end of stream as `EOFException` and a reset as `SocketException`, closing a
  listening socket ending `accept` with an `IOException`; an 8 KB buffer on the sending side, as
  `BufferedOutputStream`; `siteLocalAddresses` from `getifaddrs` without cellular (`pdp_ip`) and VPN (`utun`,
  `ipsec`) interfaces.
- [ ] **QR encoder** (`qrPixels`): byte mode ISO-8859-1, error correction M, zxing's version and mask choice, so that
  `qr.json`'s matrices match module for module.
- [ ] **Tests**: give `commonTest` a temporary folder and common stand-ins for the JVM queues the tests use, then move
  the Store, Moves, sync and simulation tests there, and `GoldenFixturesTest`'s fixed move (the sync transcripts over
  real sockets, the backup).
- [ ] **Whatever the first simulator run of `commonTest` shows** (above).
- [ ] **Phone keys**: an iPhone that admits an Android phone keeps no key on its entry (`updateCrypto` is null, so
  `isPublicKey` is false); the phone puts its own key back on its next sync. A check of the key's shape alone (an X.509
  P-256 public key in Base64, no crypto) would keep it; to be decided with the update files.
- [ ] **Double text on Android**: the fixtures were recorded on JDK 21, Android phones run ART. Checking ART's
  `Double.toString` against `JavaText.doubleText` on random values, in an instrumented test on the emulator, would
  say whether the iPhone should copy ART's choice on the rare values where the two could differ.
