package app.housemove

import app.housemove.data.hexBytes
import app.housemove.data.hmacSha256
import app.housemove.data.sameBytes
import app.housemove.data.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HMAC-SHA256 as syncing by itself works it out (data/Hmac.kt, on top of the platform's SHA-256), against RFC 4231's
 * test cases: on the JVM in every test run, and on the iPhone simulator in CI, where SHA-256 is CommonCrypto's.
 */
class HmacCommonTest {
    private fun bytes(n: Int, b: Int) = ByteArray(n) { b.toByte() }

    private fun text(s: String) = s.encodeToByteArray()

    @Test
    fun rfc4231() {
        val cases = listOf(
            Triple(bytes(20, 0x0b), text("Hi There"), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            Triple(text("Jefe"), text("what do ya want for nothing?"), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            Triple(bytes(20, 0xaa), bytes(50, 0xdd), "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            Triple(ByteArray(25) { (it + 1).toByte() }, bytes(50, 0xcd), "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
            // Test case 5 is given truncated to 128 bits; whole, it is this.
            Triple(bytes(20, 0x0c), text("Test With Truncation"), "a3b6167473100ee06e0c796c2955552bfa6f7c0a6a8aef8b93f860aab0cd20c5"),
            // A key longer than a block is hashed first.
            Triple(bytes(131, 0xaa), text("Test Using Larger Than Block-Size Key - Hash Key First"), "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
            Triple(
                bytes(131, 0xaa),
                text("This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm."),
                "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            ),
        )
        for ((n, c) in cases.withIndex()) assertEquals(c.third, hmacSha256(c.first, c.second).toHex(), "RFC 4231 test case ${n + 1}")
        // An empty key and message, and a key of exactly one block.
        assertEquals("b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad", hmacSha256(ByteArray(0), ByteArray(0)).toHex())
        assertEquals("c668fc54e1a2a267c502413f4580c5bb5d9229fef5fb9b4ff5f4ce5e76299783", hmacSha256(bytes(64, 1), text("x")).toHex())
    }

    @Test
    fun bytesAndHex() {
        assertTrue(sameBytes(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(sameBytes(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(sameBytes(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertEquals("00ff7f80", hexBytes("00FF7f80")!!.toHex())
        assertNull(hexBytes("abc"))
        assertNull(hexBytes("zz"))
        assertEquals(0, hexBytes("")!!.size)
    }
}
