package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Syncing without a network, with a phone that stops answering, with a connection that drops
 * while photos are on their way, and with things on the network that are not HouseMove: never a
 * hang, never a message in a programmer's words, and nothing for anyone without the code.
 */
class NetworkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var folders = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()
    private val wires = ArrayList<Wire>()
    private val servers = ArrayList<ServerSocket>()

    private fun phone(name: String = ""): Store =
        Store(tmp.newFolder("p" + folders++)) { now++ }.also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    private class Hosting(val host: WifiSync.Host, val results: LinkedBlockingQueue<WifiSync.Result>) {
        fun result(): WifiSync.Result = results.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")
    }

    private fun hosting(store: Store, letNewPhonesIn: Boolean = true): Hosting {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        return Hosting(WifiSync.host(store, { now }, letNewPhonesIn) { results.add(it) }.also { open += it }, results)
    }

    @After
    fun hangUp() {
        open.forEach { it.stop() }
        wires.forEach { it.close() }
        servers.forEach { it.close() }
        WifiSync.addresses = { WifiSync.localAddresses() }
        WifiSync.timeoutMs = 30_000
    }

    private fun invited(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val h = hosting(host)
        val phone = phone(name)
        val r = WifiSync.join(phone, h.host.inviteFor(p.id, local).valueOrNull()!!)
        assertTrue(r.error, r.ok)
        assertTrue(h.result().ok)
        return phone
    }

    /** Words for a person, not for a programmer. */
    private fun plain(message: String?) {
        assertNotNull(message)
        for (bad in listOf("Exception", "null", "java.", "Bad ", "EOF", "Socket", "status", "kotlinx", "JSON", "offset")) {
            assertFalse("'$message' reads like an error report", message!!.contains(bad))
        }
    }

    // ---- no network --------------------------------------------------------------------------

    @Test
    fun withNoWiFiJoiningAndInvitingSaySoAtOnce() {
        val asha = phone("Asha")
        asha.createBox("L001", null)
        val before = asha.data.value
        WifiSync.addresses = { emptyList() }
        assertEquals(WifiSync.NO_NETWORK, WifiSync.networkProblem())
        // Joining a code that names addresses on a Wi-Fi this phone is not on: no waiting for each to time out.
        val started = System.currentTimeMillis()
        val r = WifiSync.join(asha, WifiSync.Invite(listOf("192.168.1.23", "10.0.0.7"), 40_000, "ABCDEFGH"))
        assertEquals(WifiSync.NO_NETWORK, r.error)
        assertTrue(System.currentTimeMillis() - started < 1000)
        assertEquals(before, asha.data.value)
        // Hosting starts (there is nothing to wait for), but an invite with nowhere to connect to is not made.
        val h = hosting(asha)
        val p = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        assertEquals(WifiSync.NO_NETWORK, h.host.inviteFor(p.id).reason)
        // With a network again it works as before.
        WifiSync.addresses = { listOf("192.168.1.10") }
        assertNull(WifiSync.networkProblem())
        assertEquals(listOf("192.168.1.10"), h.host.inviteFor(p.id).valueOrNull()!!.hosts)
    }

    @Test
    fun aPhoneThatStopsAnsweringIsGivenUpOnInsteadOfWaitedForForever() {
        WifiSync.timeoutMs = 1500
        val asha = phone("Asha")
        asha.createBox("L001", null)
        // Something that accepts the connection and then says nothing at all.
        val silent = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            try {
                while (true) {
                    val s = silent.accept()
                    thread(isDaemon = true) { Thread.sleep(20_000); s.close() }
                }
            } catch (_: Exception) {
            }
        }
        val started = System.currentTimeMillis()
        val r = WifiSync.join(asha, WifiSync.Invite(local, silent.localPort, "ABCDEFGH"))
        assertFalse(r.ok)
        plain(r.error)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 8000)

        // The other way round: a phone connects to this one's code, says hello and goes quiet. The host gives up too.
        val h = hosting(asha)
        Socket("127.0.0.1", h.host.port).use { s ->
            val out = DataOutputStream(s.getOutputStream())
            out.writeUTF("HMSYNC3"); out.flush()
            val result = h.result()
            assertEquals(WifiSync.CONNECTION_LOST, result.error)
        }
        // And the next real sync goes through.
        val hal = invited(asha, "Hal", Role.HELPER)
        assertNotNull(hal.data.value.findByLabel("L001"))
    }

    @Test
    fun aConnectionThatDropsAtOnceIsNotTakenForAnotherVersion() {
        // Before, a hello that went unanswered and then a second try that got nothing at all said "different versions".
        val asha = phone("Asha")
        val dropper = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            try {
                while (true) dropper.accept().close()
            } catch (_: Exception) {
            }
        }
        assertEquals(WifiSync.CONNECTION_LOST, WifiSync.join(asha, WifiSync.Invite(local, dropper.localPort, "ABCDEFGH")).error)
    }

    // ---- connections that drop ---------------------------------------------------------------

    @Test
    fun aConnectionLostWhilePhotosAreOnTheirWaySaysSoPlainlyAndTheNextSyncFetchesThem() {
        val asha = phone("Asha")
        val hal = invited(asha, "Hal", Role.HELPER)
        var n = 0
        for (cut in listOf(Wire.Cut(5, 2), Wire.Cut(6, 2), Wire.Cut(7, 3000), Wire.Cut(8, 3000), Wire.Cut(8, 0))) {
            n++
            val hers = asha.createBox("A$n", null)
            val his = hal.createBox("H$n", null)
            listOf(asha to hers.id, hal to his.id).forEach { (s, owner) ->
                s.addPhoto(owner, PhotoKind.CONTENTS, s.newPhotoFile().apply { writeBytes(ByteArray(20_000) { 1 }) })
            }
            val h = hosting(asha)
            val wire = Wire(h.host.port, cut).also { wires += it }
            val joined = WifiSync.join(hal, h.host.invite(local).copy(port = wire.port))
            val hosted = h.result()
            assertFalse(hosted.ok && joined.ok)
            // The phone that has taken in the other's data says that only photos are missing; one that has not, that the sync did not finish.
            if (!joined.ok) assertEquals("cut in phase ${cut.phase}", WifiSync.PHOTOS_CUT, joined.error)
            if (!hosted.ok) assertEquals("cut in phase ${cut.phase}", if (cut.phase <= 5) WifiSync.CONNECTION_LOST else WifiSync.PHOTOS_CUT, hosted.error)
            assertNotNull(hal.data.value.box(hers.id))
            assertNotNull(asha.data.value.box(his.id))
            // The next sync finishes it: every photo is on both phones, whole.
            val again = hosting(asha)
            assertTrue(WifiSync.join(hal, again.host.invite(local)).ok)
            assertTrue(again.result().ok)
            for (p in asha.data.value.livePhotos) for (s in listOf(asha, hal)) assertEquals(20_000L, File(s.photoDir, p.file).length())
        }
    }

    @Test
    fun whatIsNotHouseMoveIsNotAnsweredAndEveryMessageIsInWords() {
        val asha = phone("Asha")
        asha.createBox("L001", null)
        val h = hosting(asha)
        // A web browser, a port scanner, something that sends a long first line.
        for (junk in listOf("GET / HTTP/1.1\r\n\r\n".toByteArray(), ByteArray(0), ByteArray(3) { 0x7F })) {
            Socket("127.0.0.1", h.host.port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply { write(junk); flush() }
                s.shutdownOutput()
                // The host hangs up; with what it did not read still waiting, the system may say so as a reset.
                val answer = try {
                    s.getInputStream().read()
                } catch (_: java.net.SocketException) {
                    -1
                }
                assertEquals("nothing is said back", -1, answer)
            }
            val r = h.result()
            plain(r.error)
        }
        // A phone without the right code is told no, and nothing more: not even that it speaks another version.
        for (magic in listOf("HMSYNC2", "HMSYNC3")) {
            Socket("127.0.0.1", h.host.port).use { s ->
                s.soTimeout = 3000
                val out = DataOutputStream(s.getOutputStream().buffered())
                out.writeUTF(magic); out.writeUTF("WRONGONE"); out.writeUTF("Eve"); out.writeUTF("E"); out.writeInt(SCHEMA)
                if (magic == "HMSYNC3") { out.writeUTF(""); out.writeUTF("") }
                out.flush()
                val din = DataInputStream(s.getInputStream())
                assertEquals("NO", din.readUTF())
                assertEquals(-1, din.read())
            }
            assertEquals(WifiSync.WRONG_CODE, h.result().error)
        }
        // A host that answers OK and then sends nonsense where the data should be.
        val fake = ServerSocket(0).also { servers += it }
        thread(isDaemon = true) {
            try {
                fake.accept().use { s ->
                    val din = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    repeat(4) { din.readUTF() }; din.readInt(); repeat(2) { din.readUTF() }
                    out.writeUTF("OK"); out.writeUTF("Odd"); out.writeUTF("O"); out.writeUTF(""); out.flush()
                    din.readUTF()
                    ByteArray(din.readInt()).also { din.readFully(it) }
                    val junk = "{not data at all".toByteArray()
                    out.writeInt(junk.size); out.write(junk); out.flush()
                    Thread.sleep(500)
                }
            } catch (_: Exception) {
            }
        }
        val before = asha.data.value
        val r = WifiSync.join(asha, WifiSync.Invite(local, fake.localPort, "ANY"))
        assertEquals(WifiSync.UNREADABLE, r.error)
        assertEquals(before.boxes, asha.data.value.boxes)
        for (m in listOf(WifiSync.PHOTOS_CUT, WifiSync.UNREADABLE, WifiSync.NOT_HOUSEMOVE, WifiSync.WRONG_CODE, WifiSync.CODE_NOT_ACCEPTED, WifiSync.UNREACHABLE, WifiSync.NO_NETWORK, WifiSync.CONNECTION_LOST)) plain(m)
    }

    // ---- codes seen by someone else -----------------------------------------------------------

    @Test
    fun aCodeOrInvitePhotographedByAStrangerGivesThemNothing() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road") }
        asha.createBox("L001", null)
        asha.put(Expense(id = "e1", title = "Deposit", amount = 900.0))
        val hal = invited(asha, "Hal", Role.HELPER)
        // The move is shared now, so the plain code is shown with new phones kept out, as the screens do.
        val h = hosting(asha, letNewPhonesIn = asha.isAlone())
        val photographed = h.host.invite(local)
        val stranger = phone("Eve")
        val r = WifiSync.join(stranger, photographed)
        // Since 2.1 its own move is not Asha's, and that is what stops it, before anything else is looked at.
        assertEquals(WifiSync.WRONG_MOVE, r.error)
        assertEquals(WifiSync.WRONG_MOVE, h.result().error)
        assertTrue(stranger.data.value.liveBoxes.isEmpty())
        assertTrue(stranger.data.value.people.none { it.id == asha.me!!.id })
        // A member still syncs with the same code.
        assertTrue(WifiSync.join(hal, photographed).ok)
        assertTrue(h.result().ok)
        // Once the code is no longer shown, nothing on Asha's phone answers it, not even for a member. (The port itself may
        // since have been taken by some other program on this machine, so what is said is not checked, only what is got.)
        h.host.stop()
        val later = asha.createBox("L002", null).id
        for (s in listOf(stranger, hal)) {
            WifiSync.join(s, photographed, connectTimeoutMs = 1000)
            assertNull(s.data.value.box(later))
        }
        // An invite for a person that someone else has already used, or that has run out, lets nobody in.
        val p = asha.addPerson("Dee", Role.DECIDER).valueOrNull()!!
        val again = hosting(asha, letNewPhonesIn = false)
        val invite = again.host.inviteFor(p.id, local).valueOrNull()!!
        val dee = phone("Dee")
        assertTrue(WifiSync.join(dee, invite).ok)
        assertTrue(again.result().ok)
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(stranger, invite).error)
        val p2 = asha.addPerson("Dan", Role.DECIDER).valueOrNull()!!
        val late = again.host.inviteFor(p2.id, local).valueOrNull()!!
        now += (WifiSync.INVITE_MINUTES + 1) * 60_000L
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(stranger, late).error)
        assertTrue(stranger.data.value.liveBoxes.isEmpty())
        assertNull(stranger.data.value.expense("e1"))
        assertNotEquals(p2.id, stranger.me!!.id)
    }
}
