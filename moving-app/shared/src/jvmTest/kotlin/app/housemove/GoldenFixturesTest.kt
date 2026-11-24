package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.UTFDataFormatException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Golden fixtures: values and bytes that HouseMove 2.1.1 makes, kept in src/jvmTest/resources/golden/ so that every later
 * version, and the Kotlin Multiplatform port for the iPhone (on the JVM and on iOS), can be checked against them. An
 * iPhone has to sync with phones on 2.1.1 and work out the same check values, ids, tie-breaks and bytes, so these files
 * are the yardstick. golden/README.md says how each was made and what a port must do with it.
 *
 * Normally (check mode) every fixture is made again from today's code and must match the file exactly; the backup and
 * the update file, which hold random bytes, must open with today's code as they did, and what in them is not random
 * must match.
 *
 * Record mode rewrites the files. Only do this on purpose, when a change to what 2.1.1 makes is intended and every
 * port is going to follow it:
 *
 *     -Dgolden.record=true          on the test JVM (an IDE run, or JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Dgolden.record=true" for Gradle)
 *     GOLDEN_RECORD=true            in the environment, e.g.  GOLDEN_RECORD=true tools/test.sh --tests app.housemove.GoldenFixturesTest
 *
 * (shared/build.gradle.kts does not pass -D options through to the test JVM, hence the two ways.) Recording plays the
 * fixed move twice and refuses to write anything that did not come out the same both times.
 */
class GoldenFixturesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ==== the fixtures that are worked out from inputs alone ==========================================================

    @Test
    fun modifiedUtf8AndIntegers() = golden("modified-utf8.json", render(modifiedUtf8()))

    @Test
    fun doubles() = golden("doubles.json", render(doublesFixture()))

    @Test
    fun seals() = golden("seal.json", render(sealFixture()))

    @Test
    fun recordTextAndTieBreaks() = golden("records.json", render(recordsFixture()))

    @Test
    fun hashesIdsAndText() = golden("hashes.json", render(hashesFixture()))

    @Test
    fun invites() = golden("invites.json", render(invitesFixture()))

    @Test
    fun jsonSettings() = golden("json.json", render(jsonFixture()))

    @Test
    fun qrCodes() = golden("qr.json", render(qrFixture()))

    @Test
    fun updateFileCrypto() = golden("crypto.json", render(cryptoFixture()))

    /** None of the above changes with the phone's language, number system or time zone. */
    @Test
    fun localeAndTimeZoneChangeNothing() {
        val plain = withDefaults(Locale.ROOT, "UTC") { pureFixtures() }
        for ((tag, zone) in LOCALES) {
            val there = withDefaults(Locale.forLanguageTag(tag), zone) { pureFixtures() }
            for ((name, text) in plain) assertEquals("$name made with the default locale $tag and time zone $zone", text, there.getValue(name))
        }
    }

    // ==== the fixed move: its data, its syncs, a backup and an update file ============================================

    @Test
    fun fixedMove() {
        val run = GoldenMove.run(tmp.newFolder("run1"))
        val made = scenarioFixtures(run)
        if (RECORD) {
            // Recorded only when a second run comes out the same, byte for byte, except where the app draws random bytes.
            val again = scenarioFixtures(GoldenMove.run(tmp.newFolder("run2")))
            for ((name, text) in made) assertEquals("$name came out differently on a second run, so it cannot be recorded", text, again.getValue(name))
            for ((name, text) in made) golden(name, text)
            // The update opens the same on a phone started from the recorded folder as it did on Asha's phone.
            assertEquals(openedJson(run.opened), openedJson(openFromFolder(run.recipient, run.update)))
            golden("backup.json", render(backupFixture(run)))
            golden("update.json", render(updateFixture(run)))
            return
        }
        for ((name, text) in made) golden(name, text)
        // What is not random in a backup and an update file made today is what was recorded.
        val backup = parse("backup.json")
        assertEquals("backup.json: what a backup made today holds", backup.getValue("deterministic"), backupFacts(run.backup, run.moveData))
        val update = parse("update.json")
        assertEquals("update.json: what an update file made today holds", update.getValue("deterministic"), updateFacts(run.update))
        assertEquals("the update file carries Priya's data", run.senderData, zipContents(openUpdate(run.update).zip).getValue("data.json").decodeToString())
        assertEquals("update.json: the recipient's folder", update.getValue("recipient").jsonObject.getValue("files"), filesJson(run.recipient))
        assertEquals("update.json: what opening it said", update.getValue("opened"), openedJson(run.opened))
    }

    /** The fixed move does not depend on the phone's language or time zone either. */
    @Test
    fun fixedMoveIsTheSameInAnotherLocale() {
        val plain = withDefaults(Locale.ROOT, "UTC") { scenarioFixtures(GoldenMove.run(tmp.newFolder("root"))) }
        val there = withDefaults(Locale.forLanguageTag("ar-EG-u-nu-arab"), "Asia/Kathmandu") { scenarioFixtures(GoldenMove.run(tmp.newFolder("ar"))) }
        for ((name, text) in plain) assertEquals("$name in ar-EG, Asia/Kathmandu", text, there.getValue(name))
    }

    @Test
    fun recordedBackupOpens() {
        if (RECORD) return // checked by fixedMove while recording
        val f = parse("backup.json")
        val bytes = unhex(f.getValue("sample").jsonObject.getValue("hex").jsonPrimitive.content)
        val moveData = read("move-data.json") ?: throw AssertionError("golden/move-data.json is missing")
        assertEquals(f.getValue("deterministic"), backupFacts(bytes, moveData))
        assertEquals(f.getValue("opened"), backupOpened(bytes, moveData))
    }

    @Test
    fun recordedUpdateOpens() {
        if (RECORD) return // checked by fixedMove while recording
        val f = parse("update.json")
        val bytes = unhex(f.getValue("sample").jsonObject.getValue("hex").jsonPrimitive.content)
        val files = f.getValue("recipient").jsonObject.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(f.getValue("intermediate"), updateIntermediate(bytes))
        assertEquals(f.getValue("deterministic"), updateFacts(bytes))
        assertEquals(f.getValue("opened"), openedJson(openFromFolder(files, bytes)))
    }

    /** Open update file [bytes] on a phone started from the folder [files] (file name to text). */
    private fun openFromFolder(files: Map<String, String>, bytes: ByteArray): Update.Opened {
        val dir = tmp.newFolder()
        for ((name, text) in files) File(dir, name).writeText(text)
        return GoldenIds.using(GoldenIds()) {
            val s = Store(dir) { GoldenMove.T_PRIYA }
            try {
                Update.open(s, ByteArrayInputStream(bytes))
            } finally {
                s.close()
            }
        }
    }

    // ==== fixture files ===============================================================================================

    companion object {
        /** Rewrite the fixtures instead of checking them. See the class comment. */
        val RECORD: Boolean = System.getProperty("golden.record") == "true" || System.getenv("GOLDEN_RECORD") == "true"

        /** The fixtures in the source tree: the tests run in shared/, or in the project folder. */
        val SOURCE: File? = listOf(".", "shared", "moving-app/shared").map { File(it).absoluteFile }
            .firstOrNull { File(it, "src/jvmTest/kotlin/app/housemove").isDirectory }?.let { File(it, "src/jvmTest/resources/golden") }

        /** Default locales and time zones tried: other digits, other case rules, odd offsets. */
        val LOCALES = listOf(
            "tr-TR" to "Europe/Istanbul", "ar-EG-u-nu-arab" to "Africa/Cairo", "fa-IR" to "Asia/Tehran", "th-TH-u-nu-thai" to "Asia/Bangkok",
            "hi-IN" to "Asia/Kolkata", "de-DE" to "Pacific/Kiritimati", "ja-JP-u-ca-japanese" to "America/St_Johns",
        )

        @OptIn(ExperimentalSerializationApi::class)
        private val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }

    private fun read(name: String): String? {
        SOURCE?.let { File(it, name) }?.takeIf { it.isFile }?.let { return it.readText() }
        return javaClass.getResourceAsStream("/golden/$name")?.use { it.readBytes().decodeToString() }
    }

    private fun parse(name: String): JsonObject =
        Json.parseToJsonElement(read(name) ?: throw AssertionError("golden/$name is missing: record the fixtures (see GoldenFixturesTest)")).jsonObject

    /** Compare [made] with the fixture [name], or write it in record mode. */
    private fun golden(name: String, made: String) {
        if (RECORD) {
            val dir = SOURCE ?: throw AssertionError("cannot find src/jvmTest/resources from ${File(".").absolutePath}")
            dir.mkdirs()
            File(dir, name).writeText(made)
            println("recorded golden/$name (${made.length} characters)")
            return
        }
        val want = read(name) ?: throw AssertionError("golden/$name is missing: record the fixtures (see GoldenFixturesTest)")
        if (want == made) return
        val a = want.split('\n')
        val b = made.split('\n')
        val i = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size)
        fun around(s: String?, at: Int) = s?.let { if (it.length <= 240) it else it.substring(maxOf(0, at - 80), minOf(it.length, at + 160)) }
        val col = (a.getOrNull(i) ?: "").zip(b.getOrNull(i) ?: "").indexOfFirst { it.first != it.second }.let { if (it < 0) 0 else it }
        throw AssertionError(
            "golden/$name is not what today's code makes (line ${i + 1}, column ${col + 1}).\n" +
                "  recorded: ${around(a.getOrNull(i), col)}\n  made now: ${around(b.getOrNull(i), col)}\n" +
                "If the change is intended, every port has to follow it: re-record (see GoldenFixturesTest) and say so in the commit.",
        )
    }

    private fun render(e: JsonElement): String = escapeLoneSurrogates(PRETTY.encodeToString(JsonElement.serializer(), e)) + "\n"

    /** A lone surrogate cannot be written as UTF-8, so inside the fixtures it is written as a \u escape (which JSON readers take back). */
    private fun escapeLoneSurrogates(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                b.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c.isSurrogate()) b.append("\\u").append(c.code.toString(16).padStart(4, '0')) else b.append(c)
            i++
        }
        return b.toString()
    }

    private fun <T> withDefaults(locale: Locale, zone: String, block: () -> T): T {
        val l = Locale.getDefault()
        val z = TimeZone.getDefault()
        Locale.setDefault(locale)
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            return block()
        } finally {
            Locale.setDefault(l)
            TimeZone.setDefault(z)
        }
    }

    private fun pureFixtures(): Map<String, String> = mapOf(
        "modified-utf8.json" to render(modifiedUtf8()), "doubles.json" to render(doublesFixture()), "seal.json" to render(sealFixture()),
        "records.json" to render(recordsFixture()), "hashes.json" to render(hashesFixture()), "invites.json" to render(invitesFixture()),
        "json.json" to render(jsonFixture()), "qr.json" to render(qrFixture()), "crypto.json" to render(cryptoFixture()),
    )

    // ==== small helpers =================================================================================================

    private fun hex(b: ByteArray): String = b.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) + Character.digit(s[2 * it + 1], 16)).toByte() }

    private fun utf16(s: String): String = s.map { it.code.toString(16).padStart(4, '0') }.joinToString(" ")

    private fun sha(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun plainAscii(s: String) = s.all { it.code in 0x20..0x7e }

    /**
     * A string, with its UTF-16 code units beside it when it is short and not plain printable ASCII (for the reader: the
     * JSON string itself is exact, with a lone surrogate or a control character written as a \u escape).
     */
    private fun JsonObjectBuilder.text(key: String, s: String) {
        put(key, s)
        if (!plainAscii(s) && s.length <= 64) put(key + "Utf16", utf16(s))
    }

    private fun writeUtf(s: String): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).writeUTF(s) }.toByteArray()

    private fun bytesOf(f: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { b -> DataOutputStream(b).also(f).flush() }.toByteArray()

    private fun errorName(e: Throwable): String = "error: " + e.javaClass.simpleName

    private fun json(r: Any): String = when (r) {
        is Item -> AppJson.encodeToString(Item.serializer(), r)
        is Box -> AppJson.encodeToString(Box.serializer(), r)
        is Room -> AppJson.encodeToString(Room.serializer(), r)
        is Photo -> AppJson.encodeToString(Photo.serializer(), r)
        is BoxEntry -> AppJson.encodeToString(BoxEntry.serializer(), r)
        is Damage -> AppJson.encodeToString(Damage.serializer(), r)
        is Person -> AppJson.encodeToString(Person.serializer(), r)
        is Task -> AppJson.encodeToString(Task.serializer(), r)
        is Expense -> AppJson.encodeToString(Expense.serializer(), r)
        is Supply -> AppJson.encodeToString(Supply.serializer(), r)
        is Trip -> AppJson.encodeToString(Trip.serializer(), r)
        is Reading -> AppJson.encodeToString(Reading.serializer(), r)
        is Proposal -> AppJson.encodeToString(Proposal.serializer(), r)
        is Activity -> AppJson.encodeToString(Activity.serializer(), r)
        is Hidden -> AppJson.encodeToString(Hidden.serializer(), r)
        is Collect -> AppJson.encodeToString(Collect.serializer(), r)
        is ContentsLine -> AppJson.encodeToString(ContentsLine.serializer(), r)
        is RefusedChange -> AppJson.encodeToString(RefusedChange.serializer(), r)
        is MoveInfo -> AppJson.encodeToString(MoveInfo.serializer(), r)
        is PhoneEntry -> AppJson.encodeToString(PhoneEntry.serializer(), r)
        is Stamp -> AppJson.encodeToString(Stamp.serializer(), r)
        is Grant -> AppJson.encodeToString(Grant.serializer(), r)
        is AppData -> AppJson.encodeToString(AppData.serializer(), r)
        else -> error("no serializer for ${r.javaClass}")
    }

    /** AppJson of [r], or why it cannot be written (a NaN or an infinity in it). */
    private fun jsonOrError(r: Any): String = try {
        json(r)
    } catch (e: Exception) {
        errorName(e)
    }

    /** What a merge compares two copies of a record by when their times are equal (Merge.kt, ownPart). */
    private val ownPartFn = Class.forName("app.housemove.data.MergeKt").getDeclaredMethod("ownPart", Rec::class.java).apply { isAccessible = true }

    private fun ownPart(r: Rec): String = ownPartFn.invoke(null, r) as String

    // ==== modified UTF-8 (writeUTF) and the integers of the sync wire ==================================================

    private val STRINGS = listOf(
        "ascii" to "Asha",
        "emoji, a surrogate pair" to "Priya 🏠",
        "NUL" to "a\u0000b",
        "accented, precomposed" to "Zoë Café",
        "accented, decomposed" to "Zoë Café",
        "CJK" to "日本語の箱",
        "two- and three-byte" to "£5 €5",
        "lone high surrogate" to "x\uD83Cy",
        "lone low surrogate" to "x\uDFE0y",
        "a pair the wrong way round" to "\uDFE0\uD83C",
        "empty" to "",
        "control characters" to "tab\there\nnew\r",
        "BOM and U+FFFF" to "﻿start￿",
        "no-break and ideographic spaces" to " spaced　",
        "DEL and C1 controls" to "\u007F\u0080\u009F",
        "the edges of two and three bytes" to "\u007F\u0080߿ࠀ￿",
    )

    private fun modifiedUtf8(): JsonObject = buildJsonObject {
        put(
            "about",
            "Strings as DataOutputStream.writeUTF writes them (Java's modified UTF-8: a two-byte big-endian length, NUL as C0 80, " +
                "every UTF-16 code unit on its own, so a surrogate pair is two three-byte groups and a lone surrogate is kept), and as " +
                "String.toByteArray() writes them (standard UTF-8; a lone surrogate becomes '?'). The sync wire uses writeUTF for every " +
                "string and toByteArray for the JSON of the data.",
        )
        putJsonArray("strings") {
            for ((name, s) in STRINGS) addJsonObject {
                put("name", name)
                text("text", s)
                put("writeUTF", hex(writeUtf(s)))
                put("toByteArray", hex(s.toByteArray()))
            }
        }
        putJsonArray("long") {
            val cases = listOf(
                Triple("three-byte CJK, at the limit", "日", 21845), Triple("three-byte CJK, one over", "日", 21846),
                Triple("emoji, six bytes each", "🏠", 10922), Triple("emoji, one over", "🏠", 10923),
                Triple("NUL, two bytes each", "\u0000", 32767), Triple("NUL, one over", "\u0000", 32768),
                Triple("ASCII, at the limit", "a", 65535), Triple("ASCII, one over", "a", 65536),
            )
            for ((name, unit, times) in cases) addJsonObject {
                put("name", name)
                text("unit", unit)
                put("times", times)
                try {
                    val b = writeUtf(unit.repeat(times))
                    put("utfLength", b.size - 2)
                    put("head", hex(b.copyOf(8)))
                    put("sha256", sha(b))
                } catch (e: UTFDataFormatException) {
                    put("writeUTF", errorName(e))
                }
            }
        }
        putJsonArray("readUTF") {
            val cases = listOf(
                "empty" to "0000", "a raw zero byte is read as NUL" to "000100", "NUL as written" to "0002c080",
                "emoji as written" to "0006eda0bcedbfa0", "emoji as standard UTF-8: refused" to "0004f09f8fa0",
                "lone surrogate" to "0003eda0bc", "an overlong two-byte '@' is accepted" to "0002c180",
                "a stray continuation byte" to "000180", "shorter than its length" to "0005414243",
            )
            for ((name, h) in cases) addJsonObject {
                put("name", name)
                put("hex", h)
                try {
                    text("text", DataInputStream(ByteArrayInputStream(unhex(h))).readUTF())
                } catch (e: IOException) {
                    put("result", errorName(e))
                }
            }
        }
        putJsonArray("decodeToString") {
            val cases = listOf(
                "emoji" to "f09f8fa0", "emoji in modified UTF-8" to "eda0bcedbfa0", "NUL in modified UTF-8" to "c080",
                "a byte that is never UTF-8" to "ff", "cut short" to "e282", "Zoë" to "5a6fc3ab",
            )
            for ((name, h) in cases) addJsonObject {
                put("name", name)
                put("hex", h)
                text("text", unhex(h).decodeToString())
            }
        }
        putJsonArray("writeInt") {
            for (v in listOf(0, 5, -1, 65536, Int.MIN_VALUE, Int.MAX_VALUE)) addJsonObject {
                put("value", v)
                put("hex", hex(bytesOf { it.writeInt(v) }))
            }
        }
        putJsonArray("writeLong") {
            for (v in listOf(0L, 16L, -2L, GoldenMove.T_ASHA, Long.MAX_VALUE)) addJsonObject {
                put("value", v.toString())
                put("hex", hex(bytesOf { it.writeLong(v) }))
            }
        }
    }

    // ==== Doubles ======================================================================================================

    private val DOUBLES: List<Pair<String, Double>> = listOf(
        "0.1" to 0.1, "1e7" to 1e7, "1e-3" to 1e-3, "1e-4" to 1e-4, "-0.0" to -0.0, "1e21" to 1e21, "NaN" to Double.NaN,
        "0.0" to 0.0, "1.0" to 1.0, "12.5" to 12.5, "9999999.0" to 9999999.0, "12345678.9" to 12345678.9, "0.1 + 0.2" to 0.1 + 0.2,
        "1e-5" to 1e-5, "2.5e-7" to 2.5e-7, "1e20" to 1e20, "1e22" to 1e22, "1/3" to 1.0 / 3, "2/3" to 2.0 / 3, "-1.5" to -1.5,
        "1e16" to 1e16, "123456789012345680" to 123456789012345680.0, "MAX_VALUE" to Double.MAX_VALUE, "MIN_VALUE" to Double.MIN_VALUE,
        "smallest normal" to java.lang.Double.MIN_NORMAL, "Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY,
        "a NaN with other bits" to Double.fromBits(0x7ff8000000000001L), "2^53 + 2" to 9007199254740994.0, "100.0" to 100.0, "1e-3 + 1e-19" to Math.nextUp(1e-3),
    )

    private fun doublesFixture(): JsonObject = buildJsonObject {
        put(
            "about",
            "Doubles as 2.1.1 writes them: Double.toString (in record text, which merges compare), doubleToLongBits (what seal() hashes), " +
                "AppJson (kotlinx.serialization, as the Store saves and the sync sends), and fmtNum (what the screens show). " +
                "numberInput: what a number field makes of typed or pasted text (text.replace(',', '.').trim().toDoubleOrNull()).",
        )
        putJsonArray("doubles") {
            for ((name, d) in DOUBLES) addJsonObject {
                put("name", name)
                put("rawBits", java.lang.Long.toHexString(d.toRawBits()).padStart(16, '0'))
                put("toString", d.toString())
                put("doubleToLongBits", java.lang.Double.doubleToLongBits(d).toString())
                put("seal", seal(d))
                put("json", try { AppJson.encodeToString(Expense.serializer(), Expense(id = "e", amount = d)) } catch (e: Exception) { errorName(e) })
                put("fmtNum", try { fmtNum(d) } catch (e: Exception) { errorName(e) })
                put("expenseToString", Expense(id = "e", amount = d).toString())
            }
        }
        // What the number fields (ui/Common.kt, NumberInput) make of what is typed or pasted. NaN and infinities get in this way.
        putJsonArray("numberInput") {
            val typed = listOf(
                "12.5", "12,5", " 7 ", "1e7", "1E-4", "-0", ".5", "5.", "+5", "1,000.5", "1 000", "NaN", "nan", "Infinity", "-Infinity", "1" + "0".repeat(309),
                "0x10", "0x1p4", "5d", "5f", "5L", "١٢", "12٫5", "１２", "", "-",
            )
            for (t in typed) addJsonObject {
                text("typed", t)
                val v = t.replace(',', '.').trim().toDoubleOrNull()
                put("value", v?.toString() ?: "null")
                if (v != null) put("rawBits", java.lang.Long.toHexString(v.toRawBits()).padStart(16, '0'))
            }
        }
    }

    // ==== seal() ========================================================================================================

    private fun part(p: Any?): JsonObject = buildJsonObject {
        when (p) {
            null -> put("type", "null")
            is String -> { put("type", "string"); text("value", p) }
            is Double -> { put("type", "double"); put("value", p.toString()); put("rawBits", java.lang.Long.toHexString(p.toRawBits()).padStart(16, '0')) }
            is Long -> { put("type", "long"); put("value", p.toString()) }
            is Int -> { put("type", "int"); put("value", p) }
            is Boolean -> { put("type", "boolean"); put("value", p) }
            is Enum<*> -> { put("type", "enum"); put("value", p.name) }
            else -> error("part $p")
        }
        // The text seal() hashes for this part.
        text("hashed", when (p) {
            null -> "\u0000"
            is Double -> java.lang.Double.doubleToLongBits(p).toString()
            else -> p.toString()
        })
    }

    private val SEAL_CASES: List<Pair<String, List<Any?>>> = listOf(
        "nothing" to emptyList(), "null" to listOf(null), "empty string" to listOf(""), "a NUL character (the same as null)" to listOf("\u0000"),
        "two nulls" to listOf(null, null), "two empty strings" to listOf("", ""), "Asha" to listOf("Asha"), "emoji" to listOf("Priya 🏠"),
        "NUL inside" to listOf("a\u0000b"), "lone surrogate" to listOf("x\uD83Cy"), "true" to listOf(true), "false" to listOf(false),
        "Int 5" to listOf(5), "Long 5 (the same)" to listOf(5L), "String 5 (the same)" to listOf("5"), "-1" to listOf(-1L),
        "Long.MAX_VALUE" to listOf(Long.MAX_VALUE), "Long.MIN_VALUE" to listOf(Long.MIN_VALUE), "a decision" to listOf(Disposition.SELL, 20_600L),
        "where" to listOf(Stage.LOADED, null), "0.1" to listOf(0.1), "-0.0" to listOf(-0.0), "0.0" to listOf(0.0), "NaN" to listOf(Double.NaN),
        "1e21" to listOf(1e21), "a withdrawal" to listOf("withdrawn", "00000000-0000-4000-8000-000000000031", "00000000-0000-4000-8000-000000000020"),
        "İ" to listOf("İ"), "ß" to listOf("ß"), "1000 letters" to listOf("a".repeat(1000)),
        "a seal inside a seal" to listOf("Boxes", seal("Boxes", 1e-3), null),
    )

    private val ITEM = Item(
        id = "i1", name = "Sofa", disposition = Disposition.SELL, deadlineDay = 20_590, destRoomId = "r2", listed = true, gone = false,
        stage = Stage.LOADED, tripId = "t1", handoverTo = "u2", paid = true, handedOver = false, price = 1e7, soldFor = 1e21,
        widthCm = 0.1, depthCm = 1e-4, heightCm = -0.0, value = 1e-3,
    )
    private val BOX = Box(id = "b1", label = "L001", stage = Stage.DELIVERED, size = BoxSize.L, contents = "Plates, Mugs", priority = Priority.FIRST_NIGHT)
    private val HIDDEN = Hidden(id = "i1", value = 1e-3, make = "Sony", model = "X90", serial = "SN-0042", boughtDay = 19_000, price = 1e-4, soldFor = -0.0)
    private val COLLECT = Collect(id = "i1", to = "u2", amount = 0.1)
    private val DAMAGE = Damage(id = "d1", thingId = "b1", note = "Corner crushed", by = "u3", at = GoldenMove.T_ASHA, cleared = true)
    private val EXPENSE = Expense(id = "e1", title = "Van hire", amount = 1e21, category = "Van", day = 20_599, planned = true)
    private val TASK = Task(id = "t1", title = "Book the van", offsetDays = -28, category = "Admin", notes = "Ring 3 firms", assignedTo = "u2", done = true)
    private val MOVE = MoveInfo(name = "Maple Road", moveDay = 20_600, vanCapacityM3 = 12.5, currency = "£", kind = MoveKind.MOVERS, off = listOf("BUDGET", "PLAN"), setupDone = true)
    private val PROPOSALS = listOf(
        Proposal(id = "q1", kind = ProposalKind.ITEM_DECISION, targetId = "i1", by = "u2", disposition = Disposition.SELL, price = 0.1, state = ProposalState.APPROVED, reason = "ok"),
        Proposal(id = "q2", kind = ProposalKind.COST, targetId = "e2", by = "u2", expense = Expense(id = "e2", title = "Boxes", amount = 1e-3, planned = true)),
        Proposal(id = "q3", kind = ProposalKind.TASK, targetId = "t2", by = "u2", task = Task(id = "t2", title = "Pack books", offsetDays = -3)),
        Proposal(id = "q4", kind = ProposalKind.QUESTION, targetId = "b1", by = "u2", deletes = true),
        Proposal(id = "q5", kind = ProposalKind.COST, targetId = "e1", by = "u2", expense = EXPENSE.copy(deleted = true)),
        Proposal(id = "q6", kind = ProposalKind.ITEM_ROOM, targetId = "i1", by = "u2", roomId = "r2", state = ProposalState.OVERTAKEN, answers = "q7"),
    )

    private fun sealFixture(): JsonObject = buildJsonObject {
        put(
            "about",
            "seal() (data/Rules.kt): h = 1125899906842597 (a Long); for each part, the text hashed (null: one NUL character; a Double: " +
                "doubleToLongBits as a decimal Long; anything else: toString()), for each UTF-16 code unit c of it h = 31*h + c, then " +
                "h = 31*h + 31; all with Long overflow. The value is (h and 0xFFFFFFFFFF) in base 36, lower case.",
        )
        putJsonArray("cases") {
            for ((name, parts) in SEAL_CASES) addJsonObject {
                put("name", name)
                putJsonArray("parts") { parts.forEach { add(part(it)) } }
                put("seal", seal(*parts.toTypedArray()))
            }
        }
        putJsonArray("records") {
            fun record(type: String, r: Any, sums: List<Pair<String, String>>) = addJsonObject {
                put("type", type)
                put("json", json(r))
                putJsonObject("sums") { for ((k, v) in sums) put(k, v) }
            }
            record("Item", ITEM, listOf(
                "decisionSum" to ITEM.decisionSum(), "roomSum" to ITEM.roomSum(), "outcomeSum" to ITEM.outcomeSum(), "whereSum" to ITEM.whereSum(),
                "handoverSum" to ITEM.handoverSum(), "handedOverSum" to ITEM.handedOverSum(), "decisionSum20" to ITEM.decisionSum20(), "outcomeSum20" to ITEM.outcomeSum20(),
            ))
            record("Box", BOX, listOf("whereSum" to BOX.whereSum()))
            record("Hidden", HIDDEN, listOf("detailsSum" to HIDDEN.detailsSum(), "priceSum" to HIDDEN.priceSum(), "saleSum" to HIDDEN.saleSum()))
            record("Collect", COLLECT, listOf("collectSum" to COLLECT.collectSum()))
            record("Damage", DAMAGE, listOf("clearedSum" to DAMAGE.clearedSum()))
            record("Expense", EXPENSE, listOf("costSum" to EXPENSE.costSum(), "paidSum" to EXPENSE.paidSum()))
            record("Task", TASK, listOf("planSum" to TASK.planSum(), "doneSum" to TASK.doneSum()))
            record("MoveInfo", MOVE, listOf("settingsSum" to MOVE.settingsSum()))
            record("MoveInfo", MOVE.copy(doneDay = 20_610), listOf("settingsSum" to MOVE.copy(doneDay = 20_610).settingsSum()))
            for (p in PROPOSALS) record("Proposal", p, listOf("stateSum" to p.stateSum(), "withdrawnSum" to p.withdrawnSum()))
        }
        putJsonObject("fixed") {
            put("deletedSum", deletedSum())
            put("retiredSum(${GoldenMove.PHONE_ASHA})", retiredSum(GoldenMove.PHONE_ASHA))
        }
    }

    // ==== record text and merge tie-breaks =============================================================================

    private val PHONE_ENTRY = PhoneEntry(
        id = GoldenMove.PHONE_PRIYA, name = "Priya's phone", letter = "BA", labelsFrom = 1000, labelsTo = 1999, addedAt = GoldenMove.T_PRIYA,
        retired = Stamp("u1", GoldenMove.T_PRIYA + 5, retiredSum(GoldenMove.PHONE_PRIYA)), collected = true, key = GoldenMove.KEY_PRIYA.public, keyAt = 7,
    )
    private val PERSON = Person(
        id = "u2", name = "Priya 🏠", role = Role.DECIDER, letter = "B", joinedAt = GoldenMove.T_PRIYA, removed = false,
        grants = listOf(Grant(Role.DECIDER, false, GoldenMove.T_ASHA, "u1", GoldenMove.PHONE_ASHA), Grant(Role.HELPER, true, GoldenMove.T_PRIYA, "u1", "", thanks = true)),
        phones = listOf(PHONE_ENTRY), updatedAt = GoldenMove.T_PRIYA,
    )

    private val RECORDS: List<Pair<String, Rec>> = listOf(
        "Room" to Room(id = "r1", home = Home.DEST, name = "Zoë's room", code = "ZOE", color = 3, floor = "1", mapsTo = null, doorWidthCm = 1e-4, packed = true,
            createdBy = "u1", hands = listOf("u1", "u2"), deletedBy = Stamp("u1", 9, deletedSum()), updatedAt = 9, deleted = true),
        "Item" to ITEM.copy(label = "L042", originRoomId = "r1", fragile = true, electronic = null, notes = "a\u0000b", hands = listOf("u1"), whereBy = Stamp("u2", 5, ITEM.whereSum()), updatedAt = 5),
        "Box" to BOX.copy(hands = listOf("u3"), whereBy = Stamp("u3", 6, BOX.whereSum()), damaged = true, damageNote = "Corner crushed", parentId = "b0", updatedAt = 6),
        "Photo" to Photo(id = "ph1", ownerId = "b1", kind = PhotoKind.CONTENTS, file = "ph1.jpg", note = "", by = "u2", createdAt = 3, hands = listOf("u2"), updatedAt = 3),
        "BoxEntry" to BoxEntry(id = "be1", boxId = "b1", code = "9780141439518", title = "Pride and Prejudice", author = "Austen", createdAt = 4, hands = listOf("u3"), updatedAt = 4),
        "Damage" to DAMAGE.copy(clearedBy = Stamp("u1", 8, DAMAGE.clearedSum()), updatedAt = 8),
        "Person" to PERSON,
        "Task" to TASK.copy(planBy = Stamp("u1", 2, TASK.planSum()), updatedAt = 2),
        "Expense" to EXPENSE.copy(costBy = Stamp("u1", 2, EXPENSE.costSum()), updatedAt = 2),
        "Supply" to Supply(id = "s1", name = "Tape", need = 6, have = 2, updatedAt = 1),
        "Trip" to Trip(id = "t9", name = "Trip 1", sort = 1, from = Place.OLD_HOME, to = Place.STORAGE, updatedAt = 1),
        "Reading" to Reading(id = "m1", home = Home.ORIGIN, kind = "Electricity", value = "01234.5", day = 20_600, updatedAt = 1),
        "Proposal" to PROPOSALS[1].copy(decided = Stamp("u1", 7, PROPOSALS[1].stateSum()), createdAt = 6, updatedAt = 7),
        "Activity" to Activity(id = "a1", by = "u2", at = 5, text = "sealed L001 ✔", refId = "b1", updatedAt = 5),
        "Hidden" to HIDDEN.copy(detailsBy = Stamp("u1", 3, HIDDEN.detailsSum()), updatedAt = 3),
        "Collect" to COLLECT.copy(by = Stamp("u1", 3, COLLECT.collectSum()), updatedAt = 3),
        "ContentsLine" to ContentsLine(id = lineId("b1", "Plates"), boxId = "b1", text = "Plates", sort = 2, by = "u3", batch = 0, updatedAt = 2),
        "RefusedChange" to RefusedChange(id = "refused/x", by = "u3", at = 4, what = "outcome", targetId = "i1", tried = "mark {} gone", role = Role.HELPER, updatedAt = 4),
    )

    /** Pairs of copies of one record with the same time, which a merge tells apart by their text. */
    private val TIES: List<Triple<String, Rec, Rec>> = listOf(
        Triple("Expense amount 1e-3 against 1e-4", Expense(id = "e", amount = 1e-3, updatedAt = 5), Expense(id = "e", amount = 1e-4, updatedAt = 5)),
        Triple("Expense amount -0.0 against 0.0", Expense(id = "e", amount = -0.0, updatedAt = 5), Expense(id = "e", amount = 0.0, updatedAt = 5)),
        Triple("Expense amount 1e7 against 9999999.0", Expense(id = "e", amount = 1e7, updatedAt = 5), Expense(id = "e", amount = 9999999.0, updatedAt = 5)),
        Triple("Expense amount NaN against 1e21", Expense(id = "e", amount = Double.NaN, updatedAt = 5), Expense(id = "e", amount = 1e21, updatedAt = 5)),
        Triple("Expense amount 0.1 against 0.1 + 0.2", Expense(id = "e", amount = 0.1, updatedAt = 5), Expense(id = "e", amount = 0.1 + 0.2, updatedAt = 5)),
        Triple("Item width 0.1 against 1e-4 (by ownPart)", Item(id = "i", name = "Sofa", widthCm = 0.1, updatedAt = 5), Item(id = "i", name = "Sofa", widthCm = 1e-4, updatedAt = 5)),
        Triple("Item: only a protected part differs (ownPart equal; toString decides)", Item(id = "i", stage = Stage.LOADED, updatedAt = 5), Item(id = "i", stage = Stage.DELIVERED, updatedAt = 5)),
        Triple("Room door 1e21 against 1e20", Room(id = "r", home = Home.DEST, name = "Hall", doorWidthCm = 1e21, updatedAt = 5), Room(id = "r", home = Home.DEST, name = "Hall", doorWidthCm = 1e20, updatedAt = 5)),
        Triple("Hidden value 1e-3 against 0.001 + 1 ulp", Hidden(id = "h", value = 1e-3, updatedAt = 5), Hidden(id = "h", value = Math.nextUp(1e-3), updatedAt = 5)),
        Triple("Person name emoji against plain", PERSON.copy(name = "Priya 🏠"), PERSON.copy(name = "Priya")),
        Triple("Task title Zoë precomposed against decomposed", Task(id = "t", title = "Zoë", updatedAt = 5), Task(id = "t", title = "Zoë", updatedAt = 5)),
        Triple("Activity text emoji against U+FF01", Activity(id = "a", text = "😀", updatedAt = 5), Activity(id = "a", text = "！", updatedAt = 5)),
        Triple("a deletion against a live copy", Supply(id = "s", name = "Tape", deleted = true, updatedAt = 5), Supply(id = "s", name = "Tape!", updatedAt = 5)),
    )

    private fun recordsFixture(): JsonObject = buildJsonObject {
        put(
            "about",
            "Kotlin data-class toString() of each record (Model.kt), as 2.1.1 compares copies of a record with the same updatedAt: " +
                "Merge.kt newer() compares ownPart() (the record with what the merge settles itself blanked out) and then toString(), the " +
                "greater text winning; mergeMove compares MoveInfo.toString(); mergePhones compares a PhoneEntry without its retirement " +
                "and key. Strings compare by UTF-16 code units. Each record is also given as AppJson, so a port can decode the same one.",
        )
        putJsonArray("records") {
            for ((type, r) in RECORDS) addJsonObject {
                put("type", type)
                put("json", json(r))
                text("toString", r.toString())
                text("ownPart", ownPart(r))
            }
            for ((type, r) in listOf("MoveInfo" to MOVE.copy(changedBy = Stamp("u1", 1, MOVE.settingsSum()), updatedAt = 1, doneDay = 20_610, labelsPrinted = 99), "Stamp" to Stamp("u1", -1, "zz"), "Grant" to PERSON.grants[1]))
                addJsonObject {
                    put("type", type)
                    put("json", json(r))
                    text("toString", r.toString())
                }
            addJsonObject {
                put("type", "PhoneEntry, as mergePhones compares it")
                put("json", json(PHONE_ENTRY))
                text("toString", PHONE_ENTRY.copy(retired = Stamp(), collected = false, key = "", keyAt = 0).toString())
            }
        }
        putJsonArray("tieBreaks") {
            for ((name, x, y) in TIES) addJsonObject {
                put("name", name)
                put("a", jsonOrError(x))
                put("b", jsonOrError(y))
                text("ownPartA", ownPart(x))
                text("ownPartB", ownPart(y))
                text("toStringA", x.toString())
                text("toStringB", y.toString())
                val ab = mergeList(listOf(x), listOf(y)).single()
                val ba = mergeList(listOf(y), listOf(x)).single()
                assertEquals("$name: the same whichever phone merges", ab, ba)
                put("winner", when (ab) { x -> "a"; y -> "b"; else -> "neither: " + ab.toString() })
            }
            // MoveInfo, through a whole merge.
            val m1 = MOVE.copy(vanCapacityM3 = 1e-3, updatedAt = 5)
            val m2 = MOVE.copy(vanCapacityM3 = 1e-4, updatedAt = 5)
            addJsonObject {
                put("name", "MoveInfo van 1e-3 against 1e-4 (whole merge, no stamps)")
                put("a", json(m1))
                put("b", json(m2))
                text("toStringA", m1.toString())
                text("toStringB", m2.toString())
                val ab = merge(AppData(move = m1), AppData(move = m2)).move
                val ba = merge(AppData(move = m2), AppData(move = m1)).move
                assertEquals(ab, ba)
                put("winner", when (ab) { m1 -> "a"; m2 -> "b"; else -> "neither: $ab" })
            }
            // A phone of a person, as both phones know it.
            val e1 = PHONE_ENTRY.copy(name = "Priya's phone", retired = Stamp(), collected = false)
            val e2 = PHONE_ENTRY.copy(name = "Priya 🏠's phone", retired = Stamp(), collected = false, key = GoldenMove.KEY_ASHA.public, keyAt = 7)
            addJsonObject {
                put("name", "PhoneEntry name, and a key made at the same time")
                put("a", json(e1))
                put("b", json(e2))
                val ab = mergePhones(listOf(e1), listOf(e2)).single()
                val ba = mergePhones(listOf(e2), listOf(e1)).single()
                assertEquals(ab, ba)
                put("merged", json(ab))
            }
        }
    }

    // ==== String.hashCode, digests, ids and text rules ====================================================================

    private val IDS = listOf(
        "", "a", "A", "Asha", "Priya 🏠", "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000020",
        "f47ac10b-58cc-4372-a567-0e02b2c3d479", "9b2e6c1d-7f3a-4e8b-b5c2-1d0e9f8a7b6c", GoldenMove.PHONE_ASHA, "polygenelubricants", "日本", "\u0000", "x\uD800y",
    )

    private val LETTERS = listOf("A", "B", "Z", "a", " c ", "AA", "AZ", "BA", "ZZ", "zz", "AAA", "A1", "1", "Ä", "É", "ß", "ﬃ", "ı", "i", "İ", "Ω", "🏠", "", "Priya 🏠")

    private val LINE_TEXTS = listOf(
        "Plates", "  Plates  ", "PLATES", "Plates.", "İstanbul mugs", "ΟΔΟΣ", "ΟΔΟΣ cups", "ΣΑΣ", "Σ", "aΣb", "Straße", "STRASSE", "ẞ",
        "Zoë", "Zoë", " Plates　", "Plates​", "ǅ", "ﬃ", "Ⅻ", "K", "x\uD83Cy",
    )

    private fun hashesFixture(): JsonObject = buildJsonObject {
        put(
            "about",
            "Values 2.1.1 works out from String.hashCode, SHA-256 and the text rules of Kotlin on the JVM: lowercase()/uppercase() are " +
                "Locale.ROOT with full case mapping (İ lowercases to i + U+0307, a final Σ to ς, ß uppercases to SS), trim() removes what " +
                "Char.isWhitespace calls white space (U+00A0 and U+3000 included, U+200B not), toByteArray() is UTF-8 with '?' for a lone surrogate.",
        )
        putJsonArray("hashCode") {
            for (s in IDS) addJsonObject {
                text("text", s)
                put("hashCode", s.hashCode())
                put("floorMod26", Math.floorMod(s.hashCode(), 26))
                put("floorMod5000", Math.floorMod(s.hashCode(), 5000))
            }
        }
        putJsonArray("legacyLabels") {
            for (l in LETTERS) addJsonObject {
                text("letter", l)
                text("normalised", l.trim().uppercase())
                val r = legacyLabels(l)
                put("first", r.first)
                put("last", r.last)
            }
        }
        putJsonArray("letterInstead") {
            val first = Person(id = "00000000-0000-4000-8000-000000000001", name = "Asha", letter = "B", grants = listOf(Grant(Role.ORGANISER, false, 1, "")))
            for (id in IDS.filter { it.isNotEmpty() && it != first.id }) addJsonObject {
                val me = Person(id = id, name = "Me", letter = "b", grants = listOf(Grant(Role.DECIDER, false, 2, first.id)))
                text("personId", id)
                put("start", Math.floorMod(id.hashCode(), 26))
                put("letter", AppData(people = listOf(first, me)).letterInstead(me))
            }
            val me = Person(id = "Priya 🏠", name = "Me", letter = "B", grants = listOf(Grant(Role.DECIDER, false, 2, first.id)))
            val taken = Person(id = "u9", name = "Other", letter = "B" + ('A' + Math.floorMod(me.id.hashCode(), 26)), grants = listOf(Grant(Role.HELPER, false, 3, first.id)))
            addJsonObject {
                put("name", "the first choice is taken")
                text("personId", me.id)
                put("taken", taken.letter)
                put("letter", AppData(people = listOf(first, me, taken)).letterInstead(me))
            }
        }
        putJsonArray("derivedMoveId") {
            for (f in listOf("00000000-0000-4000-8000-000000000001", "f47ac10b-58cc-4372-a567-0e02b2c3d479", "Priya 🏠", "x\uD800y")) addJsonObject {
                text("founder", f)
                put("moveId", AppData(people = listOf(Person(id = f, grants = listOf(Grant(Role.ORGANISER, false, GoldenMove.T_ASHA, ""))))).derivedMoveId())
            }
            addJsonObject {
                put("name", "nobody with an open grant: the person with the earliest grant, then the smaller id")
                val people = listOf(
                    Person(id = "u9", grants = listOf(Grant(Role.ORGANISER, false, 5, "x"))),
                    Person(id = "u10", grants = listOf(Grant(Role.ORGANISER, false, 5, "x"))),
                    Person(id = "u0", grants = listOf(Grant(Role.ORGANISER, false, 6, "x"))),
                )
                put("people", people.joinToString(",") { it.id })
                put("moveId", AppData(people = people).derivedMoveId())
            }
        }
        putJsonArray("moveTagAndDailyKey") {
            for (m in listOf("m6c0dbd4c3ba1d4b5e1f2", "m0123456789abcdef0123", "", "mZoë")) addJsonObject {
                text("moveId", m)
                put("moveTag", moveTag(m))
                put("dailyBackupKey", DailyBackup.moveKey(m))
            }
        }
        putJsonArray("digest") {
            for (s in listOf("", "abc", "housemove move 00000000-0000-4000-8000-000000000001", "Priya 🏠", "x\uD800y", "x?y", "\u0000")) addJsonObject {
                text("text", s)
                put("toByteArray", hex(s.toByteArray()))
                put("digest", digest(s))
            }
        }
        putJsonArray("lineId") {
            for (t in LINE_TEXTS) addJsonObject {
                text("text", t)
                text("trimmedLowercase", t.trim().lowercase())
                put("lineId", lineId("b1", t))
            }
        }
        putJsonArray("storedLines") {
            for (t in listOf("Plates, Mugs; ΟΔΟΣ cups, Straße", "Kettle and toaster", "a,,b ;\n c.", "Plates, plates, PLATES", "İ, i̇, I", "Straße, STRASSE")) addJsonObject {
                text("text", t)
                putJsonArray("lines") { storedLines(t).forEach { add(it) } }
            }
        }
        putJsonArray("refusedId") {
            val cases = listOf(
                listOf("decision", "i1", "u2", GoldenMove.T_ASHA), listOf("outcome", "00000000-0000-4000-8000-000000000005", "u3", 0L),
                listOf("settings", "move", "u2", GoldenMove.T_PRIYA), listOf("where", "b1", "Priya 🏠", 1L), listOf("decision", "i1", "", 5L),
            )
            for (c in cases) addJsonObject {
                put("what", c[0] as String)
                put("targetId", c[1] as String)
                text("by", c[2] as String)
                put("at", (c[3] as Long).toString())
                val r = Refusals(Roles(emptyList()))
                r.add(c[0] as String, c[1] as String, Stamp(c[2] as String, c[3] as Long, "s"), "change {}")
                val made = r.into(emptyList())
                put("record", if (made.isEmpty()) "none: a change in nobody's name is not recorded" else json(made.single()))
            }
        }
        putJsonArray("caseAndTrim") {
            for (s in listOf("İ", "ı", "i", "ß", "ẞ", "ΟΔΟΣ", "ΟΔΟΣ ΚΑΙ", "Σ", "aΣ", "ǅ", "ﬃ", "K", "ŉ", " x ", "​x", "\u001Fx\u007F")) addJsonObject {
                text("text", s)
                text("lowercase", s.lowercase())
                text("uppercase", s.uppercase())
                text("trim", s.trim())
                put("equalsIgnoreCaseLower", s.equals(s.lowercase(), ignoreCase = true))
            }
        }
        putJsonArray("compareTo") {
            val pairs = listOf(
                "a" to "b", "B" to "a", "Zoë" to "Zoe", "😀" to "！", "é" to "é", "1.0E-4" to "0.001",
                "10000000.0" to "1.0E7", "" to "a", "Priya" to "Priya 🏠", "u10" to "u9",
            )
            for ((a, b) in pairs) addJsonObject {
                text("a", a)
                text("b", b)
                put("sign", Integer.signum(a.compareTo(b)))
            }
        }
    }

    // ==== invites ========================================================================================================

    private val MOVE_ID = AppData(people = listOf(Person(id = "00000000-0000-4000-8000-000000000001", grants = listOf(Grant(Role.ORGANISER, false, 1, ""))))).derivedMoveId()

    private val INVITES = listOf(
        "plain, as before 2.1" to WifiSync.Invite(listOf("192.168.1.23"), 40123, "ABCDEFGH"),
        "for a person, as 2.0" to WifiSync.Invite(listOf("192.168.1.23"), 40123, "ABCDEFGH", "u2", "Sam Lee", Role.HELPER),
        "plain sync code, 2.1" to WifiSync.Invite(listOf("192.168.1.23", "10.0.0.7"), 40123, "HM4RT8WZ", moveId = MOVE_ID, moveName = "Maple Road"),
        "for a person, 2.1" to WifiSync.Invite(listOf("192.168.1.23", "10.0.0.7"), 40123, "GQ7K2MXP", "00000000-0000-4000-8000-000000000020", "Priya 🏠", Role.DECIDER, MOVE_ID, "Maple Road *~ (Zoë's)"),
        "Helper code" to WifiSync.Invite(listOf("172.20.10.2"), 5555, "K2M4P6R8", WifiSync.HELPER_CODE, "", Role.HELPER, MOVE_ID, "50% + 1: done"),
        "an Organiser, a blank move name" to WifiSync.Invite(listOf("10.0.0.7"), 1, "ZZZZZZZZ", "u1", "日本 太郎", Role.ORGANISER, MOVE_ID, ""),
    )

    private fun inviteJson(i: WifiSync.Invite?): JsonElement = if (i == null) kotlinx.serialization.json.JsonNull else buildJsonObject {
        putJsonArray("hosts") { i.hosts.forEach { add(it) } }
        put("port", i.port)
        put("token", i.token)
        i.personId?.let { put("personId", it) }
        text("personName", i.personName)
        i.role?.let { put("role", it.name) }
        put("moveId", i.moveId)
        text("moveName", i.moveName)
        put("helperCode", i.helperCode)
        text("question", i.question())
    }

    private fun invitesFixture(): JsonObject = buildJsonObject {
        put(
            "about",
            "The text of an invite or sync code QR (WifiSync.Invite): HMSYNC:hosts:port:token[:personId:ROLE:name][:moveId:moveName], " +
                "names through java.net.URLEncoder/URLDecoder with UTF-8 (space as '+', '*' '-' '.' '_' kept, '~' as %7E).",
        )
        putJsonArray("encode") {
            for ((name, i) in INVITES) addJsonObject {
                put("name", name)
                put("invite", inviteJson(i))
                text("text", i.encode())
                put("parsedBack", inviteJson(WifiSync.Invite.parse(i.encode())))
            }
        }
        putJsonArray("parse") {
            val texts = listOf(
                "HMSYNC:192.168.1.23:40123:ABCDEFGH", "  HMSYNC:192.168.1.23:40123:ABCDEFGH\n", "hmsync:192.168.1.23:40123:ABCDEFGH",
                "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:HELPER:Sam+Lee", "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:HELPER:Sam Lee",
                "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:HELPER:%7e%7E%2a*", "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:HELPER:50%",
                "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:HELPER:%G1", "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:HELPER:%C3",
                "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:BOSS:Sam", "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:HELPER", "HMSYNC:192.168.1.23:port:ABCDEFGH",
                "HMSYNC::40123:ABCDEFGH", "HMSYNC:a,,b:40123:ABCDEFGH", "HMSYNC:192.168.1.23:40123: ", "HMSYNC:fe80::1:40123:ABCDEFGH",
                "HMSYNC:192.168.1.23:40123:ABCDEFGH:::$MOVE_ID:Maple+Road", "HMSYNC:192.168.1.23:40123:ABCDEFGH::HELPER::$MOVE_ID:Maple",
                "HMSYNC:192.168.1.23:40123:ABCDEFGH:*::Zo%C3%AB:$MOVE_ID:Zo%C3%AB", "HMSYNC:192.168.1.23:40123:ABCDEFGH:u2:DECIDER:Priya:$MOVE_ID:%",
                "HMSYNC:192.168.1.23:99999999999:ABCDEFGH", "HMSYNC:192.168.1.23:-1:ABCDEFGH",
            )
            for (t in texts) addJsonObject {
                text("text", t)
                put("invite", inviteJson(WifiSync.Invite.parse(t)))
            }
        }
        putJsonArray("urlEncode") {
            for (s in listOf("Maple Road", "*", "~", "-_.", "a+b", "50%", "a:b", "Zoë", "Priya 🏠", "日本", "x\uD800y", "\u0000", "!'()")) addJsonObject {
                text("text", s)
                put("encoded", URLEncoder.encode(s, "UTF-8"))
            }
        }
        putJsonArray("urlDecode") {
            for (s in listOf("Maple+Road", "%7e", "%7E", "a%2Bb", "%E6%97%A5", "Zoë", "%", "%G1", "%C3", "%ed%a0%80", "+%20+")) addJsonObject {
                text("text", s)
                try {
                    text("decoded", URLDecoder.decode(s, "UTF-8"))
                } catch (e: Exception) {
                    put("decoded", errorName(e))
                }
            }
        }
    }

    // ==== AppJson ========================================================================================================

    private fun jsonFixture(): JsonObject = buildJsonObject {
        put(
            "about",
            "AppJson (data/Store.kt): kotlinx.serialization Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }, " +
                "everything else as kotlinx-serialization-json 1.8.1 has it: fields in declaration order, defaults left out (AppData.version is " +
                "always written), no spaces, non-ASCII written as is, Doubles as Double.toString, NaN and Infinity refused.",
        )
        putJsonArray("encode") {
            val cases: List<Pair<String, Any>> = listOf(
                "an empty AppData" to AppData(), "an empty MoveInfo" to MoveInfo(), "a bare item" to Item(id = "i1"), "a stamp" to Stamp("u1", 5, "abc"),
                "an item with Doubles" to Item(id = "i1", widthCm = 0.1, depthCm = 1e-4, heightCm = -0.0, price = 1e7, value = 1e-3, soldFor = 1e21),
                "a person" to PERSON, "a suggestion with a cost" to PROPOSALS[1],
                "text that needs escaping" to Activity(id = "a1", text = "\"quoted\" back\\slash /slash \u0000\u0001\u001F\u007F\b\t\n\u000C\r    Zoë 日本 🏠 x\uD800y"),
                "an AppData with one of each list" to AppData(move = MOVE, rooms = listOf(RECORDS[0].second as Room), items = listOf(Item(id = "i1")), boxes = listOf(BOX)),
            )
            for ((name, r) in cases) addJsonObject {
                put("name", name)
                text("json", json(r))
            }
        }
        putJsonArray("decode") {
            val cases = listOf(
                "unknown keys are ignored" to """{"version":5,"surprise":{"a":[1,2]}}""",
                "an unknown enum value takes the default" to """{"move":{"kind":"SPACESHIP","name":"x"}}""",
                "null for a field that cannot be null takes the default" to """{"items":[{"id":"i","disposition":null,"fragile":null}]}""",
                "an unknown enum value where null is allowed" to """{"proposals":[{"id":"q","disposition":"SWAP"}]}""",
                "numbers in other forms" to """{"items":[{"id":"i","price":1e21,"widthCm":1E-4,"depthCm":-0,"heightCm":5,"value":0.10}]}""",
                "a number in quotes" to """{"items":[{"id":"i","price":"5"}]}""",
                "an integer in quotes" to """{"move":{"moveDay":"20600"}}""",
                "a number too large for a Double" to """{"items":[{"id":"i","price":1e400}]}""",
                "NaN" to """{"items":[{"id":"i","price":NaN}]}""",
                "a field that must be there is missing" to """{"rooms":[{"id":"r"}]}""",
                "a version from the future" to """{"version":6}""",
                "a Long beyond Long" to """{"move":{"moveDay":9223372036854775808}}""",
                "a lone surrogate escaped" to """{"activity":[{"id":"a","text":"x\ud800y"}]}""",
                "duplicate keys: the last wins" to """{"move":{"name":"a","name":"b"}}""",
                "trailing comma" to """{"version":5,}""",
            )
            for ((name, input) in cases) addJsonObject {
                put("name", name)
                text("input", input)
                val decoded = try {
                    AppJson.decodeFromString(AppData.serializer(), input)
                } catch (e: Exception) {
                    put("decoded", errorName(e))
                    null
                }
                if (decoded != null) put("reencoded", try { json(decoded) } catch (e: Exception) { errorName(e) })
            }
        }
    }

    // ==== QR codes =======================================================================================================

    private fun qrFixture(): JsonObject = buildJsonObject {
        val tag = moveTag(MOVE_ID)
        put(
            "about",
            "What 2.1.1 puts in its QR codes (data/Qr.kt, WifiSync.Invite) and how it draws them: zxing 3.5.3 QRCodeWriter, error " +
                "correction M, quiet zone 1 module, no character set given (byte mode is ISO-8859-1), the version and mask zxing chooses.",
        )
        put("moveId", MOVE_ID)
        put("moveTag", tag)
        putJsonArray("labelText") {
            for ((code, t) in listOf("L042" to tag, "L042" to "", "A7" to tag, "L100000" to tag, " L042" to " ", "Zoë 1" to tag)) addJsonObject {
                text("code", code)
                text("moveTag", t)
                text("text", labelQrText(code, t))
            }
        }
        putJsonArray("readLabel") {
            val texts = listOf(
                "HM2:$tag:L042", "  HM2:$tag: L042 \n", "HM2:${tag.lowercase()}:L042", "hm2:$tag:L042", "HM:L042", "HM: L042", "HM2::L042",
                "HM2:$tag", "L042", "HM2:$tag:" + "X".repeat(30), "HM2:$tag:" + "x".repeat(23) + "🏠", "HM:" + "y".repeat(23) + "🏠z",
                "9780141439518", "HMSYNC:192.168.1.23:40123:ABCDEFGH",
            )
            for (t in texts) addJsonObject {
                text("text", t)
                val r = readLabel(t)
                text("code", r.code)
                r.tag?.let { text("tag", it) }
                text("labelFromQr", labelFromQr(t))
            }
        }
        putJsonArray("matrices") {
            val texts = listOf(
                "label" to labelQrText("L042", tag),
                "label from before 2.1" to labelQrText("L042", ""),
                "invite for a person" to INVITES[3].second.encode(),
                "plain sync code" to INVITES[2].second.encode(),
            )
            for ((name, text) in texts) addJsonObject {
                put("name", name)
                text("text", text)
                val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
                val qr = Encoder.encode(text, ErrorCorrectionLevel.M, hints)
                val m = qr.matrix
                put("mode", qr.mode.toString())
                put("version", qr.version.versionNumber)
                put("errorCorrection", qr.ecLevel.toString())
                put("mask", qr.maskPattern)
                put("modules", m.width)
                putJsonArray("rows") { for (y in 0 until m.height) add((0 until m.width).joinToString("") { x -> if (m.get(x, y).toInt() == 1) "#" else "." }) }
                // Qr.matrix as the label sheet (240 px) and the screens (640 px) draw it: the modules scaled whole, centred, quiet zone 1.
                putJsonArray("drawn") {
                    for (px in listOf(240, 640)) addJsonObject {
                        val b = Qr.matrix(text, px)
                        val scale = px / (m.width + 2)
                        val pad = (px - m.width * scale) / 2
                        put("sizePx", px)
                        put("scale", scale)
                        put("padding", pad)
                        var same = b.width == px && b.height == px
                        for (y in 0 until px) for (x in 0 until px) {
                            val mx = (x - pad).floorDiv(scale)
                            val my = (y - pad).floorDiv(scale)
                            val on = mx in 0 until m.width && my in 0 until m.width && m.get(mx, my).toInt() == 1
                            if (b.get(x, y) != on) same = false
                        }
                        put("isTheModulesScaled", same)
                    }
                }
            }
        }
    }

    // ==== update-file crypto (deterministic parts) ===========================================================================

    private fun cryptoFixture(): JsonObject = buildJsonObject {
        put(
            "about",
            "The building blocks of update files (data/Sealed.kt) on fixed inputs: HKDF-SHA256 (one block, at most 32 bytes; an empty salt " +
                "is 32 zero bytes), AES-256-GCM chunks with a 12-byte nonce of an 8-byte prefix and the chunk number (big-endian), bound by " +
                "AAD = SHA-256(header) + chunk number (4 bytes, big-endian) + 1 for the last chunk or 0, each chunk written as its length " +
                "(4 bytes, big-endian) and the sealed bytes (ciphertext and 16-byte tag).",
        )
        putJsonArray("hkdf") {
            val cases = listOf(
                Triple("RFC 5869 case 1 (its first 32 bytes)", "0b".repeat(22) to "000102030405060708090a0b0c", "f0f1f2f3f4f5f6f7f8f9"),
                Triple("an empty salt", "0b".repeat(22) to "", "f0f1f2f3f4f5f6f7f8f9"),
                Triple("as for a phone", "11".repeat(32) to "22".repeat(16), hex("HouseMove update key 1|$MOVE_ID|${GoldenMove.PHONE_ASHA}".toByteArray())),
            )
            for ((name, ikmSalt, info) in cases) addJsonObject {
                put("name", name)
                put("ikm", ikmSalt.first)
                put("salt", ikmSalt.second)
                put("info", info)
                put("okm32", hex(Sealing.hkdf(unhex(ikmSalt.first), unhex(ikmSalt.second), unhex(info))))
                put("okm16", hex(Sealing.hkdf(unhex(ikmSalt.first), unhex(ikmSalt.second), unhex(info), 16)))
            }
        }
        val key = ByteArray(32) { it.toByte() }
        val prefix = unhex("a1a2a3a4a5a6a7a8")
        val headerHash = MessageDigest.getInstance("SHA-256").digest("""{"format":1}""".toByteArray())
        putJsonObject("chunks") {
            put("key", hex(key))
            put("prefix", hex(prefix))
            put("headerHash", hex(headerHash))
            putJsonArray("one") {
                for ((plain, index, last) in listOf(Triple("hello", 0, true), Triple("hello", 0, false), Triple("", 3, true))) addJsonObject {
                    put("plaintext", hex(plain.toByteArray()))
                    put("index", index)
                    put("last", last)
                    put("sealed", hex(Sealing.chunkCipher(Cipher.ENCRYPT_MODE, key, prefix, headerHash, index, last).doFinal(plain.toByteArray())))
                }
            }
            // A stream of 150,000 bytes (byte i is i mod 251) through SealedOut: three chunks.
            val plain = ByteArray(150_000) { (it % 251).toByte() }
            val out = ByteArrayOutputStream()
            SealedOut(DataOutputStream(out), key, prefix, headerHash).use { it.write(plain) }
            val sealed = out.toByteArray()
            putJsonObject("stream") {
                put("plaintext", "150000 bytes, byte i = i mod 251")
                put("sealedLength", sealed.size)
                put("sha256", sha(sealed))
                putJsonArray("chunks") {
                    var p = 0
                    while (p < sealed.size) {
                        val n = ByteBuffer.wrap(sealed, p, 4).int
                        addJsonObject {
                            put("length", n)
                            put("tag", hex(sealed.copyOfRange(p + 4 + n - 16, p + 4 + n)))
                        }
                        p += 4 + n
                    }
                }
                val back = SealedIn(DataInputStream(ByteArrayInputStream(sealed)), key, prefix, headerHash).readBytes()
                put("opensBack", back.contentEquals(plain))
            }
        }
    }

    // ==== the fixed move =====================================================================================================

    /** The fixtures of the fixed move that are the same on every run. */
    private fun scenarioFixtures(run: GoldenRun): Map<String, String> {
        val data = AppJson.decodeFromString(AppData.serializer(), run.moveData)
        assertEquals("move-data.json decodes and encodes back to itself", run.moveData, json(data))
        return mapOf(
            "move-data.json" to run.moveData,
            "sync-join.json" to render(transcript(JOIN_ABOUT, run.join)),
            "sync-plain.json" to render(transcript(PLAIN_ABOUT, run.plain)),
            "sync-refusals.json" to render(refusalsFixture(run)),
        )
    }

    private val JOIN_ABOUT =
        "Priya's phone (on its own until now) joins Asha's move with an invite made for her as a Decider: every message of the " +
            "exchange, in order, as the bytes went over the socket, with what each part of it is. The invite's code is GQ7K2MXP " +
            "(drawn at random in the app). The phones, their keys, clocks and ids are fixed (golden/README.md)."

    private val PLAIN_ABOUT =
        "Then both phones work (Priya adds a lamp with a photo, Asha a box with a photo) and sync again with Asha's plain sync code " +
            "HM4RT8WZ: a sync between two phones of the move, a photo each way."

    /** Every message of a sync, field by field. */
    private fun transcript(about: String, phases: List<Pair<String, ByteArray>>): JsonObject = buildJsonObject {
        put("about", about)
        putJsonArray("messages") {
            phases.forEachIndexed { i, (from, bytes) ->
                addJsonObject {
                    put("step", i + 1)
                    put("from", from)
                    put("length", bytes.size)
                    putJsonArray("fields") { for (f in fields(i + 1, bytes)) add(f) }
                    put("hex", hex(bytes))
                }
            }
        }
    }

    /** The fields of message [step] of a sync (see WifiSync's class comment), each with where it starts and how long it is. */
    private fun fields(step: Int, bytes: ByteArray): List<JsonObject> {
        val r = WireReader(bytes)
        val out = ArrayList<JsonObject>()
        fun field(name: String, type: String, read: JsonObjectBuilder.(DataInputStream) -> Unit) {
            val at = r.pos
            val body = buildJsonObject { read(r.din) }
            out += buildJsonObject {
                put("name", name)
                put("type", type)
                put("offset", at)
                put("length", r.pos - at)
                body.forEach { (k, v) -> put(k, v) }
            }
        }
        fun utf(name: String, type: String = "utf") = field(name, type) { text("text", it.readUTF()) }
        fun int(name: String) = field(name, "int") { put("value", it.readInt()) }
        fun data(name: String) = field(name, "data") {
            val n = it.readInt()
            val b = ByteArray(n).also { x -> it.readFully(x) }
            put("size", n)
            put("sha256", sha(b))
            put("encoding", "standard UTF-8 JSON (AppJson), after a 4-byte length")
        }
        fun names(name: String) = field(name, "names") {
            val n = it.readInt()
            put("count", n)
            putJsonArray("names") { repeat(n) { _ -> add(it.readUTF()) } }
        }
        fun files() {
            while (true) {
                val at = r.pos
                val name = r.din.readUTF()
                if (name.isEmpty()) {
                    out += buildJsonObject {
                        put("name", "end of files")
                        put("type", "utf")
                        put("offset", at)
                        put("length", r.pos - at)
                        put("text", "")
                    }
                    break
                }
                val size = r.din.readLong()
                val b = ByteArray(size.toInt()).also { r.din.readFully(it) }
                out += buildJsonObject {
                    put("name", "file")
                    put("type", "file")
                    put("offset", at)
                    put("length", r.pos - at)
                    put("file", name)
                    put("size", size.toString())
                    put("sha256", sha(b))
                    put("contents", hex(b))
                }
            }
        }
        when (step) {
            1 -> { utf("magic"); utf("token"); utf("name"); utf("letter"); int("schema"); utf("person", "utf-json"); utf("hello", "utf-json") }
            2 -> { utf("answer"); utf("name"); utf("letter"); utf("hello", "utf-json") }
            3 -> { utf("go"); data("data") }
            4 -> data("merged data")
            5, 6 -> names("photo files held")
            7, 8 -> files()
            else -> error("step $step")
        }
        assertTrue("message $step was read to its end (${r.pos} of ${bytes.size})", r.done)
        return out
    }

    private fun refusalsFixture(run: GoldenRun): JsonObject = buildJsonObject {
        put(
            "about",
            "What a hosting phone on 2.1.1 answers to hellos it turns down (sent straight to it, read until it hangs up), and every word " +
                "either phone says, as writeUTF writes it. VERSION from 2.1 is followed by its data layout (an int); from 2.0 it is not.",
        )
        putJsonArray("cases") {
            for ((name, sent, answer) in run.refusals) addJsonObject {
                put("name", name)
                put("sent", hex(sent))
                put("answer", hex(answer))
                val d = DataInputStream(ByteArrayInputStream(answer))
                val word = if (answer.isEmpty()) "" else d.readUTF()
                put("word", word)
                if (d.available() >= 4) put("layout", d.readInt())
                put("answerReadToItsEnd", d.available() == 0)
            }
        }
        putJsonArray("words") {
            for (w in listOf("OK", "ONEWAY", "NO", "VERSION", "REMOVED", "STRANGER", "EXPIRED", "WRONGMOVE", "RETIRED", "UNDONE", "UNCONFIRMED", "WAIT", "AGAIN", "GO", "TAKE", "DONE", "HMSYNC3", "HMSYNC2", "HMSYNC1"))
                addJsonObject {
                    put("word", w)
                    put("writeUTF", hex(writeUtf(w)))
                }
        }
    }

    // ---- the backup -------------------------------------------------------------------------------------------------------

    private class ZipInfo(
        val name: String, val versionMadeBy: Int, val versionNeeded: Int, val flags: Int, val method: Int, val time: Int, val date: Int,
        val crc: Long, val compressed: Long, val size: Long, val extra: Int, val localExtra: Int, val descriptorSignature: Boolean,
    )

    /** The entries of a zip, from its central directory. */
    private fun zipEntries(b: ByteArray): List<ZipInfo> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var e = b.size - 22
        while (e >= 0 && bb.getInt(e) != 0x06054b50) e--
        check(e >= 0) { "no end of central directory" }
        val count = bb.getShort(e + 10).toInt() and 0xffff
        var p = bb.getInt(e + 16)
        val out = ArrayList<ZipInfo>()
        repeat(count) {
            check(bb.getInt(p) == 0x02014b50)
            fun u16(o: Int) = bb.getShort(p + o).toInt() and 0xffff
            fun u32(o: Int) = bb.getInt(p + o).toLong() and 0xffffffffL
            val nameLen = u16(28)
            val local = u32(42).toInt()
            val localName = bb.getShort(local + 26).toInt() and 0xffff
            val localExtra = bb.getShort(local + 28).toInt() and 0xffff
            val end = local + 30 + localName + localExtra + u32(20).toInt()
            out += ZipInfo(
                String(b, p + 46, nameLen, Charsets.UTF_8), u16(4), u16(6), u16(8), u16(10), u16(12), u16(14), u32(16), u32(20), u32(24), u16(30),
                localExtra, end + 4 <= b.size && bb.getInt(end) == 0x08074b50,
            )
            p += 46 + nameLen + u16(30) + u16(32)
        }
        return out
    }

    private fun zipContents(b: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes()
            }
        }
        return out
    }

    /** What is the same in every zip 2.1.1 writes for the same contents: names, order, flags, method, sizes and CRCs before compression. */
    private fun zipFacts(b: ByteArray, contents: Map<String, ByteArray>, random: Set<String>): JsonArray = buildJsonObject {
        putJsonArray("e") {
            for (z in zipEntries(b)) addJsonObject {
                put("name", z.name)
                put("versionMadeBy", z.versionMadeBy)
                put("versionNeeded", z.versionNeeded)
                put("flags", "0x" + z.flags.toString(16).padStart(4, '0'))
                put("method", z.method)
                put("extraLength", z.extra)
                put("localExtraLength", z.localExtra)
                put("dataDescriptorWithSignature", z.descriptorSignature)
                if (z.name !in random) {
                    put("size", z.size.toString())
                    put("crc32", z.crc.toString(16).padStart(8, '0'))
                    put("sha256", sha(contents.getValue(z.name)))
                }
            }
        }
    }.getValue("e").jsonArray

    private fun backupFacts(b: ByteArray, moveData: String): JsonObject = buildJsonObject {
        val contents = zipContents(b)
        put("entries", zipFacts(b, contents, setOf("about.json")))
        put("dataJsonIsMoveData", contents["data.json"]?.decodeToString() == moveData)
        val about = Json.parseToJsonElement(contents.getValue("about.json").decodeToString()).jsonObject
        putJsonObject("aboutJsonWithoutAt") { about.filterKeys { it != "at" }.forEach { (k, v) -> put(k, v) } }
        putJsonArray("aboutJsonKeys") { about.keys.forEach { add(it) } }
    }

    private fun backupOpened(b: ByteArray, moveData: String): JsonObject = buildJsonObject {
        val peek = Backup.peek(ByteArrayInputStream(b))
        putJsonObject("peek") {
            put("moveId", peek.moveId)
            putJsonArray("aliases") { peek.aliases.forEach { add(it) } }
            put("moveName", peek.moveName)
            put("by", peek.by)
            put("small", peek.small)
            put("dataIsMoveData", json(peek.data) == moveData)
        }
        // Restored on a phone that has nothing yet: it becomes the person whose phone made the backup.
        val dir = tmp.newFolder()
        GoldenMove.prepare(dir, "pfreshphone23456789ab", GoldenMove.KEY_PRIYA, "Asha", "A")
        GoldenIds.using(GoldenIds()) {
            val s = Store(dir) { GoldenMove.T_PRIYA }
            try {
                val r = Backup.import(s, ByteArrayInputStream(b))
                putJsonObject("import") {
                    put("records", r.records)
                    put("photos", r.photos)
                    put("moveId", s.moveId)
                    put("me", s.me?.name ?: "")
                    put("role", s.me?.role?.name ?: "")
                    put("items", s.data.value.liveItems.size)
                    putJsonArray("photoFiles") {
                        for (p in s.data.value.livePhotos) addJsonObject {
                            put("file", p.file)
                            put("sha256", sha(File(s.photoDir, p.file).readBytes()))
                        }
                    }
                }
            } finally {
                s.close()
            }
        }
    }

    private fun backupFixture(run: GoldenRun): JsonObject = buildJsonObject {
        put(
            "about",
            "A backup of the fixed move (Backup.export on Asha's phone right after move-data.json): a zip of data.json, about.json and " +
                "photos/. Random in it: the time in about.json ('at', System.currentTimeMillis), every entry's DOS time and date (the " +
                "local time when it was made), and so about.json's compressed bytes, CRC and sizes, and every offset after it. The " +
                "compressed bytes of the other entries come from the JVM's zlib and need not match another deflate.",
        )
        putJsonObject("sample") {
            put("hex", hex(run.backup))
            putJsonArray("entries") {
                for (z in zipEntries(run.backup)) addJsonObject {
                    put("name", z.name)
                    put("dosTime", "0x" + z.time.toString(16).padStart(4, '0'))
                    put("dosDate", "0x" + z.date.toString(16).padStart(4, '0'))
                    put("compressedSize", z.compressed.toString())
                }
            }
        }
        put("deterministic", backupFacts(run.backup, run.moveData))
        put("opened", backupOpened(run.backup, run.moveData))
    }

    // ---- the update file ----------------------------------------------------------------------------------------------------

    private fun filesJson(files: Map<String, String>): JsonObject = buildJsonObject { for ((k, v) in files.toSortedMap()) put(k, v) }

    private fun openedJson(o: Update.Opened): JsonObject = buildJsonObject {
        put("ok", o.ok)
        put("message", o.message)
        put("fromName", o.fromName)
        put("changes", o.changes)
        put("photos", o.photos)
        put("fromRemoved", o.fromRemoved ?: "")
        put("mayTakeIn", o.mayTakeIn)
    }

    private class OpenedUpdate(
        val header: String, val headerBytes: ByteArray, val secret: ByteArray, val kek: ByteArray, val contentKey: ByteArray, val prefix: ByteArray,
        val chunks: List<JsonObject>, val zip: ByteArray, val format: Int,
    )

    /** Open an update file for Asha's phone, step by step, with nothing but the JCA. */
    private fun openUpdate(b: ByteArray): OpenedUpdate {
        val d = DataInputStream(ByteArrayInputStream(b))
        val magic = ByteArray(8).also { d.readFully(it) }
        check(magic.decodeToString() == "HMUPDATE")
        val format = d.readInt()
        val headerBytes = ByteArray(d.readInt()).also { d.readFully(it) }
        val header = Json.parseToJsonElement(headerBytes.decodeToString()).jsonObject
        val to = header.getValue("to").jsonArray.map { it.jsonObject }.single { it.getValue("phone").jsonPrimitive.content == GoldenMove.PHONE_ASHA }
        fun b64(k: String) = Base64.getDecoder().decode(to.getValue(k).jsonPrimitive.content)
        val kf = KeyFactory.getInstance("EC")
        val mine = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(GoldenMove.KEY_ASHA.private)))
        val eph = kf.generatePublic(X509EncodedKeySpec(b64("epk")))
        val secret = KeyAgreement.getInstance("ECDH").run { init(mine); doPhase(eph, true); generateSecret() }
        val moveId = header.getValue("moveId").jsonPrimitive.content
        val kek = Sealing.hkdf(secret, b64("salt"), "HouseMove update key 1|$moveId|${GoldenMove.PHONE_ASHA}".toByteArray())
        val contentKey = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, b64("iv")))
            updateAAD(GoldenMove.PHONE_ASHA.toByteArray())
            doFinal(b64("key"))
        }
        val prefix = Base64.getDecoder().decode(header.getValue("nonce").jsonPrimitive.content)
        val headerHash = MessageDigest.getInstance("SHA-256").digest(headerBytes)
        val sealed = ArrayList<ByteArray>()
        while (d.available() > 0) sealed += ByteArray(d.readInt()).also { d.readFully(it) }
        val zip = ByteArrayOutputStream()
        val chunks = sealed.mapIndexed { i, c ->
            val last = i == sealed.size - 1
            val nonce = ByteBuffer.allocate(12).put(prefix).putInt(i).array()
            val aad = ByteBuffer.allocate(37).put(headerHash).putInt(i).put(if (last) 1 else 0).array()
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(c)
            }
            zip.write(plain)
            buildJsonObject {
                put("index", i)
                put("last", last)
                put("length", c.size)
                put("nonce", hex(nonce))
                put("aad", hex(aad))
                put("plaintextLength", plain.size)
            }
        }
        return OpenedUpdate(headerBytes.decodeToString(), headerBytes, secret, kek, contentKey, prefix, chunks, zip.toByteArray(), format)
    }

    /** What is the same in every update file 2.1.1 makes for the same data: everything but the keys, nonces, time and zip times. */
    private fun updateFacts(b: ByteArray): JsonObject = buildJsonObject {
        val u = openUpdate(b)
        val header = Json.parseToJsonElement(u.header).jsonObject
        put("magic", "HMUPDATE")
        put("format", u.format)
        putJsonObject("header") {
            for ((k, v) in header) if (k != "at" && k != "nonce" && k != "to") put(k, v)
            putJsonArray("to") { header.getValue("to").jsonArray.forEach { add(it.jsonObject.getValue("phone")) } }
            putJsonArray("keys") { header.keys.forEach { add(it) } }
            putJsonArray("toKeys") { header.getValue("to").jsonArray.first().jsonObject.keys.forEach { add(it) } }
        }
        put("chunks", u.chunks.size)
        val contents = zipContents(u.zip)
        put("entries", zipFacts(u.zip, contents, emptySet()))
    }

    /** The values met on the way into the recorded update file, worked out from it and Asha's private key. */
    private fun updateIntermediate(b: ByteArray): JsonObject = buildJsonObject {
        val u = openUpdate(b)
        put("header", u.header)
        put("headerSha256", sha(u.headerBytes))
        put("ecdhSecret", hex(u.secret))
        put("kek", hex(u.kek))
        put("contentKey", hex(u.contentKey))
        put("prefix", hex(u.prefix))
        putJsonArray("chunks") { u.chunks.forEach { add(it) } }
        put("zipLength", u.zip.size)
        put("zipSha256", sha(u.zip))
        putJsonArray("zipEntries") {
            for (z in zipEntries(u.zip)) addJsonObject {
                put("name", z.name)
                put("dosTime", "0x" + z.time.toString(16).padStart(4, '0'))
                put("dosDate", "0x" + z.date.toString(16).padStart(4, '0'))
                put("compressedSize", z.compressed.toString())
            }
        }
    }

    private fun updateFixture(run: GoldenRun): JsonObject = buildJsonObject {
        put(
            "about",
            "An update file Priya's phone made for Asha's (Update.write), and Asha's folder just before it arrived. Opening it with " +
                "today's code must say what 'opened' says. Random in it: the header's time ('at'), the 8-byte nonce prefix, the content " +
                "key, each recipient's ephemeral key, salt and IV, and so every byte after the header; and the inner zip's DOS times.",
        )
        putJsonObject("recipient") {
            put("phoneId", GoldenMove.PHONE_ASHA)
            put("publicKey", GoldenMove.KEY_ASHA.public)
            put("privateKey", GoldenMove.KEY_ASHA.private)
            put("moveId", run.moveId)
            put("files", filesJson(run.recipient))
        }
        putJsonObject("sender") {
            put("phoneId", GoldenMove.PHONE_PRIYA)
            put("publicKey", GoldenMove.KEY_PRIYA.public)
            put("privateKey", GoldenMove.KEY_PRIYA.private)
        }
        putJsonObject("sample") { put("hex", hex(run.update)) }
        put("intermediate", updateIntermediate(run.update))
        put("deterministic", updateFacts(run.update))
        put("opened", openedJson(run.opened))
    }
}
