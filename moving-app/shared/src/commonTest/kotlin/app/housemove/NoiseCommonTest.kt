package app.housemove

import app.housemove.data.IOException
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.sync.AutoSession
import app.housemove.sync.CipherState
import app.housemove.sync.Handshake
import app.housemove.sync.Noise
import app.housemove.sync.NoiseKey
import app.housemove.sync.SecureChannel
import app.housemove.sync.WifiSync
import app.housemove.sync.dataInputOf
import app.housemove.sync.dataOutputOf
import app.housemove.sync.flush
import app.housemove.sync.noiseCrypto
import app.housemove.sync.noiseKey
import app.housemove.sync.readFully
import app.housemove.sync.readInt
import app.housemove.sync.write
import app.housemove.sync.writeInt
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encrypted sync's building blocks (Noise.kt, SecureChannel.kt; design/encryption.md, Part 2), on the JVM with the
 * JCA and on the iPhone simulator with the reference crypto: the recorded handshakes and frames byte for byte; IK and
 * XXpsk0 with fresh keys; what is refused (a changed or replayed message, a wrong pre-shared key or prologue, a key that
 * is not the one expected, a point off the curve); and the channel's frames: in order, once, unchanged, within their
 * limits, with the key changed on schedule.
 */
class NoiseCommonTest {
    private val undoCrypto = useTestCryptoIfNone()

    @AfterTest
    fun cleanUp() = undoCrypto()

    private val crypto get() = noiseCrypto()!!

    private fun key(): NoiseKey = crypto.generateKey().noiseKey()!!

    @Test
    fun theRecordedFileComesThroughWhole() {
        val text = NoiseFiles.text.getValue("handshakes.json")
        assertEquals(NoiseFiles.sha256.getValue("handshakes.json"), sha256(text.toUtf8()).toHex())
    }

    /** The JVM recorded these with the JCA; here they are made again with this platform's crypto (the reference one on the simulator). */
    @Test
    fun theHandshakesAndFramesAreTheRecordedOnes() {
        val made = NoiseKat.PRETTY.encodeToString(JsonObject.serializer(), NoiseKat.make(crypto, ::keyFromScalar)) + "\n"
        assertEquals(NoiseFiles.text.getValue("handshakes.json"), made)
    }

    @Test
    fun theNoncesAndTheNames() {
        assertEquals("000000000000000000000001", Noise.nonce(1).toHex())
        assertEquals("000000000000000001020304", Noise.nonce(0x01020304).toHex())
        assertEquals("00000000ffffffffffffffff", Noise.nonce(-1).toHex())
        assertEquals("Noise_IK_P256_AESGCM_SHA256", Noise.protocolName(Noise.IK))
        assertEquals("Noise_XXpsk0_P256_AESGCM_SHA256", Noise.protocolName(Noise.XX_PSK0))
        assertTrue(WifiSync.isE2("E2GQ7K2MXP"))
        assertFalse(WifiSync.isE2("GQ7K2MXP"), "an older phone's code")
        assertFalse(WifiSync.isE2("E2GQ7K2M"), "eight characters, even beginning E2, are an older phone's")
        assertFalse(WifiSync.isE2("X2GQ7K2MXP"))
    }

