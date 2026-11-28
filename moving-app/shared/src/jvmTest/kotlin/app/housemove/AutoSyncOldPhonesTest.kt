package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.Role
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phones on 2.1.1, 2.2 and 2.3 beside phones on 2.4 (design/encryption.md, "Old phones"). An old phone is never dialled to
 * sync by itself (2.4 advertises and looks for `HN-…` names only), and code sync goes on in both directions, with exactly
 * the messages 2.1.1 had, unencrypted, and both phones say so ([WifiSync.Result.plain]). An old phone stands in through
 * [WifiSync.olderPhone]: it shows eight-character codes and sends the plain hello whatever it scans. (The golden fixtures
 * prove the bytes; this proves that a 2.4 phone adds nothing to a code sync with an old one, either way round.)
 */
class AutoSyncOldPhonesTest {
    private val root = Files.createTempDirectory("hm-old-phones").toFile()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()

    @After
    fun cleanUp() {
        phones.forEach { it.close() }
        WifiSync.olderPhone = { false }
        root.deleteRecursively()
    }

    private fun phone(name: String, engineOn: Boolean) = AutoPhone(root, name, lan, engineOn).also { phones += it }

    /** The fields of 2.1's hello ([WifiSync]'s Hello), as 2.1.1 to 2.3 write and read it. */
    private val helloFields = setOf(
        "claim", "phoneId", "deviceName", "moveId", "aliases", "derived", "ids", "moveName", "elsewhere", "labelsElsewhere", "name", "person",
        "youAre", "key", "keyAt",
    )

    @Test
    fun anOldPhoneIsNeverDialledAndCodeSyncWorksBothWays() {
        val asha = phone("Asha", engineOn = true)
        // Priya's phone runs 2.2: it has no engine, and so does not advertise.
        val priya = phone("Priya", engineOn = false)
        val old = priya.moves.phone.id
        WifiSync.olderPhone = { it == old }
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        // Priya's phone shows its code: eight characters, as 2.2 makes them. Whatever connects to it is noted.
        val heard = CopyOnWriteArrayList<WifiSync.Result>()
        val priyaHost = WifiSync.host(priya.store, letNewPhonesIn = false) { heard += it }
        try {
            assertEquals(8, priyaHost.token.length)
            // Asha's phone looks for the move's phones a good while: it finds none, and connects to nothing.
            holds("Asha's phone dialled Priya's 2.2 phone", 2_500) { heard.isEmpty() }
            assertTrue(asha.engine.status.value.phones == 0)

            // Code sync, both ways, and both phones say it was not encrypted.
            priya.store.createItem("Lamp", null)
            val r1 = WifiSync.join(asha.store, priyaHost.invite(listOf("127.0.0.1")), letNewPhonesIn = false)
            assertTrue("Asha scans Priya's code: ${r1.error}", r1.ok && r1.plain)
            assertTrue(asha.store.data.value.liveItems.any { it.name == "Lamp" })
            asha.store.createItem("Sofa", null)
            val asHost = CopyOnWriteArrayList<WifiSync.Result>()
            val ashaHost = WifiSync.host(asha.store, letNewPhonesIn = false) { asHost += it }
            try {
                assertTrue("Asha's phone shows an E2 code", WifiSync.isE2(ashaHost.token))
                val r2 = WifiSync.join(priya.store, ashaHost.invite(listOf("127.0.0.1")), letNewPhonesIn = false)
                assertTrue("Priya scans Asha's code: ${r2.error}", r2.ok && r2.plain)
                waitUntil("Asha's phone said how it went") { asHost.isNotEmpty() }
                assertTrue(asHost.single().ok && asHost.single().plain)
                assertEquals(
                    "Priya's phone has an older HouseMove, so this sync was not encrypted. Sync on a Wi-Fi you trust, or update HouseMove on Priya's phone first.",
                    WifiSync.notEncrypted(asHost.single().peerName),
                )
            } finally {
                ashaHost.stop()
            }
            assertTrue(priya.store.data.value.liveItems.any { it.name == "Sofa" })
            // Only the one sync Priya's code was scanned for was ever served there.
            assertEquals(1, heard.size)
            assertTrue(heard.single().ok && heard.single().plain)
            // Asha's phone knows why Priya's does not sync by itself.
            waitUntil("Asha's phone names the older phone") { asha.engine.status.value.older == "Priya" }
        } finally {
            priyaHost.stop()
        }
    }

    /** The joiner's hello and the host's answer, through [wire], say only what 2.1 says, in 2.1.1's eight messages. */
    private fun assertTheEightMessages(wire: Wire, token: String) {
        assertEquals(1, wire.connections)
        assertEquals(8, wire.phases)
        DataInputStream(ByteArrayInputStream(wire.bytes(1))).use { d ->
            assertEquals("HMSYNC3", d.readUTF())
            assertEquals(token, d.readUTF())
            d.readUTF(); d.readUTF()
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
    }

    @Test
    fun aCodeSyncWithAnOlderPhoneIsStill211sEightMessagesEitherWay() {
        val asha = phone("Asha", engineOn = false)
        val priya = phone("Priya", engineOn = false)
        val old = priya.moves.phone.id
        WifiSync.olderPhone = { it == old }
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        priya.store.createItem("Lamp", null)

        // The 2.4 phone scans the older phone's code.
        val heard = CopyOnWriteArrayList<WifiSync.Result>()
        val oldHost = WifiSync.host(priya.store, letNewPhonesIn = false) { heard += it }
        val wire = Wire(oldHost.port)
        try {
            val r = WifiSync.join(asha.store, oldHost.invite(listOf("127.0.0.1")).copy(port = wire.port), letNewPhonesIn = false)
            assertTrue(r.error, r.ok && r.plain)
            waitUntil("the old host's result") { heard.isNotEmpty() }
            assertTheEightMessages(wire, oldHost.token)
        } finally {
            wire.close()
            oldHost.stop()
        }

        // The older phone scans the 2.4 phone's E2 code: it sends its plain hello with that code, and is answered as 2.3 did.
        val heard2 = CopyOnWriteArrayList<WifiSync.Result>()
        val newHost = WifiSync.host(asha.store, letNewPhonesIn = false) { heard2 += it }
        val wire2 = Wire(newHost.port)
        try {
            val r = WifiSync.join(priya.store, newHost.invite(listOf("127.0.0.1")).copy(port = wire2.port), letNewPhonesIn = false)
            assertTrue(r.error, r.ok)
            waitUntil("the 2.4 host's result") { heard2.isNotEmpty() }
            assertTrue(heard2.single().ok && heard2.single().plain)
            assertTheEightMessages(wire2, newHost.token)
        } finally {
            wire2.close()
            newHost.stop()
        }
        assertFalse(asha.store.pins.speaksV2(priya.store.phone.id))
    }
}
