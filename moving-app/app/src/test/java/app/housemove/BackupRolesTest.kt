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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Backups on a move with people in it: who may restore, and what a restore may never undo. */
class BackupRolesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var folders = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()

    private fun phone(name: String = ""): Store =
        Store(tmp.newFolder("p" + folders++)) { now++ }.also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    @After
    fun hangUp() = open.forEach { it.stop() }

    private fun sync(host: Store, joiner: Store): Pair<WifiSync.Result, WifiSync.Result> {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }) { results.add(it) }.also { open += it }
        val r = WifiSync.join(joiner, h.invite(local))
        return (results.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")) to r
    }

    private fun synced(host: Store, joiner: Store) {
        val (a, b) = sync(host, joiner)
        assertTrue(a.error, a.ok)
        assertTrue(b.error, b.ok)
    }

    private fun invited(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }) { results.add(it) }.also { open += it }
        val phone = phone(name)
        val r = WifiSync.join(phone, h.inviteFor(p.id, local).valueOrNull()!!)
        assertTrue(r.error, r.ok)
        assertTrue(results.poll(15, TimeUnit.SECONDS)!!.ok)
        return phone
    }

    private fun backupOf(s: Store) = ByteArrayOutputStream().also { Backup.export(s, it) }.toByteArray()

    private fun refusedRestoring(s: Store, zip: ByteArray, replace: Boolean = false): String =
        try {
            Backup.import(s, ByteArrayInputStream(zip), replace)
            throw AssertionError("the restore went through")
        } catch (e: IllegalArgumentException) {
            e.message!!
        }

    private fun Store.photo(ownerId: String, kind: PhotoKind): File {
        val f = newPhotoFile().apply { writeBytes(ByteArray(700) { 4 }) }
        addPhoto(ownerId, kind, f)
        return f
    }

    @Test
    fun onlyAnOrganiserRestoresAndARefusedRestoreLeavesNothingBehind() {
        val asha = phone("Asha")
        val box = asha.createBox("L001", null)
        asha.photo(box.id, PhotoKind.CONTENTS)
        val dee = invited(asha, "Dee", Role.DECIDER)
        val hal = invited(asha, "Hal", Role.HELPER)
        val zip = backupOf(asha)
        for (s in listOf(dee, hal)) {
            val before = s.data.value
            val files = s.photoDir.list()!!.toSet()
            assertEquals("Only an Organiser can restore a backup.", refusedRestoring(s, zip))
            assertEquals("Only an Organiser can restore a backup.", refusedRestoring(s, zip, replace = true))
            assertEquals(before, s.data.value)
            assertEquals(files, s.photoDir.list()!!.toSet())
            assertEquals("Only an Organiser can restore a backup.", s.whyNotRestore(asha.data.value))
        }
    }

    @Test
    fun restoringAnOlderBackupOfTheSameMoveGivesNobodyBackTheRightsTheyHaveLost() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val sofa = asha.createItem("Sofa", null).id
        val dee = invited(asha, "Dee", Role.DECIDER)
        val omar = invited(asha, "Omar", Role.ORGANISER)
        val early = backupOf(asha) // Dee is a Decider and Omar an Organiser in this one
        // Since then: Dee is removed, Omar made a Helper, and a new Helper added and removed again.
        assertTrue(asha.removePerson(dee.me!!.id).ok)
        assertTrue(asha.setRole(omar.me!!.id, Role.HELPER).ok)
        val gus = invited(asha, "Gus", Role.HELPER)
        assertTrue(asha.removePerson(gus.me!!.id).ok)

        for (replace in listOf(false, true)) {
            Backup.import(asha, ByteArrayInputStream(early), replace)
            val d = asha.data.value
            assertTrue("replace=$replace", d.person(dee.me!!.id)!!.removed)
            assertEquals(Role.HELPER, d.person(omar.me!!.id)!!.role)
            assertTrue(d.person(gus.me!!.id)!!.removed)
            assertEquals(Role.ORGANISER, asha.myRole)
            assertNotNull(d.item(sofa))
            // Their phones, which never heard, are turned away as before.
            assertEquals(WifiSync.REMOVED_SELF, sync(asha, dee).second.error)
            assertEquals(WifiSync.REMOVED_SELF, sync(asha, gus).second.error)
            // Omar's phone syncs, and learns he is a Helper now: nothing an Organiser may do counts from him.
            synced(asha, omar)
            assertEquals(Role.HELPER, omar.myRole)
            assertNotNull(omar.decide(sofa, Disposition.BIN).reason)
        }
    }

    @Test
    fun aBackupOfAnotherMoveIsNotMixedIntoASharedMoveButComesOntoAPhoneOnItsOwn() {
        val other = phone("Zed")
        other.setMove { it.copy(name = "Elm Street", setupDone = true) }
        other.createBox("Z1", null)
        val elm = backupOf(other)

        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        asha.createBox("L001", null)
        invited(asha, "Hal", Role.HELPER)
        val before = asha.data.value
        for (replace in listOf(false, true)) {
            val why = refusedRestoring(asha, elm, replace)
            assertTrue(why, why.startsWith("That backup is of a different move (“Elm Street”)."))
            assertEquals(before, asha.data.value)
        }
        // A phone on its own takes it in: that is how a move comes onto a new phone.
        val fresh = phone("New")
        Backup.import(fresh, ByteArrayInputStream(elm), replace = true)
        assertEquals("Elm Street", fresh.data.value.move.name)
        assertNotNull(fresh.data.value.findByLabel("Z1"))
        // A backup from before 2.0 has no people: it is the same move when it has the same things in it.
        val old13 = """{"version":3,"move":{"name":"Maple Road"},"boxes":[{"id":"${before.liveBoxes[0].id}","label":"L001","contents":"from 1.3","updatedAt":5}]}"""
        val zip13 = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("data.json")); z.write(old13.toByteArray()); z.closeEntry() }
        }.toByteArray()
        Backup.import(asha, ByteArrayInputStream(zip13))
        assertNotNull(asha.data.value.findByLabel("L001"))
        val stranger13 = """{"version":3,"move":{"name":"Oak Lane"},"boxes":[{"id":"zz","label":"Q1","updatedAt":5}]}"""
        val zipOther13 = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("data.json")); z.write(stranger13.toByteArray()); z.closeEntry() }
        }.toByteArray()
        assertTrue(refusedRestoring(asha, zipOther13).startsWith("That backup is of a different move"))
    }

    @Test
    fun aBackupFromAHelpersPhoneRestoredOnAnOrganisersLosesNothing() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val tv = asha.createItem("TV", null).id
        asha.put(Expense(id = "e1", title = "Van", amount = 120.0, planned = true))
        val receipt = asha.newPhotoFile().apply { writeBytes(ByteArray(500) { 2 }) }
        assertTrue(asha.attachReceipt("e1", receipt).ok)
        val serial = asha.photo(tv, PhotoKind.SERIAL)
        val dee = invited(asha, "Dee", Role.DECIDER)
        synced(asha, dee)
        assertTrue(dee.suggestCost(Expense(id = "e2", title = "Tape", amount = 6.0)).ok)
        synced(asha, dee)
        val hal = invited(asha, "Hal", Role.HELPER)
        val box = hal.createBox("H1", null).id
        val zip = backupOf(hal)
        // Hal's phone holds none of these, so neither does his backup.
        assertTrue(hal.data.value.expenses.isEmpty())
        val costs = asha.data.value.expenses.toSet()
        val hidden = asha.data.value.photos.filter { it.kind in HIDDEN_FROM_HELPERS }.toSet()
        val costSuggestions = asha.data.value.proposals.filter { it.kind == ProposalKind.COST }.toSet()
        assertEquals(2, hidden.size)
        assertEquals(1, costSuggestions.size)

        for (replace in listOf(false, true)) {
            Backup.import(asha, ByteArrayInputStream(zip), replace)
            val d = asha.data.value
            assertEquals("replace=$replace", costs, d.expenses.toSet())
            assertEquals(hidden, d.photos.filter { it.kind in HIDDEN_FROM_HELPERS }.toSet())
            assertEquals(costSuggestions, d.proposals.filter { it.kind == ProposalKind.COST }.toSet())
            assertTrue(File(asha.photoDir, receipt.name).isFile)
            assertTrue(File(asha.photoDir, serial.name).isFile)
            assertNotNull(d.box(box))
        }
    }

    @Test
    fun aFileThatIsNotABackupIsRefusedInWordsAndLeavesNoFilesBehind() {
        val asha = phone("Asha")
        val zipWithBadData = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z ->
                z.putNextEntry(ZipEntry("photos/x.jpg")); z.write(ByteArray(100)); z.closeEntry()
                z.putNextEntry(ZipEntry("data.json")); z.write("{ not json".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val newer = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("data.json")); z.write("""{"version":${SCHEMA + 1}}""".toByteArray()); z.closeEntry() }
        }.toByteArray()
        for ((zip, said) in listOf(zipWithBadData to Backup.NOT_A_BACKUP, "PK\u0003\u0004garbage".toByteArray() to Backup.NOT_A_BACKUP, ByteArray(0) to Backup.NOT_A_BACKUP, newer to Backup.TOO_NEW)) {
            assertEquals(said, refusedRestoring(asha, zip))
            assertTrue(asha.photoDir.list()!!.isEmpty())
        }
        // A photo in a backup whose record is not in it is not kept either.
        val stray = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z ->
                z.putNextEntry(ZipEntry("data.json")); z.write("{}".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("photos/stray.jpg")); z.write(ByteArray(100)); z.closeEntry()
            }
        }.toByteArray()
        assertEquals(0, Backup.import(asha, ByteArrayInputStream(stray)).photos)
        assertTrue(asha.photoDir.list()!!.isEmpty())
    }
}