    /** Both patterns with keys made now: the two sides agree, and the channel carries what each says, both ways. */
    @Test
    fun ikAndXxpsk0WithFreshKeys() {
        val a = key()
        val b = key()
        for (pattern in listOf(Noise.IK, Noise.XX_PSK0)) {
            val psk = if (pattern == Noise.XX_PSK0) WifiSync.codeKey("E2ABCDEFGH", "m-move") else null
            val i = Handshake(crypto, pattern, true, "prologue".toUtf8(), a, rs = if (pattern == Noise.IK) b.point else null, psk = psk)
            val r = Handshake(crypto, pattern, false, "prologue".toUtf8(), b, psk = psk)
            var turn = 0
            while (!i.done || !r.done) {
                val (from, to) = if (turn % 2 == 0) i to r else r to i
                val p = "message ${turn + 1}".toUtf8()
                assertContentEquals(p, to.read(from.write(p)), "$pattern message ${turn + 1}")
                turn++
            }
            assertContentEquals(i.hash, r.hash)
            assertContentEquals(b.point, i.remoteStatic)
            assertContentEquals(a.point, r.remoteStatic)
            val (iSend, iReceive) = i.split()
            val (rSend, rReceive) = r.split()
            val toR = BytesOut()
            val toI = BytesOut()
            val ci = SecureChannel(dataInputOf(bytesIn(ByteArray(0))), dataOutputOf(toR.stream), iSend, iReceive)
            ci.dataOut.writeInt(42)
            ci.dataOut.write("over".toUtf8())
            ci.dataOut.flush()
            val cr = SecureChannel(dataInputOf(bytesIn(toR.bytes())), dataOutputOf(toI.stream), rSend, rReceive)
            assertEquals(42, cr.dataIn.readInt())
            val four = ByteArray(4).also { cr.dataIn.readFully(it) }
            assertEquals("over", four.decodeToString())
            cr.dataOut.write("and out".toUtf8())
            cr.dataOut.flush()
            val back = SecureChannel(dataInputOf(bytesIn(toI.bytes())), dataOutputOf(BytesOut().stream), iSend, iReceive)
            assertEquals("and out", ByteArray(7).also { back.dataIn.readFully(it) }.decodeToString())
        }
    }

    /** A handshake between [a] and [b]'s known key, its messages, for tampering. */
    private fun ikMessages(a: NoiseKey, b: NoiseKey): List<ByteArray> {
        val i = Handshake(crypto, Noise.IK, true, "p".toUtf8(), a, b.point)
        val r = Handshake(crypto, Noise.IK, false, "p".toUtf8(), b)
        val m1 = i.write("one".toUtf8())
        r.read(m1)!!
        val m2 = r.write("two".toUtf8())
        return listOf(m1, m2)
    }

    @Test
    fun aChangedMessageDoesNotOpen() {
        val a = key()
        val b = key()
        val (m1, _) = ikMessages(a, b)
        // Any byte changed, anywhere: the one-use key (another key, or no point), the encrypted static key, the payload.
        for (at in listOf(1, 40, 64, 66, 100, 145, m1.size - 1)) {
            val bad = m1.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertNull(Handshake(crypto, Noise.IK, false, "p".toUtf8(), b).read(bad), "a changed byte at $at")
        }
        // Cut short, or with a byte more.
        assertNull(Handshake(crypto, Noise.IK, false, "p".toUtf8(), b).read(m1.copyOf(m1.size - 1)))
        assertNull(Handshake(crypto, Noise.IK, false, "p".toUtf8(), b).read(m1 + byteArrayOf(0)))
        // The same message, unchanged, does open.
        assertNotNull(Handshake(crypto, Noise.IK, false, "p".toUtf8(), b).read(m1))
    }

    @Test
    fun anotherPrologueOrPreSharedKeyOrKeyDoesNotOpen() {
        val a = key()
        val b = key()
        val (m1, _) = ikMessages(a, b)
        // Another move in the prologue; a phone with another key than the one the dialler meant.
        assertNull(Handshake(crypto, Noise.IK, false, "q".toUtf8(), b).read(m1))
        assertNull(Handshake(crypto, Noise.IK, false, "p".toUtf8(), key()).read(m1))
        // XXpsk0's first message opens only with the key of the code it was made with (and the move's ID).
        val psk = WifiSync.codeKey("E2ABCDEFGH", "m-move")
        val x1 = Handshake(crypto, Noise.XX_PSK0, true, WifiSync.codePrologue("m-move"), a, psk = psk).write()
        assertNull(Handshake(crypto, Noise.XX_PSK0, false, WifiSync.codePrologue("m-move"), b, psk = WifiSync.codeKey("E2ABCDEFGJ", "m-move")).read(x1))
        assertNull(Handshake(crypto, Noise.XX_PSK0, false, WifiSync.codePrologue("m-move"), b, psk = WifiSync.codeKey("E2ABCDEFGH", "m-other")).read(x1))
        assertNotNull(Handshake(crypto, Noise.XX_PSK0, false, WifiSync.codePrologue("m-move"), b, psk = psk).read(x1))
    }

