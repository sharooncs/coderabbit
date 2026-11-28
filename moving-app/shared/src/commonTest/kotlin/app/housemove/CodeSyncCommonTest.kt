package app.housemove

import app.housemove.data.Lock
import app.housemove.data.Outcome
import app.housemove.data.Role
import app.housemove.data.deleteRecursively
import app.housemove.data.phoneEntry
import app.housemove.data.startThread
import app.housemove.data.synchronized
import app.housemove.sync.LocalLan
import app.housemove.sync.Pin
import app.housemove.sync.WifiSync
import app.housemove.sync.accept
import app.housemove.sync.close
import app.housemove.sync.dataIn
import app.housemove.sync.dataOut
import app.housemove.sync.flush
import app.housemove.sync.localPort
import app.housemove.sync.read
import app.housemove.sync.soTimeout
import app.housemove.sync.writeUTF
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Code syncs and invite joins between two phones with 2.4 (WifiSync with an E2 code: Noise XXpsk0, design/encryption.md,
 * Part 2): encrypted, each pinning the other's key as met, the joining phone's key on its new entry; a wrong code gets
 * nothing; a phone that scanned an E2 code never falls back to the plain hello; a plain hello with an E2 code from a phone
 * that has synced encrypted before is refused; and a phone met vouches for the keys it has met.
 */
class CodeSyncCommonTest {
    private val undoCrypto = useTestCryptoIfNone()
    private val root = newTempFolder()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()

    @AfterTest
    fun cleanUp() {
        phones.forEach { it.close() }
        root.deleteRecursively()
        undoCrypto()
    }

    private fun phone(name: String) = AutoPhone(root, name, lan, engineOn = false).also { phones += it }

