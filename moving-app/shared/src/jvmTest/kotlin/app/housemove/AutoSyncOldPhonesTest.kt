package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.Role
import app.housemove.sync.LocalLan
import app.housemove.sync.Meetings
import app.housemove.sync.WifiSync
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phones on 2.1.1 and 2.2 beside phones that sync by themselves (design/auto-sync.md, "Old phones"). An old phone does
 * not advertise, so no new phone ever dials it, and it never hears a word of the handshake; code sync goes on in both
 * directions, with exactly the messages it always had. (The golden fixtures prove the bytes; this proves that phones
 * holding keys and syncing by themselves add nothing to a code sync.)
 */
class AutoSyncOldPhonesTest {
    private val root = Files.createTempDirectory("hm-old-phones").toFile()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()

    @After
    fun cleanUp() {
        phones.forEach { it.close() }
        Meetings.clear()
        root.deleteRecursively()
    }

    private fun phone(name: String, engineOn: Boolean) = AutoPhone(root, name, lan, engineOn).also { phones += it }

    /** The fields of 2.1's hello ([WifiSync]'s Hello), as 2.1.1 and 2.2 write and read it. */
    private val helloFields = setOf(
        "claim", "phoneId", "deviceName", "moveId", "aliases", "derived", "ids", "moveName", "elsewhere", "labelsElsewhere", "name", "person",
        "youAre", "key", "keyAt",
    )

    @Test
    fun anOldPhoneIsNeverDialledAndCodeSyncWorksBothWays() {
        val asha = phone("Asha", engineOn = true)
        // Priya's phone runs 2.2: it has no engine, and so does not advertise.
        val priya = phone("Priya", engineOn = false)
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        // Priya's phone shows its code; whatever connects to it is noted, as the screen would say it.
        val heard = CopyOnWriteArrayList<WifiSync.Result>()
        val priyaHost = WifiSync.host(priya.store, letNewPhonesIn = false) { heard += it }
        try {
            // Asha's phone looks for the move's phones a good while: it finds none, and connects to nothing.
            holds("Asha's phone dialled Priya's 2.2 phone", 2_500) { heard.isEmpty() }
            assertTrue(asha.engine.status.value.phones == 0)

            // Code sync, both ways.
            priya.store.createItem("Lamp", null)
            val r1 = WifiSync.join(asha.store, priyaHost.invite(listOf("127.0.0.1")), letNewPhonesIn = false)
            assertTrue("Asha scans Priya's code: ${r1.error}", r1.ok)
            assertTrue(asha.store.data.value.liveItems.any { it.name == "Lamp" })
            asha.store.createItem("Sofa", null)
            val r2 = asha.codeSync(priya)
            assertTrue("Priya scans Asha's code: ${r2.error}", r2.ok)
            assertTrue(priya.store.data.value.liveItems.any { it.name == "Sofa" })
            // Only the one sync Priya's code was scanned for was ever served there.
            assertEquals(1, heard.size)
            assertTrue(heard.single().ok)
        } finally {
            priyaHost.stop()
        }
    }

    @Test
    fun phonesThatSyncByThemselvesSendTheSameCodeSyncAsBefore() {
        val asha = phone("Asha", engineOn = true)
        val priya = phone("Priya", engineOn = true)
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        waitUntil("the two hold keys") { priya.store.ring.keys(priya.store.data.value).isNotEmpty() && asha.store.ring.pairs().isNotEmpty() }
        priya.store.createItem("Lamp", null)

        // A code sync between them, through a wire that keeps every byte: the eight messages of 2.1.1, and nothing else.
        val heard = CopyOnWriteArrayList<WifiSync.Result>()
        val host = WifiSync.host(asha.store, letNewPhonesIn = false) { heard += it }
        val wire = Wire(host.port)
        try {
            val r = WifiSync.join(priya.store, host.invite(listOf("127.0.0.1")).copy(port = wire.port), letNewPhonesIn = false)
            assertTrue(r.error, r.ok)
            waitUntil("the host's result") { heard.isNotEmpty() }
            assertEquals(1, wire.connections)
            assertEquals(8, wire.phases)
            // The joiner's hello and the host's answer say only what 2.1 says.
            DataInputStream(ByteArrayInputStream(wire.bytes(1))).use { d ->
                assertEquals("HMSYNC3", d.readUTF())
                d.readUTF(); d.readUTF(); d.readUTF()
                assertEquals(5, d.readInt())
                d.readUTF()
                val hello = AppJson.parseToJsonElement(d.readUTF()).jsonObject
                assertTrue("joiner's hello: ${hello.keys}", helloFields.containsAll(hello.keys))
            }
            DataInputStream(ByteArrayInputStream(wire.bytes(2))).use { d ->
                assertEquals("OK", d.readUTF())
                d.readUTF(); d.readUTF()
                val hello = AppJson.parseToJsonElement(d.readUTF()).jsonObject
                assertTrue("host's hello: ${hello.keys}", helloFields.containsAll(hello.keys))
            }
            // And the photo lists end with the last file: nothing is said after them.
            assertTrue(wire.bytes(8)!!.isNotEmpty())
        } finally {
            wire.close()
            host.stop()
        }
        assertTrue(File(root, "Asha").isDirectory)
    }
}
