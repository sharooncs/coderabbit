package app.housemove

import app.housemove.data.hexBytes
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.sync.Hpke
import app.housemove.sync.NoiseKey
import app.housemove.sync.noiseCrypto
import app.housemove.sync.noiseKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HPKE (Hpke.kt, RFC 9180) on the JVM with the JCA and on the iPhone simulator with the reference crypto: the CFRG's test
 * vectors for exactly HouseMove's suite, DHKEM(P-256, HKDF-SHA256), HKDF-SHA256 and AES-256-GCM, in mode_auth and
 * mode_base (src/jvmTest/resources/hpke/vectors.json); then mode_auth with keys made now, and what it refuses: another
 * sender's key, another recipient, another info, a changed encapsulated key or message, a message out of turn, a point
 * off the curve.
 */
class HpkeCommonTest {
    private val undoCrypto = useTestCryptoIfNone()

    @AfterTest
    fun cleanUp() = undoCrypto()

    private val crypto get() = noiseCrypto()!!

    private fun key(): NoiseKey = crypto.generateKey().noiseKey()!!

    private fun scalarKey(hex: String): NoiseKey = keyFromScalar(hexBytes(hex)!!).noiseKey()!!

    private fun JsonObject.hex(name: String): ByteArray = hexBytes(getValue(name).jsonPrimitive.content)!!

    private val vectors: List<JsonObject> by lazy {
        Json.parseToJsonElement(HpkeFiles.text.getValue("vectors.json")).jsonObject.getValue("vectors").jsonArray.map { it.jsonObject }
    }

    @Test
    fun theVectorsComeThroughWhole() {
        assertEquals(HpkeFiles.sha256.getValue("vectors.json"), sha256(HpkeFiles.text.getValue("vectors.json").toUtf8()).toHex())
        assertEquals(listOf(0, 2), vectors.map { it.getValue("mode").jsonPrimitive.int })
        for (v in vectors) {
            assertEquals(Hpke.KEM_ID, v.getValue("kem_id").jsonPrimitive.int)
            assertEquals(Hpke.KDF_ID, v.getValue("kdf_id").jsonPrimitive.int)
            assertEquals(Hpke.AEAD_ID, v.getValue("aead_id").jsonPrimitive.int)
        }
    }

    /** Every value of the vectors, made again: the keys, the KEM's shared secret, the key schedule, each message and export. */
    @Test
    fun theCfrgVectorsForThisSuite() {
        for (v in vectors) {
            val mode = v.getValue("mode").jsonPrimitive.int
            val e = scalarKey(v.getValue("skEm").jsonPrimitive.content)
            val r = scalarKey(v.getValue("skRm").jsonPrimitive.content)
            assertEquals(v.getValue("pkEm").jsonPrimitive.content, e.point.toHex(), "mode $mode: the one-use key")
            assertEquals(v.getValue("pkRm").jsonPrimitive.content, r.point.toHex(), "mode $mode: the recipient's key")
            val info = v.hex("info")
            val s = if (mode == Hpke.MODE_AUTH) scalarKey(v.getValue("skSm").jsonPrimitive.content) else null
            s?.let { assertEquals(v.getValue("pkSm").jsonPrimitive.content, it.point.toHex(), "the sender's key") }
            val sender = if (s != null) Hpke.setupAuthS(crypto, r.point, info, s) { e } else Hpke.setupBaseS(crypto, r.point, info) { e }
            assertNotNull(sender, "mode $mode: set up")
            assertEquals(v.getValue("enc").jsonPrimitive.content, sender.enc.toHex(), "mode $mode: enc")
            val schedule = if (s != null) Hpke.scheduleAuthR(crypto, sender.enc, r, info, s.point) else Hpke.scheduleBaseR(crypto, sender.enc, r, info)
            assertNotNull(schedule)
            for (sch in listOf(sender.schedule, schedule)) {
                assertEquals(v.getValue("shared_secret").jsonPrimitive.content, sch.sharedSecret.toHex(), "mode $mode: shared secret")
                assertEquals(v.getValue("key_schedule_context").jsonPrimitive.content, sch.keyScheduleContext.toHex(), "mode $mode: context")
                assertEquals(v.getValue("secret").jsonPrimitive.content, sch.secret.toHex(), "mode $mode: secret")
                assertEquals(v.getValue("key").jsonPrimitive.content, sch.context.key.toHex(), "mode $mode: key")
                assertEquals(v.getValue("base_nonce").jsonPrimitive.content, sch.context.baseNonce.toHex(), "mode $mode: base nonce")
                assertEquals(v.getValue("exporter_secret").jsonPrimitive.content, sch.context.exporterSecret.toHex(), "mode $mode: exporter secret")
            }
            val seal = sender.context
            val open = schedule.context
            for (m in v.getValue("encryptions").jsonArray.map { it.jsonObject }) {
                val seq = m.getValue("seq").jsonPrimitive.content.toLong()
                seal.seq = seq
                open.seq = seq
                assertEquals(m.getValue("nonce").jsonPrimitive.content, seal.nonce(seq).toHex(), "mode $mode, message $seq: nonce")
                assertEquals(m.getValue("ct").jsonPrimitive.content, seal.seal(m.hex("aad"), m.hex("pt")).toHex(), "mode $mode, message $seq sealed")
                assertContentEquals(m.hex("pt"), open.open(m.hex("aad"), m.hex("ct")), "mode $mode, message $seq opened")
                assertEquals(seq + 1, open.seq)
            }
            for (x in v.getValue("exports").jsonArray.map { it.jsonObject }) {
                val l = x.getValue("L").jsonPrimitive.int
                assertEquals(x.getValue("exported_value").jsonPrimitive.content, seal.export(x.hex("exporter_context"), l).toHex(), "mode $mode: export")
                assertEquals(x.getValue("exported_value").jsonPrimitive.content, open.export(x.hex("exporter_context"), l).toHex())
            }
        }
    }