    @Test
    fun twoPhonesWith24JoinAndSyncEncryptedAndPinEachOther() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        val heard = ArrayList<WifiSync.Result>()
        val lock = Lock()
        val host = WifiSync.host(asha.store, letNewPhonesIn = false) { synchronized(lock) { heard += it } }
        try {
            assertTrue(WifiSync.isE2(host.token))
            val person = (asha.store.addPerson("Priya", Role.DECIDER) as Outcome.Ok).value
            val invite = (host.inviteFor(person.id, listOf("127.0.0.1")) as Outcome.Ok).value
            assertTrue(WifiSync.isE2(invite.token), "an invite is an E2 code too")
            val r = priya.moves.join(priya.moves.readInvite(invite.encode())!!)
            assertTrue(r.ok && r.joinedAs != null && !r.plain, "joined encrypted: ${r.error}")
            waitUntil("the host said how it went") { synchronized(lock) { heard.isNotEmpty() } }
            assertTrue(synchronized(lock) { heard.single().ok && !heard.single().plain })
            // The invite carried the joining phone's key: it is on its new entry, and pinned, as met, on both phones.
            val priyaKey = priya.store.phone.keys.publicText
            assertEquals(priyaKey, asha.store.data.value.phoneEntry(priya.store.phone.id)!!.second.key)
            // From 2.5 the joining phone also said, inside the channel, that it opens HPKE (update files of format 2, the mailbox).
            assertEquals(Pin(priya.store.phone.id, priyaKey, Pin.MET, asha.store.pins.pinOf(priya.store.phone.id)!!.at, v2 = true, hpke = true), asha.store.pins.pinOf(priya.store.phone.id))
            assertEquals(asha.store.phone.keys.publicText, priya.store.pins.pinOf(asha.store.phone.id)?.key)
            assertEquals(Pin.MET, priya.store.pins.pinOf(asha.store.phone.id)?.how)
            // Then a sync with the plain code shown: encrypted as well.
            priya.store.createItem("Lamp", null)
            val again = WifiSync.join(asha.store, priya.codeHost().invite(listOf("127.0.0.1")), letNewPhonesIn = false)
            assertTrue(again.ok && !again.plain, "${again.error}")
            assertTrue(asha.store.data.value.liveItems.any { it.name == "Lamp" })
        } finally {
            host.stop()
        }
    }

    @Test
    fun aWrongCodeGetsNothing() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        val heard = ArrayList<WifiSync.Result>()
        val lock = Lock()
        val host = WifiSync.host(asha.store, letNewPhonesIn = false) { synchronized(lock) { heard += it } }
        try {
            val before = asha.store.data.value
            val right = host.invite(listOf("127.0.0.1"))
            val wrong = right.copy(token = right.token.dropLast(1) + if (right.token.last() == 'A') "B" else "A")
            val r = WifiSync.join(priya.store, wrong, letNewPhonesIn = false)
            assertEquals(WifiSync.CODE_NOT_ACCEPTED, r.error)
            waitUntil("the host noted it") { synchronized(lock) { heard.isNotEmpty() } }
            assertEquals(WifiSync.WRONG_CODE, synchronized(lock) { heard.single().error })
            assertEquals(before, asha.store.data.value)
            // The right one works.
            assertTrue(WifiSync.join(priya.store, right, letNewPhonesIn = false).ok)
        } finally {
            host.stop()
        }
    }

    /**
     * Someone in between (or a phone that only looks like the one that showed the code) answers a 2.4 phone's handshake as
     * an old phone would: it hangs up, or says VERSION. The phone that scanned an E2 code does not then say its hello the
     * old way: nothing it sent was plain, and its code never went in the clear.
     */
    @Test
    fun aScannedE2CodeNeverFallsBackToPlain() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        val code = asha.codeHost().invite(listOf("127.0.0.1"))
        for (answer in listOf<String?>(null, "VERSION", "NO")) {
            val server = loopbackServer()
            val lock = Lock()
            val got = ArrayList<ByteArray>()
            var connections = 0
            startThread("old-looking") {
                while (true) {
                    val s = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    synchronized(lock) { connections++ }
                    s.soTimeout = 3_000
                    val buf = ByteArray(4096)
                    val n = try {
                        s.dataIn().read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        -1
                    }
                    synchronized(lock) { if (n > 0) got += buf.copyOf(n) }
                    if (answer != null) try {
                        val out = s.dataOut()
                        out.writeUTF(answer)
                        out.flush()
                    } catch (_: Exception) {
                    }
                    s.close()
                }
            }
            try {
                val r = WifiSync.join(priya.store, code.copy(port = server.localPort), letNewPhonesIn = false)
                assertFalse(r.ok)
                assertFalse(r.plain)
                assertEquals(WifiSync.CODE_NOT_ACCEPTED, r.error)
            } finally {
                server.close()
            }
            val sent = synchronized(lock) { got.fold(ByteArray(0)) { a, b -> a + b } }
            val text = sent.decodeToString()
            assertEquals(1, synchronized(lock) { connections }, "one connection: no second hello the old way")
            assertTrue(text.startsWith("\u0000\u0007HMSYNC4"), "it opened with the handshake")
            for (clear in listOf("HMSYNC3", "HMSYNC2", code.token, asha.store.moveId, priya.store.phone.id)) assertFalse(text.contains(clear), "$clear went in the clear")
        }
    }

    /**
     * Someone who saw Asha's E2 code on her screen tries it the old, plain way, as Priya's phone, which has synced
     * encrypted with Asha's before. A 2.4 phone never does that, so Asha's phone refuses it, before anything of the move is
     * sent. A phone with an older HouseMove is still taken that way.
     */
    @Test
    fun aPlainHelloWithAnE2CodeFromAPhoneThatSpeaks24IsRefused() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        assertTrue(asha.store.pins.speaksV2(priya.store.phone.id))
        val heard = ArrayList<WifiSync.Result>()
        val lock = Lock()
        val host = WifiSync.host(asha.store, letNewPhonesIn = false) { synchronized(lock) { heard += it } }
        try {
            val before = asha.store.data.value
            val r = WifiSync.joinPlain(priya.store, host.invite(listOf("127.0.0.1")), letNewPhonesIn = false)
            assertFalse(r.ok)
            waitUntil("Asha's phone said why") { synchronized(lock) { heard.isNotEmpty() } }
            assertEquals(WifiSync.NOT_ENCRYPTED_REFUSED, synchronized(lock) { heard.single().error })
            assertEquals(before, asha.store.data.value)
            // The encrypted way, the same code works.
            assertTrue(WifiSync.join(priya.store, host.invite(listOf("127.0.0.1")), letNewPhonesIn = false).ok)
        } finally {
            host.stop()
        }
    }

    /** A phone met in a code sync or join vouches for the keys it has met: the new phone pins them as met at once. */
    @Test
    fun aPhoneMetVouchesForTheKeysItHasMet() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        val omar = phone("Omar")
        asha.found("Maple Road")
        asha.invite(priya, Role.HELPER)
        asha.invite(omar, Role.DECIDER)
        // Omar's phone never met Priya's: it has her key from Asha's vouch in its join, as met.
        val pin = omar.store.pins.pinOf(priya.store.phone.id)
        assertEquals(priya.store.phone.keys.publicText, pin?.key)
        assertEquals(Pin.MET, pin?.how)
        // Priya's phone joined before Omar's and has not heard of it: a code sync with Asha's brings Omar's entry, and Asha's
        // vouch makes its key met.
        assertEquals(null, priya.store.pins.pinOf(omar.store.phone.id))
        assertTrue(asha.codeSync(priya).ok)
        assertEquals(omar.store.phone.keys.publicText, priya.store.pins.pinOf(omar.store.phone.id)?.key)
        assertEquals(Pin.MET, priya.store.pins.pinOf(omar.store.phone.id)?.how)
    }
}
