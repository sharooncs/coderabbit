package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Suggestions and approvals: a Decider suggests, one Organiser approves or declines, and only then is it carried out. */
class ProposalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private fun store(name: String) = Store(tmp.newFolder(name)) { clock++ }

    private fun joined(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = store("phone-" + name)
        assertTrue(phone.joinAs(p.id, host.data.value).ok)
        host.mergeIn(phone.data.value)
        return phone
    }

    /** What a sync does to the data, both ways. */
    private fun sync(a: Store, b: Store) {
        b.mergeIn(a.mergeIn(b.data.value))
    }

    private lateinit var asha: Store // Organiser
    private lateinit var dee: Store // Decider
    private lateinit var dan: Store // Decider
    private lateinit var hal: Store // Helper
    private lateinit var hall: Room
    private lateinit var sofa: String
    private lateinit var lamp: String

    @Before
    fun aMoveWithFourPeople() {
        asha = store("asha")
        asha.setDevice { it.copy(name = "Asha") }
        val lounge = asha.createRoom(Home.DEST, "Lounge")
        hall = asha.createRoom(Home.DEST, "Hall")
        val old = asha.createRoom(Home.ORIGIN, "Lounge").copy(mapsTo = lounge.id).also { asha.put(it) }
        sofa = asha.createItem("Sofa", old.id).id
        lamp = asha.createItem("Lamp", old.id).id
        dee = joined(asha, "Dee", Role.DECIDER)
        dan = joined(asha, "Dan", Role.DECIDER)
        hal = joined(asha, "Hal", Role.HELPER)
    }

    private fun Store.item(id: String) = data.value.item(id)!!
    private fun Store.proposal(id: String) = data.value.proposal(id)!!

    @Test
    fun aSuggestionIsApprovedAppliedAndOnlyThenCarriedOut() {
        val mine = dee.suggestDecision(sofa, Disposition.SELL, 80.0, note = "Too big for the new lounge").valueOrNull()!!
        assertEquals(ProposalState.WAITING, mine.state)
        assertEquals(ProposalKind.ITEM_DECISION, mine.kind)
        assertEquals(dee.me!!.id, mine.by)
        assertEquals("Sell Sofa for 80", dee.data.value.describe(mine))
        // Nothing has changed yet, and Dee cannot act on it.
        assertEquals(Disposition.UNDECIDED, dee.item(sofa).disposition)
        assertFalse(dee.data.value.mayCarryOut(dee.me, dee.item(sofa)))
        assertEquals("An Organiser has to approve your suggestion for this first.", dee.carryOut(sofa, listed = true).reason)
        assertNotNull(dee.approve(mine.id).reason) // nor approve it herself

        // Dan suggests something else for the same sofa.
        val other = dan.suggestDecision(sofa, Disposition.DONATE).valueOrNull()!!
        sync(asha, dee)
        sync(asha, dan)
        assertEquals(listOf(mine.id, other.id), asha.data.value.waitingProposals().map { it.id })
        assertEquals(2, asha.data.value.waitingCount())
        assertEquals(2, asha.data.value.proposalsFor(sofa).size)

        // One Organiser approves one of them.
        val approved = asha.approve(mine.id).valueOrNull()!!
        assertEquals(ProposalState.APPROVED, approved.state)
        assertEquals(asha.me!!.id, approved.decidedBy)
        assertTrue(approved.decidedAt > approved.createdAt)
        assertEquals(Disposition.SELL, asha.item(sofa).disposition)
        assertEquals(80.0, asha.item(sofa).price!!, 0.0)
        assertEquals(asha.me!!.id, asha.item(sofa).decisionBy.by)
        // The competing suggestion is closed.
        assertEquals(ProposalState.NOT_CHOSEN, asha.proposal(other.id).state)
        assertEquals(0, asha.data.value.waitingCount())
        assertEquals("That suggestion is no longer waiting.", asha.approve(mine.id).reason)
        assertNotNull(asha.decline(other.id, "too late").reason)

        // Dee now has the go-ahead; Dan, whose suggestion it was not, has not.
        sync(asha, dee)
        sync(asha, dan)
        assertEquals(ProposalState.APPROVED, dee.proposal(mine.id).state)
        assertTrue(dee.data.value.mayCarryOut(dee.me, dee.item(sofa)))
        assertTrue(dee.carryOut(sofa, listed = true).ok)
        assertTrue(dee.carryOut(sofa, gone = true, soldFor = 75.0).ok)
        assertFalse(dan.data.value.mayCarryOut(dan.me, dan.item(sofa)))
        assertNotNull(dan.carryOut(sofa, gone = true).reason)
        assertFalse(hal.data.value.mayCarryOut(hal.me, hal.item(sofa)))
        // She still cannot change the decision itself.
        dee.put(dee.item(sofa).copy(disposition = Disposition.MOVE))
        assertEquals(Disposition.SELL, dee.item(sofa).disposition)

        // What she did reaches everyone.
        sync(asha, dee)
        sync(asha, dan)
        for (phone in listOf(asha, dee, dan)) {
            val i = phone.item(sofa)
            assertTrue(i.listed && i.gone)
            assertEquals(75.0, i.soldFor!!, 0.0)
            assertEquals(80.0, i.price!!, 0.0) // the asking price is kept apart from what it sold for
            assertEquals(dee.me!!.id, i.outcomeBy.by)
        }
        // Each person's own suggestions and how they ended.
        assertEquals(listOf(ProposalState.APPROVED), dee.data.value.proposalsBy(dee.me!!.id).map { it.state })
        assertEquals(mapOf(ProposalState.NOT_CHOSEN to 1), dan.data.value.proposalCounts(dan.me!!.id))
        assertTrue(dan.data.value.proposalsBy(hal.me!!.id).isEmpty())

        val log = asha.data.value.latestActivity(30).map { asha.data.value.line(it) }
        for (line in listOf("Dee suggested: Sell Sofa for 80", "Asha approved: Sell Sofa for 80", "Asha decided to sell Sofa", "Dee sold Sofa")) {
            assertTrue("$line not in $log", line in log)
        }
    }

    @Test
    fun theGoAheadEndsWhenTheDecisionIsChangedAndComesBackWithANewApproval() {
        val first = dee.suggestDecision(sofa, Disposition.SELL, 80.0).valueOrNull()!!
        sync(asha, dee)
        asha.approve(first.id)
        sync(asha, dee)
        assertTrue(dee.carryOut(sofa, listed = true).ok)

        // The Organiser thinks again.
        assertTrue(asha.decide(sofa, Disposition.MOVE).ok)
        sync(asha, dee)
        assertFalse(dee.data.value.mayCarryOut(dee.me, dee.item(sofa)))
        assertNotNull(dee.carryOut(sofa, gone = true).reason)
        // The approval is still on record, and what Dee did while it stood is kept.
        assertEquals(ProposalState.APPROVED, dee.proposal(first.id).state)
        assertTrue(asha.item(sofa).listed)
        assertFalse(asha.item(sofa).gone)

        // Even deciding the same thing again is a new decision: the old approval does not cover it.
        assertTrue(asha.decide(sofa, Disposition.SELL, 80.0).ok)
        sync(asha, dee)
        assertFalse(dee.data.value.mayCarryOut(dee.me, dee.item(sofa)))

        // A fresh suggestion, approved, gives the go-ahead again, also when it asks for what is already decided.
        val second = dee.suggestDecision(sofa, Disposition.SELL).valueOrNull()!!
        sync(asha, dee)
        assertEquals(ProposalState.WAITING, asha.proposal(second.id).state)
        assertTrue(asha.approve(second.id).ok)
        sync(asha, dee)
        assertTrue(dee.data.value.mayCarryOut(dee.me, dee.item(sofa)))
        assertEquals(80.0, dee.item(sofa).price!!, 0.0) // no price was proposed, so the one there stays
        assertTrue(dee.carryOut(sofa, gone = true).ok)
    }

    @Test
    fun decliningGivesAReasonAndChangesNothing() {
        val p = dee.suggestDecision(lamp, Disposition.BIN).valueOrNull()!!
        sync(asha, dee)
        assertNotNull(dan.decline(p.id, "no").reason) // a Decider cannot decline either
        val declined = asha.decline(p.id, "  It was a present from Gran ").valueOrNull()!!
        assertEquals(ProposalState.DECLINED, declined.state)
        assertEquals("It was a present from Gran", declined.reason)
        assertEquals(asha.me!!.id, declined.decidedBy)
        sync(asha, dee)
        assertEquals("It was a present from Gran", dee.proposal(p.id).reason)
        assertEquals(Disposition.UNDECIDED, dee.item(lamp).disposition)
        assertFalse(dee.data.value.mayCarryOut(dee.me, dee.item(lamp)))
        assertEquals(mapOf(ProposalState.DECLINED to 1), dee.data.value.proposalCounts(dee.me!!.id))
        assertTrue(asha.data.value.latestActivity().any { it.text == "declined: Bin Lamp" })
        // A Helper cannot suggest at all, and is told what they can do instead.
        assertEquals("Helpers cannot suggest this. You can ask for a decision instead.", hal.suggestDecision(lamp, Disposition.BIN).reason)
        assertNotNull(hal.suggestRoom(lamp, hall.id).reason)
        assertNotNull(hal.suggestCost(Expense(id = newId(), title = "Pizza", amount = 30.0)).reason)
        assertNotNull(hal.suggestTask(Task(id = newId(), title = "Tidy")).reason)
        assertTrue(hal.data.value.proposals.isEmpty())
    }

    @Test
    fun anOrganisersDirectChangeOvertakesAWaitingSuggestion() {
        // Seen by the Organiser first, then changed directly with the ordinary save the old screens use.
        val seen = dee.suggestDecision(sofa, Disposition.SELL, 80.0).valueOrNull()!!
        val room = dee.suggestRoom(sofa, hall.id).valueOrNull()!!
        sync(asha, dee)
        asha.put(asha.item(sofa).copy(disposition = Disposition.DONATE))
        assertEquals(ProposalState.OVERTAKEN, asha.proposal(seen.id).state)
        assertEquals(asha.me!!.id, asha.proposal(seen.id).decidedBy)
        // A suggestion about something else on the same item still waits...
        assertEquals(ProposalState.WAITING, asha.proposal(room.id).state)
        // ...until that is changed directly too.
        asha.planRoom(sofa, null)
        assertEquals(ProposalState.OVERTAKEN, asha.proposal(room.id).state)

        // Not seen yet: Dan suggests on his phone while the Organiser decides on hers.
        val unseen = dan.suggestDecision(lamp, Disposition.SELL).valueOrNull()!!
        asha.decide(lamp, Disposition.MOVE)
        sync(asha, dan)
        for (phone in listOf(asha, dan)) {
            assertEquals(ProposalState.OVERTAKEN, phone.proposal(unseen.id).state)
            assertEquals(Disposition.MOVE, phone.item(lamp).disposition)
        }
        assertEquals(asha.proposal(unseen.id), dan.proposal(unseen.id))
        assertNotNull(asha.approve(unseen.id).reason)

        // A suggestion made after the change is a new one and waits.
        val later = dan.suggestDecision(lamp, Disposition.SELL).valueOrNull()!!
        sync(asha, dan)
        assertEquals(ProposalState.WAITING, asha.proposal(later.id).state)
        // Changing something that is not the decision (a note, loading it) overtakes nothing.
        asha.put(asha.item(lamp).copy(notes = "brass", stage = Stage.LOADED))
        assertEquals(ProposalState.WAITING, asha.proposal(later.id).state)
    }

    @Test
    fun anyoneCanAskAndTheAnswerDependsOnWhoGivesIt() {
        // A Helper asks; the Organiser's answer is the decision.
        val q = hal.ask(sofa, "Is this going to the new house?").valueOrNull()!!
        assertEquals(ProposalKind.QUESTION, q.kind)
        assertNull(q.disposition)
        assertEquals("What to do with Sofa?", hal.data.value.describe(q))
        sync(asha, hal)
        assertEquals(listOf(q.id), asha.data.value.waitingProposals().map { it.id })
        val answered = asha.answer(q.id, Disposition.MOVE, note = "Yes, lounge").valueOrNull()!!
        assertEquals(q.id, answered.id)
        assertEquals(ProposalState.APPROVED, answered.state)
        assertEquals(Disposition.MOVE, asha.item(sofa).disposition)
        assertNotNull(asha.answer(q.id, Disposition.BIN).reason) // already dealt with
        sync(asha, hal)
        assertEquals(ProposalState.APPROVED, hal.proposal(q.id).state)
        assertEquals("Yes, lounge", hal.proposal(q.id).reason)

        // A Decider's answer is only a suggestion; approving it settles the question too.
        val q2 = hal.ask(lamp).valueOrNull()!!
        sync(asha, hal)
        sync(asha, dee)
        assertNotNull(hal.answer(q2.id, Disposition.BIN).reason) // a Helper cannot answer
        val suggestion = dee.answer(q2.id, Disposition.DONATE).valueOrNull()!!
        assertNotEquals(q2.id, suggestion.id)
        assertEquals(ProposalKind.ITEM_DECISION, suggestion.kind)
        assertEquals(q2.id, suggestion.answers)
        assertEquals(ProposalState.WAITING, dee.proposal(q2.id).state)
        assertEquals(Disposition.UNDECIDED, dee.item(lamp).disposition)
        sync(asha, dee)
        assertTrue(asha.approve(suggestion.id).ok)
        assertEquals(ProposalState.APPROVED, asha.proposal(q2.id).state)
        assertEquals(Disposition.DONATE, asha.item(lamp).disposition)
        sync(asha, dee)
        assertTrue(dee.carryOut(lamp, gone = true).ok)
        assertEquals(0, asha.data.value.waitingCount())

        // A question nobody answered is overtaken when the item is decided anyway; one can also be dismissed.
        val q3 = dan.ask(sofa, "Sell it after all?").valueOrNull()!!
        val q4 = dan.ask(lamp).valueOrNull()!!
        sync(asha, dan)
        asha.decide(sofa, Disposition.STORE)
        assertEquals(ProposalState.OVERTAKEN, asha.proposal(q3.id).state)
        assertEquals(ProposalState.DECLINED, asha.decline(q4.id, "Already gone").valueOrNull()!!.state)
        assertNotNull(asha.approve(q3.id).reason)
    }

    @Test
    fun costsAndTasksAreSuggestedApprovedAndThenCarriedOut() {
        // A new cost.
        val skip = Expense(id = newId(), title = "Skip hire", amount = 220.0, category = "Fees", planned = true)
        val p = dee.suggestCost(skip, "for the garden waste").valueOrNull()!!
        assertEquals(ProposalKind.COST, p.kind)
        assertEquals(skip.id, p.targetId)
        assertNull(dee.data.value.expense(skip.id)) // not a cost until approved
        sync(asha, dee)
        assertTrue(asha.approve(p.id).ok)
        val cost = asha.data.value.expense(skip.id)!!
        assertEquals(220.0, cost.amount, 0.0)
        assertTrue(cost.planned)
        assertEquals(asha.me!!.id, cost.costBy.by)

        // Dee may now pay it and attach the receipt; Dan may not.
        sync(asha, dee)
        sync(asha, dan)
        assertTrue(dee.data.value.mayPay(dee.me, dee.data.value.expense(skip.id)!!))
        assertFalse(dee.markPaid(skip.id).valueOrNull()!!.planned)
        val receipt = dee.newPhotoFile().apply { writeBytes(ByteArray(64) { 5 }) }
        assertTrue(dee.attachReceipt(skip.id, receipt).ok)
        assertTrue(receipt.isFile)
        assertNotNull(dan.markPaid(skip.id).reason)
        sync(asha, dee)
        assertFalse(asha.data.value.expense(skip.id)!!.planned)
        assertEquals(1, asha.data.value.photosOf(skip.id, PhotoKind.RECEIPT).size)

        // A change to an existing cost: two suggestions, one approved, and whether it was paid is untouched.
        val more = dee.suggestCost(cost.copy(amount = 250.0)).valueOrNull()!!
        val less = dan.suggestCost(cost.copy(amount = 200.0)).valueOrNull()!!
        sync(asha, dee)
        sync(asha, dan)
        assertTrue(asha.approve(less.id).ok)
        assertEquals(200.0, asha.data.value.expense(skip.id)!!.amount, 0.0)
        assertFalse(asha.data.value.expense(skip.id)!!.planned)
        assertEquals(ProposalState.NOT_CHOSEN, asha.proposal(more.id).state)
        // The change was Dan's, so the go-ahead is now his and no longer Dee's.
        sync(asha, dee)
        sync(asha, dan)
        assertFalse(dee.data.value.mayPay(dee.me, dee.data.value.expense(skip.id)!!))
        assertTrue(dan.data.value.mayPay(dan.me, dan.data.value.expense(skip.id)!!))
        // An Organiser editing a cost directly overtakes a waiting suggestion about it.
        val again = dee.suggestCost(cost.copy(amount = 300.0)).valueOrNull()!!
        sync(asha, dee)
        asha.put(asha.data.value.expense(skip.id)!!.copy(amount = 210.0))
        assertEquals(ProposalState.OVERTAKEN, asha.proposal(again.id).state)

        // A new task, given to the person who suggested it.
        val clean = Task(id = newId(), title = "Book the cleaner", offsetDays = -3, category = "Admin", assignedTo = dee.me!!.id)
        val t = dee.suggestTask(clean).valueOrNull()!!
        assertNull(dee.data.value.task(clean.id))
        sync(asha, dee)
        assertTrue(asha.approve(t.id).ok)
        assertEquals("Book the cleaner", asha.data.value.task(clean.id)!!.title)
        sync(asha, dee)
        assertTrue(dee.tick(clean.id).valueOrNull()!!.done)
        // A change to it has to be suggested again; the tick survives the approval.
        val retitled = dee.suggestTask(dee.data.value.task(clean.id)!!.copy(title = "Book the cleaner for Friday")).valueOrNull()!!
        sync(asha, dee)
        assertTrue(asha.approve(retitled.id).ok)
        assertEquals("Book the cleaner for Friday", asha.data.value.task(clean.id)!!.title)
        assertTrue(asha.data.value.task(clean.id)!!.done)
        assertEquals("A task", asha.data.value.describe(Proposal(id = "x", kind = ProposalKind.TASK)))
        // The log names no amounts: it is sent to Helpers' phones too.
        assertTrue(asha.data.value.activity.none { "220" in it.text || "250" in it.text })
    }

    @Test
    fun aRoomSuggestionChangesOnlyTheRoomAndLeavesTheDecisionAlone() {
        asha.decide(sofa, Disposition.MOVE)
        val decision = dee.suggestDecision(sofa, Disposition.STORE).valueOrNull()!!
        val toHall = dee.suggestRoom(sofa, hall.id, "It blocks the lounge door").valueOrNull()!!
        val nowhere = dan.suggestRoom(sofa, null).valueOrNull()!!
        assertEquals("Sofa to Hall", dee.data.value.describe(toHall))
        sync(asha, dee)
        sync(asha, dan)
        assertTrue(asha.approve(toHall.id).ok)
        assertEquals(hall.id, asha.item(sofa).destRoomId)
        assertEquals(asha.me!!.id, asha.item(sofa).roomBy.by)
        assertEquals(Disposition.MOVE, asha.item(sofa).disposition)
        // The other room suggestion is closed; the suggestion about the decision is a different matter and still waits.
        assertEquals(ProposalState.NOT_CHOSEN, asha.proposal(nowhere.id).state)
        assertEquals(ProposalState.WAITING, asha.proposal(decision.id).state)
        assertEquals(listOf(decision.id), asha.data.value.waitingProposals().map { it.id })
        // Approving where it goes is not a go-ahead to sell or bin it.
        sync(asha, dee)
        assertFalse(dee.data.value.mayCarryOut(dee.me, dee.item(sofa)))
        assertEquals(hall.id, dee.item(sofa).destRoomId)
    }

    @Test
    fun withNoOrganiserNothingWaitsForApproval() {
        // One phone in the old "helper mode": a move with nobody to approve anything.
        val solo = store("solo")
        solo.setDevice { it.copy(helper = true) }
        val item = solo.createItem("Chair", null)
        assertTrue(solo.decide(item.id, Disposition.SELL, 10.0).ok)
        assertTrue(solo.carryOut(item.id, gone = true, soldFor = 8.0).ok)
        val q = solo.ask(item.id).valueOrNull()!!
        assertEquals(ProposalState.APPROVED, solo.answer(q.id, Disposition.BIN).valueOrNull()!!.state)
        assertEquals(Disposition.BIN, solo.data.value.item(item.id)!!.disposition)
    }
}