    /** mode_auth with keys made now: what one seals the other opens, in order, and only with the right keys and info. */
    @Test
    fun modeAuthWithFreshKeys() {
        val sender = key()
        val recipient = key()
        val stranger = key()
        val info = "HouseMove mailbox 1|m1|p2".toUtf8()
        val s = Hpke.setupAuthS(crypto, recipient.point, info, sender)!!
        val one = s.context.seal("a".toUtf8(), "first".toUtf8())
        val two = s.context.seal("b".toUtf8(), "second".toUtf8())
        assertEquals(Hpke.ENC, s.enc.size)
        assertEquals("first".length + Hpke.TAG, one.size)

        val r = Hpke.setupAuthR(crypto, s.enc, recipient, info, sender.point)!!
        assertNull(r.open("a".toUtf8(), two), "the second message before the first does not open")
        assertContentEquals("first".toUtf8(), r.open("a".toUtf8(), one))
        assertNull(r.open("a".toUtf8(), two), "another aad")
        assertContentEquals("second".toUtf8(), r.open("b".toUtf8(), two))
        assertNull(r.open("a".toUtf8(), one), "a message already opened does not open again")

        // Another sender's key: the recipient's pin of the sender is what proves who sealed it.
        assertNull(Hpke.setupAuthR(crypto, s.enc, recipient, info, stranger.point)!!.open("a".toUtf8(), one))
        // Another recipient, another info, a changed enc or message.
        assertNull(Hpke.setupAuthR(crypto, s.enc, stranger, info, sender.point)!!.open("a".toUtf8(), one))
        assertNull(Hpke.setupAuthR(crypto, s.enc, recipient, "HouseMove mailbox 1|m1|p3".toUtf8(), sender.point)!!.open("a".toUtf8(), one))
        val otherEnc = key().point
        assertNull(Hpke.setupAuthR(crypto, otherEnc, recipient, info, sender.point)!!.open("a".toUtf8(), one))
        val bent = one.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(Hpke.setupAuthR(crypto, s.enc, recipient, info, sender.point)!!.open("a".toUtf8(), bent))
        assertNull(Hpke.setupAuthR(crypto, s.enc, recipient, info, sender.point)!!.open("a".toUtf8(), one.copyOf(one.size - 1)))
        assertNull(Hpke.setupAuthR(crypto, s.enc, recipient, info, sender.point)!!.open("a".toUtf8(), ByteArray(3)))

        // A point off the curve, or not a point at all, sets nothing up.
        val off = recipient.point.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertNull(Hpke.setupAuthS(crypto, off, info, sender))
        assertNull(Hpke.setupAuthR(crypto, off, recipient, info, sender.point))
        assertNull(Hpke.setupAuthR(crypto, s.enc, recipient, info, off))
        assertNull(Hpke.setupAuthS(crypto, ByteArray(65), info, sender))
        assertNull(Hpke.setupAuthR(crypto, s.enc.copyOf(64), recipient, info, sender.point))

        // Each sealing has its own one-use key: the same message twice gives other bytes.
        val again = Hpke.setupAuthS(crypto, recipient.point, info, sender)!!
        assertTrue(!again.enc.contentEquals(s.enc))
        assertTrue(!again.context.seal("a".toUtf8(), "first".toUtf8()).contentEquals(one))
    }

    @Test
    fun theNonceOfALaterMessage() {
        val c = Hpke.setupAuthS(crypto, key().point, ByteArray(0), key())!!.context
        val base = c.baseNonce
        assertContentEquals(base, c.nonce(0))
        val n = c.nonce(0x0102)
        assertEquals((base[11].toInt() xor 0x02).toByte(), n[11])
        assertEquals((base[10].toInt() xor 0x01).toByte(), n[10])
        assertContentEquals(base.copyOf(10), n.copyOf(10))
    }
}
