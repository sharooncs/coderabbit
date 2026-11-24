package app.housemove

import app.housemove.data.AppData
import app.housemove.data.Backup
import app.housemove.data.Disposition
import app.housemove.data.Expense
import app.housemove.data.Home
import app.housemove.data.PhotoKind
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.Supply
import app.housemove.data.Task
import app.housemove.data.Update
import app.housemove.data.idSource
import app.housemove.sync.WifiSync
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/*
 * The fixed move the golden fixtures are made from (see GoldenFixturesTest and src/jvmTest/resources/golden/README.md).
 *
 * Two phones, each a Store in a folder of its own with its phone ID, its key pair for update files and its settings
 * written in beforehand, a clock that stands still (Store.now() then counts up by one from the last stamp), and ids
 * from a counter. Asha founds "Maple Road" and adds Priya; Priya's phone joins with an invite (whose one-time code
 * is set, not drawn); both work and sync again; Priya sends Asha an update file. Every byte that crosses between
 * the phones is kept, before it is passed on.
 */

/** Ids for one run of the fixed move: "00000000-0000-4000-8000-000000000001" and on, for its threads only. */
internal class GoldenIds {
    private var n = 0

    @Synchronized
    fun next(): String {
        n++
        return "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    }

    companion object {
        /** Inherited, so the threads a phone's sync host starts take their ids from the same counter. */
        private val local = InheritableThreadLocal<GoldenIds?>()

        fun <T> using(ids: GoldenIds, block: () -> T): T {
            val before = idSource
            idSource = { local.get()?.next() ?: UUID.randomUUID().toString() }
            local.set(ids)
            try {
                return block()
            } finally {
                local.remove()
                idSource = before
            }
        }
    }
}

/** A phone's key pair for update files as Keys.kt keeps it (X.509 and PKCS#8, Base64). */
internal data class GoldenKey(val public: String, val private: String, val at: Long) {
    val fileText: String get() = """{"public":"$public","private":"$private","at":$at}"""
}

/**
 * A relay on localhost between a joining phone and a hosting one that keeps every byte, split where the direction
 * changes. A sync is a strict back-and-forth, so each change of direction is the start of the next message. Bytes
 * are kept before they are passed on, so everything the receiving phone has read is kept.
 */
internal class Recorder(private val targetPort: Int) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val phases = ArrayList<Pair<Int, ByteArrayOutputStream>>()
    private var lastDir = -1
    private val open = ArrayList<Socket>()

    init {
        thread(name = "golden-relay", isDaemon = true) {
            val joiner = try {
                server.accept()
            } catch (_: IOException) {
                return@thread
            }
            val host = Socket("127.0.0.1", targetPort)
            joiner.tcpNoDelay = true
            host.tcpNoDelay = true
            synchronized(open) { open += listOf(joiner, host) }
            thread(name = "golden-relay-out", isDaemon = true) { pump(0, joiner, host) }
            thread(name = "golden-relay-back", isDaemon = true) { pump(1, host, joiner) }
        }
    }

    private fun pump(dir: Int, from: Socket, to: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val out = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                synchronized(phases) {
                    if (dir != lastDir) {
                        lastDir = dir
                        phases += dir to ByteArrayOutputStream()
                    }
                    phases.last().second.write(buf, 0, n)
                }
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: IOException) {
        }
        try {
            to.shutdownOutput()
        } catch (_: IOException) {
        }
    }

    /** Who sent each message ("joiner" or "host"), and its bytes, in order. */
    fun phases(): List<Pair<String, ByteArray>> = synchronized(phases) { phases.map { (if (it.first == 0) "joiner" else "host") to it.second.toByteArray() } }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
        synchronized(open) { open.toList() }.forEach {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
    }
}

