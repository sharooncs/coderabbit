# Golden fixtures: HouseMove 2.1.1

These files record values and bytes that the released Android app, 2.1.1, makes. The code they were recorded from is the
2.1.1 release on branch `claude/intelligent-brown-mk8754` (commit 4e77f9a). An iPhone must sync with 2.1.1 phones and work
out the same check values, ids, merge winners and bytes. So every later version, and the Kotlin Multiplatform port on the
JVM and on iOS, must reproduce these files. This is step 0a of `design/ios-plan.md`.

`GoldenFixturesTest` (`app/src/test/java/app/housemove/`) makes every file again from today's code and compares it with
the recorded file, character for character. `GoldenMove.kt` beside it plays the fixed move described below.

## Checking and recording

- Check: `tools/test.sh --tests app.housemove.GoldenFixturesTest` (the fixtures are also checked in every full `tools/test.sh` run).
- Record: only on purpose, when a change to what 2.1.1 makes is intended and every port will follow it.
  - In an IDE run, put `-Dgolden.record=true` on the test JVM.
  - Through Gradle, use `GOLDEN_RECORD=true tools/test.sh --tests app.housemove.GoldenFixturesTest`, or
    `JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dgolden.record=true"`. `app/build.gradle.kts` does not pass `-D` options
    on to the test JVM, which is why there are two ways.
  - Recording plays the fixed move twice and writes nothing if the second run differs. The key pairs are constants in
    `GoldenMove.kt`, so they never change. Commit the diff, and say in the commit message what changed and why.

## Conventions

- All files are UTF-8 JSON with two-space indents, except `move-data.json`, which is the exact bytes of a `data.json`.
- Bytes are lowercase hex with no separators. Longs that can exceed 2^53 are JSON strings.
- `somethingUtf16` gives the UTF-16 code units of a short non-ASCII string next to it, for people reading the file. The
  JSON string itself is exact.
  - Lone surrogates and control characters are written as `\uXXXX` escapes.
  - kotlinx.serialization reads such an escape back into the lone surrogate. Some other JSON readers do not.
- `"error: X"` means the JVM threw exception class `X`.
- **What a port does with every file:** read it in a test on the JVM and on the iOS simulator. Recompute each value from
  the inputs given, using the port's code. Compare exactly. For anything given as AppJson, decode it with the port and
  encode it again; the text must not change.

## The fixed move (inputs for move-data, sync-*, backup and update)

| What | Value |
|---|---|
| Asha's phone (Organiser, founder) | phone ID `pashaphone23456789ab`, key pair `KEY_ASHA` (below), clock fixed at 1767225600000 (2026-01-01 00:00 UTC), settings `{"name":"Asha","letter":"A"}` |
| Priya's phone (joins as a Decider) | phone ID `ppriyaphone2345678ab`, key pair `KEY_PRIYA`, clock fixed at 1767312000000 (a day later; also the hosting phone's clock for invites), settings `{"name":"Priya 🏠","letter":"P"}` |
| Ids (`newId()`) | `00000000-0000-4000-8000-000000000001`, `…0002` and on, in the order the code asks for them, on both phones and the sync threads |
| Invite code / plain sync code | `GQ7K2MXP` / `HM4RT8WZ` (both drawn at random in the app) |
| Photo files | the ASCII text `golden photo: plates`, `golden photo: lamp` and `golden photo: hall` |

The key pairs are EC P-256, X.509 / PKCS#8 in Base64. They are given in `update.json` under `recipient` (Asha) and
`sender` (Priya), and in `GoldenMove.kt`.

A clock that stands still makes `Store.now()` count up by one from the last stamp.

Each phone's folder is written before its first start (`phone-id`, `phone-key.json`, `device.json`), as the app would
have left it.

The steps:

1. Asha's phone sets the move up:
   - "Maple Road", move day 20600, "£", van 12.5 m³.
   - Rooms: Kitchen (new home), Kitchen (old home, mapped to it), Zoë's room.
   - Sofa: 210 × 0.1 × 1e-4 cm, fragile, decided Sell at 1e7.
   - "TV 日本": width -0.0, value 1e-3, Sony X90, serial SN-0042.
   - Box L001: contents "Plates, Mugs; ΟΔΟΣ cups, Straße", damage "Corner crushed", one contents photo.
   - A task, a cost of 1e21, a supply.
   - Adds "Priya 🏠" as a Decider.
   → `move-data.json` and `backup.json`
