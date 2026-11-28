package app.housemove

import app.housemove.data.hmacSha256
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The common HMAC-SHA256 (data/Hmac.kt) against the JVM's own, javax.crypto.Mac, on random keys and messages of every length that matters. */
class HmacJvmTest {
    @Test
    fun theSameAsJavaxCryptoMac() {
        val r = Random(4231)
        for (i in 0 until 3000) {
            // Keys around the block size (64) and beyond, messages from empty to several blocks.
            val key = ByteArray(if (i < 200) i else 1 + r.nextInt(200)).also { r.nextBytes(it) }
            val message = ByteArray(r.nextInt(300)).also { r.nextBytes(it) }
            val mac = Mac.getInstance("HmacSHA256")
            // javax.crypto refuses an empty key; HMAC itself pads it with zeros like any short key.
            val want = if (key.isEmpty()) {
                mac.init(SecretKeySpec(ByteArray(64), "HmacSHA256")); mac.doFinal(message)
            } else {
                mac.init(SecretKeySpec(key, "HmacSHA256")); mac.doFinal(message)
            }
            assertArrayEquals("key ${key.size} bytes, message ${message.size} bytes", want, hmacSha256(key, message))
        }
    }
}
