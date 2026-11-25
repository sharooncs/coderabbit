package app.housemove

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.Backup
import app.housemove.data.CountDownLatch
import app.housemove.data.Disposition
import app.housemove.data.Expense
import app.housemove.data.File
import app.housemove.data.Home
import app.housemove.data.Lock
import app.housemove.data.PhotoKind
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.Supply
import app.housemove.data.Task
import app.housemove.data.ZipIn
import app.housemove.data.awaitMillis
import app.housemove.data.countDown
import app.housemove.data.mkdirs
import app.housemove.data.newId
import app.housemove.data.outputStream
import app.housemove.data.peek
import app.housemove.data.readBytes
import app.housemove.data.readText
import app.housemove.data.sha256
import app.housemove.data.startThread
import app.housemove.data.synchronized
import app.housemove.data.toHex
import app.housemove.data.write
import app.housemove.data.writeText
import app.housemove.sync.Socket
import app.housemove.sync.WifiSync
import app.housemove.sync.accept
import app.housemove.sync.localPort
import app.housemove.sync.close
import app.housemove.sync.connect
import app.housemove.sync.dataInUnbuffered
import app.housemove.sync.dataOut
import app.housemove.sync.flush
import app.housemove.sync.read
import app.housemove.sync.soTimeout
import app.housemove.sync.tcpNoDelay
import app.housemove.sync.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The golden fixtures' fixed move (golden/README.md) played through the real Store and WifiSync code, over real
 * sockets on 127.0.0.1, with the hosting phone and the joining phone in this one process on threads of their own:
 * the move set up and its data.json, Priya's phone joining with the invite, the plain sync after, and the hellos the
 * hosting phone turns down. Every byte that crosses is kept by a relay between the two, and must be the bytes the
 * released Android app, 2.1.1, sent (sync-join.json, sync-plain.json, sync-refusals.json, move-data.json). A backup
 * of the move made then holds what backup.json says every backup of it holds, and the backup Android made opens.
 *
 * This runs on the JVM (java.io, java.net, java.util.zip) and on the iPhone simulator (Files.ios.kt, Net.ios.kt, the
 * common zip writer and reader): the same bytes on both. Kotlin tests on the iPhone cannot call the app's CryptoKit; there the
 * phones use the reference crypto ([ReferenceCrypto]) with the fixed keys in their folders, as the JVM's phones use the JCA.
 */
class GoldenSyncCommonTest {
    private companion object {
        const val T_ASHA = 1_767_225_600_000L
        const val T_PRIYA = T_ASHA + 86_400_000L
        const val INVITE_CODE = "GQ7K2MXP"
        const val HOST_TOKEN = "HM4RT8WZ"

        val KEY_ASHA = keyFile(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtZeJYt7fkoLA9xt5p2FaHuA/dSrcQr0TzbtIN8R0qNNRMgwA9KqG0QLi7W3ON2u7ovAhOVft0PmW/Ez54L30hQ==",
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgUUSEJ2b0AW/COagdMHllSWYXyv712X78/d+DXGQJGj2hRANCAAS1l4li3t+SgsD3G3mnYVoe4D91KtxCvRPNu0g3xHSo01EyDAD0qobRAuLtbc43a7ui8CE5V+3Q+Zb8TPngvfSF",
            T_ASHA - 60_000,
        )
        val KEY_PRIYA = keyFile(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEay0G0qqU14TY9r0UogFeqHX0Mov3QcTNEALy2ygSOdyJYCZRXqRRjQsu9mPtU9ANyBKH6yoQfXjgeBLAxWhD5A==",
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg7vTzQRDK7yPvjrvwkSo/A2T4/xyMwe4L9oPJSLqPMm2hRANCAARrLQbSqpTXhNj2vRSiAV6odfQyi/dBxM0QAvLbKBI53IlgJlFepFGNCy72Y+1T0A3IEofrKhB9eOB4EsDFaEPk",
            T_PRIYA - 60_000,
        )

        fun keyFile(public: String, private: String, at: Long) = """{"public":"$public","private":"$private","at":$at}"""
    }

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(GoldenFiles.text.getValue(name)).jsonObject

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Ids as the fixed move takes them: "00000000-0000-4000-8000-000000000001" and on. */
    private class Ids {
        private val lock = Lock()
        private var n = 0