2. Priya's phone, on its own until now, joins with the invite. → `sync-join.json`
3. Priya adds a lamp with a photo. Asha adds box L002 with a photo. They sync with the plain code. → `sync-plain.json`
4. Hellos that the hosting phone turns down. → `sync-refusals.json`
5. Priya adds a bookcase and sends Asha an update file. Asha opens it. → `update.json`

## The files

### modified-utf8.json: strings and integers on the sync wire

What it holds:
- `strings`: each string as `DataOutputStream.writeUTF` writes it (Java's modified UTF-8), and as `toByteArray()` writes
  it (standard UTF-8).
  - In modified UTF-8, the length comes first as two big-endian bytes, NUL is `c080`, and every UTF-16 code unit is
    encoded on its own. So "🏠" is the two three-byte groups `eda0bc edbfa0`, and a lone surrogate survives.
  - Standard UTF-8 writes "🏠" as `f09f8fa0` and a lone surrogate as `3f` ('?').
- `long`: strings near the 65,535-byte limit, where one more character makes `writeUTF` throw.
- `readUTF`: how Android reads what another phone writes.
  - It accepts a raw zero byte and an overlong `c180`.
  - It **refuses four-byte UTF-8** (`f0…`). An iPhone that writes "🏠" as standard UTF-8 in a `writeUTF` field would make
    an Android phone fail the sync as "could not be read".
- `decodeToString`: standard UTF-8 decoding of bad bytes. Each malformed piece becomes U+FFFD.
- `writeInt`, `writeLong`: big-endian, two's complement.

A port must encode every `writeUTF` field (magic, token, names, letters, words, hello JSON, file names) exactly like this,
and decode with the same results and errors.

### doubles.json: Doubles as text, as bits and as JSON

For each Double, including 0.1, 1e7, 1e-3, 1e-4, -0.0, 1e21 and NaN, it gives:
- `toString`: JVM `Double.toString`, for example `1.0E7`, `1.0E-4`, `-0.0`, `NaN`. Record text uses this, and merges
  compare record text.
- `doubleToLongBits`: NaN made canonical. This is what `seal()` hashes, so `seal`.
- `json`: AppJson's encoding of `Expense(id = "e", amount = d)`. `-0.0` is written and `0.0` is left out as the default.
  NaN and the infinities throw.
- `fmtNum`: the screens' number text. It has quirks a port must keep: 1e-3 shows as "0.0", 12345678.9 as "1.23456789E7",
  1e21 as "2.147483647E7" (because `roundToInt` clamps), and NaN throws.
- `expenseToString`: the record's `toString` with that Double in it.

`numberInput` shows what the number fields (`ui/Common.kt`, `NumberInput`) make of typed or pasted text. "NaN",
"Infinity", "0x1p4", "5d" and a 310-digit number are all accepted. Arabic-Indic and full-width digits are not.

### seal.json: check values

What it holds:
- `about`: the algorithm.
- `cases`: typed parts (`null`, `string`, `double` with raw bits, `long`, `int`, `boolean`, `enum`), the exact text
  hashed for each part (`hashed`), and the seal.
- `records`: representative records as AppJson, with every `*Sum()` of `data/Rules.kt` worked out over them.
- `fixed`: `deletedSum()` and `retiredSum(...)`.

Collisions that are part of 2.1.1:
- seal(null) equals seal("\u0000").
- seal(5), seal(5L) and seal("5") are equal.
- seal(-0.0) equals seal(Long.MIN_VALUE).
- deletedSum() equals seal(true), the paid, done and cleared sums of "true".

The port must hash UTF-16 code units, with Long overflow, and print the result in base 36.

### records.json: record text and merge tie-breaks

`records` holds one record of every type, as AppJson, with:
- its Kotlin data-class `toString()`;
- `ownPart`, the text `Merge.kt` compares first when two copies have the same `updatedAt`. It is read from the private
  `ownPart` by reflection.

Also included: MoveInfo, Stamp, Grant, and a PhoneEntry in the form `mergePhones` compares.

`tieBreaks` holds pairs of copies with the same time, the texts compared, and the winner. Every case is checked to give the
same winner whichever phone merges. **This is where Double formatting decides data:**
- 1e-4 beats 1e-3, because "1.0E-4" > "0.001".
- 0.0 beats -0.0.
- NaN beats 1e21.

A port whose `Double.toString` printed 1e-4 as "0.0001" would choose the other copy. An Android phone and an iPhone would
then each keep their own copy, sync after sync, and never agree.

Strings compare by UTF-16 code units, so an emoji sorts before U+FF01.

A port must produce the same `toString` and `ownPart` text, and the same winners.

### hashes.json: String.hashCode, digests, ids and text rules

| Section | What it holds |
|---|---|
| `hashCode` | `String.hashCode` for ids, with the `floorMod` values used |
| `legacyLabels` | the label block a pre-2.1 phone gets from its letter (`data/Phone.kt`) |
| `letterInstead` | the letter a person takes when two share one (`data/People.kt`, which starts at `floorMod(id.hashCode(), 26)`) |
| `derivedMoveId` | the move ID worked out from a founder: `"m"` + the first 20 hex characters of SHA-256 |
| `moveTagAndDailyKey` | the four-character tag printed in label QR codes, and the daily backup's key |
| `digest` | SHA-256 over `toByteArray()`, as lowercase hex (`"%02x"`) |
| `lineId` | a box contents line's id, `boxId + "/" + seal(text.trim().lowercase())`, with the lowercased text |
| `storedLines` | how a contents text splits into lines |
| `refusedId` | the record a refused change becomes, and its id |
| `caseAndTrim` | `lowercase()`, `uppercase()` and `trim()` on hard cases |
| `compareTo` | the sign of `String.compareTo` |

Kotlin on the JVM behaves like this, and a port must match it:
- `lowercase()` and `uppercase()` use Locale.ROOT with full case mapping:
  - İ → "i̇" (two characters)
  - a final Σ → ς, but Σ on its own → σ
  - ẞ → ß, and ß uppercases to "SS"
  - the Kelvin sign → k, and ﬃ uppercases to "FFI"
- `trim()` removes U+00A0, U+2007, U+3000 and U+001C–U+001F, but not U+200B or DEL.
- A lone surrogate becomes '?' in `toByteArray()`, so `digest("x\uD800y")` equals `digest("x?y")`. Kotlin/Native's
  `encodeToByteArray` writes U+FFFD instead.

### invites.json: invite and sync-code text

What it holds:
- `encode`: `WifiSync.Invite.encode()` for each kind of invite, and what parsing gives back.
- `parse`: hand-written texts and what `Invite.parse` makes of them. `null` means refused.
- `urlEncode` and `urlDecode`: `java.net.URLEncoder` and `URLDecoder` with UTF-8.
  - Encoding: a space becomes '+'. `*`, `-`, `.` and `_` stay as they are. `~` becomes `%7E`. A lone surrogate becomes `%3F`.
  - Decoding: a malformed `%` throws, and the invite is refused. A cut-short UTF-8 sequence becomes U+FFFD.

Things to know:
- An IPv6 address cannot be in an invite: its colons split the text.
- A port must make the same text from the same invite, and must parse every text the same way.

### json.json: AppJson

`AppJson` is defined in `data/Store.kt`: kotlinx.serialization with `ignoreUnknownKeys`, `encodeDefaults = false` and
`coerceInputValues`. Fields are written in declaration order. `AppData.version` is always written. There are no spaces,
and non-ASCII characters are written as they are.

What it holds:
- `encode`: records and the text AppJson writes for them, including the escaping of quotes, controls, U+2028, emoji and a
  lone surrogate.
- `decode`: inputs and what they decode to, encoded again.
  - Unknown keys are dropped.
  - An unknown enum value, or a null for a field that has a default, takes the default.
  - Quoted numbers are accepted, and the last of two duplicate keys wins.
  - 1e400, NaN, a missing required field, a Long beyond Long, and a trailing comma are all refused.

### qr.json: QR texts and matrices

What it holds:
- `labelText`: `labelQrText(code, moveTag)`, for example `HM2:8F0B:L042`, or `HM:L042` before 2.1.
- `readLabel`: what a scanned text reads as, and `labelFromQr`.
- `matrices`: the QR codes for a label, an older label, an invite and a plain sync code. Each gives zxing 3.5.3's mode,
  version, error-correction level (M) and mask, and every module (`#` dark, `.` light).
- `drawn`: shows that `Qr.matrix(text, 240)` (label sheets) and `Qr.matrix(text, 640)` (screens) are those modules scaled
  by a whole number, centred, with a quiet zone of one module.

Things to know:
- `take(24)` on a scanned code can cut a surrogate pair in half.
- No character set is given to zxing, so byte mode is ISO-8859-1. The texts 2.1.1 encodes are ASCII: printed labels are
  L-numbers, and names in invites are URL-encoded.
- A port's encoder need not choose the same mask to be read. To match module for module, it must use error correction M
  and the same version and mask.

### crypto.json: the building blocks of update files

These come from `data/Sealed.kt`, on fixed inputs.

What it holds:
- HKDF-SHA256: one block, so at most 32 bytes, and an empty salt is 32 zero bytes. The first case is RFC 5869 test 1.
- AES-256-GCM chunks:
  - nonce = the 8-byte prefix + the chunk number, as 4 big-endian bytes;
  - AAD = SHA-256(header) + the chunk number + 1 for the last chunk, or 0.
- A 150,000-byte stream through `SealedOut`: three chunks, their tags, and the SHA-256 of the whole output.

An update-file port, a later step, must reproduce these exactly.

### move-data.json: a saved move

This is Asha's `data.json` right after step 1, byte for byte. It is what the Store writes, and the same text that goes
over the sync and into a backup.

A port must decode it and encode it back to the same bytes. Field names, defaults left out, number text (`1.0E7`, `1.0E-4`,
`-0.0`, `1.0E21`), stamps and check values must all match.

### sync-join.json and sync-plain.json: two syncs, byte for byte

Every message of step 2 (an invite join) and step 3 (a plain sync), in order, as it went over the socket. Each message
gives the full `hex`, and `fields` saying what each part is, with its offset and length:

| Step | Who sends | Fields |
|---|---|---|
| 1 | joiner | `HMSYNC3`, token, name, letter, schema (int 5), its own Person as JSON, its hello as JSON |
| 2 | host | `OK`, name, letter, its hello as JSON (with its Person in it) |
| 3 | joiner | `GO`, then the data: a four-byte length and standard-UTF-8 AppJson |
| 4 | host | the merged data |
| 5, 6 | joiner, host | the photo files each holds: a count, then names |
| 7, 8 | joiner, host | the files each sends: name, size as a long, bytes, …, then an empty name |

All strings are `writeUTF`, so the hello JSON with "Priya 🏠" in it is modified UTF-8. The data is `toByteArray()`.

A port must:
- given the same phones in the same state, write exactly these bytes;
- read these bytes with the same result;
- (plan step 4) take the joiner's or the host's part against a 2.1.1 peer.

The data payloads decode with AppJson. Their SHA-256 is given.

### sync-refusals.json: hellos turned down, and every word

What it holds:
- `cases`: what a 2.1.1 host answers to:
  - a wrong code: `NO`;
  - another data layout: `VERSION` then the int 5;
  - version 1.3's hello: `VERSION`;
  - 1.3's hello from a phone on this layout: `AGAIN`;
  - version 1.2's hello: `NO`.
- `words`: every word either phone says, as `writeUTF` bytes.

### backup.json: a backup, which holds random bytes

`sample` is a backup made after step 1: a zip of `data.json`, `about.json` and `photos/…`.

Random in it:
- `about.json`'s `at`, which is `System.currentTimeMillis`;
- every entry's DOS time and date, in the phone's local time;
- therefore `about.json`'s compressed bytes, CRC and sizes, and every offset after them.

`deterministic` is what must hold in every backup 2.1.1 makes of this move:
- entry names and order, and version 20;
- flags `0x0808`: a data descriptor with its signature after the data, and UTF-8 names;
- DEFLATE, and no extra fields;
- the uncompressed size, CRC and SHA-256 of `data.json` (equal to `move-data.json`) and of the photo;
- `about.json` without `at`.

`opened` is what today's code says when it peeks at the sample, and when it restores the sample on a phone that has
nothing: that phone becomes Asha, Organiser, with 7 records and 1 photo.

The compressed bytes come from the JVM's zlib. Another deflate need not match them; it only has to be readable. A port's
zip reader must accept data descriptors.

### update.json: an update file, which holds random bytes

What it holds:
- `sample`: the update Priya's phone made for Asha's phone in step 5.
- `recipient`: Asha's phone ID, key pair, move ID, and the files of her folder just before the update came. A Store
  started from them is Asha's phone at that moment.
- `sender`: Priya's phone ID and key pair.
- `intermediate`: everything met on the way in, worked out from the sample and Asha's private key: the header, its
  SHA-256, the ECDH secret, the KEK, the content key, the nonce prefix, each chunk's nonce, AAD and length, and the inner
  zip.
- `deterministic`: what every such update file holds: the magic `HMUPDATE`, format 1, the header without `at`, `nonce`
  and the wrapped keys, the chunk count, and the inner zip's entries.
- `opened`: what today's code says when it opens the sample on that folder: "Taken in from Priya 🏠: 1 change."

Random in it: the header's `at`, the nonce prefix, the content key, the ephemeral key, salt and IV of each recipient, so
every byte after the header, and the inner zip's DOS times.

The iPhone v1 leaves update files out. When they come, a port must open the sample, reproduce `intermediate` from it, and
write files that today's code opens.

## What could not be made fixed, and how it was handled

| What | Why it is random | How the fixtures deal with it |
|---|---|---|
| Phone IDs, phone key pairs, a new phone's letter | drawn at first start | written into the phone's folder before it starts |
| Ids (`UUID.randomUUID`) | the app's `idSource` | swapped for a counter (the test hook `idSource`) |
| Clocks | the system clock | `Store(dir) { fixedTime }` and the host's clock |
| Invite code, sync code | `SecureRandom` | the Host is built through its internal constructor with a fixed code; the invite's code is moved to a fixed one in the Host's private `personal` map, by reflection |
| Backup | about.json `at`, zip DOS times (`System.currentTimeMillis`, not injectable) | recorded as a sample; what is fixed is checked |
| Update file | content key, nonce prefix, ephemeral keys, salts and IVs (a private `SecureRandom` in `Sealing`), header `at`, zip DOS times | the same; the crypto steps are fixed in `crypto.json`, and `intermediate` is worked out from the sample |
| A 2.0 move's derived ID with no people | `randomCode` | not covered |

If the hooks are renamed in the port (`idSource`, `WifiSync.Host`'s constructor and its `personal` map, Merge.kt's
`ownPart`), adjust `GoldenMove.kt` and `GoldenFixturesTest.kt`. The fixtures stay as they are.

## Language, number system and time zone

Nothing in these files depends on the JVM's default locale or time zone. `GoldenFixturesTest` makes every fixture again
under tr-TR, ar-EG (Arabic digits), fa-IR, th-TH (Thai digits), hi-IN, de-DE and ja-JP (Japanese calendar), with time
zones from UTC−3:30 to UTC+14. It also plays the fixed move in ar-EG at UTC+5:45. Everything comes out the same.

The data layer formats numbers with `"%02x"`, which is not localised; `"%d"` would give Arabic digits. Locale-dependent
calls are kept out of the data layer, or are given Locale.ROOT or Locale.US.

What does follow the phone's language or time zone, by design, is display, plus the day of `today()`:
- `fmtDay` and `fmtDayShort` show month and day names in the phone's language.
- `today()` uses the phone's time zone. It decides the day `markDone` records, which is synced, and when a Helper's copy
  expires.
- A zip's DOS times are in the phone's local time.
- The number fields do not take Arabic-Indic or full-width digits (see `doubles.json`).
