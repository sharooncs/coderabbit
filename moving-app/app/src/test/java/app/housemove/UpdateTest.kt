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
 * Working apart (design 2.1, section 4): phone keys, and update files sent between phones that cannot meet. An
 * update is sealed for the Organisers' and Deciders' phones the sender knows; it is checked for its move and its
 * phone before anything is opened, and merged with the rules of a sync.
 */
class UpdateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private var folders = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()

    private fun phone(name: String = ""): Store =
        Store(tmp.newFolder("p" + folders++)) { now++ }.also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    @After
    fun hangUp() {
        open.forEach { it.stop() }
        Shrink.jpeg = null
    }

    private fun sync(host: Store, joiner: Store) {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host, { now }) { results.add(it) }.also { open += it }
        val r = WifiSync.join(joiner, h.invite(local))
        val a = results.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")
        assertTrue(a.error, a.ok)
        assertTrue(r.error, r.ok)
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

    private fun Store.photo(ownerId: String, kind: PhotoKind, size: Int = 700): File {
        // Bytes that do not pack small, as a real photo's do not.
        val f = newPhotoFile().apply { writeBytes(ByteArray(size).also { java.util.Random(size.toLong() + folders).nextBytes(it) }) }
        addPhoto(ownerId, kind, f)
        return f
    }

    private fun updateFrom(s: Store, organisersOnly: Boolean = s.removedFromMove()): ByteArray =
        ByteArrayOutputStream().also { Update.write(s, it, organisersOnly) }.toByteArray()

    private fun Store.open(file: ByteArray, takeInRemoved: Boolean = false): Update.Opened = Update.open(this, ByteArrayInputStream(file), takeInRemoved)

    /** Asha (Organiser), Dee (Decider) and Hal (Helper), all synced, so every phone knows every key. */
    private class Move(val asha: Store, val dee: Store, val hal: Store)

    private fun move(): Move {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val dee = invited(asha, "Dee", Role.DECIDER)
        val hal = invited(asha, "Hal", Role.HELPER)
        sync(asha, dee)
        sync(asha, hal)
        return Move(asha, dee, hal)
    }

    // ---- keys ----------------------------------------------------------------------------

    @Test
    fun eachPhoneKeepsOneKeyPairBesideItsIdAndItsPublicKeyTravelsWithItsEntry() {
        val dir = tmp.newFolder("same")
        val a = Store(dir) { now++ }
        val key = a.phone.keys.publicText
        a.close()
        // The same installed copy opened again has the same pair; another copy has another.
        val again = Store(dir) { now++ }
        assertEquals(key, again.phone.keys.publicText)
        assertNotEquals(key, phone().phone.keys.publicText)
        assertNotNull(PhoneKeys.publicFrom(key))
        assertNull(PhoneKeys.publicFrom("not a key"))
        // It is on the phone's own entry in the move.
        assertEquals(key, again.me!!.phones.single { it.id == again.phone.id }.key)

        val m = move()
        // After the syncs every phone knows the key of every Organiser's and Decider's phone, the Helper's too.
        for (s in listOf(m.asha, m.dee, m.hal)) for (other in listOf(m.asha, m.dee)) {
            val entry = s.data.value.phoneEntry(other.phone.id)!!.second
            assertEquals(other.phone.keys.publicText, entry.key)
        }
        // The admitting phone had the joining phone's key from its hello, before any later sync.
        val omar = invited(m.asha, "Omar", Role.ORGANISER)
        assertEquals(omar.phone.keys.publicText, m.asha.data.value.phoneEntry(omar.phone.id)!!.second.key)
    }

    @Test
    fun aPhoneSendsToEveryOrganisersAndDecidersPhoneItKnowsButNotToHelpersOrItself() {
        val m = move()
        val to = m.hal.updateRecipients().map { it.second.id }.toSet()
        assertEquals(setOf(m.asha.phone.id, m.dee.phone.id), to)
        assertEquals(setOf(m.dee.phone.id), m.asha.updateRecipients().map { it.second.id }.toSet())
        assertEquals(setOf(m.asha.phone.id), m.hal.updateRecipients(organisersOnly = true).map { it.second.id }.toSet())
        // A phone on its own has nobody to send to.
        val alone = phone("Solo")
        val e = assertThrows(java.io.IOException::class.java) { updateFrom(alone) }
        assertEquals(Update.NO_RECIPIENTS, e.message)
    }

    // ---- the round trip ------------------------------------------------------------------

    @Test
    fun anUpdateCarriesTheSendersWorkAndSmallCopiesOfItsPhotosAndOpeningItTwiceChangesNothingMore() {
        val m = move()
        Shrink.jpeg = { f, side, quality -> "small $side $quality ".toByteArray() + f.readBytes().take(10).toByteArray() }
        val box = m.hal.createBox("H1", null)
        m.hal.setContents(box.id, "Plates\nCups")
        val photo = m.hal.photo(box.id, PhotoKind.CONTENTS)
        val file = updateFrom(m.hal)

        val first = m.asha.open(file)
        assertTrue(first.message, first.ok)
        assertTrue(first.changes > 0)
        assertEquals(1, first.photos)
        assertEquals("H1", m.asha.data.value.box(box.id)!!.label)
        assertEquals(listOf("Plates", "Cups"), m.asha.data.value.linesOf(box.id).map { it.text })
        // The photo came as its small copy, and is noted as one, so a sync later brings the full photo in its place.
        val got = File(m.asha.photoDir, photo.name)
        assertTrue(got.readBytes().decodeToString().startsWith("small 1024 70 "))
        assertTrue(m.asha.isSmallCopy(photo.name))

        val before = m.asha.data.value
        val second = m.asha.open(file)
        assertTrue(second.ok)
        assertEquals(Update.NOTHING_NEW, second.message)
        assertEquals(before, m.asha.data.value)

        // The Decider's phone opens the same file.
        assertTrue(m.dee.open(file).ok)
        assertEquals("H1", m.dee.data.value.box(box.id)!!.label)

        // Meeting the Helper's phone later, the full photo replaces the small copy.
        sync(m.asha, m.hal)
        assertArrayEquals(photo.readBytes(), got.readBytes())
        assertFalse(m.asha.isSmallCopy(photo.name))
    }

    @Test
    fun anUpdateIsMergedWithTheRulesOfASync() {
        val m = move()
        val sofa = m.asha.createItem("Sofa")
        sync(m.asha, m.dee)
        // The Decider suggests; a change that is not theirs to make is refused at the merge, as at a sync.
        assertTrue(m.dee.suggestDecision(sofa.id, Disposition.SELL, 50.0).ok)
        m.dee.update { d -> d.copy(items = d.items.map { if (it.id == sofa.id) it.copy(disposition = Disposition.BIN) else it }) }
        val opened = m.asha.open(updateFrom(m.dee))
        assertTrue(opened.message, opened.ok)
        assertEquals(Disposition.UNDECIDED, m.asha.data.value.item(sofa.id)!!.disposition)
        assertEquals(1, m.asha.data.value.waitingProposals().size)
    }

    // ---- what is checked first -----------------------------------------------------------

    @Test
    fun anUpdateForAnotherMoveIsRefusedBeforeAnythingIsOpened() {
        val m = move()
        val elm = phone("Eve")
        elm.setMove { it.copy(name = "Elm Street") }
        val before = elm.data.value
        val r = elm.open(updateFrom(m.hal))
        assertFalse(r.ok)
        assertEquals(Update.OTHER_MOVE, r.message)
        assertEquals(before, elm.data.value)
    }

    @Test
    fun anUpdateNotAddressedToThisPhoneSaysSo() {
        val m = move()
        // The Helper's phone is never sent one, and nor is the phone that made it.
        val r = m.hal.open(updateFrom(m.asha))
        assertFalse(r.ok)
        assertEquals(Update.NOT_FOR_THIS_PHONE, r.message)
        assertTrue(r.message.startsWith("This update was sent to the move's other phones"))
        assertEquals(Update.FROM_THIS_PHONE, m.asha.open(updateFrom(m.asha)).message)
        // A phone that joined after the update was made is not among its phones either.
        val file = updateFrom(m.hal)
        val omar = invited(m.asha, "Omar", Role.ORGANISER)
        assertEquals(Update.NOT_FOR_THIS_PHONE, omar.open(file).message)
    }

    @Test
    fun aTamperedOrCutFileChangesNothingAndLeavesNoPhotoBehind() {
        val m = move()
        val box = m.hal.createBox("H2", null)
        m.hal.photo(box.id, PhotoKind.CONTENTS, size = 200_000)
        val file = updateFrom(m.hal)
        val before = m.asha.data.value
        fun refused(bytes: ByteArray, wanted: String) {
            val r = m.asha.open(bytes)
            assertFalse(r.ok)
            assertEquals(wanted, r.message)
            assertEquals(before, m.asha.data.value)
            assertTrue(m.asha.photoDir.listFiles()!!.none { it.name.endsWith(".import") })
        }
        // A byte changed in the sealed contents, at the start and near the end.
        for (at in listOf(file.size / 2, file.size - 40)) refused(file.copyOf().also { it[at] = (it[at] + 1).toByte() }, Update.DAMAGED)
        // Cut short, and with something added after it.
        refused(file.copyOf(file.size - 1), Update.DAMAGED)
        refused(file.copyOf(file.size - 100_000), Update.DAMAGED)
        refused(file + byteArrayOf(0, 0, 0, 20) + ByteArray(20), Update.DAMAGED)
        // The header changed: it is bound to every chunk.
        val text = String(file, Charsets.ISO_8859_1)
        val at = text.indexOf("\"at\":") + 6
        refused(file.copyOf().also { it[at] = if (it[at] == '1'.code.toByte()) '2'.code.toByte() else '1'.code.toByte() }, Update.DAMAGED)
        // Not an update at all.
        refused("hello".toByteArray(), Update.NOT_AN_UPDATE)
        refused(ByteArray(0), Update.NOT_AN_UPDATE)
        // The file as it was still opens.
        assertTrue(m.asha.open(file).ok)
    }

    // ---- roles ---------------------------------------------------------------------------

    @Test
    fun aHelpersUpdateCarriesOnlyWhatAHelperHoldsAndAHelperTakesInNothingHidden() {
        val m = move()
        val tv = m.asha.createItem("TV")
        assertTrue(m.asha.setDetails(tv.id) { it.copy(serial = "SN-1", value = 400.0) }.ok)
        m.asha.put(Expense(id = "e1", title = "Van", amount = 120.0, planned = true))
        m.asha.photo(tv.id, PhotoKind.SERIAL)
        sync(m.asha, m.hal)
        // Hal's own photo of the serial label goes to the Organisers, as a Helper's serial number does.
        val label = m.hal.photo(tv.id, PhotoKind.SERIAL)
        val file = updateFrom(m.hal)
        val carried = m.dee.let { d ->
            assertTrue(d.open(file).ok)
            d.data.value
        }
        assertTrue(carried.photos.any { it.file == label.name })
        // What Hal holds has no costs and no hidden details, and of the serial labels only his own: his update has no more.
        val held = m.hal.data.value
        assertTrue(held.expenses.isEmpty() && held.hidden.isEmpty())
        assertTrue(held.photos.filter { it.kind in HIDDEN_FROM_HELPERS }.all { it.by == m.hal.me!!.id })

        // Dee is made a Helper; Omar's phone still thinks she is a Decider and sends her an update.
        val omar = invited(m.asha, "Omar", Role.ORGANISER)
        sync(m.asha, omar)
        sync(omar, m.dee)
        assertTrue(m.asha.setRole(m.dee.me!!.id, Role.HELPER).ok)
        sync(m.asha, m.dee)
        assertEquals(Role.HELPER, m.dee.myRole)
        // Her phone has let go of what Helpers are not sent.
        assertTrue(m.dee.data.value.hidden.isEmpty())
        omar.put(Expense(id = "e2", title = "Boxes", amount = 30.0, planned = true))
        assertTrue(omar.setDetails(tv.id) { it.copy(serial = "SN-2") }.ok)
        val fromOmar = updateFrom(omar)
        assertTrue(m.dee.phone.id in Update.peek(ByteArrayInputStream(fromOmar)).to)
        assertTrue(m.dee.open(fromOmar).ok)
        val d = m.dee.data.value
        assertTrue(d.expenses.isEmpty())
        assertTrue(d.hidden.isEmpty())
        assertTrue(d.photos.none { it.kind in HIDDEN_FROM_HELPERS && it.by != m.dee.me!!.id })
    }

    @Test
    fun anUpdateFromSomeoneRemovedIsRefusedUnlessAnOrganiserTakesInTheirWorkAlone() {
        val m = move()
        val sofa = m.asha.createItem("Sofa")
        sync(m.asha, m.hal)
        // Hal packs a box and moves the sofa without hearing that he was removed meanwhile.
        val box = m.hal.createBox("H9", null)
        assertTrue(m.asha.removePerson(m.hal.me!!.id).ok)
        val packed = m.hal.loadOnto(null, listOf(sofa.id))
        assertTrue(packed.ok)
        // His phone learns at its next sync that he has been removed, and sends his last work to an Organiser.
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(m.asha, { now }) { results.add(it) }.also { open += it }
        assertEquals(WifiSync.REMOVED_SELF, WifiSync.join(m.hal, h.invite(local)).error)
        results.poll(15, TimeUnit.SECONDS)
        assertTrue(m.hal.removedFromMove())
        assertEquals(setOf(m.asha.phone.id), m.hal.updateRecipients().map { it.second.id }.toSet())
        val file = updateFrom(m.hal)

        val before = m.asha.data.value
        val refused = m.asha.open(file)
        assertFalse(refused.ok)
        assertEquals(m.hal.me!!.id, refused.fromRemoved)
        assertEquals(Update.fromRemoved("Hal"), refused.message)
        assertEquals(before.boxes, m.asha.data.value.boxes)

        // Taken in as Hal's work alone: the box he added comes in; moving the sofa after his removal is refused, as usual.
        val taken = m.asha.open(file, takeInRemoved = true)
        assertTrue(taken.message, taken.ok)
        assertNotNull(m.asha.data.value.box(box.id))
        assertEquals(Stage.AT_ORIGIN, m.asha.data.value.item(sofa.id)!!.stage)
        assertTrue(m.asha.data.value.refused.any { it.by == m.hal.me!!.id })
        assertTrue(m.asha.data.value.person(m.hal.me!!.id)!!.removed)
    }

    @Test
    fun aDeciderCannotTakeInTheWorkOfSomeoneRemoved() {
        val m = move()
        m.hal.createBox("H3", null)
        assertTrue(m.asha.removePerson(m.hal.me!!.id).ok)
        sync(m.asha, m.dee)
        val r = m.dee.open(updateFrom(m.hal, organisersOnly = false), takeInRemoved = true)
        assertFalse(r.ok)
        assertEquals(Update.fromRemoved("Hal"), r.message)
    }

    @Test
    fun aRetiredPhonesUpdateIsTakenInOnceAndThenRefused() {
        val m = move()
        val box = m.hal.createBox("H4", null)
        val file = updateFrom(m.hal)
        val second = run {
            m.hal.createBox("H5", null)
            updateFrom(m.hal)
        }
        assertTrue(m.asha.retirePhone(m.hal.me!!.id, m.hal.phone.id).ok)
        assertTrue(m.asha.open(file).ok)
        assertNotNull(m.asha.data.value.box(box.id))
        assertTrue(m.asha.data.value.phoneEntry(m.hal.phone.id)!!.second.collected)
        assertEquals(Update.RETIRED_GONE, m.asha.open(second).message)
    }

    @Test
    fun anUpdateOpensIntoTheMoveItIsForOnAPhoneWithSeveralMoves() {
        val root = tmp.newFolder("several")
        val moves = Moves(File(root, "files"), File(root, "no_backup"), { "Asha's phone" }) { now++ }
        val maple = moves.create("Maple Road", "Asha")
        val elm = moves.create("Elm Street", "Asha")
        assertSame(elm, moves.current.value)
        val dee = invited(maple, "Dee", Role.DECIDER)
        val box = dee.createBox("D1", null)
        val file = updateFrom(dee)
        val r = moves.openUpdate({ ByteArrayInputStream(file) })
        assertTrue(r.message, r.ok)
        assertNotNull(maple.data.value.box(box.id))
        assertNull(elm.data.value.box(box.id))
        // A move this phone does not hold.
        val other = phone("Zed")
        val zedDee = invited(other, "Dee", Role.DECIDER)
        assertEquals(Update.NOT_ON_PHONE, moves.openUpdate({ ByteArrayInputStream(updateFrom(zedDee)) }).message)
    }
}
