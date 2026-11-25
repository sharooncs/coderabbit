package app.housemove

import app.housemove.data.MoveIdentity
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.currentTimeMillis
import app.housemove.data.hexBytes
import app.housemove.data.phoneEntry
import app.housemove.data.toHex
import app.housemove.sync.AutoListener
import app.housemove.sync.AutoSession
import app.housemove.sync.AutoSlot
import app.housemove.sync.LocalLan
import app.housemove.sync.Meetings
import app.housemove.sync.Socket
import app.housemove.sync.WifiSync
import app.housemove.sync.accept
import app.housemove.sync.close
import app.housemove.sync.connect
import app.housemove.sync.dataIn
import app.housemove.sync.dataOut
import app.housemove.sync.flush
import app.housemove.sync.localPort
import app.housemove.sync.readFully
import app.housemove.sync.readUTF
import app.housemove.sync.soTimeout
import app.housemove.sync.write
import app.housemove.sync.writeInt
import app.housemove.sync.writeUTF
import app.housemove.data.Lock
import app.housemove.data.startThread
import app.housemove.data.synchronized
import app.housemove.data.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The handshake of syncing by itself (AutoSession, design/auto-sync.md "Trust without a code"), message by message:
 * what a phone of the move is let in with, and what a stranger, a wrong key, a replayed proof, a removed person's
 * phone, a retired phone and a phone of another move are told. The proofs of a fixed handshake are written down here,
 * worked out once with another HMAC (Python's), so every platform must make the same bytes.
 */
class AutoHandshakeCommonTest {
    private val root = newTempFolder()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()
    private val listeners = ArrayList<AutoListener>()

    @AfterTest
    fun cleanUp() {
        listeners.forEach { it.stop() }
        phones.forEach { it.close() }
        Meetings.clear()
        root.deleteRecursively()
    }

    /** Asha (Organiser), Priya (Decider) and Hal (Helper), all joined, with no meetings left and one Wi-Fi key each. */
    private fun move(): Triple<AutoPhone, AutoPhone, AutoPhone> {
        val (asha, priya, hal) = listOf("Asha", "Priya", "Hal").map { AutoPhone(root, it, lan, engineOn = false).also { p -> phones += p } }
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        asha.invite(hal, Role.HELPER)
        Meetings.clear()
        asha.store.ring.mintIfNone(asha.store.data.value)
        val keys = asha.store.ring.keys(asha.store.data.value)
        priya.store.ring.take(keys, priya.store.data.value)
        hal.store.ring.take(keys, hal.store.data.value)
        return Triple(asha, priya, hal)
    }

    private fun Store.key(): ByteArray = ring.keys(data.value).first().bytes!!

    private fun Store.tag(): String = AutoSession.advertisedTag(identity.value, currentTimeMillis())

    private fun listen(store: Store, slot: AutoSlot = AutoSlot()): Int {
        val l = AutoListener(true, { store }, slot, {}, { _, _ -> })
        l.start()
        listeners += l
        return l.port
    }

    /** A phone, or not, speaking the handshake by hand. */
    private class Raw(port: Int) : AutoCloseable {
        val s = Socket().also {
            it.connect("127.0.0.1", port, 3_000)
            it.soTimeout = 5_000
        }
        val din = s.dataIn()
        val out = s.dataOut()

        fun hello(tag: String, nD: ByteArray, magic: String = AutoSession.MAGIC) {
            out.writeUTF(magic); out.writeUTF(tag); out.writeUTF(""); out.write(nD); out.flush()
        }

        /** The answering phone's nonce, or null when it hung up without one. */
        fun nonce(): ByteArray? = try {
            ByteArray(AutoSession.NONCE).also { din.readFully(it) }
        } catch (_: Exception) {
            null
        }

        /** Sends [proofs]; the word that comes back, or "closed". */
        fun prove(proofs: List<ByteArray>): String {
            out.writeInt(proofs.size)
            for (p in proofs) out.write(p)
            out.flush()
            return try {
                din.readUTF()
            } catch (_: Exception) {
                "closed"
            }
        }

        override fun close() = s.close()
    }

    private fun nonce(b: Int) = ByteArray(AutoSession.NONCE) { (b + it).toByte() }

    /** The word [port] answers to a proof made by [make] from both nonces. */
    private fun answerTo(port: Int, tag: String, make: (nD: ByteArray, nA: ByteArray) -> List<ByteArray>): String =
        Raw(port).use { r ->
            val nD = nonce(7)
            r.hello(tag, nD)
            val nA = r.nonce() ?: return "closed"
            r.prove(make(nD, nA))
        }

    private fun dialler(key: ByteArray, tag: String, nD: ByteArray, nA: ByteArray, id: String) = AutoSession.proof(key, "HMAUTO1 dialler", tag, nD, nA, id)

    @Test
    fun aPhoneOfTheMoveWithTheKeyIsLetIn() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        assertEquals("OK", answerTo(port, asha.store.tag()) { nD, nA -> listOf(dialler(priya.store.key(), asha.store.tag(), nD, nA, priya.store.phone.id)) })
        // And the whole of it, dialled as the engine dials: a sync.
        val d = AutoSession.dial(priya.store, "127.0.0.1", port)
        assertEquals("OK", d.word)
        assertTrue(d.result!!.ok, "${d.result.error}")
        assertEquals(asha.store.phone.id, d.peerPhone)
        assertEquals(AutoSession.Kind.WIFI, d.via)
        // They now hold a pair key, the same on both phones.
        assertEquals(asha.store.ring.pairs()[priya.store.phone.id]!!.toHex(), priya.store.ring.pairs()[asha.store.phone.id]!!.toHex())
        assertEquals(AutoSession.Kind.PAIR, AutoSession.dial(priya.store, "127.0.0.1", port).via)
    }

    @Test
    fun aStrangerOnTheWifiIsToldNo() {
        val (asha, _, _) = move()
        val port = listen(asha.store)
        val tag = asha.store.tag()
        // No proof, made-up proofs, and a made-up key for a phone of the move: nothing.
        assertEquals("NO", answerTo(port, tag) { _, _ -> emptyList() })
        assertEquals("NO", answerTo(port, tag) { _, _ -> List(5) { n -> nonce(40 + n) } })
        val made = ByteArray(32) { 3 }
        val members = asha.store.data.value.people.flatMap { p -> p.phones.map { it.id } }
        assertEquals("NO", answerTo(port, tag) { nD, nA -> members.map { dialler(made, tag, nD, nA, it) } })
        // A phone of another move, with keys of its own, is not even answered: its tag is not this move's.
        val stranger = AutoPhone(root, "Stranger", lan, engineOn = false).also { phones += it }
        stranger.found("Elm Street")
        stranger.store.ring.mintIfNone(stranger.store.data.value)
        assertEquals("LOST", AutoSession.dial(stranger.store, "127.0.0.1", port).word)
    }

    @Test
    fun aWrongKeyIsToldNo() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        // Priya's phone holds a key the move never had.
        val other = AutoPhone(root, "Other", lan, engineOn = false).also { phones += it }
        other.found("Elm Street")
        other.store.ring.mintIfNone(other.store.data.value)
        val wrong = other.store.key()
        val tag = asha.store.tag()
        assertEquals("NO", answerTo(port, tag) { nD, nA -> listOf(dialler(wrong, tag, nD, nA, priya.store.phone.id)) })
        // The right key, but proving the phone the answering one is: its own proof is not taken back.
        assertEquals("NO", answerTo(port, tag) { nD, nA -> listOf(dialler(asha.store.key(), tag, nD, nA, asha.store.phone.id)) })
    }

    @Test
    fun aReplayedProofIsToldNo() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        val tag = asha.store.tag()
        val key = priya.store.key()
        val me = priya.store.phone.id
        // A proof made for one connection's nonces, sent on the next: the answering phone's nonce is new each time.
        val nD = nonce(1)
        val firstNA = Raw(port).use { r ->
            r.hello(tag, nD)
            r.nonce()!!
        }
        val recorded = dialler(key, tag, nD, firstNA, me)
        Raw(port).use { r ->
            r.hello(tag, nD)
            val nA = r.nonce()!!
            assertNotEquals(firstNA.toHex(), nA.toHex(), "a new nonce for each connection")
            assertEquals("NO", r.prove(listOf(recorded)))
        }
        // The answering phone's proof, sent back to it as a dialler's: the labels differ, so it proves nothing.
        Raw(port).use { r ->
            r.hello(tag, nD)
            val nA = r.nonce()!!
            val theirs = AutoSession.proof(key, "HMAUTO1 answerer", tag, nD, nA, me, asha.store.phone.id)
            assertEquals("NO", r.prove(listOf(theirs)))
        }
    }

    @Test
    fun aRemovedPersonsPhoneIsToldNoEvenWithTheKey() {
        val (asha, priya, hal) = move()
        val port = listen(asha.store)
        val oldKey = hal.store.key()
        val halPerson = asha.store.data.value.phoneEntry(hal.store.phone.id)!!.first
        assertTrue(asha.store.removePerson(halPerson.id).ok)
        val tag = asha.store.tag()
        // As itself: not a phone of the move. As Priya's phone, with the key it kept: that key is no longer taken.
        assertEquals("NO", answerTo(port, tag) { nD, nA -> listOf(dialler(oldKey, tag, nD, nA, hal.store.phone.id)) })
        assertEquals("NO", answerTo(port, tag) { nD, nA -> listOf(dialler(oldKey, tag, nD, nA, priya.store.phone.id)) })
        assertTrue(asha.store.ring.keys(asha.store.data.value).isEmpty(), "the key Hal's phone holds was dropped")
        assertEquals("NO", AutoSession.dial(hal.store, "127.0.0.1", port).word)
    }

    @Test
    fun aRetiredPhoneIsToldNo() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        val person = asha.store.data.value.phoneEntry(priya.store.phone.id)!!.first
        assertTrue(asha.store.retirePhone(person.id, priya.store.phone.id).ok)
        assertEquals("NO", AutoSession.dial(priya.store, "127.0.0.1", port).word)
    }

    @Test
    fun anotherMoveOrAnotherAppIsNotAnswered() {
        val (asha, priya, _) = move()
        val port = listen(asha.store)
        // Another move's tag (or an old one): the phone hangs up without a word.
        Raw(port).use { r ->
            r.hello(AutoSession.tag("m-another-move", AutoSession.hourOf(currentTimeMillis())), nonce(1))
            assertNull(r.nonce())
        }
        Raw(port).use { r ->
            r.hello(AutoSession.tag(asha.store.moveId, AutoSession.hourOf(currentTimeMillis()) - 5), nonce(1))
            assertNull(r.nonce())
        }
        // A code sync's hello on this socket: not answered either.
        Raw(port).use { r ->
            r.hello(asha.store.tag(), nonce(1), magic = "HMSYNC3")
            assertNull(r.nonce())
        }
        // The engine never dials a phone that does not advertise, but if a 2.2 phone's code-sync socket were dialled,
        // it would only note "not a HouseMove phone" and answer nothing: the handshake goes nowhere.
        val lock = Lock()
        val results = ArrayList<WifiSync.Result>()
        val old = WifiSync.host(asha.store, letNewPhonesIn = false) { synchronized(lock) { results += it } }
        try {
            assertEquals("LOST", AutoSession.dial(priya.store, "127.0.0.1", old.port).word)
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
        assertEquals("BUSY", AutoSession.dial(priya.store, "127.0.0.1", port).word)
        slot.release()
        assertEquals("OK", AutoSession.dial(priya.store, "127.0.0.1", port).word)
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

    @Test
    fun theTag() {
        val now = 1_767_225_600_000L
        val hour = AutoSession.hourOf(now)
        assertEquals(490_896L, hour)
        val id = MoveIdentity("m3906c46cfd44f4074ce7")
        assertEquals("b89ba908a3987924", AutoSession.tag(id.id, hour))
        assertEquals("ac415fa1822d972b", AutoSession.tag(id.id, hour - 1))
        assertEquals("b89ba908a3987924", AutoSession.advertisedTag(id, now))
        assertEquals(3, AutoSession.tagsOf(id, now).size)
        assertTrue(AutoSession.tag(id.id, hour + 1) in AutoSession.tagsOf(id, now))
        assertTrue(AutoSession.tag(id.id, hour + 2) !in AutoSession.tagsOf(id, now))
        // A move known by two IDs advertises the lower one's tag, and takes either's.
        val two = MoveIdentity("mz-second", aliases = listOf("ma-first"))
        assertEquals(AutoSession.tag("ma-first", hour), AutoSession.advertisedTag(two, now))
        assertTrue(AutoSession.tag("mz-second", hour) in AutoSession.tagsOf(two, now))
        // The service name holds the tag and nothing else of the move.
        val name = AutoSession.serviceName("b89ba908a3987924", "k7qz")
        assertEquals("HM-b89ba908a3987924-k7qz", name)
        assertEquals("b89ba908a3987924", AutoSession.tagOfService(name))
        assertNull(AutoSession.tagOfService("HouseMove check 123456"))
        assertNull(AutoSession.tagOfService("HM-B89BA908A3987924-k7qz"))
    }

    /** A fixed handshake's proofs, as Python's hmac works them out from the same parts. */
    @Test
    fun theProofsOfAFixedHandshake() {
        val tag = "b89ba908a3987924"
        val nD = ByteArray(32) { it.toByte() }
        val nA = ByteArray(32) { (32 + it).toByte() }
        val key = ByteArray(32) { (100 + it).toByte() }
        val d = "ppriyaphone2345678ab"
        val a = "pashaphone23456789ab"
        assertEquals("56b01f239f8b9c74104b487fd4d996330345ef353f5f6feec89fa928000a3450", AutoSession.proof(key, "HMAUTO1 dialler", tag, nD, nA, d).toHex())
        assertEquals("40d9629389d92a79344553b08b876c3fe455f2f6eb90647640616605870cf225", AutoSession.proof(key, "HMAUTO1 answerer", tag, nD, nA, d, a).toHex())
        assertEquals("cd3593e32ffaf5d28b143d9f3b34b42cf563b26699601f241aa12ffc0856a373", AutoSession.proof(key, "HMAUTO1 confirm", tag, nD, nA, d, a).toHex())
        assertEquals("5405bc80fb234ac8e3702bcbc922349af38ae0e6d9438ec1d477818457ba3b18", AutoSession.proof(key, "HMAUTO1 pair", tag, nD, nA, a, d).toHex())
        assertEquals(
            "56e291e31e3c92065ca8415a8b14ba791eafce5f05bc3f816c7f8d5f45feaf63",
            AutoSession.proof("meeting:GQ7K2MXP".encodeToByteArray(), "HMAUTO1 dialler", tag, nD, nA, d).toHex(),
        )
        assertEquals(32, hexBytes("56b01f239f8b9c74104b487fd4d996330345ef353f5f6feec89fa928000a3450")!!.size)
    }
}
