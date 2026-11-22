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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The awkward moments between several phones, each written out as one small
 * story: two people wanting different things, news arriving late or in the
 * middle of a sync, clocks that are wrong, connections that drop, invites used
 * twice, and people whose role changes while their phone is away.
 * Phones meet over real localhost sockets, as in SimulationTest.
 */
class PhonesTogetherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The right time. Every look at a clock moves it on, so each change has its own moment, in the order it was made. */
    private var now = 1_000_000L
    private var folders = 0
    private val local = listOf("127.0.0.1")
    private val open = ArrayList<WifiSync.Host>()
    private val wires = ArrayList<Wire>()

    private fun phone(name: String = "", wrongBy: Long = 0): Store =
        Store(tmp.newFolder("p" + folders++)) { now++ + wrongBy }.also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    private class Hosting(val host: WifiSync.Host, private val results: LinkedBlockingQueue<WifiSync.Result>) {
        fun result(): WifiSync.Result = results.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")
    }

    private fun hosting(store: Store, clock: () -> Long = { now }): Hosting {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        return Hosting(WifiSync.host(store, clock) { results.add(it) }.also { open += it }, results)
    }

    @After
    fun hangUp() {
        open.forEach { it.stop() }
        wires.forEach { it.close() }
    }

    /** [joiner] scans the ordinary code on [host]'s phone. What the host showed, then what the joiner showed. */
    private fun sync(host: Store, joiner: Store): Pair<WifiSync.Result, WifiSync.Result> {
        val h = hosting(host)
        val r = WifiSync.join(joiner, h.host.invite(local))
        return h.result() to r
    }

    private fun synced(host: Store, joiner: Store) {
        val (a, b) = sync(host, joiner)
        assertTrue(a.error, a.ok)
        assertTrue(b.error, b.ok)
    }

    /** Everyone meets everyone, twice round. */
    private fun everyone(vararg phones: Store) {
        repeat(2) { for (a in phones) for (b in phones) if (a !== b) synced(a, b) }
    }

    /** A phone brought into [host]'s move by an invite for a newly added person. */
    private fun invited(host: Store, name: String, role: Role, wrongBy: Long = 0): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val h = hosting(host)
        val phone = phone(wrongBy = wrongBy)
        val r = WifiSync.join(phone, h.host.inviteFor(p.id, local).valueOrNull()!!)
        assertTrue(r.error, r.ok)
        assertTrue(h.result().ok)
        return phone
    }

    /** A sync over a wire that can be cut, or that lets something happen in the middle. What the host showed (if anything), then the joiner. */
    private fun syncVia(host: Store, joiner: Store, cut: Wire.Cut? = null, onPhase: (Int) -> Unit = {}): Pair<WifiSync.Result, WifiSync.Result> {
        val h = hosting(host)
        val wire = Wire(h.host.port, cut, onPhase).also { wires += it }
        val r = WifiSync.join(joiner, h.host.invite(local).copy(port = wire.port))
        val first = h.result()
        repeat(wire.connections - 1) { h.result() }
        return first to r
    }

    private fun Store.item(id: String) = data.value.item(id)!!
    private fun Store.box(id: String) = data.value.box(id)!!
    private fun Store.proposal(id: String) = data.value.proposal(id)!!
    private val Store.id: String get() = me!!.id

    private fun Store.photo(ownerId: String, kind: PhotoKind, bytes: Int): File {
        val f = newPhotoFile().apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }
        addPhoto(ownerId, kind, f)
        return f
    }

    /** Everything a phone holds, without regard to order. With [costs] false, without what a Helper's phone is not sent. */
    private fun all(s: Store, costs: Boolean = true): List<Any> = s.data.value.let { d ->
        listOf(
            d.move, d.rooms.toSet(), d.items.toSet(), d.boxes.toSet(), d.tasks.toSet(), d.people.toSet(), d.entries.toSet(), d.trips.toSet(), d.activity.toSet(),
            if (costs) d.expenses.toSet() else 0,
            d.proposals.filter { costs || it.kind != ProposalKind.COST }.toSet(),
            d.photos.filter { costs || it.kind !in HIDDEN_FROM_HELPERS }.toSet(),
            // What else Helpers are not sent (2.1), and what every phone holds alike.
            if (costs) listOf(d.hidden.toSet(), d.collect.toSet(), d.refused.toSet()) else 0,
            d.damage.toSet(), d.lines.toSet(),
        )
    }

    private fun assertSame(vararg phones: Store, costs: Boolean = true) {
        for (p in phones.drop(1)) assertEquals(all(phones[0], costs), all(p, costs))
    }

    // ---- two people wanting different things ---------------------------------------------

    @Test
    fun twoDecidersSuggestDifferentThingsForOneItemAndOneOrganiserChooses() {
        val asha = phone("Asha")
        val sofa = asha.createItem("Sofa", null).id
        val dee = invited(asha, "Dee", Role.DECIDER)
        val dan = invited(asha, "Dan", Role.DECIDER)
        val sell = dee.suggestDecision(sofa, Disposition.SELL, 80.0).valueOrNull()!!
        val give = dan.suggestDecision(sofa, Disposition.DONATE).valueOrNull()!!
        // The two Deciders meet before any Organiser has seen either suggestion: both wait on both phones, and neither can settle it.
        synced(dee, dan)
        for (s in listOf(dee, dan)) assertEquals(setOf(sell.id, give.id), s.data.value.waitingProposals().map { it.id }.toSet())
        assertNotNull(dee.approve(sell.id).reason)
        assertNotNull(dan.decline(sell.id, "mine is better").reason)
        assertEquals(Disposition.UNDECIDED, dan.item(sofa).disposition)

        // The Organiser hears of both from one of them and chooses Dan's.
        synced(asha, dan)
        assertEquals(2, asha.data.value.waitingCount())
        assertTrue(asha.approve(give.id).ok)
        everyone(asha, dee, dan)
        for (s in listOf(asha, dee, dan)) {
            val d = s.data.value
            assertEquals(Disposition.DONATE, d.item(sofa)!!.disposition)
            assertEquals(ProposalState.APPROVED, d.proposal(give.id)!!.state)
            assertEquals(ProposalState.NOT_CHOSEN, d.proposal(sell.id)!!.state)
            assertTrue(d.mayCarryOut(d.person(dan.id), d.item(sofa)!!))
            assertFalse(d.mayCarryOut(d.person(dee.id), d.item(sofa)!!))
        }
        // Only the Decider whose suggestion it was may carry it out.
        assertNotNull(dee.carryOut(sofa, gone = true).reason)
        assertTrue(dan.carryOut(sofa, gone = true).ok)
        everyone(asha, dee, dan)
        assertTrue(listOf(asha, dee, dan).all { it.item(sofa).gone })
        assertSame(asha, dee, dan)
    }

    @Test
    fun anApprovalAndAnotherOrganisersDirectChangeAtTheSameMomentEndTheSameEverywhere() {
        for (approvalFirst in listOf(true, false)) {
            val asha = phone("Asha")
            val lamp = asha.createItem("Lamp", null).id
            val omar = invited(asha, "Omar", Role.ORGANISER)
            val dee = invited(asha, "Dee", Role.DECIDER)
            val p = dee.suggestDecision(lamp, Disposition.SELL, 40.0).valueOrNull()!!
            everyone(asha, omar, dee)
            // Asha approves on her phone while Omar, on his, decides the same lamp directly. Neither phone knows of the other's change.
            if (approvalFirst) {
                assertTrue(asha.approve(p.id).ok)
                assertTrue(omar.decide(lamp, Disposition.STORE).ok)
                // Dee hears of the approval before she hears of Omar's change, and acts on it.
                synced(asha, dee)
                assertTrue(dee.carryOut(lamp, gone = true, soldFor = 35.0).ok)
            } else {
                assertTrue(omar.decide(lamp, Disposition.STORE).ok)
                assertTrue(asha.approve(p.id).ok)
            }
            everyone(asha, omar, dee)
            assertSame(asha, omar, dee)
            val d = asha.data.value
            // The later of the two stands. The approval stays on record either way.
            assertEquals(ProposalState.APPROVED, d.proposal(p.id)!!.state)
            assertEquals(asha.id, d.proposal(p.id)!!.decidedBy)
            if (approvalFirst) {
                assertEquals(Disposition.STORE, d.item(lamp)!!.disposition)
                assertEquals(omar.id, d.item(lamp)!!.decisionBy.by)
                // Omar had changed the decision before Dee acted, so her go-ahead had already ended: what she marked is not kept.
                assertFalse(d.item(lamp)!!.gone)
                assertNull(d.hiddenOf(lamp)?.soldFor)
                assertFalse(d.mayCarryOut(d.person(dee.id), d.item(lamp)!!))
                assertNotNull(dee.carryOut(lamp, gone = true).reason)
            } else {
                assertEquals(Disposition.SELL, d.item(lamp)!!.disposition)
                assertEquals(40.0, d.priceOf(lamp)!!, 0.0)
                assertEquals(asha.id, d.item(lamp)!!.decisionBy.by)
                assertTrue(dee.carryOut(lamp, gone = true, soldFor = 35.0).ok)
                everyone(asha, omar, dee)
                assertTrue(omar.item(lamp).gone)
            }
        }
    }

    // ---- people coming, going and changing role -------------------------------------------

    @Test
    fun someoneRemovedInTheMiddleOfTheirSyncAndWhatTheyDoAfterwards() {
        val asha = phone("Asha")
        asha.createBox("L001", null)
        val hal = invited(asha, "Hal", Role.HELPER)
        val halId = hal.id
        hal.createBox("B1", null)

        // Hal is syncing with Asha; she removes him after his phone has sent its data and before hers has merged it.
        var removed = false
        val (hosted, joined) = syncVia(asha, hal) { phase -> if (phase == 3) removed = asha.removePerson(halId).ok }
        assertTrue(removed)
        assertTrue(hosted.error, hosted.ok)
        assertTrue(joined.error, joined.ok)
        // That sync runs to its end: his work so far arrives, and his phone hears that he is out.
        assertNotNull(asha.data.value.findByLabel("B1"))
        assertTrue(hal.me!!.removed)
        assertTrue(hal.can(Action.WORK))
        assertFalse(hal.can(Action.SUGGEST))

        // He can go on using what is on his phone, but none of it reaches the move, whichever phone scans the other's code.
        hal.createBox("B2", null)
        val before = asha.data.value
        val (atAsha, atHal) = sync(asha, hal)
        assertEquals(WifiSync.REMOVED_SELF, atHal.error)
        assertTrue(atAsha.error, atAsha.error!!.contains("has been removed from this move"))
        val (atHal2, atAsha2) = sync(hal, asha)
        assertEquals(WifiSync.REMOVED_SELF, atHal2.error)
        assertTrue(atAsha2.error, atAsha2.error!!.contains("has been removed from this move"))
        assertEquals(before, asha.data.value)

        // Let back in, his phone syncs again and what he did meanwhile arrives.
        assertTrue(asha.restorePerson(halId).ok)
        synced(asha, hal)
        assertFalse(hal.me!!.removed)
        assertNotNull(asha.data.value.findByLabel("B2"))
        assertSame(asha, hal, costs = false)

        // Removed a moment later instead, when the merged data is already on its way back: his phone has not heard, and is turned away next time.
        removed = false
        val (h2, j2) = syncVia(asha, hal) { phase -> if (phase == 4) removed = asha.removePerson(halId).ok }
        assertTrue(removed && h2.ok && j2.ok)
        assertFalse(hal.me!!.removed)
        hal.createBox("B3", null)
        assertEquals(WifiSync.REMOVED_SELF, sync(asha, hal).second.error)
        assertNull(asha.data.value.findByLabel("B3"))
        // Another phone in the move turns him away too, once it has heard.
        val dee = invited(asha, "Dee", Role.DECIDER)
        assertEquals(WifiSync.REMOVED_SELF, sync(dee, hal).second.error)
        assertNull(dee.data.value.findByLabel("B3"))
    }

    @Test
    fun anOrganiserMadeAHelperWhileOfflineKeepsTheirWorkButNotWhatTheyDecidedAfterwards() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val sofa = asha.createItem("Sofa", null).id
        val lamp = asha.createItem("Lamp", null).id
        val omar = invited(asha, "Omar", Role.ORGANISER)

        // Omar's phone is away. First he does what an Organiser may...
        assertTrue(omar.decide(sofa, Disposition.SELL, 70.0).ok)
        omar.put(Expense(id = "early", title = "Skip", amount = 200.0, planned = true))
        // ...then Asha makes him a Helper, which his phone does not know...
        assertTrue(asha.setRole(omar.id, Role.HELPER).ok)
        // ...and he carries on: more decisions, a cost, a task, the settings, and ordinary packing.
        assertTrue(omar.decide(lamp, Disposition.BIN).ok)
        omar.put(Expense(id = "late", title = "Pizza", amount = 30.0))
        omar.put(Task(id = "late-task", title = "Wash the van"))
        omar.setMove { it.copy(name = "Omar's move") }
        val box = omar.createBox("B1", null)
        omar.put(omar.item(lamp).copy(notes = "brass, heavy"))

        synced(asha, omar)
        for (s in listOf(asha, omar)) {
            val d = s.data.value
            assertEquals(Role.HELPER, d.person(omar.id)!!.role)
            // What he decided while he was an Organiser stands; what he decided afterwards does not.
            assertEquals(Disposition.SELL, d.item(sofa)!!.disposition)
            assertEquals(70.0, d.priceOf(sofa)!!, 0.0)
            assertEquals(Disposition.UNDECIDED, d.item(lamp)!!.disposition)
            assertNull(d.task("late-task"))
            assertNull(d.expenses.firstOrNull { it.id == "late" })
            assertEquals("Maple Road", d.move.name)
            // The packing and the note are anyone's work, and are kept.
            assertNotNull(d.box(box.id))
            assertEquals("brass, heavy", d.item(lamp)!!.notes)
        }
        assertEquals(200.0, asha.data.value.expense("early")!!.amount, 0.0)
        // His phone now knows, and says no.
        assertEquals(Role.HELPER, omar.myRole)
        assertNotNull(omar.decide(lamp, Disposition.BIN).reason)
        // From here on it is sent no costs: the one he made stays on his phone as it was, and changes to it pass him by.
        asha.put(asha.data.value.expense("early")!!.copy(amount = 250.0))
        asha.put(Expense(id = "fuel", title = "Fuel", amount = 60.0))
        synced(asha, omar)
        synced(omar, asha)
        assertEquals(200.0, omar.data.value.expense("early")!!.amount, 0.0)
        assertNull(omar.data.value.expense("fuel"))
        assertEquals(250.0, asha.data.value.expense("early")!!.amount, 0.0)
    }

    @Test
    fun aHelperMadeAnOrganiserCanApproveOnceTheirPhoneHasHeard() {
        val asha = phone("Asha")
        val sofa = asha.createItem("Sofa", null).id
        asha.put(Expense(id = "van", title = "Van", amount = 180.0))
        val dee = invited(asha, "Dee", Role.DECIDER)
        val hal = invited(asha, "Hal", Role.HELPER)
        val p = dee.suggestDecision(sofa, Disposition.SELL, 90.0).valueOrNull()!!
        synced(hal, dee) // the Helper's phone carries the suggestion around
        assertTrue(hal.data.value.expenses.isEmpty())

        assertTrue(asha.setRole(hal.id, Role.ORGANISER).ok)
        // His phone has not heard yet: it still says no.
        assertEquals("Only an Organiser can approve or decline a suggestion.", hal.approve(p.id).reason)
        synced(asha, hal)
        assertEquals(Role.ORGANISER, hal.myRole)
        // An Organiser's phone is sent the costs.
        assertEquals(180.0, hal.data.value.expense("van")!!.amount, 0.0)
        val approved = hal.approve(p.id).valueOrNull()!!
        assertEquals(hal.id, approved.decidedBy)

        // Dee hears it from Hal directly, and acts; Asha's phone, which made him an Organiser, accepts all of it.
        synced(hal, dee)
        assertTrue(dee.carryOut(sofa, gone = true, soldFor = 85.0).ok)
        everyone(asha, dee, hal)
        assertSame(asha, dee, hal)
        val item = asha.item(sofa)
        assertEquals(Disposition.SELL, item.disposition)
        assertEquals(hal.id, item.decisionBy.by)
        assertEquals(dee.id, item.outcomeBy.by)
        assertTrue(item.gone)
        assertEquals(ProposalState.APPROVED, asha.proposal(p.id).state)
        // And he can now do the rest of what Organisers do.
        assertTrue(hal.changeMove { it.copy(name = "Hal's turn") }.ok)
        assertTrue(hal.addPerson("Mo", Role.HELPER).ok)
    }

    @Test
    fun theLastOrganiserStaysEvenWhenTwoStepDownAtOnce() {
        val asha = phone("Asha")
        val sofa = asha.createItem("Sofa", null).id
        // On her own she cannot step down or remove herself.
        assertNotNull(asha.setRole(asha.id, Role.HELPER).reason)
        assertNotNull(asha.removePerson(asha.id).reason)
        val omar = invited(asha, "Omar", Role.ORGANISER)
        val hal = invited(asha, "Hal", Role.HELPER)
        everyone(asha, omar, hal)

        // Each of the two Organisers steps down on their own phone, each seeing the other still there.
        assertTrue(asha.setRole(asha.id, Role.DECIDER).ok)
        assertTrue(omar.removePerson(omar.id).ok)
        everyone(asha, hal)
        synced(hal, omar)
        everyone(asha, hal)
        // The move must still have someone to run it: the one who stepped down second did so when nobody else was left, so that does not count.
        for (s in listOf(asha, hal)) {
            val d = s.data.value
            assertTrue(d.hasOrganiser())
            assertEquals(Role.DECIDER, d.person(asha.id)!!.role)
            assertEquals(Role.ORGANISER, d.person(omar.id)!!.role)
            assertFalse(d.person(omar.id)!!.removed)
        }
        // So the rules still hold: a Helper cannot decide or take the move over.
        assertNotNull(hal.decide(sofa, Disposition.BIN).reason)
        assertNotNull(hal.setRole(hal.id, Role.ORGANISER).reason)
        assertNotNull(hal.addPerson("Friend", Role.ORGANISER).reason)
        // Omar's phone hears that he is still the Organiser, and carries on as one.
        synced(asha, omar)
        assertEquals(Role.ORGANISER, omar.myRole)
        assertTrue(omar.decide(sofa, Disposition.MOVE).ok)
        everyone(asha, omar, hal)
        assertEquals(Disposition.MOVE, hal.item(sofa).disposition)

        // Two Organisers who demote each other at the same moment: the earlier one counts, the other does not.
        assertTrue(omar.setRole(asha.id, Role.ORGANISER).ok)
        everyone(asha, omar, hal)
        assertTrue(asha.setRole(omar.id, Role.HELPER).ok)
        assertTrue(omar.setRole(asha.id, Role.HELPER).ok)
        everyone(asha, omar, hal)
        for (s in listOf(asha, omar, hal)) {
            assertEquals(Role.ORGANISER, s.data.value.person(asha.id)!!.role)
            assertEquals(Role.HELPER, s.data.value.person(omar.id)!!.role)
        }
    }

    @Test
    fun anOrganiserWhoTakesThemselvesOutIsOutForEveryone() {
        val asha = phone("Asha")
        val omar = invited(asha, "Omar", Role.ORGANISER)
        val hal = invited(asha, "Hal", Role.HELPER)
        everyone(asha, omar, hal)
        // Omar removes himself on his own phone. From that moment the others' phones would refuse his, so he has nobody to tell...
        assertTrue(omar.removePerson(omar.id).ok)
        assertEquals(Role.ORGANISER, asha.data.value.person(omar.id)!!.role)
        assertFalse(asha.data.value.person(omar.id)!!.removed)
        // ...except that the phone that turns his away takes note of what his says about himself, and passes it on.
        val (atHal, atOmar) = sync(hal, omar)
        assertEquals(WifiSync.REMOVED_SELF, atOmar.error)
        assertTrue(atHal.error, atHal.error!!.contains("has been removed from this move"))
        assertTrue(hal.data.value.person(omar.id)!!.removed)
        synced(asha, hal)
        assertTrue(asha.data.value.person(omar.id)!!.removed)
        // So Asha knows she is the only Organiser left, and cannot step down as well.
        assertEquals("A move needs at least one Organiser. Make someone else an Organiser first.", asha.setRole(asha.id, Role.HELPER).reason)
        // Only what his phone says about himself is taken, and only what the rules allow: a Helper's phone that claims a
        // promotion for its person while being turned away changes nothing.
        assertTrue(asha.removePerson(hal.id).ok)
        val t = hal.now()
        hal.update { d -> d.copy(people = d.people.map { if (it.id == hal.id) it.copy(role = Role.ORGANISER, grants = it.grants + Grant(Role.ORGANISER, false, t, hal.id), updatedAt = t) else it }) }
        assertEquals(WifiSync.REMOVED_SELF, sync(asha, hal).second.error)
        assertTrue(asha.data.value.person(hal.id)!!.removed)
        assertEquals(Role.HELPER, asha.data.value.person(hal.id)!!.role)
    }

    @Test
    fun aHelpersPhoneTakesInNoCostsEvenFromAPhoneThatThinksSheIsMore() {
        val asha = phone("Asha")
        val tv = asha.createItem("TV", null).id
        asha.put(Expense(id = "van", title = "Van hire", amount = 180.0))
        val receipt = asha.photo("van", PhotoKind.RECEIPT, 3000)
        val serial = asha.photo(tv, PhotoKind.SERIAL, 3100)
        val picture = asha.photo(tv, PhotoKind.GENERAL, 3200)
        val omar = invited(asha, "Omar", Role.ORGANISER)
        val hana = invited(asha, "Hana", Role.HELPER)
        everyone(asha, omar, hana)
        // Omar makes Asha a Decider, and Hana's phone hears of it. Asha's phone has not: on it she makes Hana a Decider,
        // which does not count, since she was no longer an Organiser. Her phone, taking Hana for a Decider, sends her everything
        // when it scans Hana's code: the costs with its data, and the receipt and serial-label files after.
        assertTrue(omar.setRole(asha.id, Role.DECIDER).ok)
        synced(omar, hana)
        assertTrue(asha.setRole(hana.id, Role.DECIDER).ok)
        asha.put(Expense(id = "fuel", title = "Fuel", amount = 60.0))
        synced(hana, asha)
        // Hana's phone knows better: she is a Helper, and it has taken in none of it. The ordinary photo did come.
        assertEquals(Role.HELPER, hana.myRole)
        assertTrue(hana.data.value.expenses.isEmpty())
        assertTrue(hana.data.value.proposals.none { it.kind == ProposalKind.COST })
        assertEquals(listOf(PhotoKind.GENERAL), hana.data.value.photos.map { it.kind })
        assertEquals(listOf(picture.name), hana.photoDir.list()!!.toList())
        assertFalse(File(hana.photoDir, receipt.name).exists() || File(hana.photoDir, serial.name).exists())
        // Asha's phone has heard in turn, and from now on sends a Helper's share.
        assertEquals(Role.HELPER, asha.data.value.person(hana.id)!!.role)
        // Made a Decider by someone who may, she is sent it all.
        assertTrue(omar.setRole(hana.id, Role.DECIDER).ok)
        synced(omar, hana)
        synced(asha, hana)
        assertEquals(Role.DECIDER, hana.myRole)
        assertEquals(180.0, hana.data.value.expense("van")!!.amount, 0.0)
        assertEquals(3000L, File(hana.photoDir, receipt.name).length())
        assertEquals(3100L, File(hana.photoDir, serial.name).length())
    }

    @Test
    fun twoPeopleWhoFindTheyHaveTheSameLetterDoNotKeepIt() {
        val asha = phone("Asha")
        asha.setDevice { it.copy(letter = "A") }
        val hal = invited(asha, "Hal", Role.HELPER)
        val dee = invited(asha, "Dee", Role.DECIDER)
        everyone(asha, hal, dee)
        // Hal and Dee each choose the letter Q on their own phones at the same moment. Each phone sees nobody else using it.
        assertTrue(hal.setLetter(hal.id, "Q").ok)
        assertTrue(dee.setLetter(dee.id, "Q").ok)
        val before = listOf(hal.nextHandCode(), dee.nextHandCode())
        assertEquals(listOf("Q1", "Q1"), before) // nothing could have stopped these two
        // When their phones learn of each other, the one who has been in the move longer keeps Q and the other's phone takes another.
        everyone(asha, hal, dee)
        for (s in listOf(asha, hal, dee)) {
            assertTrue(s.data.value.duplicateLetters().isEmpty())
            assertEquals("Q", s.data.value.person(hal.id)!!.letter)
            assertTrue(s.data.value.person(dee.id)!!.letter.let { it.length == 2 && it.startsWith("Q") })
        }
        assertEquals("Q", hal.device.value.letter)
        assertEquals(dee.me!!.letter, dee.device.value.letter)
        val codes = (1..3).flatMap { listOf(hal.nextHandCode(), dee.nextHandCode()) }
        assertEquals(codes.toString(), 6, codes.toSet().size)

        // A phone that was started on its own and is let in as it is, with the letter of someone already in the move: the same.
        val guest = phone("Guest")
        guest.setDevice { it.copy(letter = "A") }
        guest.createBox(guest.nextHandCode(), null)
        synced(asha, guest)
        everyone(asha, hal, dee, guest)
        for (s in listOf(asha, hal, dee, guest)) {
            assertTrue(s.data.value.duplicateLetters().isEmpty())
            assertEquals("A", s.data.value.person(asha.id)!!.letter)
        }
        assertNotEquals("A", guest.device.value.letter)
        assertNotEquals(asha.nextHandCode(), guest.nextHandCode())
        assertSame(asha, dee, guest)
    }

    // ---- containers and labels ---------------------------------------------------------------

    @Test
    fun aContainerMovedOrDeletedOnOnePhoneWhileSomethingIsPutIntoItOnAnother() {
        val asha = phone("Asha")
        val trip = Trip(id = "t1", name = "Trip 1").also { asha.put(it) }
        val carton = asha.createBox("L001", null).id
        val bag = asha.createBox("L002", null, ContainerKind.BAG).id
        val crate = asha.createBox("L003", null, ContainerKind.CRATE).id
        val doomed = asha.createBox("L004", null).id
        val (lamp, vase, cup) = listOf("Lamp", "Vase", "Cup").map { name -> asha.createItem(name, null).id.also { asha.decide(it, Disposition.MOVE) } }
        val hal = invited(asha, "Hal", Role.HELPER)
        val hugo = invited(asha, "Hugo", Role.HELPER)

        // Hal packs three things while Hugo, on his phone, loads the first container and puts the second inside a crate and
        // loads that, and Asha, on hers, deletes the third. (Hugo may not delete it: Asha added it. See DeletingTest.)
        assertTrue(hal.putInto(lamp, carton).ok)
        assertTrue(hal.putInto(vase, bag).ok)
        assertTrue(hal.putInto(cup, doomed).ok)
        hugo.updateBoxes(listOf(carton)) { it.copy(stage = Stage.LOADED, tripId = trip.id) }
        assertTrue(hugo.nest(bag, crate).ok)
        hugo.updateBoxes(listOf(crate)) { it.copy(stage = Stage.LOADED, tripId = trip.id) }
        assertNotNull(hugo.tryDelete(hugo.box(doomed)).reason)
        assertTrue(asha.tryDelete(asha.box(doomed)).ok)
        everyone(asha, hal, hugo)
        assertSame(asha, hal, hugo, costs = false)
        for (s in listOf(asha, hal, hugo)) {
            val d = s.data.value
            // The lamp went on the van with its carton.
            assertEquals(carton, d.containerOf(d.item(lamp)!!)!!.id)
            assertEquals(Stage.LOADED, d.item(lamp)!!.stage)
            // The vase is in the bag, the bag in the crate, and all three are on the van as one thing.
            assertEquals(bag, d.containerOf(d.item(vase)!!)!!.id)
            assertEquals(crate, d.carrierOf(d.item(vase)!!)!!.id)
            assertEquals(Stage.LOADED, d.item(vase)!!.stage)
            assertEquals(Stage.LOADED, d.box(bag)!!.stage)
            // The cup's container is gone; the cup is not. It is a loose thing still to be loaded.
            assertNull(d.box(doomed))
            assertNull(d.containerOf(d.item(cup)!!))
            assertEquals(listOf(cup), d.looseItems().map { it.id })
            assertEquals(Stage.AT_ORIGIN, d.item(cup)!!.stage)
            // Each thing is counted once: the carton, the crate and the cup.
            assertEquals(setOf(carton, crate, cup), d.movables().map { it.id }.toSet())
            assertEquals(3, d.movables().size)
            assertEquals(Ratio(2, 3), d.progress().loaded)
        }
    }

    @Test
    fun theSameLabelUsedOnTwoPhonesIsFlaggedEverywhereAndNeverComesFromTheApp() {
        val asha = phone("Asha")
        asha.setDevice { it.copy(letter = "A") }
        val omar = invited(asha, "Omar", Role.ORGANISER)
        // Each Organiser adds and invites a Helper without the two phones having synced in between.
        val hal = invited(asha, "Hal", Role.HELPER)
        val hugo = invited(omar, "Hugo", Role.HELPER)
        // The app never hands two people the same letter, so the codes it makes up cannot clash.
        val letters = listOf(asha, omar, hal, hugo).map { it.device.value.letter }
        assertEquals(letters.toString(), 4, letters.toSet().size)
        val codes = listOf(hal, hugo, asha, omar).flatMap { s -> (1..3).map { s.nextHandCode().also { code -> s.createBox(code, null) } } }
        assertEquals(codes.toString(), 12, codes.toSet().size)

        // Two people each stick label L007 from the same printed sheet on a box. Nothing can stop that; both boxes survive and it is flagged.
        val one = hal.createBox("L007", null)
        val two = hugo.createBox("L007", null)
        everyone(asha, omar, hal, hugo)
        for (s in listOf(asha, omar, hal, hugo)) {
            val d = s.data.value
            assertTrue(d.duplicateLetters().isEmpty())
            assertEquals(listOf("L007"), d.duplicateLabels())
            assertNotNull(d.box(one.id))
            assertNotNull(d.box(two.id))
            assertTrue(d.issues().any { it.severe && it.text.contains("L007") })
        }
        // One of them is given a fresh code, and the warning goes away on every phone.
        hugo.put(hugo.box(two.id).copy(label = hugo.nextHandCode()))
        everyone(asha, omar, hal, hugo)
        assertTrue(listOf(asha, omar, hal, hugo).all { it.data.value.duplicateLabels().isEmpty() })
    }

    // ---- connections that drop ---------------------------------------------------------------

    @Test
    fun aSyncCutOffAnywhereLeavesNothingHalfDoneAndTheNextOneFinishesIt() {
        val asha = phone("Asha")
        asha.createBox("L000", null)
        val hal = invited(asha, "Hal", Role.HELPER)
        var n = 0
        for (cut in listOf(Wire.Cut(1, 9), Wire.Cut(2, 1), Wire.Cut(3, 300), Wire.Cut(4, 300), Wire.Cut(5, 2), Wire.Cut(6, 2), Wire.Cut(7, 5000), Wire.Cut(8, 5000), Wire.Cut(8, 0))) {
            n++
            // Both have something new for the other: a box each and a photo big enough to be cut in the middle.
            val hers = asha.createBox("A$n", null)
            val his = hal.createBox("H$n", null)
            val herPhoto = asha.photo(hers.id, PhotoKind.CONTENTS, 20_000)
            val hisPhoto = hal.photo(his.id, PhotoKind.CONTENTS, 20_000)
            val (hosted, joined) = syncVia(asha, hal, cut)
            assertFalse("cut in phase ${cut.phase}", hosted.ok && joined.ok)
            // Whoever noticed is told what happened in words, and never that the versions differ.
            for (r in listOf(hosted, joined)) if (!r.ok) assertTrue("cut in phase ${cut.phase}: ${r.error}", r.error in listOf(WifiSync.CONNECTION_LOST, WifiSync.PHOTOS_CUT))
            // Each phone has either all of the other's data or none of it, and no half-received photo.
            assertEquals("cut in phase ${cut.phase}: has Asha merged?", cut.phase >= 4, asha.data.value.box(his.id) != null)
            assertEquals("cut in phase ${cut.phase}: has Hal merged?", cut.phase >= 5, hal.data.value.box(hers.id) != null)
            for (s in listOf(asha, hal)) {
                assertTrue(s.photoDir.listFiles()!!.none { it.name.endsWith(".part") })
                assertTrue(s.photoDir.listFiles()!!.all { it.length() == 20_000L })
            }
            // The same two try again, and now it is all there.
            synced(asha, hal)
            assertSame(asha, hal, costs = false)
            for (f in listOf(herPhoto, hisPhoto)) for (s in listOf(asha, hal)) assertEquals(20_000L, File(s.photoDir, f.name).length())
        }
    }

    // ---- clocks ----------------------------------------------------------------------------------

    @Test
    fun aPhoneWhoseClockIsAnHourWrongEitherWayLosesNothingAndBreaksNoRule() {
        val hour = 3_600_000L
        val asha = phone("Asha")
        val sofa = asha.createItem("Sofa", null).id
        val lamp = asha.createItem("Lamp", null).id
        val box = asha.createBox("L001", null).id
        val fast = invited(asha, "Fay", Role.HELPER, wrongBy = hour)
        val slow = invited(asha, "Sol", Role.DECIDER, wrongBy = -hour)

        // Work done on either phone arrives, whatever time it thinks it is.
        fast.createBox("F1", null)
        slow.createBox("S1", null)
        fast.put(fast.box(box).copy(contents = "plates"))
        everyone(asha, fast, slow)
        for (s in listOf(asha, fast, slow)) {
            assertEquals(setOf("L001", "F1", "S1"), s.data.value.liveBoxes.map { it.label }.toSet())
            assertEquals("plates", s.box(box).contents)
        }
        // An edit made after a sync wins over what was synced, also on the phone whose clock is an hour behind the one that wrote it.
        slow.put(slow.box(box).copy(contents = "plates and bowls"))
        asha.put(asha.item(lamp).copy(notes = "brass"))
        everyone(asha, fast, slow)
        assertTrue(listOf(asha, fast, slow).all { it.box(box).contents == "plates and bowls" && it.item(lamp).notes == "brass" })

        // The slow phone's Decider suggests, the Organiser approves, she carries it out: every step is later than the one before on every phone.
        val p = slow.suggestDecision(sofa, Disposition.SELL, 50.0).valueOrNull()!!
        synced(asha, slow)
        assertEquals(ProposalState.WAITING, asha.proposal(p.id).state)
        assertTrue(asha.approve(p.id).ok)
        synced(slow, asha)
        assertTrue(slow.carryOut(sofa, gone = true, soldFor = 45.0).ok)
        everyone(asha, fast, slow)
        assertTrue(listOf(asha, fast, slow).all { it.item(sofa).gone && it.item(sofa).disposition == Disposition.SELL })

        // The fast phone's person is made an Organiser, decides, and is made a Helper again: what she decided in between stands, what she tries afterwards does not.
        assertTrue(asha.setRole(fast.id, Role.ORGANISER).ok)
        synced(asha, fast)
        assertTrue(fast.decide(lamp, Disposition.STORE).ok)
        synced(asha, fast)
        assertTrue(asha.setRole(fast.id, Role.HELPER).ok)
        assertTrue(fast.decide(lamp, Disposition.BIN).ok) // her phone has not heard
        everyone(asha, fast, slow)
        for (s in listOf(asha, fast, slow)) {
            assertEquals(Disposition.STORE, s.item(lamp).disposition)
            assertEquals(fast.id, s.item(lamp).decisionBy.by)
            assertEquals(Role.HELPER, s.data.value.person(fast.id)!!.role)
        }
        assertSame(asha, fast, slow, costs = false)

        // An invite is timed by the clock of the phone that shows it, right or wrong: ten minutes there, whatever the joining phone thinks.
        val late = hosting(asha) { now + hour }
        val forNew = late.host.inviteFor(asha.addPerson("Ned", Role.HELPER).valueOrNull()!!.id, local).valueOrNull()!!
        val ned = phone(wrongBy = -hour)
        assertTrue(WifiSync.join(ned, forNew).ok)
        assertTrue(late.result().ok)
        assertEquals("Ned", ned.me!!.name)
    }

    // ---- other versions --------------------------------------------------------------------------

    /** What a phone on 1.3 says first: its own first line, then the code, its name, its letter and its data layout. */
    private fun hello(port: Int, magic: String, token: String, schema: Int, more: List<String> = emptyList()): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            val out = DataOutputStream(s.getOutputStream().buffered())
            out.writeUTF(magic); out.writeUTF(token); out.writeUTF("Old phone"); out.writeUTF("Q"); out.writeInt(schema)
            more.forEach { out.writeUTF(it) }
            out.flush()
            DataInputStream(s.getInputStream()).readUTF()
        }

    @Test
    fun phonesOnDifferentVersionsTellEachOtherSoAndExchangeNothing() {
        val asha = phone("Asha")
        asha.createBox("L001", null)
        val before = asha.data.value
        val h = hosting(asha)
        // A phone on 1.3 (data layout 3) scans the code: it is told the versions differ, in the word it understands.
        assertEquals("VERSION", hello(h.host.port, "HMSYNC2", h.host.token, 3))
        assertEquals(WifiSync.VERSION_MISMATCH, h.result().error)
        // The same for a phone that speaks this version's hello but keeps its data in another layout, older or newer.
        for (schema in listOf(3, SCHEMA + 1)) {
            assertEquals("VERSION", hello(h.host.port, "HMSYNC3", h.host.token, schema, listOf("", "")))
            assertEquals(WifiSync.VERSION_MISMATCH, h.result().error)
        }
        assertEquals(before, asha.data.value)

        // The other way round: this phone scans an invite shown by a phone on 1.3, which hangs up on a first line it does not know.
        val server = ServerSocket(0)
        val old = thread {
            try {
                while (true) server.accept().use { s ->
                    val din = DataInputStream(s.getInputStream().buffered())
                    if (din.readUTF() == "HMSYNC2") {
                        repeat(3) { din.readUTF() }
                        if (din.readInt() != 3) DataOutputStream(s.getOutputStream()).apply { writeUTF("VERSION"); flush() }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val mine = phone("New phone")
        mine.createBox("X1", null)
        val was = mine.data.value
        val me = mine.id
        for (invite in listOf(WifiSync.Invite(local, server.localPort, "TOKEN123"), WifiSync.Invite(local, server.localPort, "TOKEN123", "someone", "Hal", Role.HELPER))) {
            assertEquals(WifiSync.VERSION_MISMATCH, WifiSync.join(mine, invite).error)
            assertEquals(was, mine.data.value)
            assertEquals(me, mine.id) // it has not become anyone else
        }
        server.close()
        old.join(2000)
    }

    // ---- invites -----------------------------------------------------------------------------------

    @Test
    fun anInviteWorksOnceAndForTenMinutesAndSurvivesADroppedConnection() {
        val asha = phone("Asha")
        asha.createBox("L001", null)
        var time = 5_000_000L
        val h = hosting(asha) { time }
        val hal = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        val invite = h.host.inviteFor(hal.id, local).valueOrNull()!!

        // The connection drops while Hal's phone is joining, before the move's data has reached it: the invite is not used up.
        val phone = phone("Hal's phone")
        val dropped = Wire(h.host.port, Wire.Cut(4, 50)).also { wires += it }
        assertFalse(WifiSync.join(phone, invite.copy(port = dropped.port)).ok)
        assertFalse(h.result().ok)
        assertNotEquals(hal.id, phone.id)
        assertEquals(0L, asha.data.value.person(hal.id)!!.joinedAt)
        // While his phone is trying again, a second phone scans the same code: one invite, one phone.
        val other = phone("Someone else")
        val told = ArrayList<String?>()
        val slowly = Wire(h.host.port, null) { phase ->
            if (phase == 3) {
                told += WifiSync.join(other, invite).error
                told += h.result().error
            }
        }.also { wires += it }
        val joined = WifiSync.join(phone, invite.copy(port = slowly.port))
        assertEquals(listOf<String?>(WifiSync.INVITE_EXPIRED, WifiSync.INVITE_EXPIRED), told)
        assertTrue(joined.error, joined.ok)
        assertTrue(h.result().ok)
        assertEquals(hal.id, phone.id)
        assertEquals(Role.HELPER, phone.myRole)
        assertTrue(asha.data.value.person(hal.id)!!.joinedAt > 0)
        assertEquals(1, other.data.value.people.size)
        assertTrue(other.data.value.liveBoxes.isEmpty())

        // Used: nobody can come in on it again, not even the same phone. That phone simply syncs with the ordinary code from now on.
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(other, invite).error)
        h.result()
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(phone, invite).error)
        h.result()
        assertTrue(WifiSync.join(phone, h.host.invite(local)).ok)
        assertTrue(h.result().ok)

        // An invite nobody used runs out after ten minutes; a new one for the same person works.
        val dee = asha.addPerson("Dee", Role.DECIDER).valueOrNull()!!
        val stale = h.host.inviteFor(dee.id, local).valueOrNull()!!
        time += WifiSync.INVITE_MINUTES * 60_000L + 1
        val deePhone = phone("Dee's phone")
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(deePhone, stale).error)
        h.result()
        assertEquals(1, deePhone.data.value.people.size)
        val fresh = h.host.inviteFor(dee.id, local).valueOrNull()!!
        time += WifiSync.INVITE_MINUTES * 60_000L
        assertTrue(WifiSync.join(deePhone, fresh).ok)
        assertTrue(h.result().ok)
        assertEquals(Role.DECIDER, deePhone.myRole)

        // The connection drops after the joining phone has taken the data in but before the photos are through. The phone is that
        // person already. If the host had not finished either, the invite is not used up and the same invite finishes the job...
        val box = asha.data.value.liveBoxes.first()
        val photo = asha.photo(box.id, PhotoKind.CONTENTS, 30_000)
        val pat = asha.addPerson("Pat", Role.HELPER).valueOrNull()!!
        val forPat = h.host.inviteFor(pat.id, local).valueOrNull()!!
        val patPhone = phone("Pat's phone")
        val drop = Wire(h.host.port, Wire.Cut(6, 1)).also { wires += it }
        assertFalse(WifiSync.join(patPhone, forPat.copy(port = drop.port)).ok)
        assertFalse(h.result().ok)
        assertEquals(pat.id, patPhone.id)
        assertEquals(0L, asha.data.value.person(pat.id)!!.joinedAt)
        val again = WifiSync.join(patPhone, forPat)
        assertTrue(again.error, again.ok)
        assertTrue(h.result().ok)
        assertEquals(30_000L, File(patPhone.photoDir, photo.name).length())
        assertTrue(asha.data.value.person(pat.id)!!.joinedAt > 0)
        // ...and if the host had (only its last photo was lost on the way), the invite is used, and the ordinary code brings the rest.
        val mo = asha.addPerson("Mo", Role.HELPER).valueOrNull()!!
        val forMo = h.host.inviteFor(mo.id, local).valueOrNull()!!
        val moPhone = phone("Mo's phone")
        val lateDrop = Wire(h.host.port, Wire.Cut(8, 4000)).also { wires += it }
        assertFalse(WifiSync.join(moPhone, forMo.copy(port = lateDrop.port)).ok)
        assertTrue(h.result().ok)
        assertEquals(mo.id, moPhone.id)
        assertFalse(File(moPhone.photoDir, photo.name).exists())
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(moPhone, forMo).error)
        h.result()
        val plain = WifiSync.join(moPhone, h.host.invite(local))
        assertTrue(plain.error, plain.ok)
        assertTrue(h.result().ok)
        assertEquals(30_000L, File(moPhone.photoDir, photo.name).length())
    }

    @Test
    fun aPhoneTriedOutAloneBringsItsThingsButNothingItDecidedWhenItIsInvited() {
        for (how in listOf("as it comes", "in the old helper mode", "with data from 1.3")) {
            val asha = phone("Asha")
            asha.setMove { it.copy(name = "Maple Road", moveDay = 20_600, vanCapacityM3 = 18.0, currency = "£", setupDone = true) }
            asha.put(Task(id = "t1", title = "Book the van"))
            asha.put(Expense(id = "van", title = "Van hire", amount = 180.0))
            val hana = asha.addPerson("Hana", Role.HELPER).valueOrNull()!!

            // Her phone was used on its own first, after Asha had started the move: as a new phone comes, or with the old
            // "helper mode" switch on (nothing is locked there, and what is set carries nobody's name), or with version 1.3.
            val phone: Store
            if (how == "with data from 1.3") {
                val dir = tmp.newFolder("old" + folders++)
                File(dir, "data.json").writeText(
                    """{"version":3,"move":{"name":"Trying it out","vanCapacityM3":9.0,"setupDone":true,"updatedAt":100},
                    "items":[{"id":"bike","name":"Bike","disposition":"SELL","price":300.0,"listed":true,"updatedAt":50}],
                    "boxes":[{"id":"x1","label":"X1","updatedAt":10}],
                    "tasks":[{"id":"own-task","title":"My own checklist","updatedAt":60}],
                    "expenses":[{"id":"own-cost","title":"Bubble wrap","amount":5.0,"updatedAt":70}]}""",
                )
                File(dir, "device.json").writeText("""{"name":"My phone","letter":"M"}""")
                phone = Store(dir) { now++ }
            } else {
                phone = phone("My phone")
                if (how == "in the old helper mode") phone.setDevice { it.copy(helper = true) }
                phone.setMove { it.copy(name = "Trying it out", vanCapacityM3 = 9.0, setupDone = true) }
                phone.put(Item(id = "bike", name = "Bike"))
                assertTrue(phone.decide("bike", Disposition.SELL, 300.0).ok)
                assertTrue(phone.carryOut("bike", listed = true).ok)
                phone.put(Box(id = "x1", label = "X1"))
                phone.put(Task(id = "own-task", title = "My own checklist"))
                phone.put(Expense(id = "own-cost", title = "Bubble wrap", amount = 5.0))
            }
            val bike = phone.data.value.item("bike")!!
            val box = phone.data.value.box("x1")!!
            assertEquals(Disposition.SELL, bike.disposition)
            assertEquals(1, phone.data.value.liveExpenses.size)

            val h = hosting(asha)
            val r = WifiSync.join(phone, h.host.inviteFor(hana.id, local).valueOrNull()!!)
            assertTrue(r.error, r.ok)
            assertTrue(h.result().ok)
            assertEquals(Role.HELPER, phone.myRole)
            for (s in listOf(asha, phone)) {
                val d = s.data.value
                // The move's own settings, tasks and costs are untouched by what the phone had.
                assertEquals(how, "Maple Road", d.move.name)
                assertEquals(18.0, d.move.vanCapacityM3, 0.0)
                assertEquals(20_600L, d.move.moveDay)
                assertEquals(how, listOf("Book the van"), d.liveTasks.map { it.title })
                // What she catalogued and packed is kept; what she decided about it alone is not a decision of this move.
                assertNotNull(d.box(box.id))
                assertEquals("Bike", d.item(bike.id)!!.name)
                assertEquals(Disposition.UNDECIDED, d.item(bike.id)!!.disposition)
                assertNull(d.item(bike.id)!!.price)
                assertFalse(d.item(bike.id)!!.listed)
            }
            // The Organiser's phone gained no cost from her, and hers, a Helper's phone now, holds none.
            assertEquals(how, listOf("van"), asha.data.value.expenses.map { it.id })
            assertTrue(how, phone.data.value.expenses.isEmpty())
            synced(asha, phone)
            assertEquals(listOf("van"), asha.data.value.expenses.map { it.id })
            assertSame(asha, phone, costs = false)
        }
    }

    @Test
    fun whatWasSetInNobodysNameCountsOnlyFromATimeWhenThereWasNobodyToAsk() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        asha.put(Task(id = "t1", title = "Book the van"))
        // A phone in the old helper mode, on its own. Nothing is locked there, and what it sets carries nobody's name.
        val ben = phone("Ben")
        ben.setDevice { it.copy(helper = true) }
        ben.setMove { it.copy(name = "Ben's list", setupDone = true) }
        val chair = ben.createItem("Chair", null).id
        assertTrue(ben.decide(chair, Disposition.SELL, 10.0).ok)
        ben.put(Task(id = "bens", title = "Ben's task"))
        ben.put(Expense(id = "bens-cost", title = "Tape", amount = 3.0))
        val box = ben.createBox("X1", null).id
        // Asha shows the ordinary code and lets the phone in as it is: a Helper. All of the above was set while her move
        // already had an Organiser to ask, so none of it counts there, although every bit of it is newer than hers.
        synced(asha, ben)
        for (s in listOf(asha, ben)) {
            val d = s.data.value
            assertEquals(Role.HELPER, d.person(ben.id)!!.role)
            assertEquals("Maple Road", d.move.name)
            assertEquals(listOf("Book the van"), d.liveTasks.map { it.title })
            assertTrue(d.expenses.none { it.id == "bens-cost" })
            assertEquals(Disposition.UNDECIDED, d.item(chair)!!.disposition)
            assertNotNull(d.box(box)) // his packing is welcome
        }
        // On a phone that is still on its own it goes on counting: there really is nobody to ask.
        val solo = phone("Solo")
        solo.setDevice { it.copy(helper = true) }
        val lamp = solo.createItem("Lamp", null).id
        assertTrue(solo.decide(lamp, Disposition.BIN).ok)
        val copy = ByteArrayOutputStream().also { Backup.export(solo, it) }.toByteArray()
        solo.put(solo.item(lamp).copy(notes = "brass"))
        Backup.import(solo, ByteArrayInputStream(copy))
        assertEquals(Disposition.BIN, solo.item(lamp).disposition)
    }

    // ---- backups -------------------------------------------------------------------------------

    @Test
    fun aBackupRestoredOnOnePhoneWhileTheOthersCarryOn() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val sofa = asha.createItem("Sofa", null).id
        val old = asha.createBox("L001", null).id
        val doomed = asha.createBox("L002", null).id
        val omar = invited(asha, "Omar", Role.ORGANISER)
        val hal = invited(asha, "Hal", Role.HELPER)
        everyone(asha, omar, hal)
        val backup = ByteArrayOutputStream().also { Backup.export(asha, it) }.toByteArray()

        // Everyone carries on: Hal packs a box and adds one, Omar deletes one, decides and makes Hal a Decider, Asha edits a box and adds one.
        hal.put(hal.box(old).copy(contents = "plates", sealed = true))
        val added = hal.createBox("B1", null).id
        assertTrue(omar.tryDelete(omar.box(doomed)).ok)
        assertTrue(omar.decide(sofa, Disposition.SELL, 60.0).ok)
        assertTrue(omar.setRole(hal.id, Role.DECIDER).ok)
        synced(omar, hal)
        asha.put(asha.box(old).copy(notes = "heavy"))
        val hers = asha.createBox("A9", null).id

        // Asha's phone goes back to the backup, replacing what is on it. What only her phone had since then is gone, as she asked.
        Backup.import(asha, ByteArrayInputStream(backup), replace = true)
        assertNull(asha.data.value.box(hers))
        assertNotNull(asha.data.value.box(doomed))
        // She is still who she was, and still an Organiser.
        assertEquals(Role.ORGANISER, asha.myRole)
        assertTrue(asha.can(Action.MANAGE_PEOPLE))
        // The others' newer work is not undone by her old copy: it comes back to her at the next sync.
        everyone(asha, omar, hal)
        assertSame(asha, omar)
        for (s in listOf(asha, omar, hal)) {
            val d = s.data.value
            assertEquals("plates", d.box(old)!!.contents)
            assertTrue(d.box(old)!!.sealed)
            assertNull(d.box(doomed)) // deleted after the backup was made: it stays deleted
            assertNotNull(d.box(added))
            assertEquals(Disposition.SELL, d.item(sofa)!!.disposition)
            assertEquals(Role.DECIDER, d.person(hal.id)!!.role)
        }
        // What she does after restoring is newer than anything, not as old as the backup.
        asha.put(asha.box(old).copy(contents = "plates, checked"))
        everyone(asha, omar, hal)
        assertTrue(listOf(asha, omar, hal).all { it.box(old).contents == "plates, checked" })
        // Restoring the same backup into the data, without replacing, changes nothing now.
        val settled = asha.data.value
        Backup.import(asha, ByteArrayInputStream(backup))
        assertEquals(settled, asha.data.value)
        // Someone who is not an Organiser cannot restore one at all.
        try {
            Backup.import(hal, ByteArrayInputStream(backup), replace = true)
            fail("a Decider restored a backup")
        } catch (e: IllegalArgumentException) {
            assertEquals("Only an Organiser can restore a backup.", e.message)
        }
    }

    // ---- who the phone is ------------------------------------------------------------------------

    @Test
    fun replacingThePhoneSettingsWholesaleDoesNotMakeThePhoneNobody() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road") } // the set-up is not finished yet
        val me = asha.id
        // A screen builds the settings afresh instead of copying them, and so leaves out who the phone is.
        asha.setDevice { DeviceInfo(name = "Asha K", letter = "K") }
        assertEquals(me, asha.device.value.personId)
        assertEquals(me, asha.id)
        assertEquals("Asha K", asha.me!!.name)
        assertEquals("K", asha.me!!.letter)
        assertEquals(Role.ORGANISER, asha.myRole)
        assertTrue(asha.changeMove { it.copy(setupDone = true) }.ok) // she can finish setting up

        // The same on a phone in someone else's move: it stays the person it joined as, with that person's rights.
        val hal = invited(asha, "Hal", Role.HELPER)
        val halId = hal.id
        hal.setDevice { DeviceInfo(name = "Hal", letter = hal.device.value.letter) }
        assertEquals(halId, hal.id)
        assertEquals(Role.HELPER, hal.myRole)
        // After a restart it is still him, and has not turned into a new person with more rights.
        hal.close()
        val again = Store(tmp.root.resolve("p1")) { now++ }
        assertEquals(halId, again.id)
        assertEquals(Role.HELPER, again.myRole)
        assertEquals(2, again.data.value.people.size)
    }

    @Test
    fun afterStartingAgainThePhoneHasAPersonItMayActAs() {
        // Erased by an Organiser.
        val asha = phone("Asha")
        asha.createBox("L001", null)
        val hal = invited(asha, "Hal", Role.HELPER)
        val was = asha.id
        assertTrue(asha.erase().ok)
        assertNotEquals(was, asha.id)
        assertEquals(asha.id, asha.device.value.personId)
        assertTrue(asha.changeMove { it.copy(name = "A new start", setupDone = true) }.ok)
        assertTrue(asha.decide(asha.createItem("Chair", null).id, Disposition.MOVE).ok)

        // Emptied with the plain replacement, as the old settings screen does, on a Helper's phone: it is on its own again, with nobody to ask.
        val halWas = hal.id
        hal.replaceAll(AppData())
        assertNotEquals(halWas, hal.id)
        assertNotNull(hal.me)
        assertEquals(listOf(hal.id), hal.data.value.people.map { it.id })
        assertTrue(hal.changeMove { it.copy(name = "Hal's own", setupDone = true) }.ok)
        assertTrue(hal.can(Action.DECIDE))
        // The same when its settings had lost track of who it was, and after a restart.
        hal.setDevice { DeviceInfo(name = "Hal") }
        hal.replaceAll(AppData())
        assertNotNull(hal.me)
        hal.close()
        val again = Store(tmp.root.resolve("p1")) { now++ }
        assertNotNull(again.me)
        assertEquals(1, again.data.value.people.size)
        assertTrue(again.changeMove { it.copy(setupDone = true) }.ok)
    }

    @Test
    fun afterRestoringABackupThePhoneHasAPersonItMayActAs() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val box = asha.createBox("L001", null).id
        val omar = invited(asha, "Omar", Role.DECIDER)
        // A backup from when Omar was a Decider.
        val early = ByteArrayOutputStream().also { Backup.export(omar, it) }.toByteArray()
        assertTrue(asha.setRole(omar.id, Role.ORGANISER).ok)
        synced(asha, omar)
        assertTrue(asha.setRole(asha.id, Role.HELPER).ok) // Asha steps back; Omar now runs the move alone
        synced(asha, omar)

        // Omar restores the old backup over his data. The backup says he is a Decider and Asha the Organiser;
        // if the phone believed that about itself, nobody could run the move, restore again or erase.
        Backup.import(omar, ByteArrayInputStream(early), replace = true)
        assertEquals(Role.ORGANISER, omar.myRole)
        assertFalse(omar.me!!.removed)
        assertTrue(omar.can(Action.RESTORE_BACKUP))
        assertTrue(omar.changeMove { it.copy(name = "Maple Road, again") }.ok)
        assertNotNull(omar.data.value.box(box))
        // The rest of who is who comes back at the next sync.
        synced(asha, omar)
        assertEquals(Role.HELPER, omar.data.value.person(asha.id)!!.role)
        assertEquals("Maple Road, again", asha.data.value.move.name)

        // A new phone restores that backup, over its data or merged into it: it becomes the person whose phone made it, with the
        // role the backup gives him, and not an Organiser of its own making. What he is now, it learns at the next sync.
        for (replace in listOf(true, false)) {
            val fresh = phone("New phone")
            val started = fresh.id
            Backup.import(fresh, ByteArrayInputStream(early), replace)
            assertEquals(omar.id, fresh.id)
            assertNull(fresh.data.value.person(started))
            assertEquals(Role.DECIDER, fresh.myRole)
            assertTrue(fresh.can(Action.WORK))
            assertFalse(fresh.can(Action.MANAGE_PEOPLE))
            assertTrue(fresh.becomePerson(omar.id).ok)
            assertNotNull(fresh.data.value.box(box))
            synced(asha, fresh)
            assertEquals(Role.ORGANISER, fresh.myRole)
            assertEquals(Role.HELPER, fresh.data.value.person(asha.id)!!.role)
        }
    }

    /**
     * A phone that is nobody: its settings name no person, or one the move does not have, while the
     * move has an Organiser. No Store function leaves a phone like that: not replacing its settings,
     * not starting again, not restoring (the three tests above), and not a merge that shows its person
     * was never rightly added (MergeRulesTest: it gets a stand-in at once). So it is written here
     * straight into the data, as a damaged file would be, to pin what happens if it comes about all
     * the same: the phone has a Helper's rights and no more, a sync with a phone that knows its person
     * brings the person back, and the next start gives it a person in any case, never an Organiser
     * by its own say-so.
     */
    @Test
    fun aPhoneWithNoPersonOnAMoveThatHasAnOrganiser() {
        val asha = phone("Asha")
        asha.setMove { it.copy(name = "Maple Road") }
        val sofa = asha.createItem("Sofa", null).id
        val hal = invited(asha, "Hal", Role.HELPER)
        val dir = tmp.root.resolve("p1")
        val halId = hal.id

        // The person is taken out of the data behind the Store's back.
        hal.update { d -> d.copy(people = d.people.filter { it.id != halId }) }
        assertNull(hal.me)
        assertTrue(hal.data.value.hasOrganiser())
        // The least rights: the work, and asking; nothing else. In particular it cannot finish a set-up that is not its to finish.
        assertEquals(Role.HELPER, hal.myRole)
        assertTrue(hal.can(Action.WORK))
        assertTrue(hal.can(Action.ASK))
        for (a in Action.entries.filter { it != Action.WORK && it != Action.ASK }) assertFalse(a.name, hal.can(a))
        assertNotNull(hal.changeMove { it.copy(setupDone = true) }.reason)
        assertNotNull(hal.decide(sofa, Disposition.BIN).reason)
        assertNotNull(hal.erase().reason)
        hal.createBox("B1", null) // packing still works
        // Its own copy of the person is gone, so it shows up at another phone as itself, a stranger; a sync brings the person back.
        synced(asha, hal)
        assertEquals(halId, hal.me!!.id)
        assertNotNull(asha.data.value.findByLabel("B1"))

        // Nobody at all in the phone's settings, and the app is started again: it gets a person, so it is never left as nobody for good.
        hal.close()
        File(dir, "device.json").writeText("""{"name":"Hal","letter":"B"}""")
        val again = Store(dir) { now++ }
        assertNotNull(again.me)
        assertEquals(again.me!!.id, again.device.value.personId)
        // It cannot know which of the people it is, so it is a new one, and not an Organiser by its own say-so.
        assertNotEquals(halId, again.id)
        assertEquals(Role.HELPER, again.myRole)
        assertNotNull(again.decide(sofa, Disposition.BIN).reason)
        // The others' phones treat it as a phone that has not been added: only an Organiser lets it in.
        val dee = invited(asha, "Dee", Role.DECIDER)
        assertEquals(WifiSync.NOT_ADDED, sync(dee, again).second.error)
        // Saying who it is puts it right without anyone's help.
        assertTrue(again.becomePerson(halId).ok)
        assertEquals(Role.HELPER, again.myRole)
        assertTrue(sync(dee, again).second.ok)
        again.close()
    }
}
