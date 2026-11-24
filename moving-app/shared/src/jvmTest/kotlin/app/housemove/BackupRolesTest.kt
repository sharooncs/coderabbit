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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backups on a move with people in it: who may restore, and what a restore may never undo. */
class BackupRolesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var folders = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()

    /** For [rewritten]: leave the backup's note of who made it as it is. */
    private val KEEP = "\u0000keep"

    private fun phone(name: String = ""): Store =
        Store(tmp.newFolder("p" + folders++)) { now++ }.also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    @After
    fun hangUp() = open.forEach { it.stop() }

    private fun sync(host: Store, joiner: Store, letNewPhonesIn: Boolean = true): Pair<WifiSync.Result, WifiSync.Result> {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }, letNewPhonesIn) { results.add(it) }.also { open += it }
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

    /**
     * A backup of [s]. Since 2.1 a Helper's phone makes none ([Store.whyNoBackup]); one made by a Helper's phone on 2.0
     * can still be restored, so for those this writes it as 2.0 did.
     */
    private fun backupOf(s: Store) = ByteArrayOutputStream().also { out ->
        if (s.whyNoBackup() == null) Backup.export(s, out) else Backup.write(s, out)
    }.toByteArray()

    @Test
    fun aHelpersPhoneMakesNoBackup() {
        val asha = phone("Asha")
        val hal = invited(asha, "Hal", Role.HELPER)
        assertNotNull(hal.whyNoBackup())
        val e = assertThrows(java.io.IOException::class.java) { Backup.export(hal, ByteArrayOutputStream()) }
        assertEquals(hal.whyNoBackup(), e.message)
        assertNull(asha.whyNoBackup())
    }

    /**
     * [zip] with its data changed by [data], and its note of who made it kept ([KEEP]), replaced by [about], or left
     * out when that is null, as in a backup from before 2.0.
     */
    private fun rewritten(zip: ByteArray, about: String? = KEEP, data: (AppData) -> AppData = { it }): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val bytes = zin.readBytes()
                    if (e.name == "about.json" && about != KEEP) continue
                    z.putNextEntry(ZipEntry(e.name))
                    if (e.name == "data.json") {
                        val d = data(AppJson.decodeFromString(AppData.serializer(), bytes.decodeToString()))
                        z.write(AppJson.encodeToString(AppData.serializer(), d).toByteArray())
                    } else {
                        z.write(bytes)
                    }
                    z.closeEntry()
                }
            }
            if (about != null && about != KEEP) {
                z.putNextEntry(ZipEntry("about.json"))
                z.write(about.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Everyone who first appears in nobody's name. In a move that is the person who started it, and nobody else. */
    private fun selfMade(s: Store) = s.data.value.people.filter { p -> p.grants.minByOrNull { it.at }?.by == "" }.map { it.id }.toSet()

    private fun organisers(s: Store) = s.data.value.livePeople.filter { it.role == Role.ORGANISER }.map { it.id }.toSet()

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

    // ---- who the phone is after a restore ----------------------------------------------------

    @Test
    fun aHelpersBackupRestoredOnANewPhoneMakesItThatHelperAndNobodyAnOrganiser() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val sofa = asha.createItem("Sofa", null).id
        val hal = invited(asha, "Hal", Role.HELPER)
        val packed = hal.createBox("H1", null).id
        synced(asha, hal)
        val zip = backupOf(hal)
        val founder = setOf(asha.me!!.id)
        assertEquals(founder, selfMade(asha))

        for (replace in listOf(false, true)) {
            val how = "replace=$replace"
            // Hal's phone is lost. A new phone, tried out on its own first, restores his backup: merged in, as the Sync
            // screen does, or over what the phone holds.
            val fresh = phone("New phone")
            val started = fresh.me!!.id
            val own = fresh.createBox("N1", null).id
            val binned = fresh.createBox("N2", null)
            assertTrue(fresh.tryDelete(binned).ok)
            val lamp = fresh.createItem("Lamp", null).id
            assertTrue(fresh.decide(lamp, Disposition.BIN).ok)
            Backup.import(fresh, ByteArrayInputStream(zip), replace)

            // It is Hal now, a Helper. The Organiser it made itself while on its own has stayed behind, so the move has
            // the one Organiser it had.
            assertEquals(how, hal.me!!.id, fresh.me!!.id)
            assertEquals(how, hal.me!!.id, fresh.device.value.personId)
            assertEquals(how, "Hal", fresh.device.value.name)
            assertEquals(how, Role.HELPER, fresh.myRole)
            assertNull(how, fresh.data.value.person(started))
            assertEquals(how, founder, selfMade(fresh))
            assertEquals(how, founder, organisers(fresh))
            for (a in listOf(Action.DECIDE, Action.MANAGE_PEOPLE, Action.RESTORE_BACKUP, Action.ERASE)) assertFalse("$how $a", fresh.can(a))
            assertNotNull(how, fresh.addPerson("Pat", Role.ORGANISER).reason)
            assertNotNull(how, fresh.data.value.box(packed))
            if (!replace) {
                // What it had of its own comes along, as when a phone joins a move: its things, but not what it decided
                // alone; and what it deleted alone stays deleted.
                val d = fresh.data.value
                assertNotNull(d.box(own))
                assertTrue(d.boxes.single { it.id == binned.id }.deleted)
                assertEquals(Disposition.UNDECIDED, d.item(lamp)!!.disposition)
            }

            // Synced with Asha's phone it is Hal there too, and nobody has gained an Organiser's rights.
            synced(asha, fresh)
            for (s in listOf(asha, fresh)) {
                assertEquals(how, founder, organisers(s))
                assertEquals(how, founder, selfMade(s))
                assertEquals(how, Role.HELPER, s.data.value.person(hal.me!!.id)!!.role)
            }
            assertNotNull(how, fresh.decide(sofa, Disposition.BIN).reason)
            assertEquals(how, Disposition.UNDECIDED, asha.data.value.item(sofa)!!.disposition)
        }
    }

    @Test
    fun aBackupThatDoesNotSayWhoMadeItMakesANewPhoneAHelperOfThatMove() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val sofa = asha.createItem("Sofa", null).id
        val dee = invited(asha, "Dee", Role.DECIDER)
        val gus = invited(asha, "Gus", Role.HELPER)
        assertTrue(asha.removePerson(gus.me!!.id).ok)
        val zip = backupOf(asha)
        val members = asha.data.value.people.map { it.id }.toSet()
        val founder = setOf(asha.me!!.id)

        // The note of who made it is missing (a backup from before 2.0 has none), names someone removed, or someone not in it.
        for (about in listOf(null, """{"by":"${gus.me!!.id}"}""", """{"by":"nobody"}""")) {
            val fresh = phone("New phone")
            val started = fresh.me!!.id
            Backup.import(fresh, ByteArrayInputStream(rewritten(zip, about)))
            assertNotNull(about, fresh.me)
            assertEquals(about, Role.HELPER, fresh.myRole)
            assertNull(about, fresh.data.value.person(started))
            assertFalse(about, fresh.me!!.id in members)
            assertEquals(about, founder, organisers(fresh))
            assertTrue(about, fresh.data.value.people.filter { it.id in selfMade(fresh) }.all { it.id == asha.me!!.id || it.role == Role.HELPER })
            assertNotNull(about, fresh.decide(sofa, Disposition.BIN).reason)
            assertEquals(about, "Maple Road", fresh.data.value.move.name)
            // Asha's phone, which is not on its own, does not let it in with the plain code; let in all the same, it is a Helper there.
            assertEquals(about, WifiSync.NOT_ADDED, sync(asha, fresh, letNewPhonesIn = false).second.error)
            synced(asha, fresh)
            assertEquals(about, founder, organisers(asha))
            assertEquals(about, Role.HELPER, asha.data.value.person(fresh.me!!.id)!!.role)
            assertEquals(about, Role.DECIDER, asha.data.value.person(dee.me!!.id)!!.role)
        }
    }

    @Test
    fun aBackupRestoredOnAPhoneWhosePersonIsInItLeavesItWhoItIsAndBringsBackNothingItDeleted() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val kept = asha.createBox("L001", null).id
        val doomed = asha.createBox("L002", null)
        val chair = asha.createItem("Chair", null)
        val omar = invited(asha, "Omar", Role.ORGANISER)
        val hal = invited(asha, "Hal", Role.HELPER)
        synced(asha, omar)
        synced(asha, hal)
        val zip = backupOf(asha)
        assertTrue(asha.tryDelete(doomed).ok)
        assertTrue(asha.tryDelete(chair).ok)
        synced(asha, omar)
        for (s in listOf(asha, omar)) {
            val me = s.me!!.id
            val people = s.data.value.people.toSet()
            Backup.import(s, ByteArrayInputStream(zip))
            val d = s.data.value
            assertEquals(me, s.me!!.id)
            assertEquals(Role.ORGANISER, s.myRole)
            assertEquals(people, d.people.toSet())
            assertNull(d.box(doomed.id))
            assertNull(d.item(chair.id))
            assertNotNull(d.box(kept))
        }
        // Restored over what it holds, it is still who it is.
        val me = omar.me!!.id
        Backup.import(omar, ByteArrayInputStream(zip), replace = true)
        assertEquals(me, omar.me!!.id)
        assertEquals(Role.ORGANISER, omar.myRole)

        // The same on a phone on its own.
        val solo = phone("Solo")
        val a = solo.createBox("S1", null).id
        val b = solo.createBox("S2", null)
        val lamp = solo.createItem("Lamp", null).id
        assertTrue(solo.decide(lamp, Disposition.SELL, 20.0).ok)
        val own = backupOf(solo)
        assertTrue(solo.tryDelete(b).ok)
        val was = solo.me!!.id
        Backup.import(solo, ByteArrayInputStream(own))
        assertEquals(was, solo.me!!.id)
        assertEquals(Role.ORGANISER, solo.myRole)
        assertEquals(listOf(was), solo.data.value.people.map { it.id })
        assertNotNull(solo.data.value.box(a))
        assertNull(solo.data.value.box(b.id))
        assertEquals(Disposition.SELL, solo.data.value.item(lamp)!!.disposition)
    }

    @Test
    fun aBackupMergedIntoASharedMoveBringsInNobodyWhoGaveThemselvesTheirRole() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val sofa = asha.createItem("Sofa", null).id
        val omar = invited(asha, "Omar", Role.ORGANISER)
        val hal = invited(asha, "Hal", Role.HELPER)
        synced(asha, omar)
        // A backup from a phone that restored this move's backup on version 2.0 while on its own, and kept the Organiser it
        // had made itself: Mallory, who appears in nobody's name. In it she renamed the move, decided, and made Pat an Organiser.
        val t = now
        now += 100
        val zip = rewritten(backupOf(asha)) { d ->
            val mallory = Person(
                id = "mallory", name = "Mallory", role = Role.ORGANISER, letter = "Z", joinedAt = t,
                grants = listOf(Grant(Role.ORGANISER, false, t, "")), updatedAt = t,
            )
            val pat = Person(id = "pat", name = "Pat", role = Role.ORGANISER, letter = "ZA", grants = listOf(Grant(Role.ORGANISER, false, t + 1, "mallory")), updatedAt = t + 1)
            d.copy(
                people = d.people + mallory + pat,
                move = d.move.copy(name = "Mallory's", updatedAt = t + 2).let { it.copy(changedBy = Stamp("mallory", t + 2, it.settingsSum())) },
                items = d.items.map { i ->
                    if (i.id != sofa) i else i.copy(disposition = Disposition.BIN, updatedAt = t + 3).let { it.copy(decisionBy = Stamp("mallory", t + 3, it.decisionSum())) }
                },
            )
        }
        val runIt = setOf(asha.me!!.id, omar.me!!.id)
        for (s in listOf(omar, asha)) {
            Backup.import(s, ByteArrayInputStream(zip))
            val d = s.data.value
            assertNull(d.person("mallory"))
            assertNull(d.person("pat"))
            assertEquals(runIt, organisers(s))
            assertEquals("Maple Road", d.move.name)
            assertEquals(Disposition.UNDECIDED, d.item(sofa)!!.disposition)
        }
        synced(asha, hal)
        assertNull(hal.data.value.person("mallory"))
        assertEquals(setOf(asha.me!!.id), selfMade(hal))
    }
}
