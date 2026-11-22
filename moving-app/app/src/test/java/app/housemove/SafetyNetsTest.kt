package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Safety nets (design 2.1, section 5): the daily small backup and what Home asks for, being taken out of a move,
 * Helpers' copies that expire after the move is done, "Done, thanks", and removing a move from a phone.
 */
class SafetyNetsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var folders = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()
    private val day = 20_400L

    private fun phone(name: String = ""): Store =
        Store(tmp.newFolder("p" + folders++)) { now++ }.also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    private fun moves(name: String): Moves {
        val root = tmp.newFolder("phone" + folders++)
        return Moves(File(root, "files"), File(root, "no_backup"), { "$name's phone" }) { now++ }
    }

    @After
    fun hangUp() {
        open.forEach { it.stop() }
        Shrink.jpeg = null
    }

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

    private fun invited(host: Store, name: String, role: Role, into: Moves? = null): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }) { results.add(it) }.also { open += it }
        val invite = h.inviteFor(p.id, local).valueOrNull()!!
        val phone = if (into == null) phone(name).also { assertTrue(WifiSync.join(it, invite).ok) }
        else {
            into.phone.updateSettings { it.copy(name = name) }
            assertTrue(into.join(into.readInvite(invite.encode())!!).ok)
            into.current.value!!
        }
        assertTrue(results.poll(15, TimeUnit.SECONDS)!!.ok)
        return phone
    }

    private fun Store.photo(ownerId: String, kind: PhotoKind): File {
        val f = newPhotoFile().apply { writeBytes(ByteArray(900).also { java.util.Random(folders.toLong()).nextBytes(it) }) }
        addPhoto(ownerId, kind, f)
        return f
    }

    // ---- the daily small backup ----------------------------------------------------------

    @Test
    fun theDailyBackupIsWrittenOnceADayOnlyOnTheSingleOrganisersPhone() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road") }
        val place = FolderPlace(tmp.newFolder("drive"))
        // A move of one person has a daily backup too.
        assertTrue(DailyBackup.due(asha, day))
        assertTrue(DailyBackup.run(asha, place, day))
        assertFalse(DailyBackup.due(asha, day))
        assertEquals(1, place.names().size)
        assertTrue(DailyBackup.due(asha, day + 1))
        // The last two of a move are kept.
        assertTrue(DailyBackup.run(asha, place, day + 1))
        assertTrue(DailyBackup.run(asha, place, day + 2))
        assertEquals(listOf(day + 2, day + 1), DailyBackup.found(place).map { it.day })

        // A Decider's and a Helper's phone have none; the single Organiser's still has.
        val dee = invited(asha, "Dee", Role.DECIDER)
        val hal = invited(asha, "Hal", Role.HELPER)
        assertTrue(DailyBackup.due(asha, day + 3))
        assertFalse(DailyBackup.applies(dee))
        assertFalse(DailyBackup.applies(hal))
        assertFalse(DailyBackup.run(hal, place, day + 3))
        // With a second Organiser the move no longer depends on one phone: neither has one.
        val omar = invited(asha, "Omar", Role.ORGANISER)
        assertFalse(DailyBackup.applies(asha))
        assertFalse(DailyBackup.applies(omar))
    }

    @Test
    fun theSmallBackupHasNoOrdinaryPhotosAndSmallReceiptsAndRestoresOnANewPhone() {
        Shrink.jpeg = { f, side, q -> "small $side $q".toByteArray() + f.readBytes().take(8).toByteArray() }
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road") }
        val tv = asha.createItem("TV")
        asha.put(Expense(id = "e1", title = "Van", amount = 120.0, planned = true))
        val ordinary = asha.photo(tv.id, PhotoKind.GENERAL)
        val serial = asha.photo(tv.id, PhotoKind.SERIAL)
        val receipt = asha.newPhotoFile().apply { writeBytes(ByteArray(900).also { java.util.Random(9).nextBytes(it) }) }
        assertTrue(asha.attachReceipt("e1", receipt).ok)
        val place = FolderPlace(tmp.newFolder("usb"))
        assertTrue(DailyBackup.run(asha, place, day))
        val name = DailyBackup.newestOfEach(place).single().name
        assertTrue(name.endsWith("-maple-road.zip"))
        val peek = place.read(name).use { Backup.peek(it) }
        assertTrue(peek.small)
        java.util.zip.ZipInputStream(place.read(name)).use { z ->
            val entries = generateSequence { z.nextEntry }.map { it.name to z.readBytes() }.toMap()
            assertFalse("photos/" + ordinary.name in entries)
            assertTrue(entries.getValue("photos/" + serial.name).decodeToString().startsWith("small 1024 70"))
            assertTrue(entries.getValue("photos/" + receipt.name).decodeToString().startsWith("small 1024 70"))
        }

        // Lost your phone? A new phone restores it, and is Asha.
        val new = moves("Asha")
        val r = new.restore({ place.read(name) })
        assertTrue(r.ok)
        val s = new.current.value!!
        assertEquals(asha.me!!.id, s.device.value.personId)
        assertEquals(Role.ORGANISER, s.myRole)
        assertTrue(s.isSmallCopy(serial.name))
        assertTrue(File(s.photoDir, receipt.name).isFile)
        assertFalse(File(s.photoDir, ordinary.name).exists())
    }

    @Test
    fun movesWriteTheDailyBackupWhereverThePhonePutsItAndNoMoreThanOnceADay() {
        val m = moves("Asha")
        val place = FolderPlace(tmp.newFolder("documents"))
        val s = m.create("Maple Road", "Asha")
        assertEquals(0, m.dailyBackups(day))
        m.backupPlace = { place }
        assertEquals(1, m.dailyBackups(day))
        assertEquals(0, m.dailyBackups(day))
        m.checkIn(day)
        assertEquals(1, place.names().size)
        assertEquals(day, s.device.value.dailyBackupDay)
        assertTrue(s.device.value.lastBackupAt > 0)
        assertEquals(1, m.dailyBackups(day + 1))
    }

    @Test
    fun homeNudgesWhenTheLastBackupIsOldAndAsksForASecondOrganiserBeforeMoveDay() {
        val asha = phone("Asha")
        val week = 7 * 86_400_000L
        // Never backed up, no folder chosen.
        val first = DailyBackup.nudges(asha, day, now, hasPlace = false)
        assertTrue(first.backup)
        assertNull(first.backupDays)
        assertFalse(first.secondOrganiser)
        asha.noteDailyBackup(day)
        val backedUp = asha.device.value.lastBackupAt
        assertFalse(DailyBackup.nudges(asha, day, backedUp + 2 * 86_400_000L, hasPlace = true).backup)
        assertTrue(DailyBackup.nudges(asha, day + 8, backedUp + week + 86_400_000L, hasPlace = true).backup)
        // A few days before move day.
        asha.setMove { it.copy(moveDay = day + 4) }
        assertTrue(DailyBackup.nudges(asha, day, backedUp, true).secondOrganiser)
        assertFalse(DailyBackup.nudges(asha, day - 10, backedUp, true).secondOrganiser)
        asha.declineSecondOrganiser()
        assertFalse(DailyBackup.nudges(asha, day, backedUp, true).secondOrganiser)
        // Nothing for a Decider.
        val dee = invited(asha, "Dee", Role.DECIDER)
        assertFalse(DailyBackup.nudges(dee, day, backedUp, true).any)
    }

    // ---- marking the move done and Helpers' copies ---------------------------------------

    @Test
    fun onlyAnOrganiserMarksTheMoveDoneAndAForgedMarkIsRefused() {
        val asha = phone("Asha")
        val dee = invited(asha, "Dee", Role.DECIDER)
        assertNotNull(dee.markDone(day = day).reason)
        assertNull(dee.data.value.move.doneDay)
        // Written into the data without the right: the merge keeps the move as an Organiser left it.
        dee.update { d -> d.copy(move = d.move.copy(doneDay = day, updatedAt = now++)) }
        synced(asha, dee)
        assertNull(asha.data.value.move.doneDay)
        assertTrue(asha.markDone(day = day).ok)
        synced(asha, dee)
        assertEquals(day, dee.data.value.move.doneDay)
        assertTrue(asha.markDone(false).ok)
        assertNull(asha.data.value.move.doneDay)
    }

    @Test
    fun aHelpersCopyExpiresAFewDaysAfterTheMoveIsDoneOnceAnOrganiserOrDeciderHasAllTheirWork() {
        val asha = phone("Asha")
        val dee = invited(asha, "Dee", Role.DECIDER)
        val hal = invited(asha, "Hal", Role.HELPER)
        val hana = invited(asha, "Hana", Role.HELPER)
        hal.createBox("H1", null)
        assertTrue(asha.markDone(day = day).ok)
        // Hal hears of it from Hana, a Helper: his box is on no Organiser's or Decider's phone yet.
        synced(asha, hana)
        synced(hana, hal)
        assertEquals(day, hal.data.value.move.doneDay)
        assertNull(hal.whyLetGo(day + 5))
        // A full sync with a Decider's phone: now it has all of Hal's work.
        synced(dee, hal)
        assertTrue(hal.unsharedWithDeciders().nothing)
        assertNull(hal.whyLetGo(day + 2))
        assertEquals(LetGo.EXPIRED, hal.whyLetGo(day + 3))
        // Anything done since keeps the copy until it too has reached an Organiser or Decider.
        hal.createBox("H2", null)
        assertNull(hal.whyLetGo(day + 9))
        // Syncing only with a Helper does not count.
        synced(hal, hana)
        assertNull(hal.whyLetGo(day + 9))
        synced(asha, hal)
        assertEquals(LetGo.EXPIRED, hal.whyLetGo(day + 9))
        // Organisers' and Deciders' copies do not expire.
        assertNull(dee.whyLetGo(day + 30))
        assertNull(asha.whyLetGo(day + 30))
        // A move not marked done does not expire either.
        assertTrue(asha.markDone(false).ok)
        synced(asha, hal)
        assertNull(hal.whyLetGo(day + 30))
    }

    @Test
    fun anExpiredCopyIsTakenOffThePhoneWithAWordToSay() {
        val asha = phone("Asha")
        val m = moves("Hal")
        val hal = invited(asha, "Hal", Role.HELPER, into = m)
        // The phones look at today's date after a sync, so the move is marked done today.
        val today = java.time.LocalDate.now().toEpochDay()
        assertTrue(asha.markDone(day = today).ok)
        synced(asha, hal)
        assertEquals(1, m.cards.value.size)
        assertTrue(m.tidy(today + 1).isEmpty())
        val said = m.tidy(today + 3)
        assertEquals(1, said.size)
        assertTrue(m.cards.value.isEmpty())
        assertEquals(said, m.farewells.value)
    }

    // ---- Done, thanks Sam ----------------------------------------------------------------

    @Test
    fun doneThanksSamRemovesSamOnceHisPhoneHasHandedEverythingInAndHisPhoneLetsGo() {
        val asha = phone("Asha")
        val dee = invited(asha, "Dee", Role.DECIDER)
        val m = moves("Sam")
        val sam = invited(asha, "Sam", Role.HELPER, into = m)
        val samId = sam.me!!.id
        sam.createBox("S1", null)
        // Only an Organiser thanks, and only a Helper.
        assertNotNull(dee.thank(samId).reason)
        assertNotNull(asha.thank(dee.me!!.id).reason)
        assertTrue(asha.thank(samId).ok)
        // Not removed yet: his phone has work no Organiser's phone has.
        assertFalse(asha.data.value.person(samId)!!.removed)
        // At the end of their next full sync his box is on Asha's phone, he is removed, and his phone lets go of its copy.
        synced(asha, sam)
        assertNotNull(asha.data.value.boxes.firstOrNull { it.label == "S1" })
        assertTrue(asha.data.value.person(samId)!!.removed)
        assertTrue(m.cards.value.isEmpty())
        assertTrue(m.farewells.value.single().contains("Thank you"))
    }

    @Test
    fun aThankedHelpersPhoneThatSyncsWithADeciderLetsGoButOnlyAnOrganiserRemovesThem() {
        val asha = phone("Asha")
        val dee = invited(asha, "Dee", Role.DECIDER)
        val sam = invited(asha, "Sam", Role.HELPER)
        assertTrue(asha.thank(sam.me!!.id).ok)
        synced(asha, dee)
        sam.createBox("S2", null)
        // A sync among Helpers does not hand anything in.
        val hana = invited(asha, "Hana", Role.HELPER)
        synced(asha, hana)
        synced(hana, sam)
        assertNull(sam.whyLetGo(day))
        synced(dee, sam)
        assertEquals(LetGo.THANKED, sam.whyLetGo(day))
        assertFalse(dee.data.value.person(sam.me!!.id)!!.removed)
    }

    // ---- taken out of a move -------------------------------------------------------------

    @Test
    fun aRemovedPhoneSaysSoAndCanStillSendItsWorkAndLeave() {
        val asha = phone("Asha")
        val m = moves("Hal")
        val hal = invited(asha, "Hal", Role.HELPER, into = m)
        hal.createBox("H7", null)
        assertTrue(asha.removePerson(hal.me!!.id).ok)
        assertFalse(hal.removedFromMove())
        val (_, r) = sync(asha, hal)
        assertEquals(WifiSync.REMOVED_SELF, r.error)
        assertTrue(hal.removedFromMove())
        m.refresh()
        assertTrue(m.cards.value.single().removed)
        assertFalse(hal.unsharedWithDeciders().nothing)
        // A removed phone does not let go by itself: its card offers to send its work first.
        assertTrue(m.tidy(day + 30).isEmpty())
        assertTrue(Update.write(hal, java.io.ByteArrayOutputStream()).phones == 1)
        assertTrue(m.remove(m.cards.value.single().moveId, evenIfUnshared = true).ok)
        assertTrue(m.cards.value.isEmpty())
    }

    @Test
    fun aPhoneLetBackInIsNoLongerTreatedAsRemoved() {
        val asha = phone("Asha")
        val hal = invited(asha, "Hal", Role.HELPER)
        assertTrue(asha.removePerson(hal.me!!.id).ok)
        sync(asha, hal)
        assertTrue(hal.removedFromMove())
        assertTrue(asha.restorePerson(hal.me!!.id).ok)
        synced(asha, hal)
        assertFalse(hal.removedFromMove())
    }

    // ---- removing a move from this phone -------------------------------------------------

    @Test
    fun removingAMoveListsWhatNoOtherPhoneHasAndTheLastOrganiserCannotRemoveASharedMove() {
        val m = moves("Asha")
        val asha = m.create("Maple Road", "Asha")
        val dee = invited(asha, "Dee", Role.DECIDER)
        val r = m.removing(asha.moveId)!!
        assertNotNull(r.refusal)
        assertFalse(m.remove(asha.moveId, evenIfUnshared = true).ok)

        val d = moves("Dee")
        val mine = invited(asha, "Dee 2", Role.DECIDER, into = d)
        val box = mine.createBox("D4", null)
        mine.photo(box.id, PhotoKind.CONTENTS)
        val what = d.removing(mine.moveId)!!
        assertNull(what.refusal)
        assertTrue(what.unshared.changes >= 1)
        assertEquals(1, what.unshared.photos)
        assertFalse(d.remove(mine.moveId).ok)
        synced(asha, mine)
        assertTrue(d.removing(mine.moveId)!!.unshared.nothing)
        assertTrue(d.remove(mine.moveId).ok)
        assertNotNull(dee)
    }
}