        fun next(): String = synchronized(lock) {
            n++
            "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
        }
    }

    private fun prepare(dir: File, phoneId: String, key: String, name: String, letter: String) {
        dir.mkdirs()
        File(dir, "phone-id").writeText(phoneId)
        File(dir, "phone-key.json").writeText(key)
        File(dir, "device.json").writeText("""{"name":"$name","letter":"$letter"}""")
    }

    private fun File.writeBytesCommon(b: ByteArray) = outputStream().use { it.write(b, 0, b.size) }

    /** What the hosting phone reports, one result after another. */
    private class Results {
        private val lock = Lock()
        private val items = ArrayList<WifiSync.Result>()
        private val arrived = Array(16) { CountDownLatch(1) }

        fun add(r: WifiSync.Result) {
            val i = synchronized(lock) {
                items += r
                items.size - 1
            }
            if (i < arrived.size) arrived[i].countDown()
        }

        fun await(i: Int): WifiSync.Result {
            if (!arrived[i].awaitMillis(30_000)) fail("the hosting phone reported nothing for sync ${i + 1}")
            return synchronized(lock) { items[i] }
        }
    }

    /**
     * A relay on 127.0.0.1 between a joining phone and the hosting one at [targetPort] that keeps every byte, split
     * where the direction changes (a sync is a strict back-and-forth, so each change of direction is the next
     * message). Bytes are kept before they are passed on, as the JVM's recorder of the fixtures (GoldenMove.kt) does.
     */
    private class Relay(targetPort: Int) {
        private val server = loopbackServer()
        val port: Int = server.localPort
        private val lock = Lock()
        private val phases = ArrayList<Pair<Int, BytesOut>>()
        private var lastDir = -1
        private val open = ArrayList<Socket>()

        init {
            startThread("golden-relay") {
                val joiner = try {
                    server.accept()
                } catch (_: Exception) {
                    return@startThread
                }
                val host = Socket()
                host.connect("127.0.0.1", targetPort, 4000)
                joiner.tcpNoDelay = true
                host.tcpNoDelay = true
                synchronized(lock) { open += listOf(joiner, host) }
                startThread("golden-relay-out") { pump(0, joiner, host) }
                startThread("golden-relay-back") { pump(1, host, joiner) }
            }
        }

        private fun pump(dir: Int, from: Socket, to: Socket) {
            val buf = ByteArray(16 * 1024)
            try {
                val input = from.dataInUnbuffered()
                val out = to.dataOut()
                while (true) {
                    val n = input.read(buf, 0, buf.size)
                    if (n < 0) break
                    synchronized(lock) {
                        if (dir != lastDir) {
                            lastDir = dir
                            phases += dir to BytesOut()
                        }
                        phases.last().second.stream.write(buf, 0, n)
                    }
                    out.write(buf.copyOf(n))
                    out.flush()
                }
            } catch (_: Exception) {
                // The other end closed.
            }
        }

        /** Who sent each message ("joiner" or "host"), and its bytes, in order. */
        fun phases(): List<Pair<String, ByteArray>> = synchronized(lock) { phases.map { (if (it.first == 0) "joiner" else "host") to it.second.bytes() } }

