package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.sync.WireReader
import app.housemove.sync.WireWriter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The golden fixtures of HouseMove 2.1.1 (src/jvmTest/resources/golden, whose README says what each holds), checked
 * with the shared code on every platform: on the JVM in every test run, and on the iPhone simulator in CI
 * (housemove-ios.yml). Each value is worked out again from the inputs in the file and compared exactly.
 *
 * Text is checked twice where the platform could make it: through the shared code's seams (Platform.kt, sync/Net.kt),
 * which on Android and the JVM are the JVM's own functions, and through the common Java-compatible rules in
 * JavaText.kt and sync/Wire.kt, which the iPhone uses. On the iPhone the two are the same code. The checks of the
 * platform's own Double.toString, lowercase() and the like (record text, JSON, line ids) are those Kotlin/Native must
 * pass for an iPhone to agree with Android phones.
 *
 * What needs files, sockets, zips, QR modules or update files stays in jvmTest (GoldenFixturesTest) until step 1b.
 */
class GoldenCommonTest {
    // ---- reading the fixtures ----------------------------------------------------------------------------------------

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(GoldenFiles.text.getValue(name)).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.strOrNull(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun bits(hex: String): Double = Double.fromBits(hex.toULong(16).toLong())

    private fun errorName(e: Throwable): String = "error: " + e::class.simpleName

    private inline fun orError(block: () -> String): String = try {
        block()
    } catch (e: Exception) {
        errorName(e)
    }

    private fun plainAscii(s: String) = s.all { it.code in 0x20..0x7e }

    private fun utf16(s: String): String = s.map { it.code.toString(16).padStart(4, '0') }.joinToString(" ")

    /** As GoldenFixturesTest writes a string: with its UTF-16 code units beside it when it is short and not plain ASCII. */
    private fun JsonObjectBuilder.text(key: String, s: String) {
        put(key, s)
        if (!plainAscii(s) && s.length <= 64) put(key + "Utf16", utf16(s))
    }

    private fun wire(write: (WireWriter) -> Unit): ByteArray {
        val out = ArrayList<Byte>()
        write(WireWriter { b -> b.forEach { out += it } })
        return out.toByteArray()
    }

    private fun reader(bytes: ByteArray): WireReader {
        var at = 0
        return WireReader { b, off, len ->
            if (at >= bytes.size) {
                -1
            } else {
                val n = minOf(len, bytes.size - at)
                bytes.copyInto(b, off, at, at + n)
                at += n
                n
            }
        }
    }

    // ---- the records, by name --------------------------------------------------------------------------------------

    private val serializers: Map<String, KSerializer<*>> = mapOf(
        "Room" to Room.serializer(), "Item" to Item.serializer(), "Box" to Box.serializer(), "Photo" to Photo.serializer(),
        "BoxEntry" to BoxEntry.serializer(), "Damage" to Damage.serializer(), "Person" to Person.serializer(), "Task" to Task.serializer(),
        "Expense" to Expense.serializer(), "Supply" to Supply.serializer(), "Trip" to Trip.serializer(), "Reading" to Reading.serializer(),
        "Proposal" to Proposal.serializer(), "Activity" to Activity.serializer(), "Hidden" to Hidden.serializer(), "Collect" to Collect.serializer(),
        "ContentsLine" to ContentsLine.serializer(), "RefusedChange" to RefusedChange.serializer(), "MoveInfo" to MoveInfo.serializer(),
        "PhoneEntry" to PhoneEntry.serializer(), "Stamp" to Stamp.serializer(), "Grant" to Grant.serializer(), "AppData" to AppData.serializer(),
    )

    private fun decode(type: String, json: String): Any = AppJson.decodeFromString(serializers.getValue(type), json)!!

    @Suppress("UNCHECKED_CAST")
    private fun encode(type: String, value: Any): String = AppJson.encodeToString(serializers.getValue(type) as KSerializer<Any>, value)

    // ---- the checks ------------------------------------------------------------------------------------------------

    @Test
    fun theFixturesComeThroughWhole() {
        assertEquals(15, GoldenFiles.text.size, "every golden/*.json file")
        for ((name, text) in GoldenFiles.text) {
            assertEquals(GoldenFiles.sha256.getValue(name), sha256(text.toUtf8()).toHex(), "$name, through the seams")
            assertEquals(GoldenFiles.sha256.getValue(name), sha256(JavaText.utf8(text)).toHex(), "$name, through JavaText")
        }
    }

    @Test
    fun modifiedUtf8AndTheIntegersOfTheSyncWire() {
        val f = fixture("modified-utf8.json")
        for (c in f.objects("strings")) {
            val name = c.str("name")
            val text = c.str("text")
            assertEquals(c.str("writeUTF"), JavaText.modifiedUtf8(text).toHex(), "writeUTF, $name")
            assertEquals(c.str("writeUTF"), wire { it.writeUTF(text) }.toHex(), "WireWriter.writeUTF, $name")
            assertEquals(text, reader(unhex(c.str("writeUTF"))).readUTF(), "WireReader.readUTF, $name")
            assertEquals(c.str("toByteArray"), text.toUtf8().toHex(), "toByteArray, $name")
            assertEquals(c.str("toByteArray"), JavaText.utf8(text).toHex(), "JavaText.utf8, $name")
        }
        for (c in f.objects("long")) {
            val name = c.str("name")
            val s = c.str("unit").repeat(c.getValue("times").jsonPrimitive.int)
            val made = try {
                JavaText.modifiedUtf8(s)
            } catch (e: UTFDataFormatException) {
                assertEquals(c.str("writeUTF"), errorName(e), name)
                continue
            }
            assertEquals(null, c.strOrNull("writeUTF"), "$name: written, where 2.1.1 refuses it")
            assertEquals(c.getValue("utfLength").jsonPrimitive.int, made.size - 2, name)
            assertEquals(c.str("head"), made.copyOf(8).toHex(), name)
            assertEquals(c.str("sha256"), sha256(made).toHex(), name)
        }
        for (c in f.objects("readUTF")) {
            val name = c.str("name")
            val got = try {
                reader(unhex(c.str("hex"))).readUTF()
            } catch (e: IOException) {
                errorName(e)
            }
            assertEquals(c.strOrNull("text") ?: c.str("result"), got, "readUTF, $name")
        }
        for (c in f.objects("decodeToString")) {
            val name = c.str("name")
            assertEquals(c.str("text"), unhex(c.str("hex")).decodeUtf8(), "decodeToString, $name")
            assertEquals(c.str("text"), JavaText.decodeUtf8(unhex(c.str("hex"))), "JavaText.decodeUtf8, $name")
        }
        for (c in f.objects("writeInt")) {
            val v = c.getValue("value").jsonPrimitive.int
            assertEquals(c.str("hex"), wire { it.writeInt(v) }.toHex(), "writeInt $v")
            assertEquals(v, reader(unhex(c.str("hex"))).readInt(), "readInt $v")
        }
        for (c in f.objects("writeLong")) {
            val v = c.str("value").toLong()
            assertEquals(c.str("hex"), wire { it.writeLong(v) }.toHex(), "writeLong $v")
            assertEquals(v, reader(unhex(c.str("hex"))).readLong(), "readLong $v")
        }
    }

    @Test
    fun doublesAsTextAsBitsAndAsJson() {
        for (c in fixture("doubles.json").objects("doubles")) {
            val name = c.str("name")
            val d = bits(c.str("rawBits"))
            assertEquals(c.str("toString"), JavaText.doubleText(d), "JavaText.doubleText, $name")
            assertEquals(c.str("toString"), d.javaText(), "javaText, $name")
            assertEquals(c.str("doubleToLongBits"), d.toBits().toString(), "doubleToLongBits, $name")
            assertEquals(c.str("seal"), seal(d), "seal, $name")
            assertEquals(c.str("fmtNum"), orError { fmtNum(d) }, "fmtNum, $name")
        }
    }

    /** The platform's own Double.toString, which record text (and so merges) and AppJson use, against the JVM's. */
    @Test
    fun thePlatformWritesDoublesAsTheJvmDoes() {
        for (c in fixture("doubles.json").objects("doubles")) {
            val name = c.str("name")
            val d = bits(c.str("rawBits"))
            assertEquals(c.str("toString"), d.toString(), "Double.toString, $name")
            assertEquals(c.str("json"), orError { AppJson.encodeToString(Expense.serializer(), Expense(id = "e", amount = d)) }, "AppJson, $name")
            assertEquals(c.str("expenseToString"), Expense(id = "e", amount = d).toString(), "Expense.toString, $name")
        }
    }

    /** What a number field makes of typed text (ui/Common.kt's NumberInput, which step 2 shares): toDoubleOrNull. */
    @Test
    fun typedNumbersReadAsOnAndroid() {
        for (c in fixture("doubles.json").objects("numberInput")) {
            val typed = c.str("typed")
            val v = typed.replace(',', '.').trim().toDoubleOrNull()
            assertEquals(c.str("value"), v?.let { JavaText.doubleText(it) } ?: "null", "typed '$typed'")
            c.strOrNull("rawBits")?.let { assertEquals(it, v!!.toRawBits().toULong().toString(16).padStart(16, '0'), "typed '$typed'") }
        }
    }

    @Test
    fun seals() {
        val f = fixture("seal.json")
        for (c in f.objects("cases")) {
            val name = c.str("name")
            val parts = c.objects("parts").map { p ->
                val part: Any? = when (p.str("type")) {
                    "null" -> null
                    "string" -> p.str("value")
                    "double" -> bits(p.str("rawBits"))
                    "long" -> p.str("value").toLong()
                    "int" -> p.getValue("value").jsonPrimitive.int
                    "boolean" -> p.str("value").toBooleanStrict()
                    "enum" -> p.str("value").let { v -> Disposition.entries.firstOrNull { it.name == v } ?: Stage.valueOf(v) }
                    else -> fail("part type ${p.str("type")}")
                }
                val hashed = when (part) {
                    null -> "\u0000"
                    is Double -> part.toBits().toString()
                    else -> part.toString()
                }
                assertEquals(p.str("hashed"), hashed, "$name: the text hashed")
                part
            }
            assertEquals(c.str("seal"), seal(*parts.toTypedArray()), name)
        }
        for (r in f.objects("records")) {
            val type = r.str("type")
            val json = r.str("json")
            val sums: Map<String, String> = when (val x = decode(type, json)) {
                is Item -> mapOf(
                    "decisionSum" to x.decisionSum(), "roomSum" to x.roomSum(), "outcomeSum" to x.outcomeSum(), "whereSum" to x.whereSum(),
                    "handoverSum" to x.handoverSum(), "handedOverSum" to x.handedOverSum(), "decisionSum20" to x.decisionSum20(), "outcomeSum20" to x.outcomeSum20(),
                )
                is Box -> mapOf("whereSum" to x.whereSum())
                is Hidden -> mapOf("detailsSum" to x.detailsSum(), "priceSum" to x.priceSum(), "saleSum" to x.saleSum())
                is Collect -> mapOf("collectSum" to x.collectSum())
                is Damage -> mapOf("clearedSum" to x.clearedSum())
                is Expense -> mapOf("costSum" to x.costSum(), "paidSum" to x.paidSum())
                is Task -> mapOf("planSum" to x.planSum(), "doneSum" to x.doneSum())
                is MoveInfo -> mapOf("settingsSum" to x.settingsSum())
                is Proposal -> mapOf("stateSum" to x.stateSum(), "withdrawnSum" to x.withdrawnSum())
                else -> fail("sums of $type")
            }
            assertEquals(r.getValue("sums").jsonObject.mapValues { it.value.jsonPrimitive.content }, sums, "$type $json")
            assertEquals(json, encode(type, decode(type, json)), "AppJson both ways, $type")
        }
        val fixed = f.getValue("fixed").jsonObject
        assertEquals(fixed.str("deletedSum"), deletedSum())
        assertEquals(fixed.str("retiredSum($PHONE_ASHA)"), retiredSum(PHONE_ASHA))
    }

    /** A record whose copy JSON cannot hold (NaN), made here; its text is checked against the fixture all the same. */
    private val unwritable: Map<String, Rec> = mapOf("Expense amount NaN against 1e21" to Expense(id = "e", amount = Double.NaN, updatedAt = 5))

    @Test
    fun recordTextAndMergeTieBreaks() {
        val f = fixture("records.json")
        for (r in f.objects("records")) {
            val type = r.str("type")
            val json = r.str("json")
            if (type == "PhoneEntry, as mergePhones compares it") {
                val e = decode("PhoneEntry", json) as PhoneEntry
                assertEquals(r.str("toString"), e.copy(retired = Stamp(), collected = false, key = "", keyAt = 0).toString(), type)
                continue
            }
            val x = decode(type, json)
            assertEquals(json, encode(type, x), "AppJson both ways, $type")
            assertEquals(r.str("toString"), x.toString(), "toString, $type")
            r.strOrNull("ownPart")?.let { assertEquals(it, ownPart(x as Rec), "ownPart, $type") }
        }
        for (t in f.objects("tieBreaks")) {
            val name = t.str("name")
            if ("merged" in t) {
                val a = decode("PhoneEntry", t.str("a")) as PhoneEntry
                val b = decode("PhoneEntry", t.str("b")) as PhoneEntry
                val ab = mergePhones(listOf(a), listOf(b)).single()
                assertEquals(ab, mergePhones(listOf(b), listOf(a)).single(), "$name: the same whichever phone merges")
                assertEquals(t.str("merged"), encode("PhoneEntry", ab), name)
                continue
            }
            val type = t.str("toStringA").substringBefore('(')
            fun copy(key: String): Any = if (t.str(key).startsWith("error: ")) unwritable.getValue(name) else decode(type, t.str(key))
            val a = copy("a")
            val b = copy("b")
            assertEquals(t.str("a"), orError { encode(type, a) }, "$name: a as AppJson")
            assertEquals(t.str("b"), orError { encode(type, b) }, "$name: b as AppJson")
            assertEquals(t.str("toStringA"), a.toString(), "$name: toString of a")
            assertEquals(t.str("toStringB"), b.toString(), "$name: toString of b")
            val (ab, ba) = if (a is MoveInfo && b is MoveInfo) {
                merge(AppData(move = a), AppData(move = b)).move to merge(AppData(move = b), AppData(move = a)).move
            } else {
                assertEquals(t.str("ownPartA"), ownPart(a as Rec), "$name: ownPart of a")
                assertEquals(t.str("ownPartB"), ownPart(b as Rec), "$name: ownPart of b")
                mergeList(listOf(a), listOf(b)).single() to mergeList(listOf(b), listOf(a)).single()
            }
            assertEquals(ab.toString(), ba.toString(), "$name: the same whichever phone merges")
            assertEquals(t.str("winner"), if (ab == a) "a" else if (ab == b) "b" else "neither: $ab", name)
        }
    }

    @Test
    fun hashesIdsAndTextRules() {
        val f = fixture("hashes.json")
        for (c in f.objects("hashCode")) {
            val s = c.str("text")
            assertEquals(c.getValue("hashCode").jsonPrimitive.int, s.hashCode(), "hashCode of '$s'")
            assertEquals(c.getValue("floorMod26").jsonPrimitive.int, s.hashCode().mod(26), "floorMod 26 of '$s'")
            assertEquals(c.getValue("floorMod5000").jsonPrimitive.int, s.hashCode().mod(5000), "floorMod 5000 of '$s'")
        }
        for (c in f.objects("legacyLabels")) {
            val l = c.str("letter")
            assertEquals(c.str("normalised"), l.trim().uppercase(), "letter '$l'")
            val r = legacyLabels(l)
            assertEquals(c.getValue("first").jsonPrimitive.int to c.getValue("last").jsonPrimitive.int, r.first to r.last, "letter '$l'")
        }
        val first = Person(id = "00000000-0000-4000-8000-000000000001", name = "Asha", letter = "B", grants = listOf(Grant(Role.ORGANISER, false, 1, "")))
        for (c in f.objects("letterInstead")) {
            val id = c.str("personId")
            val me = Person(id = id, name = "Me", letter = if ("taken" in c) "B" else "b", grants = listOf(Grant(Role.DECIDER, false, 2, first.id)))
            val people = if ("taken" in c) {
                listOf(first, me, Person(id = "u9", name = "Other", letter = c.str("taken"), grants = listOf(Grant(Role.HELPER, false, 3, first.id))))
            } else {
                assertEquals(c.getValue("start").jsonPrimitive.int, id.hashCode().mod(26), "start for '$id'")
                listOf(first, me)
            }
            assertEquals(c.strOrNull("letter"), AppData(people = people).letterInstead(me), "letter instead, for '$id'")
        }
        for (c in f.objects("derivedMoveId")) {
            val people = c.strOrNull("founder")?.let { listOf(Person(id = it, grants = listOf(Grant(Role.ORGANISER, false, T_ASHA, "")))) }
                ?: listOf(
                    Person(id = "u9", grants = listOf(Grant(Role.ORGANISER, false, 5, "x"))),
                    Person(id = "u10", grants = listOf(Grant(Role.ORGANISER, false, 5, "x"))),
                    Person(id = "u0", grants = listOf(Grant(Role.ORGANISER, false, 6, "x"))),
                ).also { assertEquals(c.str("people"), it.joinToString(",") { p -> p.id }) }
            assertEquals(c.str("moveId"), AppData(people = people).derivedMoveId(), "derived move ID of ${people.map { it.id }}")
        }
        for (c in f.objects("moveTagAndDailyKey")) {
            val m = c.str("moveId")
            assertEquals(c.str("moveTag"), moveTag(m), "tag of '$m'")
            assertEquals(c.str("dailyBackupKey"), DailyBackup.moveKey(m), "daily key of '$m'")
        }
        for (c in f.objects("digest")) {
            val s = c.str("text")
            assertEquals(c.str("toByteArray"), s.toUtf8().toHex(), "toByteArray of '$s'")
            assertEquals(c.str("toByteArray"), JavaText.utf8(s).toHex(), "JavaText.utf8 of '$s'")
            assertEquals(c.str("digest"), digest(s), "digest of '$s'")
        }
        for (c in f.objects("lineId")) {
            val t = c.str("text")
            assertEquals(c.str("trimmedLowercase"), t.trim().lowercase(), "trim().lowercase() of '$t'")
            assertEquals(c.str("lineId"), lineId("b1", t), "line id of '$t'")
        }
        for (c in f.objects("storedLines")) {
            val t = c.str("text")
            assertEquals(c.getValue("lines").jsonArray.map { it.jsonPrimitive.content }, storedLines(t), "lines of '$t'")
        }
        for (c in f.objects("refusedId")) {
            val r = Refusals(Roles(emptyList()))
            r.add(c.str("what"), c.str("targetId"), Stamp(c.str("by"), c.str("at").toLong(), "s"), "change {}")
            val made = r.into(emptyList())
            assertEquals(c.str("record"), if (made.isEmpty()) "none: a change in nobody's name is not recorded" else encode("RefusedChange", made.single()))
        }
        for (c in f.objects("caseAndTrim")) {
            val s = c.str("text")
            val utf = utf16(s)
            assertEquals(c.str("lowercase"), s.lowercase(), "lowercase of $utf")
            assertEquals(c.str("uppercase"), s.uppercase(), "uppercase of $utf")
            assertEquals(c.str("trim"), s.trim(), "trim of $utf")
            assertEquals(c.getValue("equalsIgnoreCaseLower").jsonPrimitive.content.toBooleanStrict(), s.equals(s.lowercase(), ignoreCase = true), "equals ignoring case, $utf")
        }
        for (c in f.objects("compareTo")) {
            assertEquals(c.getValue("sign").jsonPrimitive.int, c.str("a").compareTo(c.str("b")).sign, "'${c.str("a")}' against '${c.str("b")}'")
        }
    }

    private fun inviteJson(i: WifiSync.Invite?): JsonElement = if (i == null) JsonNull else buildJsonObject {
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

    @Test
    fun inviteAndSyncCodeText() {
        val f = fixture("invites.json")
        for (c in f.objects("encode")) {
            val name = c.str("name")
            val o = c.getValue("invite").jsonObject
            val i = WifiSync.Invite(
                o.getValue("hosts").jsonArray.map { it.jsonPrimitive.content }, o.getValue("port").jsonPrimitive.int, o.str("token"), o.strOrNull("personId"),
                o.str("personName"), o.strOrNull("role")?.let { Role.valueOf(it) }, o.str("moveId"), o.str("moveName"),
            )
            assertEquals(o, inviteJson(i), "$name: the invite")
            assertEquals(c.str("text"), i.encode(), "$name: its text")
            assertEquals(c.getValue("parsedBack"), inviteJson(WifiSync.Invite.parse(i.encode())), "$name: read back")
        }
        for (c in f.objects("parse")) {
            assertEquals(c.getValue("invite"), inviteJson(WifiSync.Invite.parse(c.str("text"))), "'${c.str("text")}'")
        }
        for (c in f.objects("urlEncode")) {
            val s = c.str("text")
            assertEquals(c.str("encoded"), urlEncode(s), "urlEncode of ${utf16(s)}")
            assertEquals(c.str("encoded"), JavaText.urlEncode(s), "JavaText.urlEncode of ${utf16(s)}")
        }
        for (c in f.objects("urlDecode")) {
            val s = c.str("text")
            assertEquals(c.str("decoded"), orError { urlDecode(s) }, "urlDecode of '$s'")
            assertEquals(c.str("decoded"), orError { JavaText.urlDecode(s) }, "JavaText.urlDecode of '$s'")
        }
    }

    @Test
    fun appJson() {
        val f = fixture("json.json")
        val types = mapOf(
            "an empty AppData" to "AppData", "an empty MoveInfo" to "MoveInfo", "a bare item" to "Item", "a stamp" to "Stamp",
            "an item with Doubles" to "Item", "a person" to "Person", "a suggestion with a cost" to "Proposal",
            "text that needs escaping" to "Activity", "an AppData with one of each list" to "AppData",
        )
        for (c in f.objects("encode")) {
            val name = c.str("name")
            val type = types.getValue(name)
            assertEquals(c.str("json"), encode(type, decode(type, c.str("json"))), name)
        }
        for (c in f.objects("decode")) {
            val name = c.str("name")
            val decoded = try {
                AppJson.decodeFromString(AppData.serializer(), c.str("input"))
            } catch (e: Exception) {
                assertEquals(c.str("decoded"), errorName(e), name)
                continue
            }
            assertEquals(c.str("reencoded"), orError { encode("AppData", decoded) }, name)
        }
        val moveData = GoldenFiles.text.getValue("move-data.json")
        assertEquals(moveData, encode("AppData", decode("AppData", moveData)), "move-data.json, read and written again")
    }

    @Test
    fun labelQrText() {
        val f = fixture("qr.json")
        val tag = f.str("moveTag")
        assertEquals(tag, moveTag(f.str("moveId")))
        for (c in f.objects("labelText")) assertEquals(c.str("text"), labelQrText(c.str("code"), c.str("moveTag")), c.str("code"))
        for (c in f.objects("readLabel")) {
            val t = c.str("text")
            val r = readLabel(t)
            assertEquals(c.str("code"), r.code, "code of '$t'")
            assertEquals(c.strOrNull("tag"), r.tag, "tag of '$t'")
            assertEquals(c.str("labelFromQr"), labelFromQr(t), "labelFromQr of '$t'")
        }
    }

    /**
     * Every message of the two recorded syncs read field by field with the common wire reader, and written again
     * with the common wire writer to the same bytes: what an iPhone has to read from and write to a 2.1.1 phone.
     */
    @Test
    fun syncTranscriptsReadAndWriteBackByteForByte() {
        for (file in listOf("sync-join.json", "sync-plain.json")) for (m in fixture(file).objects("messages")) {
            val bytes = unhex(m.str("hex"))
            val where = "$file step ${m.str("step")}"
            assertEquals(m.getValue("length").jsonPrimitive.int, bytes.size, where)
            val r = reader(bytes)
            val again = ArrayList<Byte>()
            val w = WireWriter { b -> b.forEach { again += it } }
            for (field in m.objects("fields")) {
                val what = "$where, ${field.str("name")}"
                when (field.str("type")) {
                    "utf", "utf-json" -> {
                        val s = r.readUTF()
                        assertEquals(field.str("text"), s, what)
                        w.writeUTF(s)
                    }
                    "int" -> {
                        val v = r.readInt()
                        assertEquals(field.getValue("value").jsonPrimitive.int, v, what)
                        w.writeInt(v)
                    }
                    "data" -> {
                        val n = r.readInt()
                        val data = ByteArray(n).also { r.readFully(it) }
                        assertEquals(field.str("sha256"), sha256(data).toHex(), what)
                        val decoded = AppJson.decodeFromString(AppData.serializer(), data.decodeUtf8())
                        val written = AppJson.encodeToString(AppData.serializer(), decoded).toUtf8()
                        assertContentEquals(data, written, "$what: AppJson both ways")
                        w.writeInt(written.size)
                        w.write(written)
                    }
                    "names" -> {
                        val n = r.readInt()
                        val names = (0 until n).map { r.readUTF() }
                        assertEquals(field.getValue("names").jsonArray.map { it.jsonPrimitive.content }, names, what)
                        w.writeInt(n)
                        names.forEach { w.writeUTF(it) }
                    }
                    "file" -> {
                        val name = r.readUTF()
                        val size = r.readLong()
                        val contents = ByteArray(size.toInt()).also { r.readFully(it) }
                        assertEquals(field.str("file"), name, what)
                        assertEquals(field.getValue("size").jsonPrimitive.long, size, what)
                        assertEquals(field.str("sha256"), sha256(contents).toHex(), what)
                        w.writeUTF(name)
                        w.writeLong(size)
                        w.write(contents)
                    }
                    else -> fail("$what: field type ${field.str("type")}")
                }
            }
            assertEquals(-1, r.read(ByteArray(1), 0, 1), "$where: nothing left over")
            assertEquals(m.str("hex"), again.toByteArray().toHex(), "$where, written again")
        }
        for (word in fixture("sync-refusals.json").objects("words")) {
            assertEquals(word.str("writeUTF"), JavaText.modifiedUtf8(word.str("word")).toHex(), word.str("word"))
        }
    }

    private companion object {
        /** GoldenMove's fixed clock and phone (jvmTest). */
        const val T_ASHA = 1767225600000L
        const val PHONE_ASHA = "pashaphone23456789ab"
    }
}

/** The same check of [JavaText], as the iPhone runs it, on values the fixtures do not hold. */
class JavaTextCommonTest {
    @Test
    fun isoDatesBothWays() {
        val cases = listOf(
            0L to "1970-01-01", -1L to "1969-12-31", 20600L to "2026-05-27", 11016L to "2000-02-29", -719528L to "0000-01-01",
            2932896L to "9999-12-31", 2932897L to "+10000-01-01", -719529L to "-0001-12-31",
        )
        for ((day, text) in cases) {
            assertEquals(text, JavaText.isoDate(day), "day $day")
            assertEquals(text, isoDate(day), "day $day, through the seam")
            if (!text.startsWith("+") && !text.startsWith("-")) assertEquals(day, JavaText.parseIsoDate(text), text)
        }
        for (bad in listOf("2026-02-29", "2026-13-01", "2026-00-10", "2026-04-31", "2026-4-01", "20260401", "", "2026-01-0x")) {
            assertTrue(runCatching { JavaText.parseIsoDate(bad) }.isFailure, "'$bad' is not a day")
        }
    }

    @Test
    fun hexAsPercent02x() {
        assertEquals("00017f80ff", byteArrayOf(0, 1, 0x7f, -128, -1).toHex())
    }

    @Test
    fun doublesNearTheEdgesOfPlainText() {
        val cases = listOf(
            1e-3 to "0.001", 9.999999999999998E-4 to "9.999999999999998E-4", 1e7 to "1.0E7", 9999999.999999998 to "9999999.999999998",
            2.0 * Double.MIN_VALUE to "9.9E-324", 3.0 * Double.MIN_VALUE to "1.5E-323", 1e23 to "1.0E23", 8.41e21 to "8.41E21",
            5e-324 to "4.9E-324", 2.2250738585072014E-308 to "2.2250738585072014E-308", 1.0 to "1.0", 0.5 to "0.5", 123456.0 to "123456.0",
        )
        for ((d, text) in cases) assertEquals(text, JavaText.doubleText(d), "$text (bits ${d.toRawBits()})")
    }
}
