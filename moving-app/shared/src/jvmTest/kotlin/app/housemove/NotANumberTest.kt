@file:OptIn(ExperimentalSerializationApi::class)

package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * NaN and the infinities (2.2). In 2.1.1 a number field took "NaN", "Infinity" or a 310-digit number; kotlinx.serialization
 * then refused to write the data, the Store said nothing and wrote nothing more, a backup threw a raw JsonEncodingException,
 * a sync failed, and the next change was gone when the app was opened again. The fields now refuse such text
 * (NumbersCommonTest); here: the Store's own safety net, what a failed save does now, and data from other phones.
 */
class NotANumberTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var folders = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()
    private val servers = ArrayList<ServerSocket>()

    private fun phone(name: String = "", dir: File = tmp.newFolder("p" + folders++)): Store =
        Store(dir) { now++ }.also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    @After
    fun hangUp() {
        open.forEach { it.stop() }
        servers.forEach { it.close() }
        WifiSync.olderPhone = { false }
    }

    private fun hosting(store: Store): Pair<WifiSync.Host, LinkedBlockingQueue<WifiSync.Result>> {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        return WifiSync.host(store, { now }) { results.add(it) }.also { open += it } to results
    }

    private fun LinkedBlockingQueue<WifiSync.Result>.next(): WifiSync.Result = poll(20, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")

    /** [name]'s phone joins [host]'s move; with [older], it is a phone with an older HouseMove, which syncs only plain. */
    private fun invited(host: Store, name: String, role: Role, older: Boolean = false): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val (h, results) = hosting(host)
        val phone = phone(name)
        if (older) WifiSync.olderPhone = { it == phone.phone.id }
        val r = WifiSync.join(phone, h.inviteFor(p.id, local).valueOrNull()!!)
        assertTrue(r.error, r.ok)
        assertTrue(results.next().ok)
        return phone
    }

    private fun sync(host: Store, joiner: Store) {
        val (h, results) = hosting(host)
        val r = WifiSync.join(joiner, h.invite(local))
        assertTrue(r.error, r.ok)
        assertTrue(results.next().ok)
    }

    private fun waitFor(what: String, ms: Long = 10_000, test: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!test()) {
            if (System.currentTimeMillis() > until) throw AssertionError("waited in vain: $what")
            Thread.sleep(20)
        }
    }

    private fun onDisk(dir: File): AppData = AppJson.decodeFromString(AppData.serializer(), File(dir, "data.json").readText())

    /** Asha's move, with one of each record that holds a number. */
    private class Move(val asha: Store, val dir: File, val sofa: Item, val cost: Expense, val hall: Room)

    private fun move(): Move {
        val dir = tmp.newFolder("asha")
        val asha = phone("Asha", dir)
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val sofa = asha.createItem("Sofa", null)
        asha.put(asha.data.value.item(sofa.id)!!.copy(widthCm = 210.0, depthCm = 95.0, heightCm = 90.0))
        assertTrue(asha.decide(sofa.id, Disposition.SELL, 80.0).ok)
        val cost = asha.saveCost(Expense(id = "van", title = "Van hire", amount = 180.5)).valueOrNull()!!
        val hall = asha.createRoom(Home.DEST, "Hall")
        asha.put(hall.copy(doorWidthCm = 76.0))
        asha.flush()
        return Move(asha, dir, asha.data.value.item(sofa.id)!!, cost, asha.data.value.rooms.first { it.id == hall.id })
    }

    // ---- the Store's safety net ------------------------------------------------------------------------------------

    @Test
    fun aChangeThatWouldPutNaNOrAnInfinityInIsRefusedInWordsAndNothingOfItIsKept() {
        val m = move()
        val asha = m.asha
        val before = asha.data.value
        val nan = Double.NaN
        val inf = Double.POSITIVE_INFINITY
        val tries: List<Pair<String, () -> Outcome<*>>> = listOf(
            "a cost's amount" to { asha.saveCost(m.cost.copy(amount = nan)) },
            "a cost's amount" to { asha.saveOrSuggest(m.cost.copy(amount = inf)) },
            "a cost's amount" to { asha.put(m.cost.copy(amount = -inf)) },
            "a cost's amount" to { asha.saveCost(Expense(id = "new", title = "Boxes", amount = nan)) },
            "an item's width" to { asha.put(m.sofa.copy(widthCm = inf)) },
            "an item's depth" to { asha.updateItems(listOf(m.sofa.id)) { it.copy(depthCm = nan) } },
            "an item's height" to { asha.put(m.sofa.copy(heightCm = nan)) },
            "a door's width" to { asha.put(m.hall.copy(doorWidthCm = nan)) },
            "the load space" to { asha.setMove { it.copy(vanCapacityM3 = nan) } },
            "the load space" to { asha.changeMove { it.copy(vanCapacityM3 = -inf) } },
            "an item's asking price" to { asha.decide(m.sofa.id, Disposition.SELL, nan) },
            "what an item sold for" to { asha.carryOut(m.sofa.id, gone = true, soldFor = inf) },
            "what an item is worth" to { asha.setDetails(m.sofa.id) { it.copy(value = nan) } },
            "an amount to collect" to { asha.arrangeHandover(m.sofa.id, to = asha.me!!.id, amount = nan) },
            "a cost's amount" to { asha.update { d -> d.copy(expenses = d.expenses.map { it.copy(amount = nan) }) } },
        )
        for ((what, attempt) in tries) {
            val out = attempt()
            assertEquals(what, Outcome.Refused(notANumberRefusal(what)), out)
            assertEquals("nothing of it was kept ($what)", before, asha.data.value)
        }
        // The rest of the app goes on: a normal change is saved, a backup can be made, and the change is there on the next start.
        assertNull(asha.saveProblem.value)
        assertTrue(asha.saveCost(m.cost.copy(amount = 95.0)).ok)
        Backup.export(asha, ByteArrayOutputStream())
        asha.close()
        val again = Store(m.dir) { now++ }
        assertEquals(95.0, again.data.value.expense("van")!!.amount, 0.0)
        assertEquals(210.0, again.data.value.item(m.sofa.id)!!.widthCm!!, 0.0)
        assertNull(again.loadWarning)
    }

    @Test
    fun aDecidersSuggestionWithNaNInItIsRefusedToo() {
        val m = move()
        val dee = invited(m.asha, "Dee", Role.DECIDER)
        val before = dee.data.value
        assertEquals(Outcome.Refused(notANumberRefusal("a suggested asking price")), dee.suggestDecision(m.sofa.id, Disposition.SELL, Double.NaN))
        assertEquals(Outcome.Refused(notANumberRefusal("a cost's amount")), dee.saveOrSuggest(m.cost.copy(amount = Double.POSITIVE_INFINITY)))
        assertEquals(Outcome.Refused(notANumberRefusal("a suggested cost's amount")), dee.suggestCost(Expense(id = "x", title = "Tape", amount = Double.NaN)))
        assertEquals(before, dee.data.value)
        assertTrue(dee.suggestDecision(m.sofa.id, Disposition.SELL, 60.0).ok)
    }

    // ---- every Double in the data ------------------------------------------------------------------------------------

    /** Where each Double of [d] is, as a path into its JSON: "items[0].widthCm", "proposals[0].expense.amount". */
    private fun doublesIn(d: SerialDescriptor, at: String = "", seen: Set<String> = emptySet()): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until d.elementsCount) {
            val e = d.getElementDescriptor(i)
            val path = (if (at.isEmpty()) "" else "$at.") + d.getElementName(i)
            val name = e.serialName.removeSuffix("?")
            when {
                e.kind == PrimitiveKind.DOUBLE || e.kind == PrimitiveKind.FLOAT -> out += path
                e.kind == StructureKind.LIST && e.getElementDescriptor(0).kind == StructureKind.CLASS -> out += doublesIn(e.getElementDescriptor(0), "$path[0]", seen)
                e.kind == StructureKind.CLASS && name !in seen -> out += doublesIn(e, path, seen + name)
                e.kind == StructureKind.MAP -> assertTrue("no Double in a map: $path", (0 until e.elementsCount).none { e.getElementDescriptor(it).kind == PrimitiveKind.DOUBLE })
            }
        }
        return out
    }

    /** [e] with [value] at [path], making the objects on the way (the first of a list must be there). */
    private fun JsonElement.with(path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        val head = path[0]
        val key = head.substringBefore('[')
        val o = (this as? JsonObject) ?: JsonObject(emptyMap())
        val inner = o[key]
        val next = if (head.endsWith("[0]")) {
            val list = inner as JsonArray
            JsonArray(listOf(list[0].with(path.drop(1), value)) + list.drop(1))
        } else (inner ?: JsonObject(emptyMap())).with(path.drop(1), value)
        return JsonObject(o + (key to next))
    }

    private val sample = AppData(
        rooms = listOf(Room(id = "r", home = Home.DEST, name = "Hall")),
        items = listOf(Item(id = "i", name = "Sofa")),
        expenses = listOf(Expense(id = "e", amount = 10.0)),
        proposals = listOf(Proposal(id = "p", expense = Expense(id = "e2", amount = 5.0))),
        hidden = listOf(Hidden(id = "i")),
        collect = listOf(Collect(id = "i")),
    )

    /** What lets a test hold NaN in data, which the app's own JSON never reads. */
    private val lenient = Json(AppJson) { allowSpecialFloatingPointValues = true }

    @Test
    fun everyDoubleInTheDataIsWatchedByTheStoreAndRefusedByEveryReaderOfData() {
        val paths = doublesIn(AppData.serializer().descriptor)
        // The list of firstNonFinite (Numbers.kt). A Double added to the data shows up here first.
        assertEquals(
            setOf(
                "move.vanCapacityM3", "rooms[0].doorWidthCm", "items[0].price", "items[0].widthCm", "items[0].depthCm", "items[0].heightCm",
                "items[0].value", "items[0].soldFor", "expenses[0].amount", "proposals[0].price", "proposals[0].expense.amount",
                "hidden[0].value", "hidden[0].price", "hidden[0].soldFor", "collect[0].amount",
            ),
            paths.toSet(),
        )
        val base = AppJson.encodeToJsonElement(AppData.serializer(), sample)
        val store = phone("Asha")
        for (path in paths) {
            val steps = path.split('.')
            // In memory, as only a bug could put it there: the Store's net sees it and refuses the change.
            val nan = lenient.decodeFromJsonElement(AppData.serializer(), base.with(steps, JsonPrimitive(Double.NaN)))
            val what = nan.firstNonFinite()
            assertNotNull(path, what)
            val before = store.data.value
            assertEquals(path, Outcome.Refused(notANumberRefusal(what!!)), store.update { nan })
            assertSame(path, before, store.data.value)
            // As text from another phone, a backup or an update file: AppJson does not read it, in any of the forms it could come in.
            val forms = listOf(
                JsonUnquotedLiteral("NaN"), JsonUnquotedLiteral("Infinity"), JsonUnquotedLiteral("-Infinity"), JsonUnquotedLiteral("1e309"),
                JsonUnquotedLiteral("-1e400"), JsonPrimitive("NaN"), JsonPrimitive("Infinity"), JsonPrimitive("-Infinity"), JsonPrimitive("1e400"),
            )
            for (form in forms) {
                val text = lenient.encodeToString(JsonElement.serializer(), base.with(steps, form))
                try {
                    AppJson.decodeFromString(AppData.serializer(), text)
                    fail("$path = $form was read")
                } catch (_: SerializationException) {
                }
            }
            // A number in quotes is read, as before (json.json): it is the infinity that is refused, not the quotes.
            val quoted = lenient.encodeToString(JsonElement.serializer(), base.with(steps, JsonPrimitive("12.5")))
            assertNull(path, AppJson.decodeFromString(AppData.serializer(), quoted).firstNonFinite())
        }
    }

    // ---- a save that fails -------------------------------------------------------------------------------------------

    @Test
    fun aSaveThatFailsIsSaidKeepsTheChangesAndIsTriedAgainUntilItWorks() {
        val dir = tmp.newFolder("disk")
        val asha = phone("Asha", dir)
        asha.saveRetryMs = 100
        asha.setMove { it.copy(name = "Maple Road") }
        asha.flush()
        assertNull(asha.saveProblem.value)
        // The phone cannot write the file now: here a folder stands where the new copy is written first (as a full disk would fail it).
        val blocker = File(dir, "data.json.tmp").apply { assertTrue(mkdir()) }
        val lamp = asha.createItem("Lamp", null)
        waitFor("the failed save to be said") { asha.saveProblem.value != null }
        assertEquals(Store.SAVE_FAILED, asha.saveProblem.value)
        // Nothing is lost: the change is in memory, and so is the next one, though neither is on the phone yet.
        val kettle = asha.createItem("Kettle", null)
        assertNotNull(asha.data.value.item(lamp.id))
        assertNotNull(asha.data.value.item(kettle.id))
        assertNull(onDisk(dir).item(lamp.id))
        Thread.sleep(400)
        assertEquals("it keeps failing while the phone cannot write", Store.SAVE_FAILED, asha.saveProblem.value)
        // Once the phone can write again, the next try saves everything by itself. (2.1.1 wrote nothing more until the app
        // went to the background, and said nothing.)
        assertTrue(blocker.delete())
        waitFor("the save to go through") { asha.saveProblem.value == null }
        assertNotNull(onDisk(dir).item(lamp.id))
        assertNotNull(onDisk(dir).item(kettle.id))
        asha.close()
        val again = Store(dir) { now++ }
        assertNotNull(again.data.value.item(kettle.id))
        assertNull(again.saveProblem.value)
    }

    @Test
    fun dataThatCannotBeWrittenIsSaidAndABackupOfItFailsInWords() {
        val m = move()
        m.asha.saveRetryMs = 100
        // Only a bug could get NaN past the Store now; replaceAll is the raw replacement, which the screens give only data read from a file.
        m.asha.replaceAll(m.asha.data.value.let { d -> d.copy(expenses = d.expenses.map { it.copy(amount = Double.NaN) }) })
        waitFor("the failed save to be said") { m.asha.saveProblem.value != null }
        val e = assertThrows(IOException::class.java) { Backup.export(m.asha, ByteArrayOutputStream()) }
        assertEquals(Backup.COULD_NOT_WRITE, e.message)
        // Putting it right is a change like any other, and saves.
        assertTrue(m.asha.saveCost(m.asha.data.value.expense("van")!!.copy(amount = 180.5)).ok)
        waitFor("the save to go through") { m.asha.saveProblem.value == null }
        assertEquals(180.5, onDisk(m.dir).expense("van")!!.amount, 0.0)
    }

    // ---- data from other phones ----------------------------------------------------------------------------------------

    /** [json] with Asha's cost of 180.5 made [to]. */
    private fun spoilt(json: String, to: String): String {
        assertEquals(1, Regex("\"amount\":180\\.5\\b").findAll(json).count())
        return json.replace("\"amount\":180.5", "\"amount\":$to")
    }

    private val spoilers = listOf("NaN", "Infinity", "-Infinity", "1e309", "\"NaN\"", "\"Infinity\"")

    @Test
    fun aSyncWhoseDataHasNaNInItIsRefusedAndChangesNothingEitherWay() {
        val m = move()
        val asha = m.asha
        // A host that answers, takes this phone's data (all a stranger is sent, which has no costs) and sends [json] as the move.
        val full = AppJson.encodeToString(AppData.serializer(), asha.data.value)
        fun joinAHostThatSends(json: String): WifiSync.Result {
            val fake = ServerSocket(0).also { servers += it }
            thread(isDaemon = true) {
                try {
                    fake.accept().use { s ->
                        val din = DataInputStream(s.getInputStream().buffered())
                        val out = DataOutputStream(s.getOutputStream().buffered())
                        repeat(4) { din.readUTF() }; din.readInt(); repeat(2) { din.readUTF() }
                        out.writeUTF("OK"); out.writeUTF("Odd"); out.writeUTF("O"); out.writeUTF(""); out.flush()
                        assertEquals("GO", din.readUTF())
                        ByteArray(din.readInt()).also { din.readFully(it) }
                        val back = json.toByteArray()
                        out.writeInt(back.size); out.write(back); out.flush()
                        // The photos, both ways: none.
                        repeat(din.readInt()) { din.readUTF() }
                        out.writeInt(0); out.flush()
                        while (din.readUTF().isNotEmpty()) Unit
                        out.writeUTF(""); out.flush()
                        Thread.sleep(300)
                    }
                } catch (_: Exception) {
                }
            }
            return WifiSync.join(asha, WifiSync.Invite(local, fake.localPort, "ANY"))
        }
        // The same host sending the data as it came is taken in: what is refused below is the number, not the host.
        val fine = joinAHostThatSends(full)
        assertTrue(fine.error, fine.ok)
        val had = asha.data.value
        for (bad in spoilers) {
            val r = joinAHostThatSends(spoilt(full, bad))
            assertEquals(bad, WifiSync.UNREADABLE, r.error)
            assertEquals(bad, had.expenses, asha.data.value.expenses)
            assertNull(bad, asha.data.value.firstNonFinite())
        }

        // The other way: a phone of the move that sends its data with the cost spoilt to a hosting phone. It says the plain
        // hello by hand, so it is a phone with an older HouseMove (a 2.4 phone saying it would be refused for that alone).
        val dee = invited(asha, "Dee", Role.DECIDER, older = true)
        sync(asha, dee)
        fun sendToTheHost(change: (String) -> String): Pair<WifiSync.Result, Boolean> {
            val (h, results) = hosting(asha)
            var answered = false
            Socket("127.0.0.1", h.port).use { s ->
                s.soTimeout = 10_000
                val out = DataOutputStream(s.getOutputStream().buffered())
                val din = DataInputStream(s.getInputStream().buffered())
                out.writeUTF("HMSYNC3"); out.writeUTF(h.invite(local).token); out.writeUTF("Dee"); out.writeUTF(dee.device.value.letter); out.writeInt(SCHEMA)
                out.writeUTF(AppJson.encodeToString(Person.serializer(), dee.me!!))
                val id = dee.identity.value
                val hello = WifiSync.Hello(phoneId = dee.phone.id, moveId = id.id, aliases = id.aliases, derived = id.derived, moveName = "Maple Road", key = dee.phone.keys.publicText, keyAt = dee.phone.keys.madeAt)
                out.writeUTF(AppJson.encodeToString(WifiSync.Hello.serializer(), hello)); out.flush()
                assertEquals("OK", din.readUTF()); repeat(3) { din.readUTF() }
                out.writeUTF("GO")
                val mine = change(AppJson.encodeToString(AppData.serializer(), dee.data.value)).toByteArray()
                out.writeInt(mine.size); out.write(mine); out.flush()
                try {
                    // The host sends the merged data back only when it took this in.
                    ByteArray(din.readInt()).also { din.readFully(it) }
                    answered = true
                    out.writeInt(0); out.flush()
                    repeat(din.readInt()) { din.readUTF() }
                    out.writeUTF(""); out.flush()
                    while (din.readUTF().isNotEmpty()) Unit
                } catch (_: IOException) {
                }
            }
            return results.next() to answered
        }
        val (taken, took) = sendToTheHost { it }
        assertTrue(taken.error, taken.ok)
        assertTrue(took)
        val hostHad = asha.data.value
        for (bad in spoilers) {
            val (r, answered) = sendToTheHost { spoilt(it, bad) }
            assertEquals(bad, WifiSync.UNREADABLE, r.error)
            assertFalse(bad, answered)
            assertEquals(bad, hostHad.expenses, asha.data.value.expenses)
            assertNull(bad, asha.data.value.firstNonFinite())
        }
    }

    /** The entries of a zip, by name. */
    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> for ((name, b) in entries) { z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun aBackupWithNaNInItIsNotRestored() {
        val m = move()
        val asha = m.asha
        val entries = unzip(ByteArrayOutputStream().also { Backup.export(asha, it) }.toByteArray())
        val json = entries.getValue("data.json").decodeToString()
        // Put back together as it came, the backup restores: what is refused below is the number.
        assertTrue(Backup.import(asha, ByteArrayInputStream(zip(entries))).records > 0)
        val before = asha.data.value
        for (bad in spoilers) {
            val spoiltZip = zip(entries + ("data.json" to spoilt(json, bad).toByteArray()))
            for (replace in listOf(false, true)) {
                val e = assertThrows(IllegalArgumentException::class.java) { Backup.import(asha, ByteArrayInputStream(spoiltZip), replace) }
                assertEquals(bad, Backup.NOT_A_BACKUP, e.message)
                assertEquals(bad, before, asha.data.value)
            }
            assertThrows(IllegalArgumentException::class.java) { Backup.peek(ByteArrayInputStream(spoiltZip)) }
        }
    }

    /** An update file from [from] for [to], made as Update.write makes one, with [json] as its data. */
    private fun updateWith(from: Store, to: Store, json: String): ByteArray {
        val id = from.identity.value
        val b64 = Base64.getEncoder()
        val contentKey = Sealing.randomBytes(32)
        val prefix = Sealing.randomBytes(8)
        val w = Sealing.wrap(JcaCrypto, contentKey, Base64.getDecoder().decode(to.phone.keys.publicText), to.phone.id, id.id)
        val header = Update.Header(
            schema = SCHEMA, moveId = id.id, aliases = id.aliases, derived = id.derived, from = from.phone.id, at = now, nonce = b64.encodeToString(prefix),
            to = listOf(Update.To(to.phone.id, b64.encodeToString(w.ephemeral), b64.encodeToString(w.salt), b64.encodeToString(w.iv), b64.encodeToString(w.sealed))),
        )
        val headerBytes = AppJson.encodeToString(Update.Header.serializer(), header).toByteArray()
        val bytes = ByteArrayOutputStream()
        val dout = DataOutputStream(bytes)
        dout.write("HMUPDATE".toByteArray())
        dout.writeInt(Update.FORMAT)
        dout.writeInt(headerBytes.size)
        dout.write(headerBytes)
        val sealed = SealedOut(JcaCrypto, dout, contentKey, prefix, Sealing.sha256(headerBytes))
        val zip = ZipOutputStream(sealed.stream)
        zip.putNextEntry(ZipEntry("data.json"))
        zip.write(json.toByteArray())
        zip.closeEntry()
        zip.finish()
        sealed.close()
        dout.flush()
        return bytes.toByteArray()
    }

    @Test
    fun anUpdateFileWithNaNInItChangesNothing() {
        val m = move()
        val asha = m.asha
        val dee = invited(asha, "Dee", Role.DECIDER)
        sync(asha, dee)
        assertTrue(dee.saveOrSuggest(Expense(id = "tape", title = "Tape", amount = 12.0)).ok)
        val json = AppJson.encodeToString(AppData.serializer(), dee.data.value)
        val before = asha.data.value
        for (bad in spoilers) {
            val r = Update.open(asha, ByteArrayInputStream(updateWith(dee, asha, spoilt(json, bad))))
            assertEquals(bad, Update.DAMAGED, r.message)
            assertEquals(bad, before, asha.data.value)
        }
        // Made the same way with the data as it was, the update is taken in: what was refused is the number.
        val r = Update.open(asha, ByteArrayInputStream(updateWith(dee, asha, json)))
        assertTrue(r.message, r.ok)
        assertTrue(asha.data.value.liveProposals.any { it.expense?.title == "Tape" })
    }
}
