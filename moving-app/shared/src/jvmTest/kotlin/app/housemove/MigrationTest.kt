package app.housemove

import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.sync.AutoListener
import app.housemove.sync.AutoSession
import app.housemove.sync.AutoSlot
import app.housemove.sync.LocalLan
import app.housemove.sync.Pin
import app.housemove.sync.WifiSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * From 2.3 to 2.4 (design/encryption.md, "Migration"): two phones on 2.3 sync by themselves with their Wi-Fi keys
 * (HMAUTO1, as 2.3 shipped it: Old23.kt). One updates: opening the move deletes 2.3's ring.json and pins the other
 * phones' keys from the move; it no longer answers 2.3's handshake, and the two sync by code, unencrypted, both saying so.
 * When the other updates too, they sync by themselves at once, encrypted, with no code: their keys were in the move.
 */
class MigrationTest {
    private val root = Files.createTempDirectory("hm-migration").toFile()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()
    private val stops = ArrayList<() -> Unit>()
    private val older = java.util.Collections.synchronizedSet(HashSet<String>())

    @After
    fun cleanUp() {
        stops.forEach { it() }
        phones.forEach { it.close() }
        WifiSync.olderPhone = { false }
        Meetings23.clear()
        root.deleteRecursively()
    }

    private fun codesDir(p: AutoPhone): File {
        val card = p.moves.cards.value.first { it.matches(p.store.moveId) }
        return File(root, "${p.name}/no-backup/moves/${card.folder}")
    }

    private fun ring(p: AutoPhone, s: Store = p.store) = Ring23.of(s, codesDir(p))

    @Test
    fun twoPhonesOn23UpdateOneAfterTheOther() {
        WifiSync.olderPhone = { it in older }
        val asha = AutoPhone(root, "Asha", lan, engineOn = false).also { phones += it }
        val priya = AutoPhone(root, "Priya", lan, engineOn = false).also { phones += it }
        asha.found("Maple Road")
        older += asha.store.phone.id
        older += priya.moves.phone.id
        // Both on 2.3: the invite is an eight-character one, the join is plain, and 2.3 notes the meeting.
        val host = WifiSync.host(asha.store, letNewPhonesIn = false) {}
        stops += { host.stop() }
        val person = asha.store.addPerson("Priya", Role.DECIDER).valueOrNull()!!
        val invite = host.inviteFor(person.id, listOf("127.0.0.1")).valueOrNull()!!
        assertEquals(8, invite.token.length)
        val joined = priya.moves.join(priya.moves.readInvite(invite.encode())!!)
        assertTrue(joined.error, joined.ok && joined.plain)
        Meetings23.note(asha.store.phone.id, asha.store.identity.value.all, priya.store.phone.id, invite.token)
        Meetings23.note(priya.store.phone.id, priya.store.identity.value.all, asha.store.phone.id, invite.token)

        // 2.3 syncs by itself: the meeting, then Wi-Fi keys and pair keys in ring.json.
        val listen23 = Listener23({ asha.store }, { ring(asha, it) })
        stops += { listen23.stop() }
        val d = Auto23.dial(priya.store, ring(priya), "127.0.0.1", listen23.port)
        assertEquals("OK", d.word)
        assertTrue(Auto23.synced(d.result))
        val ringFile = File(codesDir(asha), "ring.json")
        assertTrue("2.3 keeps its keys in ring.json", ringFile.isFile && ring(asha).keys(asha.store.data.value).isNotEmpty())
        assertTrue(ring(priya).pairs().containsKey(asha.store.phone.id))
        // Priya's phone's key was on its entry all along (since 2.1).
        val priyaKey = priya.store.phone.keys.publicText
        assertEquals(priyaKey, asha.store.data.value.people.flatMap { it.phones }.first { it.id == priya.store.phone.id }.key)

        // Asha's phone updates to 2.4: HouseMove starts again, and opening the move deletes the ring and pins from the move.
        listen23.stop()
        older -= asha.store.phone.id
        asha.restart()
        assertFalse("ring.json is gone", ringFile.exists())
        val pins = asha.store.pins.learn(asha.store.data.value, asha.store.phone.id)
        assertEquals(priyaKey, pins[priya.store.phone.id]?.key)
        assertEquals(Pin.MOVE, pins[priya.store.phone.id]?.how)

        // Priya's 2.3 phone tries its handshake on Asha's 2.4 listener: not answered.
        val listener = AutoListener(true, { asha.store }, AutoSlot(), {}, { _, _ -> }).also { it.start() }
        stops += { listener.stop() }
        assertEquals("LOST", Auto23.dial(priya.store, ring(priya), "127.0.0.1", listener.port).word)
        // And Asha's phone does not dial a phone it does not see advertise 2.4's name; by code, the two sync unencrypted.
        val r = asha.codeSync(priya)
        assertTrue(r.error, r.ok && r.plain)
        assertTrue(asha.store.pins.olderPhones(asha.store.data.value, asha.store.phone.id).contains(priya.store.phone.id))

        // Priya's phone updates too: its ring goes, and the two sync by themselves at once, encrypted, with no code.
        older -= priya.store.phone.id
        priya.restart()
        assertFalse(File(codesDir(priya), "ring.json").exists())
        val again = AutoSession.dial(priya.store, "127.0.0.1", listener.port, asha.store.phone.id)
        assertEquals("OK", again.word)
        assertTrue(again.result!!.ok && !again.result.plain)
        assertTrue(asha.store.pins.speaksV2(priya.store.phone.id))
        assertTrue(asha.store.pins.olderPhones(asha.store.data.value, asha.store.phone.id).isEmpty())
        // Their next code sync is encrypted too, and pins each other as met.
        val code = asha.codeSync(priya)
        assertTrue(code.error, code.ok && !code.plain)
        assertEquals(Pin.MET, asha.store.pins.pinOf(priya.store.phone.id)?.how)
        assertNotNull(priya.store.pins.pinOf(asha.store.phone.id))
        assertNull(priya.store.pins.pinOf(priya.store.phone.id))
    }
}
