package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.screens.roleNews
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Several moves on one phone (2.1): the list of moves, each in a folder of its own, the move from
 * 2.0 moved into the first folder, per-phone and per-move settings, removing and deleting, and
 * which move a scanned label belongs to.
 */
class MovesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var phones = 0
    private val local = listOf("127.0.0.1")
    private val hosts = ArrayList<WifiSync.Host>()

    /** One phone: the app's own folder and its folder outside Android's backup. */
    private class Device(val files: File, val noBackup: File, val name: String)

    private fun device(name: String = "Phone ${phones + 1}"): Device {
        val root = tmp.newFolder("phone" + phones++)
        return Device(File(root, "files"), File(root, "no_backup"), name)
    }

    private fun moves(d: Device): Moves = Moves(d.files, d.noBackup, { d.name }) { now++ }

    @After
    fun stop() {
        hosts.forEach { it.stop() }
        Moves.crashAt = null
    }

    private fun sync(host: Store, joiner: Store): Pair<WifiSync.Result, WifiSync.Result> {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }) { results.add(it) }.also { hosts += it }
        val r = WifiSync.join(joiner, h.invite(local))
        return (results.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")) to r
    }

    /** [phone] joins [host]'s move as a newly added [name] by scanning an invite, through its list of moves. */
    private fun joinThrough(phone: Moves, host: Store, name: String, role: Role): WifiSync.Result {
        val p = host.addPerson(name, role).valueOrNull()!!
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }) { results.add(it) }.also { hosts += it }
        val check = phone.readInvite(h.inviteFor(p.id, local).valueOrNull()!!.encode())!!
        val r = phone.join(check)
        results.poll(15, TimeUnit.SECONDS)
        return r
    }

    /** A phone as 2.0 left it: its one move in the app's folder itself, with photos. */
    private fun old20(d: Device, photos: Int = 3): List<String> {
        d.files.mkdirs()
        File(d.files, "data.json").writeText(
            """{"version":4,"move":{"name":"Maple Road","setupDone":true,"labelsPrinted":40,"updatedAt":90},
            "people":[{"id":"asha","name":"Asha","role":"ORGANISER","letter":"A","joinedAt":10,"grants":[{"role":"ORGANISER","at":10}],"updatedAt":10},
                      {"id":"hal","name":"Hal","role":"HELPER","letter":"B","joinedAt":20,"grants":[{"role":"HELPER","at":20,"by":"asha"}],"updatedAt":20}],
            "boxes":[{"id":"b1","label":"L001","contents":"Plates","updatedAt":30},{"id":"b2","label":"A1","updatedAt":31}],
            "items":[{"id":"sofa","name":"Sofa","updatedAt":40}],
            "photos":${(1..photos).joinToString(",", "[", "]") { """{"id":"ph$it","ownerId":"b1","file":"f$it.jpg","updatedAt":5$it}""" }}}""",
        )
        File(d.files, "data.json.bak").writeText(File(d.files, "data.json").readText())
        File(d.files, "device.json").writeText("""{"name":"Asha","letter":"A","nextSeq":2,"personId":"asha","bookLookup":true,"currentRoomId":"r1"}""")
        val dir = File(d.files, "photos").apply { mkdirs() }
        return (1..photos).map { n -> "f$n.jpg".also { File(dir, it).writeBytes(ByteArray(100 + n) { n.toByte() }) } }
    }

    private fun assertTheOldMoveIsWhole(m: Moves, photos: List<String>) {
        assertEquals(1, m.cards.value.size)
        val s = m.current.value!!
        val d = s.data.value
        assertEquals("Maple Road", d.move.name)
        assertEquals(setOf("b1", "b2"), d.liveBoxes.map { it.id }.toSet())
        assertNotNull(d.item("sofa"))
        assertEquals(setOf("asha", "hal"), d.people.map { it.id }.toSet())
        assertEquals("asha", s.device.value.personId)
        assertEquals(Role.ORGANISER, s.myRole)
        assertEquals("A", s.device.value.letter)
        assertEquals("r1", s.device.value.currentRoomId)
        assertEquals(true, s.device.value.bookLookup)
        for (p in photos) assertTrue(p, File(s.photoDir, p).isFile)
        assertEquals(photos.size, s.photoDir.list()!!.size)
        // Nothing is left where 2.0 kept it.
        for (name in listOf("data.json", "data.json.bak", "device.json", "upgrade-2.1.json")) assertFalse(name, File(s.photoDir.parentFile!!.parentFile!!, name).exists())
    }

    // ---- the move from 2.0 -------------------------------------------------------------

    @Test
    fun theMoveOnAPhoneFrom20BecomesItsFirstMoveWithNothingLost() {
        val d = device()
        val photos = old20(d)
        val m = moves(d)
        assertTheOldMoveIsWhole(m, photos)
        val s = m.current.value!!
        // Its ID is worked out from its founder, as on every other phone of that move.
        assertEquals(s.data.value.derivedMoveId(), s.moveId)
        assertTrue(s.identity.value.derived)
        // Handwritten codes go on from where they were; printed labels from where 2.0 left them.
        assertEquals("A2", s.nextHandCode())
        assertEquals(41, s.nextLabelNumber())
        // Opened again, it is the same.
        m.flush()
        val again = moves(d)
        assertEquals(m.cards.value.map { it.moveId }, again.cards.value.map { it.moveId })
        assertEquals("A3", again.current.value!!.nextHandCode())
    }

    @Test
    fun aCrashAtAnyStepOfMovingThe20MoveLosesNothing() {
        for (step in 1..6) {
            val d = device()
            val photos = old20(d)
            Moves.crashAt = { it == step }
            try {
                moves(d)
                fail("step $step did not stop")
            } catch (_: IllegalStateException) {
            }
            Moves.crashAt = null
            val m = moves(d)
            assertTheOldMoveIsWhole(m, photos)
            assertFalse(File(d.files, "upgrade-2.1.json").exists())
        }
    }

    @Test
    fun aPhoneFrom13IsBroughtUpToDateToo() {
        val d = device()
        d.files.mkdirs()
        File(d.files, "data.json").writeText(
            """{"version":3,"move":{"name":"Oak Lane","updatedAt":100},"items":[{"id":"tv","name":"TV","disposition":"SELL","price":90.0,"updatedAt":50}],
            "boxes":[{"id":"b1","label":"L001","updatedAt":10}],"tasks":[{"id":"k1","title":"Book the van","done":true,"updatedAt":70}]}""",
        )
        File(d.files, "device.json").writeText("""{"name":"Ben","letter":"Q","nextSeq":7}""")
        val s = moves(d).current.value!!
        val data = s.data.value
        assertEquals("Oak Lane", data.move.name)
        assertEquals(Disposition.SELL, data.item("tv")!!.disposition)
        assertEquals(90.0, data.filledIn().item("tv")!!.price!!, 0.0) // kept in its hidden record since 2.1
        assertTrue(data.task("k1")!!.done)
        assertEquals(SCHEMA, data.version)
        assertEquals(Role.ORGANISER, s.myRole)
        assertEquals("Q7", s.nextHandCode())
        // The person 1.3's data gets is as 2.0 would have made it: its grant counts, as it would have on 2.0.
        assertEquals(listOf(""), s.me!!.grants.map { it.phone })
    }

    // ---- several moves ------------------------------------------------------------------

    @Test
    fun movesHaveTheirOwnFoldersPhotosAndCodesAndThePhoneItsOwnSettings() {
        val d = device("Asha's phone")
        val m = moves(d)
        assertNull(m.current.value)
        assertTrue(m.cards.value.isEmpty())
        val maple = m.create("Maple Road", yourName = "Asha")
        maple.setDevice { it.copy(bookLookup = true) }
        val box = maple.createBox(maple.nextHandCode(), null)
        val photo = maple.newPhotoFile().apply { writeBytes(ByteArray(10)) }
        maple.addPhoto(box.id, PhotoKind.CONTENTS, photo)
        // A second move: the name you go by and the book look-up come along; the letter does not.
        val elm = m.create("Elm Street")
        assertEquals("Asha", elm.device.value.name)
        assertEquals(true, elm.device.value.bookLookup)
        assertNotEquals(maple.device.value.letter, elm.device.value.letter)
        assertNotEquals(maple.moveId, elm.moveId)
        assertNotEquals(maple.photoDir, elm.photoDir)
        assertTrue(elm.data.value.liveBoxes.isEmpty())
        assertEquals(elm, m.current.value)
        assertEquals(listOf("Elm Street", "Maple Road"), m.cards.value.map { it.name })
        // Each phone is listed with the name its owner gave it.
        assertEquals(listOf("Asha's phone"), maple.me!!.phones.map { it.name })
        assertEquals(m.phone.id, maple.me!!.phones.single().id)
        // The phone ID and the codes are kept apart from what Android's backup copies.
        assertTrue(File(d.noBackup, "phone-id").isFile)
        assertFalse(d.files.walk().any { it.name == "phone-id" || it.name == "codes.json" })
        assertTrue(maple.photoDir.startsWith(File(d.files, "photos")))

        // Back to Maple; archiving it brings Elm up again.
        assertEquals(maple, m.open(maple.moveId))
        m.archive(maple.moveId)
        assertTrue(m.cards.value.first { it.moveId == maple.moveId }.archived)
        assertEquals(elm, m.current.value)
        m.flush()
        val again = moves(d)
        assertEquals(2, again.cards.value.size)
        assertEquals(elm.moveId, again.current.value!!.moveId)
        assertEquals(m.phone.id, again.phone.id)
        assertEquals(1, again.store(maple.moveId)!!.data.value.liveBoxes.size)
    }

    @Test
    fun aMoveNobodyElseJoinedCanBeDeletedAndAnyCanBeRemovedOnceItsWorkIsElsewhere() {
        val m = moves(device())
        val mistake = m.create("Started by mistake")
        mistake.createBox("X1", null)
        assertNull(m.whyNotDelete(mistake.moveId))
        assertTrue(m.delete(mistake.moveId).ok)
        assertTrue(m.cards.value.isEmpty())
        assertFalse(mistake.photoDir.exists())

        val maple = m.create("Maple Road", yourName = "Asha")
        maple.createBox("L001", null)
        val other = moves(device())
        assertTrue(joinThrough(other, maple, "Omar", Role.ORGANISER).ok)
        val omar = other.current.value!!
        // Someone has joined: it is not this phone's alone to delete.
        assertNotNull(m.whyNotDelete(maple.moveId))
        assertNotNull(m.delete(maple.moveId).reason)

        // Omar packs a box and has not synced since: removing the move from his phone says what would be lost.
        omar.createBox("B1", null)
        val r = other.removing(omar.moveId)!!
        assertNull(r.refusal)
        assertFalse(r.unshared.nothing)
        assertTrue(r.unshared.things.any { it.contains("B1") })
        assertNotNull(other.remove(omar.moveId).reason)
        // Once synced, nothing is only on his phone, and it goes.
        val (a, b) = sync(maple, omar)
        assertTrue(a.error, a.ok)
        assertTrue(b.error, b.ok)
        assertTrue(other.removing(omar.moveId)!!.unshared.nothing)
        assertTrue(other.remove(omar.moveId).ok)
        assertTrue(other.cards.value.isEmpty())
        assertNotNull(maple.data.value.findByLabel("B1"))
    }

    @Test
    fun theLastOrganiserCannotRemoveAMoveOthersShare() {
        val m = moves(device())
        val maple = m.create("Maple Road", yourName = "Asha")
        val other = moves(device())
        assertTrue(joinThrough(other, maple, "Hal", Role.HELPER).ok)
        val r = m.removing(maple.moveId)!!
        assertNotNull(r.refusal)
        assertNotNull(m.remove(maple.moveId, evenIfUnshared = true).reason)
        // The Helper can remove it from his phone, once he says so.
        val hal = other.current.value!!
        hal.createBox("H1", null)
        assertNotNull(other.remove(hal.moveId).reason)
        assertTrue(other.remove(hal.moveId, evenIfUnshared = true).ok)
    }

    // ---- labels ------------------------------------------------------------------------

    @Test
    fun aScannedLabelSaysWhichMovesOnThePhoneHoldIt() {
        val m = moves(device())
        val maple = m.create("Maple Road")
        val elm = m.create("Elm Street")
        maple.createBox("L001", null)
        elm.createBox("L001", null)
        elm.createBox("L002", null)
        // A label printed by 2.1 carries its move's tag: it belongs to that move only.
        val printed = labelQrText("L001", maple.moveTag)
        assertEquals(ScannedLabel("L001", maple.moveTag), readLabel(printed))
        assertEquals(listOf(maple.moveId), m.movesHolding(printed).map { it.moveId })
        assertEquals(listOf(elm.moveId), m.movesHolding(labelQrText("L001", elm.moveTag)).map { it.moveId })
        // One from before has none, and is in both: the screen asks which.
        assertEquals(setOf(maple.moveId, elm.moveId), m.movesHolding("HM:L001").map { it.moveId }.toSet())
        assertEquals(listOf(elm.moveId), m.movesHolding("HM:L002").map { it.moveId })
        assertTrue(m.movesHolding("HM:L999").isEmpty())
        assertEquals("L001", labelCodeOf(printed))
        assertEquals("L001", labelCodeOf("HM:L001"))
    }

    @Test
    fun twoPhonesPrintLabelsFromTheirOwnNumbersAndWriteTheirOwnLetters() {
        val m = moves(device())
        val maple = m.create("Maple Road", yourName = "Asha")
        val other = moves(device())
        assertTrue(joinThrough(other, maple, "Omar", Role.ORGANISER).ok)
        val omar = other.current.value!!
        val third = moves(device())
        assertTrue(joinThrough(third, omar, "Dee", Role.DECIDER).ok)
        val dee = third.current.value!!
        val ranges = listOf(maple, omar, dee).map { it.labelRange() }
        for (r in ranges) assertFalse(r.isEmpty())
        for (i in ranges.indices) for (j in ranges.indices) if (i < j) assertTrue("$ranges", ranges[i].intersect(ranges[j]).isEmpty())
        assertEquals(1, maple.nextLabelNumber())
        maple.labelsPrinted(20)
        assertEquals(21, maple.nextLabelNumber())
        assertEquals(omar.labelRange().first, omar.nextLabelNumber())
        val letters = listOf(maple, omar, dee).map { it.device.value.letter }
        assertEquals(letters.toString(), 3, letters.toSet().size)
        // Dee was let in by Omar: her letter comes from his block.
        assertTrue(dee.device.value.letter.startsWith(omar.device.value.letter))
    }

    /**
     * Found by the simulation with several moves per phone: a phone founding two moves printed L001 onwards in both, two
     * moves that let it in gave it the same block, it could take in one move the letter it writes with in another, and a
     * backup of one move restored from the Sync screen of another (on its own) turned that move into the first.
     */
    @Test
    fun aPhonesMovesKeepTheirLabelNumbersLettersAndBackupsApart() {
        val m = moves(device("Asha's phone"))
        val maple = m.create("Maple Road", yourName = "Asha")
        val storage = m.create("Storage")
        // It founded both, and prints from numbers of its own in each: Maple Road's from 1, as always.
        assertEquals(1, maple.nextLabelNumber())
        assertFalse("${maple.labelRange()} ${storage.labelRange()}", maple.labelRange().overlaps(storage.labelRange()))
        // The letter it writes with in Maple Road is not taken in Storage.
        val letter = maple.device.value.letter
        val refused = storage.setLetter(storage.me!!.id, letter)
        assertFalse(refused.ok)
        assertTrue(refused.reason, refused.reason!!.contains("another of its moves"))
        // Nor is one it wrote codes with in Maple Road before it took another there.
        val written = maple.nextHandCode()
        assertTrue(maple.setLetter(maple.me!!.id, "QX").ok)
        assertEquals("QX", maple.device.value.letter)
        assertFalse(storage.setLetter(storage.me!!.id, written.takeWhile { it.isLetter() }).ok)

        // Let in to two moves of other phones, each of which gives it its first block: it prints from neither twice.
        val elm = moves(device("Omar's phone")).create("Elm Street", yourName = "Omar")
        val oak = moves(device("Dee's phone")).create("Oak Lane", yourName = "Dee")
        val priya = moves(device("Priya's phone"))
        assertTrue(joinThrough(priya, elm, "Priya", Role.DECIDER).ok)
        assertTrue(joinThrough(priya, oak, "Priya", Role.HELPER).ok)
        val inElm = priya.store(elm.moveId)!!.labelRange()
        val inOak = priya.store(oak.moveId)!!.labelRange()
        assertFalse(inElm.isEmpty() || inOak.isEmpty())
        assertFalse("$inElm $inOak", inElm.overlaps(inOak))

        // A backup of Maple Road chosen on Storage's Sync screen: Storage has things of its own, and stays as it is.
        storage.createBox("S1", null)
        maple.createBox("M1", null)
        val zip = java.io.ByteArrayOutputStream().also { Backup.export(maple, it) }.toByteArray()
        val before = storage.data.value
        val e = assertThrows(IllegalArgumentException::class.java) { Backup.import(storage, java.io.ByteArrayInputStream(zip)) }
        assertTrue(e.message, e.message!!.contains("another move"))
        assertEquals(before, storage.data.value)
        assertNotEquals(maple.moveId, storage.moveId)
        // Through the list of moves it goes into Maple Road's own card; on a phone without it, into a new one.
        assertTrue(m.restore({ java.io.ByteArrayInputStream(zip) }).ok)
        assertEquals(2, m.cards.value.size)
        val other = moves(device("Sam's phone"))
        other.create("Sam's own")
        assertTrue(other.restore({ java.io.ByteArrayInputStream(zip) }).ok)
        assertEquals(2, other.cards.value.size)
        assertNotNull(other.store(maple.moveId)!!.data.value.findByLabel("M1"))
    }

    /**
     * Found by the simulation with phones working while they sync, on a phone that erased the move it had tried out alone:
     * the erased move wrote X1 again (a code it had written before the erase, still on boxes and in backups), printed from
     * the label numbers of its other move, kept the card it had (the list opened it by an ID it no longer had), and took in
     * a backup of another move, even of one in another card, which put a person in it that no restart kept.
     */
    @Test
    fun anErasedMoveGoesOnWithItsCodesHasItsCardAtOnceAndTakesNoOtherMovesBackup() {
        val d = device("Hana's phone")
        val m = moves(d)
        val maple = m.create("Maple Road", yourName = "Hana")
        val own = m.create("Trying it out")
        val written = listOf(own.nextHandCode(), own.nextHandCode())
        written.forEach { own.createBox(it, null) }
        val zipOwn = java.io.ByteArrayOutputStream().also { Backup.export(own, it) }.toByteArray()
        val zipMaple = java.io.ByteArrayOutputStream().also { Backup.export(maple, it) }.toByteArray()
        val was = own.moveId

        assertTrue(own.erase().ok)
        assertNotEquals(was, own.moveId)
        // Its card is the erased move's at once: it opens by its new ID, and nothing goes by the old one.
        val card = m.cards.value.single { !it.matches(maple.moveId) }
        assertEquals(own.moveId, card.moveId)
        assertEquals("", card.name)
        assertSame(own, m.open(own.moveId))
        assertNull(m.store(was))
        // It goes on counting, and prints from numbers it prints from in no other move.
        val next = own.nextHandCode()
        assertFalse("$next after $written", next in written)
        assertFalse("${maple.labelRange()} ${own.labelRange()}", maple.labelRange().overlaps(own.labelRange()))

        // Neither backup goes into it: not the move it was, nor Maple Road, which has a card of its own.
        val before = own.data.value
        for (zip in listOf(zipOwn, zipMaple)) {
            val e = assertThrows(IllegalArgumentException::class.java) { Backup.import(own, java.io.ByteArrayInputStream(zip)) }
            assertTrue(e.message, e.message!!.contains("another move"))
        }
        assertEquals(before, own.data.value)
        // Through the list of moves, Maple Road's goes into its own card and the move it was into a new one.
        assertTrue(m.restore({ java.io.ByteArrayInputStream(zipMaple) }).ok)
        assertTrue(m.restore({ java.io.ByteArrayInputStream(zipOwn) }).ok)
        assertEquals(3, m.cards.value.size)

        // What each move holds is what it loads from disk.
        val held = m.cards.value.associate { c -> m.store(c.moveId)!!.let { s -> c.moveId to (s.data.value to s.device.value) } }
        m.cards.value.forEach { m.store(it.moveId)!!.close() }
        val again = moves(d)
        for ((id, h) in held) {
            val s = again.store(id)!!
            assertEquals(h.first, s.data.value)
            assertEquals(h.second, s.device.value)
        }
    }

    /**
     * Also found by the simulation: in a move with no Organiser (a phone on its own in helper mode) a person seemed to be
     * added and was gone once the move was read back; and a refused carry-out was worded from the decision, which two
     * phones may hold differently.
     */
    @Test
    fun nobodyIsAddedToAMoveWithNoOrganiserAndARefusedChangeReadsTheSameEverywhere() {
        val d = device("Hana's phone")
        val m = moves(d)
        val own = m.create("Trying it out", yourName = "Hana")
        own.setDevice { it.copy(helper = true) }
        val refused = own.addPerson("Asha", Role.ORGANISER)
        assertFalse(refused.ok)
        assertEquals(Store.NOBODY_TO_ADD, refused.reason)
        own.setDevice { it.copy(helper = false) }
        val asha = own.addPerson("Asha", Role.ORGANISER).valueOrNull()!!
        m.flush()
        assertNotNull(moves(d).store(own.moveId)!!.data.value.person(asha.id))

        val sold = Item(id = "sofa", name = "Sofa", gone = true, disposition = Disposition.SELL)
        assertEquals(outcomeVerb(sold), outcomeVerb(sold.copy(disposition = Disposition.UNDECIDED)))
        val r = RefusedChange("refused/x", by = asha.id, at = 5, what = "outcome", targetId = "sofa", tried = outcomeVerb(sold), role = Role.HELPER)
        val data = own.data.value.copy(items = listOf(sold))
        assertTrue(data.describe(r), data.describe(r).contains("tried to mark Sofa sold"))
        assertTrue(data.copy(items = listOf(sold.copy(disposition = Disposition.DONATE))).describe(r).contains("tried to mark Sofa donated"))
    }

    // ---- Android's own backup ------------------------------------------------------------

    @Test
    fun aPhoneRestoredFromAndroidsBackupGetsItsOwnPhoneIdAndLetterAndNoPhotos() {
        val d = device()
        val m = moves(d)
        val maple = m.create("Maple Road", yourName = "Asha")
        val code = maple.nextHandCode()
        maple.createBox(code, null)
        val f = maple.newPhotoFile().apply { writeBytes(ByteArray(10)) }
        maple.addPhoto(maple.data.value.liveBoxes[0].id, PhotoKind.CONTENTS, f)
        m.flush()
        // What the backup rules copy: the list of moves, the phone's settings and the moves' folders; not the photos, not no_backup.
        val copy = device()
        copy.files.mkdirs()
        for (name in listOf("moves.json", "phone.json")) File(d.files, name).copyTo(File(copy.files, name))
        File(d.files, "moves").copyRecursively(File(copy.files, "moves"))
        val n = moves(copy)
        val s = n.current.value!!
        assertNotEquals(m.phone.id, n.phone.id)
        assertEquals(maple.moveId, s.moveId)
        assertNotNull(s.data.value.findByLabel(code))
        assertEquals(maple.me!!.id, s.me!!.id)
        // It is the same person on a new phone: a letter of its own, so it never writes the codes the old phone wrote.
        assertNotEquals(maple.device.value.letter, s.device.value.letter)
        assertNotEquals(code, s.nextHandCode())
        assertFalse(File(s.photoDir, f.name).exists())
        assertEquals(2, s.me!!.phones.size)
    }

    // ---- the banner about one's own role -------------------------------------------------

    /**
     * "You joined as a Decider" is put away per move and per person, in that move's settings for the phone: it does not
     * come back when the app starts again, it is not put away in the phone's other move, and a later change of role is
     * news again.
     */
    @Test
    fun theRoleBannerIsPutAwayPerMoveAndPerPersonAndStaysAway() {
        val asha = moves(device("Asha's phone")).create("Maple Road", yourName = "Asha")
        val omar = moves(device("Omar's phone")).create("Elm Street", yourName = "Omar")
        val pd = device("Priya's phone")
        val priya = moves(pd)
        assertTrue(joinThrough(priya, asha, "Priya", Role.DECIDER).ok)
        assertTrue(joinThrough(priya, omar, "Priya", Role.HELPER).ok)
        val maple = priya.store(asha.moveId)!!
        val elm = priya.store(omar.moveId)!!
        fun news(s: Store) = s.data.value.roleNews(s.device.value)?.title
        assertEquals("You joined as a Decider", news(maple))
        assertEquals("You joined as a Helper", news(elm))

        // Got it, in Maple Road: gone there, still there in Elm Street.
        maple.roleNewsRead(maple.data.value.roleNews(maple.device.value)!!.at)
        assertNull(news(maple))
        assertEquals("You joined as a Helper", news(elm))

        // The app starts again: the same.
        priya.flush()
        val again = moves(pd)
        val maple2 = again.store(asha.moveId)!!
        val elm2 = again.store(omar.moveId)!!
        assertNull(news(maple2))
        assertEquals("You joined as a Helper", news(elm2))
        assertEquals(mapOf(maple2.device.value.personId to maple2.device.value.roleReadAt), maple2.device.value.rolesRead)

        // Made a Helper later: that is news again.
        assertTrue(asha.setRole(maple2.me!!.id, Role.HELPER).ok)
        sync(asha, maple2)
        assertEquals("You are now a Helper", news(maple2))

        // Another person on the same phone in the same move has read nothing yet.
        val sam = asha.addPerson("Sam", Role.DECIDER).valueOrNull()!!
        sync(asha, maple2)
        assertTrue(maple2.becomePerson(sam.id).ok)
        assertEquals(0L, maple2.device.value.roleReadAt)
        assertEquals("You joined as a Decider", news(maple2))
    }
}
