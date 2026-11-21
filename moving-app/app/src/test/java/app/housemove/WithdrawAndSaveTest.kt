package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Taking a suggestion back, and the guarded saves of tasks and costs that say what they did. */
class WithdrawAndSaveTest {
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

    private fun sync(a: Store, b: Store) {
        val merged = a.mergeIn(b.data.value.visibleTo(a.myRole))
        b.mergeIn(merged.visibleTo(b.myRole))
    }

    private fun everyone() = repeat(2) { for (p in listOf(omar, dee, hal)) sync(asha, p) }

    private lateinit var asha: Store
    private lateinit var omar: Store
    private lateinit var dee: Store
    private lateinit var hal: Store
    private lateinit var sofa: String

    @Before
    fun aMove() {
        asha = store()
        asha.setDevice { it.copy(name = "Asha") }
        asha.setMove { it.copy(name = "Our move", setupDone = true) }
        sofa = asha.createItem("Sofa", null).id
        omar = joined(asha, "Omar", Role.ORGANISER)
        dee = joined(asha, "Dee", Role.DECIDER)
        hal = joined(asha, "Hal", Role.HELPER)
        everyone()
    }

    // ---- withdrawing -----------------------------------------------------------------------

    @Test
    fun aSuggestionOrQuestionIsWithdrawnByWhoeverMadeItAndIsGoneEverywhere() {
        val s = dee.suggestDecision(sofa, Disposition.SELL, 80.0).valueOrNull()!!
        val q = hal.ask(sofa, "Is this going?").valueOrNull()!!
        everyone()
        // Only the person who made it may take it back, and only while it waits.
        assertEquals("Only the person who made a suggestion can withdraw it.", hal.withdraw(s.id).reason)
        assertEquals("Only the person who made a suggestion can withdraw it.", asha.withdraw(q.id).reason)
        assertEquals(s.id, dee.withdraw(s.id).valueOrNull()!!.id)
        assertTrue(hal.withdraw(q.id).ok)
        assertNull(dee.data.value.proposal(s.id))
        assertEquals("That suggestion is no longer here.", dee.withdraw(s.id).reason)
        everyone()
        for (p in listOf(asha, omar, dee, hal)) {
            val d = p.data.value
            assertNull(d.proposal(s.id))
            assertNull(d.proposal(q.id))
            assertTrue(d.waitingProposals().isEmpty())
            assertTrue(d.proposals.first { it.id == s.id }.isWithdrawn)
        }
        // Nothing was decided, and an Organiser can no longer approve what was withdrawn.
        assertEquals(Disposition.UNDECIDED, asha.data.value.item(sofa)!!.disposition)
        assertNotNull(asha.approve(s.id).reason)
        assertTrue(asha.data.value.latestActivity(5).any { it.text == "withdrew a suggestion" })
    }

    @Test
    fun anApprovalOrDeclineMadeMeanwhileOnAnotherPhoneStandsOverTheWithdrawal() {
        val s = dee.suggestDecision(sofa, Disposition.DONATE).valueOrNull()!!
        val other = dee.suggestRoom(sofa, null).valueOrNull()!!
        everyone()
        // Before the phones meet: Asha approves one and Omar declines the other, while Dee withdraws both.
        assertTrue(asha.approve(s.id).ok)
        assertTrue(omar.decline(other.id, "No").ok)
        assertTrue(dee.withdraw(s.id).ok)
        assertTrue(dee.withdraw(other.id).ok)
        // Whoever meets whom first, every phone ends with the approval and the decline, and the item decided.
        sync(dee, omar)
        sync(hal, dee)
        everyone()
        for (p in listOf(asha, omar, dee, hal)) {
            val d = p.data.value
            assertEquals(ProposalState.APPROVED, d.proposal(s.id)!!.state)
            assertEquals(ProposalState.DECLINED, d.proposal(other.id)!!.state)
            assertEquals(Disposition.DONATE, d.item(sofa)!!.disposition)
        }
        // So Dee still has her go-ahead to carry it out.
        assertTrue(dee.carryOut(sofa, gone = true).ok)
    }

