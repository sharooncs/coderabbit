package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Move IDs for moves from 2.0, open grants after the cutoff, 2.0 data loading on 2.1, and
 * backups going into the card of their move.
 */
class MoveIdTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var count = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()

    @After
    fun hangUp() = open.forEach { it.stop() }

    private fun sync(host: Store, joiner: Store): Pair<WifiSync.Result, WifiSync.Result> {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }) { results.add(it) }.also { open += it }
        val r = WifiSync.join(joiner, h.invite(local))
        return (results.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")) to r
    }

    /** A folder holding [data] and [device] as 2.0 saved them. */
    private fun saved20(data: String, device: String): File = tmp.newFolder("p" + count++).also {
        File(it, "data.json").writeText(data)
        File(it, "device.json").writeText(device)
    }

    private val move20 = """{"version":4,"move":{"name":"Maple Road","setupDone":true,"updatedAt":90},
        "people":[{"id":"asha","name":"Asha","role":"ORGANISER","letter":"A","joinedAt":10,"grants":[{"role":"ORGANISER","at":10}],"updatedAt":10},
                  {"id":"omar","name":"Omar","role":"ORGANISER","letter":"B","joinedAt":20,"grants":[{"role":"ORGANISER","at":20,"by":"asha"}],"updatedAt":20},
                  {"id":"ben","name":"Ben","role":"ORGANISER","letter":"C","joinedAt":25,"grants":[{"role":"ORGANISER","at":25}],"updatedAt":25}],
        "items":[{"id":"sofa","name":"Sofa","disposition":"SELL","price":80.0,"updatedAt":30}],
        "boxes":[{"id":"b1","label":"L001","updatedAt":31}]}"""

    // ---- moves from 2.0 -------------------------------------------------------------------

    @Test
    fun twoPhonesOfA20MoveUpgradingSeparatelyAgreeOnItsId() {
        val a = Store(saved20(move20, """{"name":"Asha","letter":"A","personId":"asha"}""")) { now++ }
        val o = Store(saved20(move20, """{"name":"Omar","letter":"B","personId":"omar"}""")) { now++ }
        assertTrue(a.identity.value.derived)
        assertEquals(a.moveId, o.moveId)
        // Ben, a partner let in with the plain code on 2.0, is still an Organiser: his open grant was made before the cutoff.
        for (s in listOf(a, o)) assertEquals(Role.ORGANISER, s.data.value.person("ben")!!.role)
        val (hosted, joined) = sync(a, o)
        assertTrue(hosted.error, hosted.ok)
        assertTrue(joined.error, joined.ok)
        assertEquals(a.moveId, o.moveId)
    }

    @Test
    fun twoPhonesThatWorkedOutDifferentIdsFallBackOnceToTheSameMoveCheckAndKeepBothAsAliases() {
        // Omar's phone last synced before Asha, who started the move, was known to it: only he and Hal are in its data.
        val late = """{"version":4,"move":{"name":"Maple Road","updatedAt":5},
            "people":[{"id":"omar","name":"Omar","role":"ORGANISER","letter":"B","joinedAt":20,"grants":[{"role":"ORGANISER","at":20}],"updatedAt":20},
                      {"id":"hal","name":"Hal","role":"HELPER","letter":"D","joinedAt":22,"grants":[{"role":"HELPER","at":22,"by":"omar"}],"updatedAt":22}],
            "boxes":[{"id":"b1","label":"L001","updatedAt":31}]}"""
        val a = Store(saved20(move20, """{"name":"Asha","letter":"A","personId":"asha"}""")) { now++ }
        val o = Store(saved20(late, """{"name":"Omar","letter":"B","personId":"omar"}""")) { now++ }
        assertNotEquals(a.moveId, o.moveId)
        val (hosted, joined) = sync(a, o)
        assertTrue(hosted.error, hosted.ok)
        assertTrue(joined.error, joined.ok)
        // Both now know the move by the ID worked out from its founder, and keep the other as an alias.
        assertEquals(a.moveId, o.moveId)
        assertTrue(o.identity.value.aliases.isNotEmpty())
        assertTrue(a.identity.value.matches(o.identity.value))
        assertTrue(sync(o, a).second.ok)

        // A move of its own on another phone, also from 2.0, shares nothing with this one: still refused.
        val other = """{"version":4,"move":{"name":"Elm Street","updatedAt":5},
            "people":[{"id":"zed","name":"Zed","role":"ORGANISER","letter":"Z","joinedAt":20,"grants":[{"role":"ORGANISER","at":20}],"updatedAt":20}],
            "boxes":[{"id":"z1","label":"Z1","updatedAt":31}]}"""
        val z = Store(saved20(other, """{"name":"Zed","letter":"Z","personId":"zed"}""")) { now++ }
        assertEquals(WifiSync.WRONG_MOVE, sync(a, z).second.error)
        assertNull(a.data.value.findByLabel("Z1"))
    }

    @Test
    fun dataSavedBy20LoadsWithNothingLost() {
        val s = Store(saved20(move20, """{"name":"Asha","letter":"A","nextSeq":4,"personId":"asha","lastSyncAt":77}""")) { now++ }
        val d = s.data.value
        assertEquals(SCHEMA, d.version)
        assertEquals(setOf("asha", "omar", "ben"), d.people.map { it.id }.toSet())
        assertEquals(Disposition.SELL, d.item("sofa")!!.disposition)
        assertEquals(80.0, d.item("sofa")!!.price!!, 0.0)
        assertNotNull(d.findByLabel("L001"))
        assertEquals("Maple Road", d.move.name)
        assertEquals(77L, s.device.value.lastSyncAt)
        assertEquals("A4", s.nextHandCode())
        // The phone is now on Asha's list of phones, using her letter.
        assertEquals(listOf(s.phone.id), s.me!!.phones.map { it.id })
        assertEquals("", s.me!!.phones.single().letter)
    }

    // ---- open grants --------------------------------------------------------------------------

    @Test
    fun openGrantsCountBeforeTheCutoffFromPhonesBefore21AndAfterItOnlyTheFounders() {
        val before = OPEN_GRANT_CUTOFF - 10_000
        val after = OPEN_GRANT_CUTOFF + 10_000
        fun person(id: String, at: Long, phone: String = "") = Person(id = id, name = id, role = Role.ORGANISER, grants = listOf(Grant(Role.ORGANISER, false, at, "", phone)), updatedAt = at)
        fun roleOf(people: List<Person>, id: String) = Roles(people).now(id)?.first
        val founder = person("founder", before - 1000, phone = "p1")
        // A partner let in by the plain code on 2.0, before the cutoff: an Organiser, as on 2.0.
        assertEquals(Role.ORGANISER, roleOf(listOf(founder, person("partner", before)), "partner"))
        // The same after the cutoff: not taken in.
        assertNull(roleOf(listOf(founder, person("late", after)), "late"))
        // An open grant made by a 2.1 phone never counts unless it is the founder's.
        assertNull(roleOf(listOf(founder, person("restored", before, phone = "p2")), "restored"))
        assertEquals("founder", Roles(listOf(founder, person("restored", before, phone = "p2"))).founder)
        // The founder's counts, whenever it was made.
        val lateFounder = person("f2", after, phone = "p3")
        assertEquals(Role.ORGANISER, roleOf(listOf(lateFounder), "f2"))
        // A new move on 2.1: its founder's grant carries the phone's ID, and counts.
        val s = Store(tmp.newFolder("fresh")) { now++ }
        assertEquals(s.phone.id, s.me!!.grants.single().phone)
        assertEquals(Role.ORGANISER, s.myRole)
        assertEquals(s.data.value.derivedMoveId(), s.moveId)
        assertFalse(s.identity.value.derived)
    }

    // ---- restoring into the right move ------------------------------------------------------

    private fun backupOf(s: Store) = ByteArrayOutputStream().also { Backup.export(s, it) }.toByteArray()

    @Test
    fun aBackupGoesIntoTheCardOfItsMoveOrANewOneWhereThePhoneIsWhoeverMadeIt() {
        val root = tmp.newFolder("mine")
        val m = Moves(File(root, "files"), File(root, "nb"), { "Asha's phone" }) { now++ }
        val maple = m.create("Maple Road", yourName = "Asha")
        val elm = m.create("Elm Street")
        maple.createBox("M1", null)
        val zip = backupOf(maple)
        maple.createBox("M2", null)
        elm.createBox("E1", null)
        // Restored on the same phone, with Elm Street in use: it goes into Maple Road's card, and nothing is lost there.
        val r = m.restore({ ByteArrayInputStream(zip) })
        assertTrue(r.reason, r.ok)
        assertEquals(2, m.cards.value.size)
        assertEquals(maple, m.current.value)
        assertNotNull(maple.data.value.findByLabel("M2"))
        assertNull(elm.data.value.findByLabel("M1"))

        // On a new phone: a new card, and the phone is Asha, whose phone made it, with a letter of its own.
        val root2 = tmp.newFolder("new")
        val n = Moves(File(root2, "files"), File(root2, "nb"), { "New phone" }) { now++ }
        n.create("Tried it out").createBox("T1", null)
        val r2 = n.restore({ ByteArrayInputStream(zip) })
        assertTrue(r2.reason, r2.ok)
        assertEquals(2, n.cards.value.size)
        val s = n.current.value!!
        assertEquals(maple.moveId, s.moveId)
        assertEquals(maple.me!!.id, s.me!!.id)
        assertEquals(Role.ORGANISER, s.myRole)
        assertNotNull(s.data.value.findByLabel("M1"))
        assertNull(s.data.value.findByLabel("T1"))
        assertNotEquals(maple.device.value.letter, s.device.value.letter)
        // Restoring it again goes into that card, not a third one.
        assertTrue(n.restore({ ByteArrayInputStream(zip) }).ok)
        assertEquals(2, n.cards.value.size)
        // Not a backup: refused in words, and no card is left behind.
        assertEquals(Backup.NOT_A_BACKUP, n.restore({ ByteArrayInputStream("nope".toByteArray()) }).reason)
        assertEquals(2, n.cards.value.size)
    }
}