        fun close() {
            try {
                server.close()
            } catch (_: Exception) {
            }
            synchronized(lock) { open.toList() }.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun <T> relay(port: Int, block: (Int) -> T): Pair<T, List<Pair<String, ByteArray>>> {
        val r = Relay(port)
        try {
            return block(r.port) to r.phases()
        } finally {
            r.close()
        }
    }

    /** A hello sent straight to the hosting phone, and all it answers until it hangs up. */
    private fun answerTo(port: Int, sent: ByteArray): ByteArray {
        val got = BytesOut()
        val s = Socket()
        try {
            s.connect("127.0.0.1", port, 4000)
            s.soTimeout = 20_000
            val out = s.dataOut()
            out.write(sent)
            out.flush()
            val input = s.dataInUnbuffered()
            val buf = ByteArray(1024)
            while (true) {
                val n = input.read(buf, 0, buf.size)
                if (n < 0) break
                got.stream.write(buf, 0, n)
            }
        } catch (_: Exception) {
            // It hung up.
        } finally {
            s.close()
        }
        return got.bytes()
    }

    private class Run(
        val moveData: String,
        val backup: ByteArray,
        val join: List<Pair<String, ByteArray>>,
        val plain: List<Pair<String, ByteArray>>,
        val refusals: List<Pair<String, ByteArray>>,
    )

    /** Steps 1 to 4 of the fixed move (golden/README.md), as GoldenMove.kt plays them on the JVM. */
    private fun play(root: File): Run {
        val dirA = File(root, "asha")
        val dirP = File(root, "priya")
        prepare(dirA, "pashaphone23456789ab", KEY_ASHA, "Asha", "A")
        prepare(dirP, "ppriyaphone2345678ab", KEY_PRIYA, "Priya 🏠", "P")

        val a = Store(dirA) { T_ASHA }
        a.setMove { it.copy(name = "Maple Road", moveDay = 20_600, currency = "£", vanCapacityM3 = 12.5, setupDone = true) }
        val kitchenTo = a.createRoom(Home.DEST, "Kitchen")
        val kitchen = a.createRoom(Home.ORIGIN, "Kitchen")
        a.put(kitchen.copy(mapsTo = kitchenTo.id))
        val zoe = a.createRoom(Home.ORIGIN, "Zoë's room")
        val sofa = a.createItem("Sofa", kitchen.id)
        a.put(sofa.copy(widthCm = 210.0, depthCm = 0.1, heightCm = 1e-4, fragile = true))
        assertTrue(a.decide(sofa.id, Disposition.SELL, price = 1e7).ok)
        val tv = a.createItem("TV 日本", zoe.id)
        a.put(a.data.value.item(tv.id)!!.copy(widthCm = -0.0))
        assertTrue(a.setDetails(tv.id) { it.copy(value = 1e-3, make = "Sony", model = "X90", serial = "SN-0042") }.ok)
        val box = a.createBox("L001", kitchen.id)
        assertTrue(a.setContents(box.id, "Plates, Mugs; ΟΔΟΣ cups, Straße").ok)
        assertTrue(a.reportDamage(box.id, "Corner crushed").ok)
        a.addPhoto(box.id, PhotoKind.CONTENTS, a.newPhotoFile().apply { writeBytesCommon("golden photo: plates".encodeToByteArray()) })
        a.put(Task(id = newId(), title = "Book the van", offsetDays = -28))
        assertTrue(a.saveCost(Expense(id = newId(), title = "Van hire", amount = 1e21, planned = true)).ok)
        a.put(Supply(id = newId(), name = "Tape", need = 6, have = 2))
        val priyaPerson = a.addPerson("Priya 🏠", Role.DECIDER).valueOrNull()!!
        a.flush()
        val moveData = File(dirA, "data.json").readText()
        val backup = BytesOut().also { Backup.export(a, it.stream) }.bytes()

        val p = Store(dirP) { T_PRIYA }
        val results = Results()
        val host = WifiSync.Host(a, loopbackServer(), HOST_TOKEN, { T_PRIYA }, true, false) { results.add(it) }
        host.start()
        try {
            val local = listOf("127.0.0.1")
            val drawn = host.inviteFor(priyaPerson.id, local).valueOrNull()!!
            // The invite's one-time code is drawn at random; here it is set, so the bytes repeat.
            host.recode(drawn.token, INVITE_CODE)
            val invite = drawn.copy(token = INVITE_CODE)
            val join = relay(host.port) { port -> WifiSync.join(p, invite.copy(port = port)) }
            val joined = results.await(0)
            assertTrue(join.first.ok && joined.ok, "the join failed: ${join.first.error} / ${joined.error}")

            val lamp = p.createItem("Lamp", null)
            p.addPhoto(lamp.id, PhotoKind.CONDITION, p.newPhotoFile().apply { writeBytesCommon("golden photo: lamp".encodeToByteArray()) })
            val box2 = a.createBox("L002", kitchen.id)
            a.addPhoto(box2.id, PhotoKind.GENERAL, a.newPhotoFile().apply { writeBytesCommon("golden photo: hall".encodeToByteArray()) })
            val syncCode = host.invite(local)
            val plain = relay(host.port) { port -> WifiSync.join(p, syncCode.copy(port = port)) }
            val synced = results.await(1)
            assertTrue(plain.first.ok && synced.ok, "the plain sync failed: ${plain.first.error} / ${synced.error}")

            val refusals = fixture("sync-refusals.json").getValue("cases").jsonArray.map { c ->
                val o = c.jsonObject
                o.getValue("name").jsonPrimitive.content to answerTo(host.port, unhex(o.getValue("sent").jsonPrimitive.content))
            }
            for (i in refusals.indices) results.await(2 + i)
            return Run(moveData, backup, join.second, plain.second, refusals)
        } finally {
            host.stop()
            a.close()
            p.close()
        }
    }

    private fun assertTranscript(name: String, phases: List<Pair<String, ByteArray>>) {
        val messages = fixture(name).getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(messages.map { it.getValue("from").jsonPrimitive.content }, phases.map { it.first }, "$name: who sent each message")
        for ((m, phase) in messages.zip(phases)) {
            val want = m.getValue("hex").jsonPrimitive.content
            val made = phase.second.toHex()
            if (want != made) {
                val at = want.zip(made).indexOfFirst { it.first != it.second }.let { if (it < 0) minOf(want.length, made.length) else it } / 2
                fail(
                    "$name, message ${m.getValue("step")}: not the bytes 2.1.1 sent (${phase.second.size} bytes, recorded " +
                        "${want.length / 2}; first difference at byte $at)\n  recorded: ${want.substring(maxOf(0, 2 * at - 40), minOf(want.length, 2 * at + 80))}\n" +
                        "  made now: ${made.substring(maxOf(0, 2 * at - 40), minOf(made.length, 2 * at + 80))}",
                )
            }
        }
    }

    @Test
    fun theFixedMoveSyncsByteForByteAsAndroid211Did() {
        val undo = useTestCryptoIfNone()
        try {
            inTempFolder { root ->
                val ids = Ids()
                val run = withIds({ ids.next() }) { play(root) }
                assertEquals(GoldenFiles.text.getValue("move-data.json"), run.moveData, "move-data.json: Asha's data.json")
                assertTranscript("sync-join.json", run.join)
                assertTranscript("sync-plain.json", run.plain)
                val cases = fixture("sync-refusals.json").getValue("cases").jsonArray.map { it.jsonObject }
                assertEquals(cases.size, run.refusals.size)
                for ((c, r) in cases.zip(run.refusals)) {
                    assertEquals(c.getValue("answer").jsonPrimitive.content, r.second.toHex(), "sync-refusals.json: the answer to ${r.first}")
                }
                assertEquals(fixture("backup.json").getValue("deterministic"), backupFacts(run.backup, run.moveData), "backup.json: what a backup made now holds")
            }
        } finally {
            undo()
        }
    }

    @Test
    fun theBackupAndroidMadeOpensAsItDidOnAndroid() {
        val undo = useTestCryptoIfNone()
        try {
            inTempFolder { root ->
                val f = fixture("backup.json")
                val bytes = unhex(f.getValue("sample").jsonObject.getValue("hex").jsonPrimitive.content)
                val moveData = GoldenFiles.text.getValue("move-data.json")
                assertEquals(f.getValue("deterministic"), backupFacts(bytes, moveData))
                assertEquals(f.getValue("opened"), backupOpened(root, bytes, moveData))
            }
        } finally {
            undo()
        }
    }

    // ---- what a backup holds (as GoldenFixturesTest works it out) -------------------------------------------------

    private class ZipInfo(
        val name: String, val versionMadeBy: Int, val versionNeeded: Int, val flags: Int, val method: Int, val crc: Long, val size: Long,
        val extra: Int, val localExtra: Int, val descriptorSignature: Boolean,
    )

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    /** The entries of a zip, from its central directory. */
    private fun zipEntries(b: ByteArray): List<ZipInfo> {
        var e = b.size - 22
        while (e >= 0 && u32(b, e) != 0x06054b50L) e--
        assertTrue(e >= 0, "no end of central directory")
        val count = u16(b, e + 10)
        var p = u32(b, e + 16).toInt()
        val out = ArrayList<ZipInfo>()
        repeat(count) {
            assertEquals(0x02014b50L, u32(b, p))
            val nameLen = u16(b, p + 28)
            val local = u32(b, p + 42).toInt()
            val localName = u16(b, local + 26)
            val localExtra = u16(b, local + 28)
            val end = local + 30 + localName + localExtra + u32(b, p + 20).toInt()
            out += ZipInfo(
                b.copyOfRange(p + 46, p + 46 + nameLen).decodeToString(), u16(b, p + 4), u16(b, p + 6), u16(b, p + 8), u16(b, p + 10),
                u32(b, p + 16), u32(b, p + 24), u16(b, p + 30), localExtra, end + 4 <= b.size && u32(b, end) == 0x08074b50L,
            )
            p += 46 + nameLen + u16(b, p + 30) + u16(b, p + 32)
        }
        return out
    }

    private fun zipContents(b: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipIn(bytesIn(b)).use { z ->
            while (true) {
                val e = z.nextEntry() ?: break
                val got = BytesOut()
                val buf = ByteArray(4096)
                while (true) {
                    val n = z.read(buf, 0, buf.size)
                    if (n < 0) break
                    got.stream.write(buf, 0, n)
                }
                out[e.name] = got.bytes()
            }
        }
        return out
    }

    private fun backupFacts(b: ByteArray, moveData: String): JsonObject = buildJsonObject {
        val contents = zipContents(b)
        putJsonArray("entries") {
            for (z in zipEntries(b)) addJsonObject {
                put("name", z.name)
                put("versionMadeBy", z.versionMadeBy)
                put("versionNeeded", z.versionNeeded)
                put("flags", "0x" + z.flags.toString(16).padStart(4, '0'))
                put("method", z.method)
                put("extraLength", z.extra)
                put("localExtraLength", z.localExtra)
                put("dataDescriptorWithSignature", z.descriptorSignature)
                if (z.name != "about.json") {
                    put("size", z.size.toString())
                    put("crc32", z.crc.toString(16).padStart(8, '0'))
                    put("sha256", sha256(contents.getValue(z.name)).toHex())
                }
            }
        }
        put("dataJsonIsMoveData", contents["data.json"]?.decodeToString() == moveData)
        val about = Json.parseToJsonElement(contents.getValue("about.json").decodeToString()).jsonObject
        putJsonObject("aboutJsonWithoutAt") { about.filterKeys { it != "at" }.forEach { (k, v) -> put(k, v) } }
        putJsonArray("aboutJsonKeys") { about.keys.forEach { add(it) } }
    }

    private fun backupOpened(root: File, b: ByteArray, moveData: String): JsonObject = buildJsonObject {
        val peek = Backup.peek(bytesIn(b))
        putJsonObject("peek") {
            put("moveId", peek.moveId)
            putJsonArray("aliases") { peek.aliases.forEach { add(it) } }
            put("moveName", peek.moveName)
            put("by", peek.by)
            put("small", peek.small)
            put("dataIsMoveData", AppJson.encodeToString(AppData.serializer(), peek.data) == moveData)
        }
        // Restored on a phone that has nothing yet: it becomes the person whose phone made the backup.
        val dir = File(root, "fresh")
        prepare(dir, "pfreshphone23456789ab", KEY_PRIYA, "Asha", "A")
        val ids = Ids()
        withIds({ ids.next() }) {
            val s = Store(dir) { T_PRIYA }
            try {
                val r = Backup.import(s, bytesIn(b))
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
                            put("sha256", sha256(File(s.photoDir, p.file).readBytes()).toHex())
                        }
                    }
                }
            } finally {
                s.close()
            }
        }
    }
}