/** What one run of the fixed move made. Everything but [backup] and [update] is the same on every run. */
internal class GoldenRun(
    /** Asha's data.json right after the move was set up, byte for byte. */
    val moveData: String,
    /** A backup of the move made then on Asha's phone (random: see backup.json). */
    val backup: ByteArray,
    /** Every message of Priya's phone joining with the invite, in order. */
    val join: List<Pair<String, ByteArray>>,
    /** Every message of the plain sync that follows, in order. */
    val plain: List<Pair<String, ByteArray>>,
    /** The hosting phone's answers to hellos it turns down: what was sent, and what came back. */
    val refusals: List<Triple<String, ByteArray, ByteArray>>,
    /** Asha's folder (file name to text) just before Priya's update reaches it. */
    val recipient: Map<String, String>,
    /** Priya's data as her update file carries it. */
    val senderData: String,
    /** The update file Priya made for Asha (random: see update.json). */
    val update: ByteArray,
    /** What opening it on Asha's phone said. */
    val opened: Update.Opened,
    val moveId: String,
)

internal object GoldenMove {
    /** 1 January 2026, 00:00 UTC: Asha's clock. */
    const val T_ASHA = 1_767_225_600_000L

    /** A day later: Priya's clock, and the hosting phone's clock for invites. */
    const val T_PRIYA = T_ASHA + 86_400_000L
    const val PHONE_ASHA = "pashaphone23456789ab"
    const val PHONE_PRIYA = "ppriyaphone2345678ab"

    /** The one-time code of Priya's invite, and the hosting phone's plain sync code. Both are drawn at random in the app. */
    const val INVITE_CODE = "GQ7K2MXP"
    const val HOST_TOKEN = "HM4RT8WZ"

    val KEY_ASHA = GoldenKey(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtZeJYt7fkoLA9xt5p2FaHuA/dSrcQr0TzbtIN8R0qNNRMgwA9KqG0QLi7W3ON2u7ovAhOVft0PmW/Ez54L30hQ==",
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgUUSEJ2b0AW/COagdMHllSWYXyv712X78/d+DXGQJGj2hRANCAAS1l4li3t+SgsD3G3mnYVoe4D91KtxCvRPNu0g3xHSo01EyDAD0qobRAuLtbc43a7ui8CE5V+3Q+Zb8TPngvfSF",
        T_ASHA - 60_000,
    )
    val KEY_PRIYA = GoldenKey(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEay0G0qqU14TY9r0UogFeqHX0Mov3QcTNEALy2ygSOdyJYCZRXqRRjQsu9mPtU9ANyBKH6yoQfXjgeBLAxWhD5A==",
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg7vTzQRDK7yPvjrvwkSo/A2T4/xyMwe4L9oPJSLqPMm2hRANCAARrLQbSqpTXhNj2vRSiAV6odfQyi/dBxM0QAvLbKBI53IlgJlFepFGNCy72Y+1T0A3IEofrKhB9eOB4EsDFaEPk",
        T_PRIYA - 60_000,
    )

    /** Stand-ins for photo files: what is in them does not matter to the app, only that it arrives whole. */
    val PHOTO_BOX = "golden photo: plates".toByteArray()
    val PHOTO_LAMP = "golden photo: lamp".toByteArray()
    val PHOTO_HALL = "golden photo: hall".toByteArray()

    /** A phone's folder as the app leaves it before the first start: its phone ID, its key pair and its settings. */
    fun prepare(dir: File, phoneId: String, key: GoldenKey, name: String, letter: String) {
        dir.mkdirs()
        File(dir, "phone-id").writeText(phoneId)
        File(dir, "phone-key.json").writeText(key.fileText)
        File(dir, "device.json").writeText("""{"name":"$name","letter":"$letter"}""")
    }

    /** The files of a phone's folder that say what it holds, as text (photos and the .bak copy left out). */
    fun folder(dir: File): Map<String, String> =
        listOf("data.json", "device.json", "move.json", "codes.json", "phone-id", "phone-key.json")
            .filter { File(dir, it).isFile }.associateWith { File(dir, it).readText() }