    /** A one-use key that is not a point of P-256 (the right length, the right first byte) fails the handshake. */
    @Test
    fun aPointOffTheCurveFailsTheHandshake() {
        val a = key()
        val b = key()
        val (m1, _) = ikMessages(a, b)
        val off = m1.copyOf().also { m -> for (i in 33 until 65) m[i] = 0x11 }
        assertNull(Handshake(crypto, Noise.IK, false, "p".toUtf8(), b).read(off))
        // And as the answer's one-use key: the dialler fails it too.
        val i = Handshake(crypto, Noise.IK, true, "p".toUtf8(), a, b.point)
        val r = Handshake(crypto, Noise.IK, false, "p".toUtf8(), b)
        r.read(i.write())!!
        val m2 = r.write().also { m -> for (k in 1 until 65) m[k] = 0x22 }
        assertNull(i.read(m2))
    }

    /**
     * IK's first message can be sent again by someone who recorded it, and the answering phone answers it; but the answer
     * and the channel need the dialler's one-use private key, so nothing comes of it: no frame the replayer makes opens.
     */
    @Test
    fun aReplayedFirstMessageOpensNoChannel() {
        val a = key()
        val b = key()
        val (m1, _) = ikMessages(a, b)
        val again = Handshake(crypto, Noise.IK, false, "p".toUtf8(), b)
        assertNotNull(again.read(m1))
        val answer = again.write("OK".toUtf8())
        // The replayer has only a's public key and the recorded message: it cannot read the answer as the dialler would.
        val replayer = Handshake(crypto, Noise.IK, true, "p".toUtf8(), key(), b.point)
        replayer.write()
        assertNull(replayer.read(answer))
        // Frames it makes up, or frames recorded from the first session, do not open in this one.
        val (send, receive) = again.split()
        val made = ByteArray(2 + 40).also { it[1] = 40 }
        val ch = SecureChannel(dataInputOf(bytesIn(made)), dataOutputOf(BytesOut().stream), send, receive)
        assertFailsWith<IOException> { ch.awaitFirst() }
    }

