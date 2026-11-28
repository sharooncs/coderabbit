package app.housemove

import app.housemove.data.Lock
import app.housemove.data.MoveIdentity
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.currentTimeMillis
import app.housemove.data.deleteRecursively
import app.housemove.data.phoneEntry
import app.housemove.data.sha256
import app.housemove.data.startThread
import app.housemove.data.synchronized
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.sync.AutoListener
import app.housemove.sync.AutoSession
import app.housemove.sync.AutoSlot
import app.housemove.sync.Handshake
import app.housemove.sync.LocalLan
import app.housemove.sync.Noise
import app.housemove.sync.NoiseKey
import app.housemove.sync.Socket
import app.housemove.sync.WifiSync
import app.housemove.sync.accept
import app.housemove.sync.close
import app.housemove.sync.connect
import app.housemove.sync.dataIn
import app.housemove.sync.dataOut
import app.housemove.sync.flush
import app.housemove.sync.localPort
import app.housemove.sync.memberPhone
import app.housemove.sync.noiseCrypto
import app.housemove.sync.noiseKey
import app.housemove.sync.pointOfKeyText
import app.housemove.sync.soTimeout
import app.housemove.sync.write
import app.housemove.sync.writeUTF
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The handshake of syncing by itself from 2.4 (AutoSession: Noise IK, design/encryption.md, Part 2), message by message:
 * a phone of the move with its pinned key is let in; a stranger, a removed person's phone, a retired phone, a phone whose
 * key is not its pin, a phone of another move, a 2.3 phone's handshake and a code sync's hello are closed without an
 * answer; one sync at a time; a phone that proved itself must say it is that phone; and someone who holds the answering
 * phone's own private key still cannot pose to it as another phone (key-compromise impersonation).
 */
class AutoHandshakeCommonTest {
    private val undoCrypto = useTestCryptoIfNone()
    private val root = newTempFolder()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()
    private val listeners = ArrayList<AutoListener>()

    @AfterTest
    fun cleanUp() {
        listeners.forEach { it.stop() }
        phones.forEach { it.close() }
        root.deleteRecursively()
        undoCrypto()
    }

    /** The private scalar of Asha's phone key in [move], so a test can play an attacker who has stolen it. */
    private val ashaScalar = sha256("HouseMove test: Asha's phone key".toUtf8())

    /** Asha (Organiser), Priya (Decider) and Hal (Helper), all joined with invites: each pins the others' keys. */
    private fun move(): Triple<AutoPhone, AutoPhone, AutoPhone> {
        val asha = AutoPhone(root, "Asha", lan, engineOn = false, keyScalar = ashaScalar).also { phones += it }
        val (priya, hal) = listOf("Priya", "Hal").map { AutoPhone(root, it, lan, engineOn = false).also { p -> phones += p } }
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        asha.invite(hal, Role.HELPER)
        // Priya's phone has not met Hal's, but knows its key from the move.
        assertTrue(asha.codeSync(priya).ok)
        return Triple(asha, priya, hal)
    }

    private fun listen(store: Store, slot: AutoSlot = AutoSlot(), proven: (String) -> Unit = {}): Int {
        val l = AutoListener(true, { store }, slot, proven, { _, _ -> })
        l.start()
        listeners += l
        return l.port
    }

    private fun dial(from: AutoPhone, to: AutoPhone, port: Int) = AutoSession.dial(from.store, "127.0.0.1", port, to.store.phone.id)

    private fun point(p: AutoPhone): ByteArray = p.store.phone.keys.noiseKey()!!.point

    /** A dial by hand, as [s] (claiming phone [claim]) to the phone whose key is [rs]: the answer's frame, or null when it closed without one. */
    private fun dialByHand(port: Int, s: NoiseKey, claim: String, rs: ByteArray, moveId: String, magic: String = AutoSession.MAGIC): ByteArray? {
        val sock = Socket()
        sock.connect("127.0.0.1", port, 3_000)
        sock.soTimeout = 5_000
        return try {
            val out = sock.dataOut()
            val hs = Handshake(noiseCrypto()!!, Noise.IK, true, AutoSession.prologue(moveId), s, rs)
            out.writeUTF(magic)
            AutoSession.writeFrame(out, hs.write(AutoSession.joined(claim.toUtf8())))
            out.flush()
            try {
                AutoSession.readFrame(sock.dataIn())
            } catch (_: Exception) {
                null
            }
        } finally {
            sock.close()
        }
    }

