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
- 1b added 43 common tests for files, zips, threads, sockets, QR codes and the fixed move's syncs (see 1b below);
  the rest of what needs a Store on disk, and the update files, `GoldenFixturesTest` among them, are still in
  `jvmTest`.

## CI

`housemove-ios.yml` runs `./gradlew :shared:iosSimulatorArm64Test` on the simulator `tools/ios.sh sim` uses
(`tools/ios.sh sim-id`, given to Gradle as `HM_SIMULATOR`), before the Xcode steps, and keeps the reports
(artifact `ios-shared-tests`). A failure lets the Xcode steps run and fails the job at the end. The iPhone workflow
runs for `moving-app/shared/**` (and sharedUi, iosApp, the Gradle setup), not for the Android app alone; the APK
workflow runs for everything in `moving-app` but the iPhone app. Since 1b, a step "Failed tests" prints every failed
test with its message and stack (`tools/failures.py`: the shared tests' JUnit XML and the Xcode result bundle), and
"Measure the Kotlin build" (18 minutes) runs only for a commit whose message says `[measure]`.

**Seen since (run 45544621082):** all 26 passed on the simulator. **Before that:** the first CI run was the first time these tests ran under Kotlin/Native. What they test there that
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

## 1b: what the iPhone has now (branch `ios-1b`)

Nothing on the iPhone throws `"step 1b"` any more. Book title lookup is still a stub (`"later"`): it is not in v1.

| Seam | The iPhone's side | How it keeps Android's behaviour |
|---|---|---|
| `File` and its functions (`Files.ios.kt`) | a path; POSIX `stat`/`lstat`, `open`/`read`/`write`, `rename`, `remove`, `mkdir`, `opendir`/`readdir`, `getcwd` | java.io.File's rules for paths (normalised, `equals` by path), `rename(2)` replacing the target (so the store's write-then-rename is atomic, as on Android; no fsync on either), `mkdirs`/`delete`/`listFiles` as java.io, `copyTo`/`deleteRecursively` as kotlin.io (a symbolic link is not followed), text through `JavaText` UTF-8; `lastModified` joins the seam |
| Folders (`Folders.ios.kt`, `AppFolders`) | Application Support/HouseMove (Moves' filesDir), Application Support/HouseMove-no-backup (noBackupDir), Caches/HouseMove | `photos/` and the no-backup folder carry `NSURLIsExcludedFromBackupKey` (set again at every start), as Android's backup rules leave out files/photos and no_backup |
| Streams | `InputStream`/`OutputStream` with file, socket, buffered (8 KB, as BufferedInput/OutputStream) and test memory kinds | `InputStream.read` joins the seam |
| `ZipWriter`/`ZipReader` | common `ZipOut`/`ZipIn` (`Zip.kt`) through a buffer; DEFLATE from zlib (`platform.zlib`, raw, windowBits -15, level -1, memLevel 8) | see "Zips" below |
| `SerialWorker` (the store's writer) | a serial dispatch queue | see "Order" below |
| `CountDownLatch` | an atomic count and a dispatch semaphore | the count stops at zero; once open every wait ends at once (each waiter that gets through signals again); the semaphore orders memory as the JVM's latch does |
| `startThread` | an NSThread with an autorelease pool and a 1 MB stack | |
| Sockets (`sync/Net.ios.kt`) | POSIX, IPv4 | see "Sockets" below |
| `siteLocalAddresses` | `getifaddrs`, then `syncAddresses` (`sync/Addresses.kt`, common) | site-local IPv4 on `en*` (Wi-Fi) and `bridge*` (Personal Hotspot, 172.20.10.x), up and running, not loopback; never `pdp_ip*`, `utun*`, `ipsec*`, `awdl*`, `llw*`, `lo*` or any other; in the order getifaddrs lists them, interface by interface, as the JVM keeps NetworkInterface's order |
| `qrPixels` | common (`QrEncoder.kt`, ported from ZXing 3.5.3, Apache 2.0, `licenses/ZXing-Apache-2.0.txt`) on every platform | ZXing's modules exactly; Android keeps ZXing for `Qr.matrix` (label PDFs) and the camera decoder |
| `updateCrypto` | null in the app; tests on the simulator may put in a stand-in that only passes key text on (`updateCryptoStandIn`, `KeyTextOnly` in commonTest) | no crypto in commonMain or iosMain: the only Apple security calls are still `SecRandomCopyBytes` and `CC_SHA256` |

**Order.** The writer keeps what the JVM's single-thread scheduled executor gives the store: tasks run one at a time,
each seeing what the one before did (a serial queue); `execute` runs in the order given (FIFO); a `schedule`d task
joins the queue when it is due (`dispatch_after`), so what is given meanwhile runs first, as the JVM orders tasks by
due time; `runAndWait` (the store's `flush`) runs after everything given before it and waits, throwing when the task
failed or was late (a late task still runs, as with `Future.get`); `shutdownNow` drops what has not started and
takes nothing more (an `IllegalStateException`, where the JVM throws `RejectedExecutionException`; the store catches
both); `awaitTermination` waits for the task running; a task's exception is dropped, as the JVM keeps it in a Future
nobody reads. One difference: the JVM's `shutdownNow` interrupts the running task; the store's writes are not
interruptible there either. `ThreadsCommonTest` checks each of these on both platforms.

**Zips.** `ZipOut` writes what `java.util.zip.ZipOutputStream` writes for the app's entries: version 20, flags
0x0808, a data descriptor with its signature, UTF-8 names, no extra fields or comments, the DOS time in local time,
ZIP64 records only when a size, an offset or the count needs them. `ZipIn` reads as `ZipInputStream`: from the start,
DEFLATE and stored entries, descriptors with or without the signature, every CRC and size checked. On the JVM, where
its DEFLATE is `java.util.zip.Deflater`, `ZipOut`'s zips are Java's byte for byte (`ZipJvmTest`: 60 random zips and
a ZIP64 one of 70,000 entries); Java reads them streaming and through the central directory; `ZipIn` reads Java's.
On the iPhone the compressed bytes are zlib's, which only have to read the same. One difference on purpose: a zip
entry whose name is not UTF-8 is an IOException (so "not a HouseMove backup"); Java throws IllegalArgumentException.

**Sockets.** Every socket has `SO_NOSIGPIPE` (a write to a phone that has gone is a SocketException, never a signal);
`TCP_NODELAY` when WifiSync asks; the hosting phone's listening socket `SO_REUSEADDR` (as Java's), backlog 50, on all
IPv4 addresses (tests: 127.0.0.1 only). A read waits at most `soTimeout` (`SO_RCVTIMEO`, as Android sets
`SO_TIMEOUT`), then `SocketTimeoutException`; a connect at most its timeout (non-blocking `connect` and `poll`), then
`SocketTimeoutException`; refused or unreachable is a `ConnectException` (a SocketException). The end of the stream
reads -1 (so the wire reader throws `EOFException`); a reset or a broken pipe is a `SocketException`. Closing the
listening socket ends a wait in `accept` (a pipe wakes its `poll`) with a SocketException; a socket closed from another
thread is shut down and its descriptor let go only when the call using it returns. Sending is buffered 8 KB, as
`BufferedOutputStream`.

**Privacy manifest.** The shared code calls `stat` and `lstat` (File timestamp APIs) on the app's own files: reason
C617.1 is now declared beside 0A2A.1.

**Tests** (commonTest, on the JVM in every run and on the simulator in CI), 79 in all (with 2.2's `NumbersCommonTest`), 43 of them new in 1b:
`GoldenSyncCommonTest` (2: the fixed move through the real Store and WifiSync, host and joiner on threads of one
process over 127.0.0.1 through a relay that keeps every byte: `move-data.json`, `sync-join.json`, `sync-plain.json`
and every answer in `sync-refusals.json` byte for byte; a backup made then holds `backup.json`'s `deterministic`;
Android's backup sample peeks and restores as `opened` says), `FilesCommonTest` (6), `ZipCommonTest` (10, the golden
sample among them), `ThreadsCommonTest` (6), `SocketsCommonTest` (7), `SyncNetworkCommonTest` (3, from NetworkTest:
a quiet phone, a dropped connection, nobody there), `MovesCommonTest` (2, from MoveIdTest), `QrCommonTest` (3,
`qr.json`'s modules and drawings), `AddressesCommonTest` (3), `StoreSaveCommonTest` (1, 2.2's save retry through the writer, from NotANumberTest). In jvmTest: `ZipJvmTest` (6) and `QrEncoderJvmTest` (2:
ZXing's modules on 3,600 texts, and `qrPixels` as `Qr.matrix`). On the simulator the iPhone v1 has no keys of its own,
so the golden phones say the key text in their folders (`KeyTextOnly`); without it the hellos and phone entries
would carry no key, which is what a real iPhone v1 sends.

Still on the JVM only: the rest of the Store, sync and simulation suites (`DataTest`, `JoiningTest`, `PeopleTest`,
`SimulationTest` and the others, which use JUnit rules, java.io and JVM queues) and the update files.

## What 1b had to do on the iPhone

- [x] **Files** (`Files.ios.kt`), with `AppFolders` (`Folders.ios.kt`).
- [x] **Zip** (`Zip.kt`, `Zip.ios.kt`).
- [x] **The store's writer** (`SerialWorker`), **`CountDownLatch`**, **`startThread`**.
- [x] **Sockets** (`sync/Net.ios.kt`) and **addresses** (`sync/Addresses.kt`).
- [x] **QR encoder** (`QrEncoder.kt`, common).
- [ ] **Tests**: done for files, zips, threads, sockets, QR codes, Moves on disk, part of NetworkTest and the fixed
  move's syncs and backup; the rest of the Store, sync and simulation suites are still JVM-only.
- [x] **Whatever the first simulator run of `commonTest` shows**: step 1a's 26 common tests passed on the simulator
  (Kotlin/Native's Double.toString, case rules, toDoubleOrNull and Regex matched the fixtures). The 1b tests' first
  run is next.
- [x] **Phone keys** (branch `crypto`, `design/encryption.md`): the update files are common Kotlin now, on a small
  crypto interface (`Crypto.kt`, which replaced `UpdateKeys.kt`'s `updateCrypto` with `platformCrypto`): the JCA on
  Android and the JVM, CryptoKit in Swift on the iPhone, registered at start-up, with the private key in the Keychain.
  An iPhone that admits an Android phone keeps its key on its entry, and its own entry carries a real key. The
  simulator's Kotlin tests use a reference crypto in commonTest (`ReferenceCrypto`) where the key-text stand-in was.
- [ ] **Double text on Android**: the fixtures were recorded on JDK 21, Android phones run ART. Checking ART's
  `Double.toString` against `JavaText.doubleText` on random values, in an instrumented test on the emulator, would
  say whether the iPhone should copy ART's choice on the rare values where the two could differ.