    @Test
    fun aWithdrawalWrittenBySomeoneElseIsNotBelieved() {
        val s = dee.suggestDecision(sofa, Disposition.BIN).valueOrNull()!!
        everyone()
        // Hal's phone deletes Dee's suggestion past its store, and also signs it as hers.
        val t = hal.now()
        hal.update { d -> d.copy(proposals = d.proposals.map { if (it.id == s.id) it.copy(deleted = true, updatedAt = t) else it }) }
        everyone()
        assertNotNull(asha.data.value.proposal(s.id))
        val t2 = hal.now()
        hal.update { d -> d.copy(proposals = d.proposals.map { if (it.id == s.id) it.copy(deleted = true, withdrawn = Stamp(hal.me!!.id, t2, "x"), updatedAt = t2) else it }) }
        everyone()
        for (p in listOf(asha, omar, dee, hal)) assertEquals(ProposalState.WAITING, p.data.value.proposal(s.id)!!.state)
    }

    // ---- tasks and costs with an answer ----------------------------------------------------

    @Test
    fun savingATaskOrACostSaysWhatHappened() {
        val task = Task(id = "t1", title = "Book the van", offsetDays = -14)
        val cost = Expense(id = "e1", title = "Van", amount = 120.0, planned = true)
        // A Helper and a Decider are told why, and nothing is saved.
        assertEquals("Only an Organiser can decide this.", hal.saveTask(task).reason)
        assertEquals("You can suggest this, and an Organiser decides.", dee.saveTask(task).reason)
        assertEquals("You can suggest this, and an Organiser decides.", dee.saveCost(cost).reason)
        assertNull(dee.data.value.task("t1"))
        // An Organiser's goes through and says so.
        assertEquals("Book the van", asha.saveTask(task).valueOrNull()!!.title)
        assertEquals(120.0, asha.saveCost(cost).valueOrNull()!!.amount, 0.0)
        everyone()
        // Changing: the same.
        val moved = dee.data.value.task("t1")!!.copy(offsetDays = -21)
        assertNotNull(dee.saveTask(moved).reason)
        assertEquals(-14, dee.data.value.task("t1")!!.offsetDays)
        assertEquals(-21, omar.saveTask(omar.data.value.task("t1")!!.copy(offsetDays = -21)).valueOrNull()!!.offsetDays)
        // Paying a cost follows the rule for carrying out: a Decider only with an approved suggestion of hers.
        assertNotNull(dee.saveCost(dee.data.value.expense("e1")!!.copy(planned = false)).reason)
        assertTrue(dee.data.value.expense("e1")!!.planned)
        // Whoever a task is given to may tick it through the same call, and nothing more.
        assertTrue(asha.assign("t1", hal.me!!.id).ok)
        everyone()
        assertTrue(hal.saveTask(hal.data.value.task("t1")!!.copy(done = true)).ok)
        assertNotNull(hal.saveTask(hal.data.value.task("t1")!!.copy(title = "Book a bigger van")).reason)
        everyone()
        assertTrue(asha.data.value.task("t1")!!.done)
        assertEquals("Book the van", asha.data.value.task("t1")!!.title)
        // The old plain saves still work as before.
        asha.put(Task(id = "t2", title = "Boxes"))
        assertNotNull(asha.data.value.task("t2"))
    }

    @Test
    fun aDecidersChangeBecomesASuggestionAndAHelpersIsRefused() {
        val made = dee.saveOrSuggest(Task(id = "t9", title = "Defrost the freezer"), "Two days before")
        val p = made.valueOrNull() as Proposal
        assertEquals(ProposalKind.TASK, p.kind)
        assertNull(dee.data.value.task("t9"))
        val cost = dee.saveOrSuggest(Expense(id = "e9", title = "Tape", amount = 8.0), "")
        assertTrue((cost.valueOrNull() as Proposal).kind == ProposalKind.COST)
        assertEquals("Only an Organiser can decide this.", hal.saveOrSuggest(Task(id = "t8", title = "Nap")).reason)
        // For an Organiser it is simply saved.
        assertTrue(asha.saveOrSuggest(Task(id = "t7", title = "Keys"), "").valueOrNull() is Task)
        everyone()
        assertTrue(asha.approve(p.id).ok)
        everyone()
        assertEquals("Defrost the freezer", hal.data.value.task("t9")!!.title)
        // A Decider suggesting a task be deleted, approved, deletes it everywhere.
        val del = dee.saveOrSuggest(dee.data.value.task("t9")!!.copy(deleted = true)).valueOrNull() as Proposal
        assertTrue(del.task!!.deleted)
        everyone()
        assertEquals("Delete the task Defrost the freezer", asha.data.value.describe(asha.data.value.proposal(del.id)!!))
        assertTrue(omar.approve(del.id).ok)
        everyone()
        for (s in listOf(asha, omar, dee, hal)) assertNull(s.data.value.task("t9"))
    }
}
