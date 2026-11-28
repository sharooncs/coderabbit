package app.housemove

import app.housemove.data.SCHEMA
import app.housemove.data.Store
import app.housemove.sync.WifiSync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A phone with another version of HouseMove that comes to this phone's code says which phone it is in its hello, as
 * every phone from 2.1 does; the hosting phone now keeps that name for its screen ([WifiSync.Result.peerDevice]), so an
 * iPhone's person is told to update it from TestFlight rather than to share Android's app with it (design/ios-plan.md,
 * step 5). What the hosting phone answers is the same, byte for byte: VERSION and its own data layout.
 */
class OtherVersionPeerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** What the host answered, and what its screen was told, when a phone on [schema] calling itself [device] came. */
    private fun cameWith(schema: Int, device: String): Pair<ByteArray, WifiSync.Result> {
        val a = Store(tmp.newFolder())
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val host = WifiSync.host(a) { results.add(it) }
        try {
            Socket("127.0.0.1", host.port).use { s ->
                val out = DataOutputStream(s.getOutputStream())
                out.writeUTF("HMSYNC3"); out.writeUTF(host.token); out.writeUTF("Priya"); out.writeUTF("P"); out.writeInt(schema)
                out.writeUTF("")
                out.writeUTF("""{"phoneId":"phone-of-priya","deviceName":"$device","moveId":"","name":"Priya"}""")
                out.flush()
                val din = DataInputStream(s.getInputStream())
                val answer = ByteArrayOutputStream()
                DataOutputStream(answer).apply {
                    writeUTF(din.readUTF())
                    writeInt(din.readInt())
                }
                return answer.toByteArray() to results.poll(5, TimeUnit.SECONDS)!!
            }
        } finally {
            host.stop()
            a.close()
        }
    }

    @Test
    fun anOlderIphoneIsKnownByItsName() {
        val (answer, r) = cameWith(SCHEMA - 1, "iPhone")
        assertEquals(WifiSync.VERSION_MISMATCH, r.error)
        assertTrue("the other phone is older", r.shareApp)
        assertEquals("iPhone", r.peerDevice)
        assertEquals("Priya", r.peerName)
        // The answer is what it always was: VERSION and this phone's data layout.
        val expected = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF("VERSION"); writeInt(SCHEMA) } }.toByteArray()
        assertArrayEquals(expected, answer)
    }

    @Test
    fun aNewerAndroidPhoneToo() {
        val (_, r) = cameWith(SCHEMA + 1, "Pixel 8")
        assertEquals(WifiSync.VERSION_MISMATCH, r.error)
        assertEquals(false, r.shareApp)
        assertEquals(SCHEMA + 1, r.peerSchema)
        assertEquals("Pixel 8", r.peerDevice)
    }
}