    @Test
    fun aPhoneOfTheMoveWithItsPinnedKeyIsLetIn() {
        val (asha, priya, hal) = move()
        val port = listen(asha.store)
        val d = dial(priya, asha, port)
        assertEquals("OK", d.word)
        assertTrue(d.result!!.ok, "${d.result.error}")
        assertEquals(asha.store.phone.id, d.peerPhone)
        assertFalse(d.result.plain)
        // Both know the other has 2.4 now, and Hal's phone, which never met Priya's, is let in by the key the move gave it.
        assertTrue(asha.store.pins.speaksV2(priya.store.phone.id) && priya.store.pins.speaksV2(asha.store.phone.id))
        val halPort = listen(hal.store)
        assertEquals("OK", dial(priya, hal, halPort).word)
    }

    @Test
    fun aStrangerIsNotAnswered() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        val id = asha.store.moveId
        val stranger = keyFromScalar(sha256("a stranger".toUtf8())).noiseKey()!!
        // Bytes that are not a handshake; a handshake to a key that is not Asha's phone's; then to the right key, as a phone
        // the move does not have, and as Priya's phone with a key that is not hers: no answer to any.
        val raw = Socket()
        raw.connect("127.0.0.1", port, 3_000)
        raw.soTimeout = 5_000
        try {
            val out = raw.dataOut()
            out.writeUTF(AutoSession.MAGIC)
            AutoSession.writeFrame(out, ByteArray(65 + 81 + 16 + 20) { (it * 7).toByte() })
            out.flush()
            assertTrue(runCatching { AutoSession.readFrame(raw.dataIn()) }.isFailure, "made-up bytes were answered")
        } finally {
            raw.close()
        }
        assertNull(dialByHand(port, stranger, "pstranger00000000000", stranger.point, id))
        assertNull(dialByHand(port, stranger, "pstranger00000000000", point(asha), id))
        assertNull(dialByHand(port, stranger, priya.store.phone.id, point(asha), id))
        // A phone of another move has no pin for this one and dials nothing.
        val other = AutoPhone(root, "Stranger", lan, engineOn = false).also { phones += it }
        other.found("Elm Street")
        assertEquals("NO", AutoSession.dial(other.store, "127.0.0.1", port, asha.store.phone.id).word)
        // Priya's own key, as Priya: let in (the handshake itself is sound).
        assertNotNull(dialByHand(port, priya.store.phone.keys.noiseKey()!!, priya.store.phone.id, point(asha), id))
    }

    @Test
    fun aRemovedPersonsPhoneIsNotAnsweredAtOnce() {
        val (asha, priya, hal) = move()
        val port = listen(asha.store)
        assertEquals("OK", dial(hal, asha, port).word)
        val halPerson = asha.store.data.value.phoneEntry(hal.store.phone.id)!!.first
        assertTrue(asha.store.removePerson(halPerson.id).ok)
        // At once: Hal's phone is unpinned, and its handshake, with the key Asha's phone knew, gets no answer.
        assertEquals("NO", dial(hal, asha, port).word)
        assertNull(asha.store.pins.learn(asha.store.data.value, asha.store.phone.id)[hal.store.phone.id])
        assertNull(asha.store.pins.pinOf(hal.store.phone.id))
        // Asha's phone dials it no more either; Priya's, which has not heard, still would, until the news reaches it.
        assertEquals("NO", AutoSession.dial(asha.store, "127.0.0.1", listen(hal.store), hal.store.phone.id).word)
        assertEquals("OK", dial(priya, asha, port).word)
        assertNull(priya.store.data.value.memberPhone(hal.store.phone.id), "the sync told Priya's phone")
        assertEquals("NO", AutoSession.dial(priya.store, "127.0.0.1", listen(hal.store), hal.store.phone.id).word)
    }

    @Test
    fun aRetiredPhoneIsNotAnswered() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        val person = asha.store.data.value.phoneEntry(priya.store.phone.id)!!.first
        assertTrue(asha.store.retirePhone(person.id, priya.store.phone.id).ok)
        assertEquals("NO", dial(priya, asha, port).word)
    }

    @Test
    fun aPhoneWithANewKeyNeedsOneCodeSync() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        val oldKey = priya.store.phone.keys.publicText
        assertFalse(priya.store.pins.ownKeyIsNew(oldKey))
        // Priya's phone lost its key file and made a new key: its entry carries it, Asha's pin is the old one.
        priya.restart(newKey = true)
        val newKey = priya.store.phone.keys.publicText
        assertTrue(newKey != oldKey && newKey.isNotEmpty())
        assertTrue(priya.store.pins.ownKeyIsNew(newKey), "Priya's phone knows it has a new key")
        assertEquals("NO", dial(priya, asha, port).word)
        // Asha's phone hears of the new key (by a sync without it: a plain one stands in for the news coming another way).
        // The pin is not replaced silently: it says so.
        assertEquals(asha.store.pins.pinOf(priya.store.phone.id)!!.key, oldKey)
        // One code sync: the new key is pinned, as met, and they sync by themselves again.
        assertTrue(asha.codeSync(priya).ok)
        assertEquals(newKey, asha.store.pins.pinOf(priya.store.phone.id)!!.key)
        assertFalse(priya.store.pins.ownKeyIsNew(newKey))
        assertEquals("OK", dial(priya, asha, port).word)
    }

    @Test
    fun anotherMoveOrAnotherAppIsNotAnswered() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        val priyaKey = priya.store.phone.keys.noiseKey()!!
        // Priya's phone with her pinned key, but another move's prologue: Asha's phone cannot open it.
        assertNull(dialByHand(port, priyaKey, priya.store.phone.id, point(asha), "m-another-move"))
        // 2.3's handshake, and a code sync's hello, on this socket: not answered either.
        assertNull(dialByHand(port, priyaKey, priya.store.phone.id, point(asha), asha.store.moveId, magic = "HMAUTO1"))
        assertNull(dialByHand(port, priyaKey, priya.store.phone.id, point(asha), asha.store.moveId, magic = "HMSYNC3"))
        // The engine never dials a phone that does not advertise, but if a code sync's socket were dialled, it would only
        // note "not a HouseMove phone" and answer nothing: the handshake goes nowhere.
        val lock = Lock()
        val results = ArrayList<WifiSync.Result>()
        val old = WifiSync.host(asha.store, letNewPhonesIn = false) { synchronized(lock) { results += it } }
        try {
            assertEquals("NO", AutoSession.dial(priya.store, "127.0.0.1", old.port, asha.store.phone.id).word)
            waitUntil("the code host noted it") { synchronized(lock) { results.isNotEmpty() } }
            assertEquals(WifiSync.NOT_HOUSEMOVE, results.first().error)
        } finally {
            old.stop()
        }
    }

    @Test
    fun oneSyncByItselfAtATime() {
        val (asha, priya, _) = move()
        val slot = AutoSlot()
        val port = listen(asha.store, slot)
        assertTrue(slot.take())
        assertEquals("BUSY", dial(priya, asha, port).word)
        slot.release()
        assertEquals("OK", dial(priya, asha, port).word)
    }

    @Test
    fun aPhoneMustSayItIsThePhoneItProved() {
        val (asha, priya, hal) = move()
        val server = loopbackServer()
        val lock = Lock()
        val hosted = ArrayList<WifiSync.Result>()
        startThread("proven-host") {
            val s = server.accept()
            val host = WifiSync.Host(asha.store, null, "", ::currentTimeMillis, false, false) {}
            // The handshake proved Hal's phone, but Priya's phone says hello.
            val halPerson = asha.store.data.value.phoneEntry(hal.store.phone.id)!!.first
            val r = host.serveProven(s.dataIn(), s.dataOut(), WifiSync.Proven(hal.store.phone.id, halPerson.id))
            synchronized(lock) { hosted += r }
            s.close()
        }
        val s = Socket()
        s.connect("127.0.0.1", server.localPort, 3_000)
        s.soTimeout = 5_000
        val ashaPerson = priya.store.data.value.phoneEntry(asha.store.phone.id)!!.first
        val r = WifiSync.joinProven(priya.store, s, s.dataIn(), s.dataOut(), WifiSync.Proven(asha.store.phone.id, ashaPerson.id))
        s.close()
        server.close()
        assertTrue(!r.ok)
        waitUntil("the host said why") { synchronized(lock) { hosted.isNotEmpty() } }
        assertEquals(WifiSync.NOT_THE_PHONE, hosted.first().error)
    }

    /**
     * Someone who has stolen Asha's private key (but not Priya's) poses to Asha's phone as Priya's. IK's first message
     * cannot stop that alone (its "ss" can be made with Asha's own key), so Asha's phone takes nothing as proved until the
     * dialler's first frame opens, which needs Priya's private key: nothing is synced, nothing is said, nothing changes.
     */
    @Test
    fun keyCompromiseImpersonationGetsNothing() {
        val (asha, priya, _) = move()
        val proved = ArrayList<String>()
        val lock = Lock()
        val port = listen(asha.store) { synchronized(lock) { proved += it } }
        val before = asha.store.data.value
        val stolen = keyFromScalar(ashaScalar).noiseKey()!!
        assertTrue(stolen.point.contentEquals(point(asha)), "the scalar is Asha's phone key")
        val priyaPoint = point(priya)
        // Priya's public key, with ECDH done with Asha's stolen private key: right for "ss", wrong for everything after.
        val posing = object : NoiseKey {
            override val point: ByteArray = priyaPoint
            override fun dh(peer: ByteArray): ByteArray? = if (peer.contentEquals(stolen.point)) stolen.dh(priyaPoint) else ByteArray(32)
        }
        val sock = Socket()
        sock.connect("127.0.0.1", port, 3_000)
        sock.soTimeout = 5_000
        try {
            val out = sock.dataOut()
            val din = sock.dataIn()
            val hs = Handshake(noiseCrypto()!!, Noise.IK, true, AutoSession.prologue(asha.store.moveId), posing, stolen.point)
            out.writeUTF(AutoSession.MAGIC)
            AutoSession.writeFrame(out, hs.write(AutoSession.joined(priya.store.phone.id.toUtf8())))
            out.flush()
            // Asha's phone answers (the first message alone looks right), but the answer cannot be read without Priya's key.
            val answer = AutoSession.readFrame(din)
            assertNull(hs.read(answer), "the answer opened without Priya's private key")
            // A first frame made up: Asha's phone does not take it, and hangs up.
            AutoSession.writeFrame(out, ByteArray(48) { it.toByte() })
            out.flush()
            val closed = try {
                AutoSession.readFrame(din)
                false
            } catch (_: Exception) {
                true
            }
            assertTrue(closed, "Asha's phone said something more")
        } finally {
            sock.close()
        }
        waitUntil("the listener is done") { listeners.single().let { true } }
        sleepMillis(300)
        assertTrue(synchronized(lock) { proved.isEmpty() }, "Asha's phone took the dialler as proved: $proved")
        assertEquals(before, asha.store.data.value)
    }

    @Test
    fun theTags() {
        val now = 1_767_225_600_000L
        val hour = AutoSession.hourOf(now)
        assertEquals(490_896L, hour)
        val id = MoveIdentity("m3906c46cfd44f4074ce7")
        val asha = "pashaphone23456789ab"
        val priya = "ppriyaphone2345678ab"
        // Each phone's own, every hour another; worked out with Python's hmac from the same parts.
        assertEquals("775f3477f74d1570", AutoSession.phoneTag(id.id, hour, asha))
        assertEquals(AutoSession.phoneTag(id.id, hour, asha), AutoSession.advertisedTag(id, asha, now))
        assertTrue(AutoSession.phoneTag(id.id, hour, asha) != AutoSession.phoneTag(id.id, hour, priya))
        assertTrue(AutoSession.phoneTag(id.id, hour, asha) != AutoSession.phoneTag(id.id, hour + 1, asha))
        // A phone of the move knows which phone advertises a tag, and with which ID, an hour either way; not otherwise.
        assertEquals(asha to id.id, AutoSession.whoAdvertises(AutoSession.phoneTag(id.id, hour - 1, asha), id, listOf(priya, asha), now))
        assertNull(AutoSession.whoAdvertises(AutoSession.phoneTag(id.id, hour + 2, asha), id, listOf(priya, asha), now))
        assertNull(AutoSession.whoAdvertises(AutoSession.phoneTag("m-another", hour, asha), id, listOf(priya, asha), now))
        // A move known by two IDs advertises the lower one's tag.
        val two = MoveIdentity("mz-second", aliases = listOf("ma-first"))
        assertEquals(AutoSession.phoneTag("ma-first", hour, asha), AutoSession.advertisedTag(two, asha, now))
        // The service name holds the tag and nothing else of the move; 2.3's names are told apart.
        val name = AutoSession.serviceName("775f3477f74d1570", "k7qz")
        assertEquals("HN-775f3477f74d1570-k7qz", name)
        assertEquals("775f3477f74d1570", AutoSession.tagOfService(name))
        assertNull(AutoSession.tagOfService("HM-b89ba908a3987924-k7qz"))
        assertTrue(AutoSession.isOldPhoneOf("HM-b89ba908a3987924-k7qz", id, now), "2.3's tag of the move, which 2.4 notices")
        assertFalse(AutoSession.isOldPhoneOf("HM-b89ba908a3987924-k7qz", MoveIdentity("m-another"), now))
        assertTrue(pointOfKeyText("not a key") == null)
        assertEquals(64, sha256(name.toUtf8()).toHex().length)
    }
}
