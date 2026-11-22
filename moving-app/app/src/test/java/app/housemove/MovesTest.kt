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
        assertEquals(90.0, data.priceOf("tv")!!, 0.0)
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
}
