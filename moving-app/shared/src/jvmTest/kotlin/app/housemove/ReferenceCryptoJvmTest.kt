package app.housemove

import app.housemove.data.JavaBase64
import app.housemove.data.JcaCrypto
import app.housemove.data.P256Der
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import kotlin.random.Random

/**
 * The reference P-256 and AES-256-GCM (ReferenceCrypto.kt, tests only), which the iPhone simulator's Kotlin tests use in
 * place of the app's CryptoKit, against the JCA on random keys and texts; and the iPhone's Base64 (JavaBase64) against
 * java.util.Base64. If these agree, a common test that fails on the simulator fails because of the common code.
 */
class ReferenceCryptoJvmTest {
    private val random = Random(20261125)

    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun scalarOf(pkcs8: ByteArray): ByteArray {
        val s = (KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey).s
        val b = s.toByteArray()
        return if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
    }

    @Test
    fun aesGcmSealsAsTheJcaDoes() {
        val lengths = listOf(0, 1, 15, 16, 17, 31, 32, 33, 64, 100, 1000, 65_536)
        for (round in 0 until 120) {
            val key = bytes(32)
            val nonce = bytes(12)
            val aad = bytes(if (round % 3 == 0) 0 else random.nextInt(1, 70))
            val plain = bytes(lengths[round % lengths.size])
            val jca = JcaCrypto.sealGcm(key, nonce, aad, plain)
            assertArrayEquals("round $round", jca, RefGcm.seal(key, nonce, aad, plain))
            assertArrayEquals(plain, RefGcm.open(key, nonce, aad, jca))
            // A byte changed anywhere, or other additional data, and it does not open.
            val spoilt = jca.copyOf().also { it[random.nextInt(it.size)] = (it[random.nextInt(it.size)].toInt() xor 1).toByte() }
            if (!spoilt.contentEquals(jca)) assertNull(RefGcm.open(key, nonce, aad, spoilt))
            assertNull(RefGcm.open(key, nonce, aad + 1, jca))
        }
    }

    @Test
    fun p256AgreesAsTheJcaDoes() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        repeat(24) {
            val a = g.generateKeyPair()
            val b = g.generateKeyPair()
            val d = scalarOf(a.private.encoded)
            // The public key the reference works out from the private one is the JCA's, byte for byte.
            assertArrayEquals(a.public.encoded, P256Der.of(RefP256.publicOf(d)))
            assertTrue(ReferenceCrypto.isPublicKey(b.public.encoded))
            val jca = KeyAgreement.getInstance("ECDH").run { init(a.private); doPhase(b.public, true); generateSecret() }
            assertArrayEquals(jca, ReferenceCrypto.keyOf(d).agree(b.public.encoded))
            // A reference key pair is one the JCA takes and agrees with.
            val r = ReferenceCrypto.generateKey()
            assertTrue(JcaCrypto.isPublicKey(r.publicDer))
            val other = KeyAgreement.getInstance("ECDH").run {
                init(b.private)
                doPhase(KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(r.publicDer)), true)
                generateSecret()
            }
            assertArrayEquals(other, r.agree(b.public.encoded))
        }
        // Not points on the curve: refused.
        val good = g.generateKeyPair().public.encoded
        val offCurve = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(ReferenceCrypto.isPublicKey(offCurve))
        assertNull(ReferenceCrypto.generateKey().agree(offCurve))
        assertFalse(ReferenceCrypto.isPublicKey(good.copyOf(90)))
    }

    @Test
    fun theGoldenPhonesKeysComeOutAsTheJcaWroteThem() {
        val keys = listOf(GoldenMove.KEY_ASHA, GoldenMove.KEY_PRIYA)
        for (k in keys) {
            val pkcs8 = Base64.getDecoder().decode(k.private)
            assertArrayEquals(scalarOf(pkcs8), RefPkcs8.scalar(pkcs8))
            assertEquals(k.public, Base64.getEncoder().encodeToString(ReferenceCrypto.keyOf(RefPkcs8.scalar(pkcs8)!!).publicDer))
            assertArrayEquals(pkcs8, RefPkcs8.of(ReferenceCrypto.keyOf(RefPkcs8.scalar(pkcs8)!!)))
        }
    }

    @Test
    fun base64IsJavas() {
        for (n in 0 until 300) {
            val b = bytes(n % 70)
            val text = Base64.getEncoder().encodeToString(b)
            assertEquals(text, JavaBase64.encode(b))
            assertArrayEquals(b, JavaBase64.decode(text))
            assertArrayEquals(Base64.getDecoder().decode(text.trimEnd('=')), JavaBase64.decode(text.trimEnd('=')))
        }
        // Spoilt and odd texts: the same bytes, or the same refusal with the same words.
        val alphabet = "AQgw+/=\n -_éĀ"
        val cases = ArrayList<String>()
        for (n in 0 until 6000) cases += String(CharArray(random.nextInt(0, 10)) { alphabet[random.nextInt(alphabet.length)] })
        cases += listOf("", "A", "AA", "AA=", "AA==", "AAA=", "AAAA=", "AA==AA", "QR==", "QUJD", "QUJ", "Q", "====", "=", "QQ=a", "QQ==\n")
        for (c in cases) {
            val java = try {
                Base64.getDecoder().decode(c).let { "ok " + it.joinToString(",") }
            } catch (e: IllegalArgumentException) {
                "refused " + e.message
            }
            val ours = try {
                JavaBase64.decode(c).let { "ok " + it.joinToString(",") }
            } catch (e: IllegalArgumentException) {
                "refused " + e.message
            }
            assertEquals("\"$c\"", java, ours)
        }
    }
}
