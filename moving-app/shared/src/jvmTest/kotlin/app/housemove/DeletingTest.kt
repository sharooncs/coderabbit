package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Who may delete what (Deleting.kt): an Organiser anything; anyone else only an item, container,
 * room or photo they added themselves that nobody else has had a hand in since; otherwise they ask.
 * The Store keeps to it, and so does every merge, so a deletion that slipped through on one phone,
 * or crossed someone else's work on another, does not spread.
 */
class DeletingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private var folders = 0
    private fun store() = Store(tmp.newFolder("s" + folders++)) { clock++ }

    private fun joined(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = store()
        assertTrue(phone.joinAs(p.id, host.data.value).ok)
        host.mergeIn(phone.data.value)
        return phone
    }

    /** What a sync does to the data, both ways. */
    private fun sync(a: Store, b: Store) {
        val merged = a.mergeIn(b.data.value.sentTo(a.myRole, a.me?.id))
        b.mergeIn(merged.sentTo(b.myRole, b.me?.id))
    }

    private fun everyone() = repeat(2) { for (p in listOf(omar, dee, hal)) sync(asha, p) }

    private fun Store.photo(ownerId: String, kind: PhotoKind = PhotoKind.CONTENTS): Photo {
        val f = newPhotoFile().apply { writeBytes(ByteArray(300) { 3 }) }
        return addPhoto(ownerId, kind, f)
    }

    private lateinit var asha: Store // Organiser
    private lateinit var omar: Store // Organiser
    private lateinit var dee: Store // Decider
    private lateinit var hal: Store // Helper
    private lateinit var room: String

    @Before
    fun aMove() {
        asha = store()
        asha.setDevice { it.copy(name = "Asha") }
        asha.setMove { it.copy(name = "Our move", setupDone = true) }
        room = asha.createRoom(Home.ORIGIN, "Kitchen").id
        omar = joined(asha, "Omar", Role.ORGANISER)
        dee = joined(asha, "Dee", Role.DECIDER)
        hal = joined(asha, "Hal", Role.HELPER)
        everyone()
    }

    // ---- the rule, on one phone ------------------------------------------------------------

    @Test
    fun anyoneDeletesWhatTheyAddedThemselvesWhileNobodyElseHasTouchedIt() {
        // Hal's duplicate item, his empty container and his wrong photo: each his own, untouched.
        val dup = hal.createItem("Kettle", room)
        val box = hal.createBox("B1", room)
        val shot = hal.photo(box.id)
        val d = hal.data.value
        for (x in listOf<Rec>(dup, box, d.photos.first { it.id == shot.id })) assertNull(x.toString(), d.mayDelete(hal.me, x))
        assertTrue(hal.tryDelete(d.photos.first { it.id == shot.id }).ok)
        assertFalse(File(hal.photoDir, shot.file).exists())
        assertTrue(hal.tryDelete(dup).ok)
        assertTrue(hal.tryDelete(box).ok)
        assertNull(hal.data.value.item(dup.id))
        assertNull(hal.data.value.box(box.id))
        // ...and it stays deleted everywhere.
        everyone()
        for (s in listOf(asha, omar, dee, hal)) {
            assertNull(s.data.value.item(dup.id))
            assertNull(s.data.value.box(box.id))
            assertTrue(s.data.value.photos.first { it.id == shot.id }.deleted)
        }
        // The log says who.
        assertTrue(asha.data.value.latestActivity(10).any { it.by == hal.me!!.id && it.text.startsWith("deleted") })
    }

    @Test
    fun nobodyButAnOrganiserDeletesWhatSomeoneElseAddedOrWorkedOn() {
        val sofa = asha.createItem("Sofa", room).id
        val halsBox = hal.createBox("B1", room).id
        val deesItem = dee.createItem("Lamp", room).id
        everyone()
        // Dee packs her lamp into Hal's box, so Hal's box now holds someone else's work.
        assertTrue(dee.putInto(deesItem, halsBox).ok)
        everyone()

        // Hal may not delete Asha's sofa, nor his own box now that Dee has packed into it.
        val d = hal.data.value
        assertEquals("Only an Organiser can delete something someone else added. You can ask an Organiser to delete it.", d.mayDelete(hal.me, d.item(sofa)!!))
        val why = d.mayDelete(hal.me, d.box(halsBox)!!)!!
        assertTrue(why, why.startsWith("Dee has worked on it since you added it"))
        assertEquals(why, hal.tryDelete(d.box(halsBox)!!).reason)
        // The plain calls the screens use do nothing either.
        hal.delete(d.item(sofa)!!)
        hal.delete(d.box(halsBox)!!)
        assertNotNull(hal.data.value.item(sofa))
        assertNotNull(hal.data.value.box(halsBox))
        // Nor does a save that marks it deleted, which is how a screen with an old copy might try.
        hal.put(d.item(sofa)!!.copy(deleted = true, notes = "stale"))
        assertNotNull(hal.data.value.item(sofa))
        assertEquals("", hal.data.value.item(sofa)!!.notes)
        // A Decider is told the same. Tasks and costs are the plan: only an Organiser deletes those.
        assertNotNull(dee.data.value.mayDelete(dee.me, dee.data.value.item(sofa)!!))
        asha.put(Task(id = "t1", title = "Book van"))
        asha.put(Expense(id = "e1", title = "Van", amount = 90.0))
        everyone()
        assertEquals("Only an Organiser can delete a task. You can ask an Organiser to delete it.", dee.tryDelete(dee.data.value.task("t1")!!).reason)
        assertEquals("Only an Organiser can delete a cost. You can suggest deleting it.", dee.tryDelete(dee.data.value.expense("e1")!!).reason)
        dee.delete(dee.data.value.task("t1")!!)
        assertNotNull(dee.data.value.task("t1"))

        // An Organiser deletes any of it.
        val o = omar.data.value
        assertTrue(o.canDelete(omar.me, o.item(sofa)!!))
        assertTrue(omar.tryDelete(o.box(halsBox)!!).ok)
        assertNull(omar.data.value.box(halsBox))
        assertNull(omar.data.value.item(deesItem)!!.boxId) // what was in it is let go, not deleted
        assertTrue(omar.tryDelete(o.task("t1")!!).ok)
        everyone()
        assertNull(hal.data.value.box(halsBox))
        assertNull(dee.data.value.task("t1"))
    }

    @Test
    fun aPhotoSomeoneElseTookOrAQuestionTheyAskedCountsAsAHand() {
        val lamp = hal.createItem("Lamp", room).id
        everyone()
        dee.photo(lamp, PhotoKind.CONDITION)
        everyone()
        assertTrue(hal.data.value.mayDelete(hal.me, hal.data.value.item(lamp)!!)!!.startsWith("Dee has worked on it"))

        val chair = hal.createItem("Chair", room).id
        everyone()
        assertTrue(dee.ask(chair, "Is this ours?").ok)
        everyone()
        assertNotNull(hal.data.value.mayDelete(hal.me, hal.data.value.item(chair)!!))

        // A room Hal added, once Asha has planned something to it, is no longer his alone either.
        val loft = hal.createRoom(Home.ORIGIN, "Loft").id
        val spare = hal.createRoom(Home.DEST, "Spare").id
        assertNull(hal.data.value.mayDelete(hal.me, hal.data.value.room(loft)!!))
        everyone()
        val bike = asha.createItem("Bike", loft).id
        assertTrue(asha.planRoom(bike, spare).ok)
        everyone()
        assertNotNull(hal.data.value.mayDelete(hal.me, hal.data.value.room(loft)!!))
        assertNotNull(hal.data.value.mayDelete(hal.me, hal.data.value.room(spare)!!))
        // But simply looking, or saving without changing anything, leaves no hand.
        val desk = hal.createItem("Desk", room)
        everyone()
        dee.put(dee.data.value.item(desk.id)!!)
        everyone()
        assertNull(hal.data.value.mayDelete(hal.me, hal.data.value.item(desk.id)!!))
    }

    @Test
    fun deletingAThingTakesTheOwnPhotosOfItWithIt() {
        val box = dee.createBox("D1", room)
        val mine = dee.photo(box.id)
        assertTrue(dee.tryDelete(box).ok)
        assertTrue(dee.data.value.photos.first { it.id == mine.id }.deleted)
        assertFalse(File(dee.photoDir, mine.file).exists())
    }

    // ---- the rule, again at merge time -----------------------------------------------------

    @Test
    fun aContainerDeletedOnOnePhoneWhileSomeoneElsePacksIntoItComesBackWithWhatWasPacked() {
        val box = hal.createBox("B7", room).id
        val dees = dee.createItem("Mixer", room).id
        everyone()
        // At the same time, before they meet: Hal deletes his box (his own, untouched as far as his phone knows),
        // and Dee packs her mixer into it.
        assertTrue(hal.tryDelete(hal.data.value.box(box)!!).ok)
        assertTrue(dee.putInto(dees, box).ok)
        sync(dee, hal)
        everyone()
        // Hal's deletion crossed Dee's work, so it does not stand, on any phone.
        for (s in listOf(asha, omar, dee, hal)) {
            assertNotNull(s.data.value.box(box))
            assertEquals(box, s.data.value.item(dees)!!.boxId)
        }
        // The same the other way round: whoever merges, and in whichever order.
        val box2 = hal.createBox("B8", room).id
        everyone()
        assertTrue(hal.tryDelete(hal.data.value.box(box2)!!).ok)
        assertTrue(dee.putInto(dees, box2).ok)
        sync(hal, asha)
        sync(asha, dee)
        everyone()
        for (s in listOf(asha, omar, dee, hal)) assertNotNull(s.data.value.box(box2))
    }

    @Test
    fun aDeletionForgedOrMadeBySomeoneWithoutTheRightDoesNotSpread() {
        val sofa = asha.createItem("Sofa", room).id
        val box = asha.createBox("A1", room).id
        val shot = asha.photo(box)
        everyone()
        // Hal's phone writes deletions straight into its data, past its store.
        val t = hal.now()
        hal.update { d ->
            d.copy(
                items = d.items.map { if (it.id == sofa) it.copy(deleted = true, deletedBy = Stamp(hal.me!!.id, t, seal(true)), updatedAt = t) else it },
                boxes = d.boxes.map { if (it.id == box) it.copy(deleted = true, updatedAt = t) else it },
                photos = d.photos.map { if (it.id == shot.id) it.copy(deleted = true, deletedBy = Stamp(asha.me!!.id, t, seal(true)).copy(sum = "x"), updatedAt = t) else it },
            )
        }
        everyone()
        for (s in listOf(asha, omar, dee, hal)) {
            assertNotNull(s.data.value.item(sofa))
            assertNotNull(s.data.value.box(box))
            assertFalse(s.data.value.photos.first { it.id == shot.id }.deleted)
        }
        // The photo file comes back to Hal's phone with the next sync over the wire; here it is only the records.
        // Someone who was removed cannot delete even what they added.
        val halsOwn = hal.createItem("Hal's lamp", room)
        everyone()
        assertTrue(asha.removePerson(hal.me!!.id).ok)
        sync(asha, hal)
        assertEquals("You have been removed from this move.", hal.data.value.mayDelete(hal.data.value.person(hal.me!!.id), hal.data.value.item(halsOwn.id)!!))
    }

    @Test
    fun deletionsMadeOnAPhoneOnItsOwnStayDeletedWhenItJoinsAMove() {
        val solo = store()
        solo.setMove { it.copy(name = "Trying it out", setupDone = true) }
        val keep = solo.createBox("K1", null).id
        val gone = solo.createBox("G1", null).id
        assertTrue(solo.tryDelete(solo.data.value.box(gone)!!).ok)
        val p = asha.addPerson("Sol", Role.HELPER).valueOrNull()!!
        assertTrue(solo.joinAs(p.id, asha.data.value).ok)
        asha.mergeIn(solo.data.value)
        sync(asha, solo)
        assertNotNull(asha.data.value.box(keep))
        assertNull(asha.data.value.box(gone))
        assertNull(solo.data.value.box(gone))
    }

    @Test
    fun dataFrom13KeepsItsDeletions() {
        // A 1.3 tombstone carries no stamp. It is taken as it stands on the phone that has it, and as such is passed on.
        val dir = tmp.newFolder("old")
        File(dir, "data.json").writeText("""{"version":3,"boxes":[{"id":"b1","label":"L1","deleted":true,"updatedAt":5},{"id":"b2","label":"L2","updatedAt":6}]}""")
        val old = Store(dir) { clock++ }
        assertTrue(old.data.value.boxes.first { it.id == "b1" }.deleted)
        val other = store()
        val merged = other.mergeIn(old.data.value)
        assertTrue(merged.boxes.first { it.id == "b1" }.deleted)
        assertNotNull(merged.box("b2"))
        // But a deletion that arrives with no stamp at all, from a phone on this version, is not believed.
        val bare = AppData(boxes = listOf(Box(id = "b2", label = "L2", deleted = true, updatedAt = 99)))
        assertNotNull(old.mergeIn(bare).box("b2"))
    }

    // ---- asking for it instead -------------------------------------------------------------

    @Test
    fun aHelperAsksAndADeciderSuggestsAndAnOrganiserApprovesOrDeclines() {
        val sofa = asha.createItem("Sofa", room).id
        val box = asha.createBox("A2", room).id
        asha.put(Expense(id = "e1", title = "Old quote", amount = 40.0))
        asha.put(Task(id = "t1", title = "Ring the council"))
        everyone()

        // Hal asks for the sofa to be deleted, and Dee for the task and the cost.
        val q = hal.askToDelete(sofa, "We have two").valueOrNull()!!
        assertTrue(q.deletes)
        assertEquals(ProposalKind.QUESTION, q.kind)
        assertEquals(q, hal.askToDelete(sofa).valueOrNull()) // asking twice gives the same request
        val t = dee.askToDelete("t1").valueOrNull()!!
        val c = dee.askToDelete("e1").valueOrNull()!!
        assertEquals(ProposalKind.COST, c.kind)
        assertTrue(c.expense!!.deleted)
        // Asking about something of one's own that one may delete is pointless, and said so.
        val own = hal.createItem("Own", room)
        assertEquals("You can delete it yourself.", hal.askToDelete(own.id).reason)
        // A Helper is not told about costs, so cannot ask about one.
        assertNull(hal.data.value.expense("e1"))
        everyone()
        val d = asha.data.value
        assertEquals("Delete Sofa?", d.describe(d.proposal(q.id)!!))
        assertTrue(d.describe(d.proposal(c.id)!!).startsWith("Delete the cost"))
        // Hal's helper-phone never holds the cost suggestion.
        assertNull(hal.data.value.proposal(c.id))
        // An answer with a decision is not how a request to delete is dealt with.
        assertNotNull(asha.answer(q.id, Disposition.BIN).reason)

        assertTrue(asha.approve(q.id).ok)
        assertTrue(omar.approve(t.id).ok)
        assertTrue(asha.approve(c.id).ok)
        assertTrue(hal.askToDelete(box).ok)
        everyone()
        val declined = asha.data.value.waitingProposals().first { it.deletes && it.targetId == box }
        assertTrue(asha.decline(declined.id, "Still need it").ok)
        everyone()
        for (s in listOf(asha, omar, dee, hal)) {
            assertNull(s.data.value.item(sofa))
            assertNull(s.data.value.task("t1"))
            assertNotNull(s.data.value.box(box))
            assertEquals(ProposalState.APPROVED, s.data.value.proposal(q.id)!!.state)
        }
        for (s in listOf(asha, omar, dee)) assertNull(s.data.value.expense("e1"))

        // An Organiser who deletes the thing directly passes the request by.
        val lamp = asha.createItem("Lamp", room).id
        everyone()
        val r = hal.askToDelete(lamp).valueOrNull()!!
        everyone()
        assertTrue(omar.tryDelete(omar.data.value.item(lamp)!!).ok)
        everyone()
        for (s in listOf(asha, omar, dee, hal)) assertEquals(ProposalState.OVERTAKEN, s.data.value.proposal(r.id)!!.state)
    }
}