    fun run(root: File): GoldenRun = GoldenIds.using(GoldenIds()) { play(root) }

    private fun play(root: File): GoldenRun {
        val dirA = File(root, "asha")
        val dirP = File(root, "priya")
        prepare(dirA, PHONE_ASHA, KEY_ASHA, "Asha", "A")
        prepare(dirP, PHONE_PRIYA, KEY_PRIYA, "Priya 🏠", "P")

        // ---- Asha founds the move and sets it up --------------------------------------------
        val a = Store(dirA) { T_ASHA }
        a.setMove { it.copy(name = "Maple Road", moveDay = 20_600, currency = "£", vanCapacityM3 = 12.5, setupDone = true) }
        val kitchenTo = a.createRoom(Home.DEST, "Kitchen")
        val kitchen = a.createRoom(Home.ORIGIN, "Kitchen")
        a.put(kitchen.copy(mapsTo = kitchenTo.id))
        val zoe = a.createRoom(Home.ORIGIN, "Zoë's room")
        // The Doubles the fixtures are about, where the app keeps them: sizes on the item, prices and values apart.
        val sofa = a.createItem("Sofa", kitchen.id)
        a.put(sofa.copy(widthCm = 210.0, depthCm = 0.1, heightCm = 1e-4, fragile = true))
        check(a.decide(sofa.id, Disposition.SELL, price = 1e7).ok)
        val tv = a.createItem("TV 日本", zoe.id)
        a.put(a.data.value.item(tv.id)!!.copy(widthCm = -0.0))
        check(a.setDetails(tv.id) { it.copy(value = 1e-3, make = "Sony", model = "X90", serial = "SN-0042") }.ok)
        val box = a.createBox("L001", kitchen.id)
        check(a.setContents(box.id, "Plates, Mugs; ΟΔΟΣ cups, Straße").ok)
        check(a.reportDamage(box.id, "Corner crushed").ok)
        a.addPhoto(box.id, PhotoKind.CONTENTS, a.newPhotoFile().apply { writeBytes(PHOTO_BOX) })
        a.put(Task(id = app.housemove.data.newId(), title = "Book the van", offsetDays = -28))
        check(a.saveCost(Expense(id = app.housemove.data.newId(), title = "Van hire", amount = 1e21, planned = true)).ok)
        a.put(Supply(id = app.housemove.data.newId(), name = "Tape", need = 6, have = 2))
        val priyaPerson = a.addPerson("Priya 🏠", Role.DECIDER).valueOrNull()!!
        a.flush()
        val moveData = File(dirA, "data.json").readText()
        val backup = ByteArrayOutputStream().also { Backup.export(a, it) }.toByteArray()

        // ---- Priya's phone joins with the invite --------------------------------------------
        val p = Store(dirP) { T_PRIYA }
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val host = WifiSync.Host(a, ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")), HOST_TOKEN, { T_PRIYA }, true, false) { results.add(it) }
        host.start()
        try {
            val local = listOf("127.0.0.1")
            val drawn = host.inviteFor(priyaPerson.id, local).valueOrNull()!!
            // The invite's one-time code is drawn at random; here it is set, so the bytes repeat.
            @Suppress("UNCHECKED_CAST")
            val personal = WifiSync.Host::class.java.getDeclaredField("personal").apply { isAccessible = true }.get(host) as HashMap<String, Any>
            synchronized(personal) { personal[INVITE_CODE] = personal.remove(drawn.token)!! }
            val invite = drawn.copy(token = INVITE_CODE)
            val join = relay(host.port) { port -> WifiSync.join(p, invite.copy(port = port)) }
            val joined = results.poll(30, TimeUnit.SECONDS)
            check(join.first.ok && joined?.ok == true) { "the join failed: ${join.first.error} / ${joined?.error}" }

            // ---- both work, then sync again: a photo each way --------------------------------
            val lamp = p.createItem("Lamp", null)
            p.addPhoto(lamp.id, PhotoKind.CONDITION, p.newPhotoFile().apply { writeBytes(PHOTO_LAMP) })
            val box2 = a.createBox("L002", kitchen.id)
            a.addPhoto(box2.id, PhotoKind.GENERAL, a.newPhotoFile().apply { writeBytes(PHOTO_HALL) })
            val syncCode = host.invite(local)
            val plain = relay(host.port) { port -> WifiSync.join(p, syncCode.copy(port = port)) }
            val synced = results.poll(30, TimeUnit.SECONDS)
            check(plain.first.ok && synced?.ok == true) { "the plain sync failed: ${plain.first.error} / ${synced?.error}" }

            // ---- hellos the hosting phone turns down -----------------------------------------
            val refusals = refusals(host.port, p)
            repeat(refusals.size) { results.poll(30, TimeUnit.SECONDS) }

            // ---- Priya sends Asha an update file -----------------------------------------------
            p.put(p.createItem("Bookcase", null).copy(widthCm = 80.5))
            a.flush()
            val recipient = folder(dirA)
            p.flush()
            val senderData = app.housemove.data.AppJson.encodeToString(AppData.serializer(), p.data.value)
            val update = ByteArrayOutputStream().also { Update.write(p, it) }.toByteArray()
            val opened = Update.open(a, ByteArrayInputStream(update))
            check(opened.ok) { "the update did not open: ${opened.message}" }
            return GoldenRun(moveData, backup, join.second, plain.second, refusals, recipient, senderData, update, opened, a.moveId)
        } finally {
            host.stop()
            a.close()
            p.close()
        }
    }

    /** Run [block] through a [Recorder] to the hosting phone at [port]; what it returned, and every message. */
    private fun <T> relay(port: Int, block: (Int) -> T): Pair<T, List<Pair<String, ByteArray>>> {
        val r = Recorder(port)
        try {
            return block(r.port) to r.phases()
        } finally {
            r.close()
        }
    }

    /** Hellos that are turned down, sent straight to the hosting phone, with its answer: until it hangs up. */
    private fun refusals(port: Int, p: Store): List<Triple<String, ByteArray, ByteArray>> {
        fun hello(magic: String, token: String, schema: Int, full: Boolean = true): ByteArray = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeUTF(magic)
                writeUTF(token)
                writeUTF(p.device.value.name)
                writeUTF(p.device.value.letter)
                writeInt(schema)
                if (full) {
                    writeUTF("")
                    writeUTF("{}")
                }
                flush()
            }
        }.toByteArray()
        val cases = listOf(
            "wrong code" to hello("HMSYNC3", "ZZZZZZZZ", app.housemove.data.SCHEMA),
            "other data layout (4)" to hello("HMSYNC3", HOST_TOKEN, 4),
            "version 1.3's hello" to hello("HMSYNC2", HOST_TOKEN, 3, full = false),
            "version 1.3's hello from a phone on this layout (a dropped connection)" to hello("HMSYNC2", HOST_TOKEN, app.housemove.data.SCHEMA, full = false),
            "version 1.2's hello (only its first line is read)" to ByteArrayOutputStream().also { DataOutputStream(it).writeUTF("HMSYNC1") }.toByteArray(),
        )
        return cases.map { (name, sent) ->
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 20_000
                s.getOutputStream().apply { write(sent); flush() }
                name to sent and readAll(s)
            }
        }
    }

    private infix fun Pair<String, ByteArray>.and(third: ByteArray) = Triple(first, second, third)

    private fun readAll(s: Socket): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            s.getInputStream().copyTo(out)
        } catch (_: IOException) {
        }
        return out.toByteArray()
    }
}

/** Reads one message after another from the bytes of one direction of a sync, with where each one starts. */
internal class WireReader(val bytes: ByteArray) {
    private val input = ByteArrayInputStream(bytes)
    val din = DataInputStream(input)
    val pos: Int get() = bytes.size - input.available()
    val done: Boolean get() = input.available() == 0
}