    /** Frames from one side, as the wire carries them: each a two-byte length and its bytes. */
    private fun frames(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            val len = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
            out += bytes.copyOfRange(at, at + 2 + len)
            at += 2 + len
        }
        return out
    }

    /** A pair of fresh cipher states, as a handshake would give them, for the channel's tests. */
    private fun ciphers(): Pair<Pair<CipherState, CipherState>, Pair<CipherState, CipherState>> {
        val k1 = sha256("one way".toUtf8())
        val k2 = sha256("the other".toUtf8())
        fun c(k: ByteArray) = CipherState(crypto).apply { initializeKey(k) }
        return (c(k1) to c(k2)) to (c(k2) to c(k1))
    }

    /** What one side sends: [messages], each flushed, through a channel with [rekeyEvery]. */
    private fun sent(messages: List<ByteArray>, rekeyEvery: Long = SecureChannel.REKEY_EVERY): ByteArray {
        val (mine, _) = ciphers()
        val out = BytesOut()
        val ch = SecureChannel(dataInputOf(bytesIn(ByteArray(0))), dataOutputOf(out.stream), mine.first, mine.second, rekeyEvery = rekeyEvery)
        for (m in messages) {
            ch.dataOut.write(m)
            ch.dataOut.flush()
        }
        return out.bytes()
    }

    /** What the other side reads of [wire], as many bytes as [n]. */
    private fun read(wire: ByteArray, n: Int, rekeyEvery: Long = SecureChannel.REKEY_EVERY, maxFrames: Long = SecureChannel.MAX_FRAMES): ByteArray {
        val (_, theirs) = ciphers()
        val ch = SecureChannel(dataInputOf(bytesIn(wire)), dataOutputOf(BytesOut().stream), theirs.first, theirs.second, rekeyEvery, maxFrames)
        return ByteArray(n).also { ch.dataIn.readFully(it) }
    }

    @Test
    fun framesComeInOrderOnceAndUnchanged() {
        val msgs = listOf("first".toUtf8(), "second".toUtf8(), "third".toUtf8())
        val wire = sent(msgs)
        val f = frames(wire)
        assertEquals(3, f.size)
        assertEquals("firstsecondthird", read(wire, 16).decodeToString())
        // Nothing of what is said can be read on the wire.
        assertFalse(wire.decodeToString().contains("second"))
        // Changed, repeated, out of order, or left out: the channel stops at that frame.
        val changed = f[1].copyOf().also { it[5] = (it[5].toInt() xor 0x80).toByte() }
        for ((what, w) in listOf(
            "changed" to f[0] + changed + f[2],
            "repeated" to f[0] + f[0] + f[1],
            "out of order" to f[1] + f[0] + f[2],
            "left out" to f[0] + f[2],
        )) assertFailsWith<IOException>(what) { read(w, 16) }
        // Cut short in the middle of a frame: the end comes early.
        assertFailsWith<app.housemove.data.EOFException> { read(f[0] + f[1].copyOf(10), 16) }
        // A frame too short to hold a tag is no frame.
        assertFailsWith<IOException> { read(f[0] + byteArrayOf(0, 5, 1, 2, 3, 4, 5), 16) }
    }

    @Test
    fun theLimitsOfTheChannel() {
        // At most 65,519 bytes of the sync in a frame, so no frame is longer than Noise's 65,535.
        val big = ByteArray(3 * SecureChannel.MAX_PLAIN + 10) { (it % 251).toByte() }
        val wire = sent(listOf(big))
        val f = frames(wire)
        assertEquals(4, f.size)
        assertTrue(f.all { it.size - 2 <= Noise.MAX_MESSAGE })
        assertEquals(Noise.MAX_MESSAGE, f[0].size - 2)
        assertContentEquals(big, read(wire, big.size))
        // The key changes every so many frames, on both sides alike: a side that does not keep to it reads nothing after.
        val small = (1..7).map { "frame $it".toUtf8() }
        val rekeyed = sent(small, rekeyEvery = 2)
        assertEquals(small.joinToString("") { it.decodeToString() }, read(rekeyed, small.sumOf { it.size }, rekeyEvery = 2).decodeToString())
        assertFailsWith<IOException> { read(rekeyed, small.sumOf { it.size }, rekeyEvery = 3) }
        assertFalse(rekeyed.contentEquals(sent(small)), "the key did change")
        // No more frames than the limit, either way: the channel stops rather than go on.
        assertFailsWith<IOException> { read(sent(small), small.sumOf { it.size }, maxFrames = 5) }
        val (mine, _) = ciphers()
        val ch = SecureChannel(dataInputOf(bytesIn(ByteArray(0))), dataOutputOf(BytesOut().stream), mine.first, mine.second, maxFrames = 2)
        ch.dataOut.write("1".toUtf8()); ch.dataOut.flush()
        ch.dataOut.write("2".toUtf8()); ch.dataOut.flush()
        assertFailsWith<IOException> {
            ch.dataOut.write("3".toUtf8())
            ch.dataOut.flush()
        }
    }

    @Test
    fun theHandshakeMessagesAreOfTheSizesWrittenDown() {
        val a = key()
        val b = key()
        val (m1, m2) = ikMessages(a, b)
        // IK: e (65), s encrypted (65 + 16), the payload "one" (3 + 16); then e (65) and "two" (3 + 16).
        assertEquals(65 + 81 + 19, m1.size)
        assertEquals(65 + 19, m2.size)
        // XXpsk0: e and an empty payload with its tag; e, s encrypted and the payload; s encrypted and the payload.
        val psk = WifiSync.codeKey("E2ABCDEFGH", "m-move")
        val i = Handshake(crypto, Noise.XX_PSK0, true, WifiSync.codePrologue("m-move"), a, psk = psk)
        val r = Handshake(crypto, Noise.XX_PSK0, false, WifiSync.codePrologue("m-move"), b, psk = psk)
        val x1 = i.write()
        assertEquals(65 + 16, x1.size)
        r.read(x1)!!
        val x2 = r.write(AutoSession.joined("p".toUtf8()))
        assertEquals(65 + 81 + 5 + 16, x2.size)
        i.read(x2)!!
        assertEquals(81 + 5 + 16, i.write(AutoSession.joined("q".toUtf8())).size)
    }
}
