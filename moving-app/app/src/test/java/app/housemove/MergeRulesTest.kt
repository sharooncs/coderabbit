package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

/**
 * The rules are applied a second time when data arrives from another phone:
 * a protected change made by someone whose role does not allow it is not
 * accepted, whatever that phone's own app let through. "Forged" here means
 * written straight into a phone's data, past its store, as a faulty or
 * tampered app would.
 */
class MergeRulesTest {
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

    /** What a sync does to the data, both ways: each phone is sent what its person's role is sent. */
    private fun sync(a: Store, b: Store) {
        val merged = a.mergeIn(b.data.value.visibleTo(a.myRole))
        b.mergeIn(merged.visibleTo(b.myRole))
    }

    private fun Store.item(id: String) = data.value.item(id)!!
    private fun Store.proposal(id: String) = data.value.proposal(id)!!

    /** Write straight into a phone's data, as its store never would. */
    private fun Store.forgeItem(id: String, f: (Item, Long) -> Item) {
        val t = now()
        update { d -> d.copy(items = d.items.map { if (it.id == id) f(it, t).copy(updatedAt = t) else it }) }
    }

    private lateinit var asha: Store // Organiser
    private lateinit var omar: Store // Organiser
    private lateinit var dee: Store // Decider
    private lateinit var hal: Store // Helper
    private lateinit var sofa: String
    private lateinit var lamp: String

    @Before
    fun aMove() {
        asha = store()
        asha.setDevice { it.copy(name = "Asha") }
        asha.setMove { it.copy(name = "Our move", setupDone = true) }
        val lounge = asha.createRoom(Home.DEST, "Lounge")
        val old = asha.createRoom(Home.ORIGIN, "Lounge").copy(mapsTo = lounge.id).also { asha.put(it) }
        sofa = asha.createItem("Sofa", old.id).id
        lamp = asha.createItem("Lamp", old.id).id
        asha.decide(sofa, Disposition.MOVE)
        asha.put(Task(id = "task", title = "Book the van"))
        asha.put(Expense(id = "cost", title = "Van", amount = 100.0, planned = true))
        omar = joined(asha, "Omar", Role.ORGANISER)
        dee = joined(asha, "Dee", Role.DECIDER)
        hal = joined(asha, "Hal", Role.HELPER)
        for (p in listOf(omar, dee, hal)) sync(asha, p)
    }

    private fun everyoneAgreesOn(itemId: String): Item {
        for (round in 1..2) for (p in listOf(omar, dee, hal)) sync(asha, p)
        val all = listOf(asha, omar, dee, hal).map { it.item(itemId) }
        assertEquals(1, all.toSet().size)
        return all[0]
    }

    // ---- forgeries ---------------------------------------------------------------

    @Test
    fun aDecidersPhoneThatForgesAnItemDecisionIsNotBelieved() {
        val me = dee.me!!.id
        // Signed with her own name, as an app that skipped the check would write it. A harmless change rides along.
        dee.forgeItem(sofa) { i, t ->
            i.copy(disposition = Disposition.SELL, price = 5.0, notes = "scuffed").let { it.copy(decisionBy = Stamp(me, t, it.decisionSum())) }
        }
        assertEquals(Disposition.SELL, dee.item(sofa).disposition)
        sync(asha, dee)
        for (phone in listOf(asha, dee)) {
            assertEquals(Disposition.MOVE, phone.item(sofa).disposition) // the decision is kept
            assertNull(phone.item(sofa).price)
            assertEquals("scuffed", phone.item(sofa).notes) // the rest is accepted
            assertEquals(asha.me!!.id, phone.item(sofa).decisionBy.by)
        }
        assertEquals(asha.item(sofa), dee.item(sofa))

        // Changed without touching the stamp, as code written before roles would.
        dee.forgeItem(sofa) { i, _ -> i.copy(disposition = Disposition.BIN) }
        // With the stamp wiped, to look like data from before roles.
        dee.forgeItem(lamp) { i, _ -> i.copy(disposition = Disposition.DONATE, decisionBy = Stamp()) }
        // The room and "it has gone" are protected the same way.
        dee.forgeItem(lamp) { i, t -> i.copy(destRoomId = null, gone = true, soldFor = 3.0).let { it.copy(roomBy = Stamp(me, t, it.roomSum()), outcomeBy = Stamp(me, t, it.outcomeSum())) } }
        // A Helper's phone gets it straight from hers, before any Organiser has seen it: it is not believed there either.
        sync(hal, dee)
        assertEquals(Disposition.MOVE, hal.item(sofa).disposition)
        assertEquals(Disposition.UNDECIDED, hal.item(lamp).disposition)
        val settledSofa = everyoneAgreesOn(sofa)
        val settledLamp = everyoneAgreesOn(lamp)
        assertEquals(Disposition.MOVE, settledSofa.disposition)
        assertEquals(Disposition.UNDECIDED, settledLamp.disposition)
        assertNotNull(settledLamp.destRoomId)
        assertFalse(settledLamp.gone)
        assertNull(settledLamp.soldFor)

        // A new item is anyone's to add, but not with a decision already on it.
        val t = hal.now()
        val mine = Item(id = "forged-new", name = "Vase", disposition = Disposition.SELL, price = 500.0, updatedAt = t)
            .let { it.copy(decisionBy = Stamp(hal.me!!.id, t, it.decisionSum())) }
        hal.update { it.copy(items = it.items + mine) }
        val vase = everyoneAgreesOn("forged-new")
        assertEquals("Vase", vase.name)
        assertEquals(Disposition.UNDECIDED, vase.disposition)
        assertNull(vase.price)

        // The real thing still works afterwards: an Organiser's decision reaches everyone.
        assertTrue(omar.decide(lamp, Disposition.STORE).ok)
        sync(asha, omar)
        assertEquals(Disposition.STORE, everyoneAgreesOn(lamp).disposition)
    }

    @Test
    fun aForgedApprovalIsNotBelieved() {
        val me = dee.me!!.id
        val p = dee.suggestDecision(lamp, Disposition.SELL, 40.0).valueOrNull()!!
        // Her phone marks it approved in her own name and applies it.
        val t = dee.now()
        dee.update { d ->
            d.copy(
                proposals = d.proposals.map { x -> if (x.id == p.id) x.copy(state = ProposalState.APPROVED, updatedAt = t).let { it.copy(decided = Stamp(me, t, it.stateSum())) } else x },
                items = d.items.map { i -> if (i.id == lamp) i.copy(disposition = Disposition.SELL, price = 40.0, updatedAt = t).let { it.copy(decisionBy = Stamp(me, t, it.decisionSum())) } else i },
            )
        }
        assertTrue(dee.data.value.mayCarryOut(dee.me, dee.item(lamp))) // her own phone was fooled
        sync(asha, dee)
        sync(hal, dee)
        for (phone in listOf(asha, dee, hal)) {
            assertEquals(ProposalState.WAITING, phone.proposal(p.id).state)
            assertEquals("", phone.proposal(p.id).decidedBy)
            assertEquals(Disposition.UNDECIDED, phone.item(lamp).disposition)
            assertFalse(phone.data.value.mayCarryOut(dee.me, phone.item(lamp)))
        }
        // Marked approved with no name at all, or in an Organiser's name but changed afterwards: still waiting.
        dee.update { d -> d.copy(proposals = d.proposals.map { if (it.id == p.id) it.copy(state = ProposalState.APPROVED, updatedAt = dee.now()) else it }) }
        sync(asha, dee)
        assertEquals(ProposalState.WAITING, asha.proposal(p.id).state)
        assertEquals(ProposalState.WAITING, dee.proposal(p.id).state)

        // Carrying out without an approval is not accepted either.
        dee.forgeItem(sofa) { i, at -> i.copy(gone = true).let { it.copy(outcomeBy = Stamp(me, at, it.outcomeSum())) } }
        sync(asha, dee)
        assertFalse(asha.item(sofa).gone)
        assertFalse(dee.item(sofa).gone)

        // A real approval of the very same suggestion then goes through everywhere.
        assertTrue(asha.approve(p.id).ok)
        assertEquals(Disposition.SELL, everyoneAgreesOn(lamp).disposition)
        for (phone in listOf(asha, omar, dee, hal)) {
            assertEquals(ProposalState.APPROVED, phone.proposal(p.id).state)
            assertEquals(asha.me!!.id, phone.proposal(p.id).decidedBy)
        }
        assertTrue(dee.carryOut(lamp, gone = true, soldFor = 35.0).ok)
        assertTrue(everyoneAgreesOn(lamp).gone)

        // What was proposed cannot be swapped after the approval: the approval was for the original.
        dee.update { d -> d.copy(proposals = d.proposals.map { if (it.id == p.id) it.copy(price = 1.0, updatedAt = dee.now()) else it }) }
        sync(asha, dee)
        assertEquals(40.0, asha.proposal(p.id).price!!, 0.0)
        assertEquals(40.0, dee.proposal(p.id).price!!, 0.0)
        assertEquals(ProposalState.APPROVED, dee.proposal(p.id).state)
    }

    @Test
    fun aForgedRoleChangeIsNotBelieved() {
        val me = dee.me!!.id
        fun forgeMe(f: (Person, Long) -> Person) {
            val t = dee.now()
            dee.update { d -> d.copy(people = d.people.map { if (it.id == me) f(it, t).copy(updatedAt = t) else it }) }
        }
        fun stillADecider() {
            sync(asha, dee)
            sync(hal, dee)
            for (phone in listOf(asha, dee, hal)) {
                assertEquals(Role.DECIDER, phone.data.value.person(me)!!.role)
                assertEquals(listOf(asha.me!!.id, omar.me!!.id).sorted(), phone.data.value.livePeople.filter { it.role == Role.ORGANISER }.map { it.id }.sorted())
            }
            assertEquals(Role.DECIDER, dee.myRole)
            assertFalse(dee.can(Action.DECIDE))
        }
        // The role simply overwritten.
        forgeMe { p, _ -> p.copy(role = Role.ORGANISER) }
        stillADecider()
        // A grant she gives herself.
        forgeMe { p, t -> p.copy(role = Role.ORGANISER, grants = p.grants + Grant(Role.ORGANISER, false, t, me)) }
        assertTrue(dee.can(Action.DECIDE)) // her own phone believes it until it next meets another
        // What she "decides" meanwhile carries her name and is thrown out with the promotion.
        dee.put(dee.item(lamp).copy(disposition = Disposition.BIN))
        assertEquals(Disposition.BIN, dee.item(lamp).disposition)
        stillADecider()
        assertEquals(Disposition.UNDECIDED, asha.item(lamp).disposition)
        assertEquals(Disposition.UNDECIDED, dee.item(lamp).disposition)
        // A grant in nobody's name, as if there had been no Organiser to ask. There was.
        forgeMe { p, t -> p.copy(role = Role.ORGANISER, grants = p.grants + Grant(Role.ORGANISER, false, t, "")) }
        stillADecider()
        // Her history wiped and rewritten: the other phones still have it.
        forgeMe { p, t -> p.copy(role = Role.ORGANISER, grants = listOf(Grant(Role.ORGANISER, false, t, ""))) }
        stillADecider()

        // She cannot add an Organiser of her own, remove one, or delete one.
        val t = dee.now()
        val friend = Person(id = "friend", name = "Friend", role = Role.ORGANISER, letter = "F", grants = listOf(Grant(Role.ORGANISER, false, t, me)), updatedAt = t)
        dee.update { d ->
            d.copy(
                people = d.people.map {
                    if (it.id == asha.me!!.id) it.copy(removed = true, deleted = true, grants = it.grants + Grant(Role.ORGANISER, true, t, me), updatedAt = t) else it
                } + friend,
            )
        }
        stillADecider()
        for (phone in listOf(asha, dee, hal)) {
            assertNull(phone.data.value.person("friend"))
            assertFalse(phone.data.value.person(asha.me!!.id)!!.removed)
        }
        assertTrue(asha.can(Action.MANAGE_PEOPLE))

        // A real promotion by an Organiser is believed, and from then on so is what she decides.
        assertTrue(omar.setRole(me, Role.ORGANISER).ok)
        sync(omar, dee)
        assertEquals(Role.ORGANISER, dee.myRole)
        assertTrue(dee.decide(lamp, Disposition.DONATE).ok)
        sync(hal, dee) // the Helper hears of the promotion and the decision together, from her
        sync(asha, hal)
        assertEquals(Disposition.DONATE, asha.item(lamp).disposition)
        assertEquals(Role.ORGANISER, asha.data.value.person(me)!!.role)
    }

    @Test
    fun forgedTasksCostsAndSettingsAreNotBelieved() {
        val h = hal.me!!.id
        val d = dee.me!!.id
        // A Helper's phone invents a task and renames the move.
        val t = hal.now()
        hal.update { x ->
            x.copy(
                tasks = x.tasks + Task(id = "sneaky", title = "Buy me lunch", updatedAt = t).let { it.copy(planBy = Stamp(h, t, it.planSum()), doneBy = Stamp(sum = it.doneSum())) } +
                    Task(id = "bare", title = "No stamp at all", updatedAt = t),
                move = x.move.copy(name = "Hal's move", labelsPrinted = 60, updatedAt = t).let { it.copy(changedBy = Stamp(h, t, it.settingsSum())) },
            )
        }
        // It ticks a task that is not Hal's, and deletes it.
        hal.update { x -> x.copy(tasks = x.tasks.map { if (it.id == "task") it.copy(done = true, deleted = true, updatedAt = hal.now()) else it }) }
        // A Decider's phone changes a cost, marks it paid and adds another.
        val t2 = dee.now()
        dee.update { x ->
            x.copy(
                expenses = x.expenses.map { e ->
                    if (e.id == "cost") e.copy(amount = 1.0, planned = false, updatedAt = t2).let { it.copy(costBy = Stamp(d, t2, it.costSum()), paidBy = Stamp(d, t2, it.paidSum())) } else e
                } + Expense(id = "pizza", title = "Pizza", amount = 30.0, updatedAt = t2).let { it.copy(costBy = Stamp(d, t2, it.costSum()), paidBy = Stamp(d, t2, it.paidSum())) },
                photos = x.photos + Photo(id = "fake-receipt", ownerId = "cost", kind = PhotoKind.RECEIPT, file = "r.jpg", by = d, createdAt = t2, updatedAt = t2),
            )
        }
        for (round in 1..2) for (p in listOf(hal, dee, omar)) sync(asha, p)
        for (phone in listOf(asha, omar, dee, hal)) {
            val x = phone.data.value
            assertEquals(listOf("Book the van"), x.liveTasks.map { it.title })
            assertNull(x.tasks.firstOrNull { it.id == "sneaky" || it.id == "bare" })
            assertFalse(x.task("task")!!.done)
            assertEquals("Our move", x.move.name)
            assertEquals(60, x.move.labelsPrinted) // counting printed labels is not a setting
        }
        for (phone in listOf(asha, omar, dee)) { // the Helper's phone is not sent costs at all
            val x = phone.data.value
            assertEquals(100.0, x.expense("cost")!!.amount, 0.0)
            assertTrue(x.expense("cost")!!.planned)
            assertNull(x.expenses.firstOrNull { it.id == "pizza" })
            assertTrue(x.photosOf("cost").isEmpty())
        }
        assertEquals(asha.data.value.tasks, dee.data.value.tasks)
        assertEquals(asha.data.value.expenses, dee.data.value.expenses)
        assertEquals(asha.data.value.move, hal.data.value.move)

        // The Helper may tick a task that is theirs, and that is believed.
        assertTrue(asha.assign("task", h).ok)
        sync(asha, hal)
        assertTrue(hal.tick("task").ok)
        sync(asha, hal)
        sync(asha, dee)
        assertTrue(asha.data.value.task("task")!!.done)
        assertTrue(dee.data.value.task("task")!!.done)
    }

    // ---- real changes, in any order ---------------------------------------------------

    @Test
    fun aRealApprovalReachesEveryoneThroughTwoAndThreePhonesInAnyOrder() {
        // Each route is a list of syncs; "approve" marks where Asha approves. However the news travels, the end is the same.
        val routes = listOf(
            // Two phones: Dee and Asha meet, Asha approves, they meet again.
            listOf("dee-asha", "approve", "asha-dee"),
            // Three phones: the suggestion travels by the Helper's phone, and so does the approval.
            listOf("dee-hal", "hal-asha", "approve", "asha-hal", "hal-dee"),
            // The suggestion goes direct, the approval comes back by way of another Organiser and the Helper.
            listOf("asha-dee", "approve", "asha-omar", "omar-hal", "hal-dee"),
            // The approval reaches the Helper before the Decider, and the Decider hears it from two phones in turn.
            listOf("dee-omar", "omar-asha", "approve", "hal-asha", "dee-hal", "dee-asha"),
            // Everyone syncs with everyone both before and after.
            listOf("dee-hal", "hal-omar", "omar-asha", "asha-dee", "approve", "omar-asha", "hal-omar", "dee-hal", "asha-dee"),
        )
        for ((n, route) in routes.withIndex()) {
            if (n > 0) aMove()
            val phones = mapOf("asha" to asha, "omar" to omar, "dee" to dee, "hal" to hal)
            val p = dee.suggestDecision(sofa, Disposition.SELL, 90.0).valueOrNull()!!
            for (step in route) {
                if (step == "approve") assertTrue("route $n", asha.approve(p.id).ok)
                else step.split("-").let { (a, b) -> sync(phones.getValue(a), phones.getValue(b)) }
            }
            // Dee has heard, and may carry it out.
            assertEquals("route $n", ProposalState.APPROVED, dee.proposal(p.id).state)
            assertEquals("route $n", Disposition.SELL, dee.item(sofa).disposition)
            assertTrue("route $n", dee.carryOut(sofa, gone = true, soldFor = 85.0).ok)
            // And once everyone has synced, every phone has the same sofa and the same suggestion.
            val item = everyoneAgreesOn(sofa)
            assertEquals("route $n", Disposition.SELL, item.disposition)
            assertEquals("route $n", 90.0, item.price!!, 0.0)
            assertTrue("route $n", item.gone)
            assertEquals("route $n", asha.me!!.id, item.decisionBy.by)
            assertEquals("route $n", dee.me!!.id, item.outcomeBy.by)
            assertEquals("route $n", 1, listOf(asha, omar, dee, hal).map { it.proposal(p.id) }.toSet().size)
            assertEquals("route $n", ProposalState.APPROVED, hal.proposal(p.id).state)
            assertEquals("route $n", 1, listOf(asha, omar, dee, hal).map { it.data.value.people.toSet() }.toSet().size)
        }
    }

    @Test
    fun twoOrganisersApprovingDifferentThingsAtOnceEndUpTheSameEverywhere() {
        val first = dee.suggestDecision(sofa, Disposition.SELL, 90.0).valueOrNull()!!
        sync(asha, dee)
        sync(omar, dee)
        val dan = joined(asha, "Dan", Role.DECIDER)
        val second = dan.suggestDecision(sofa, Disposition.DONATE).valueOrNull()!!
        sync(omar, dan)
        // Asha approves Dee's on her phone while Omar, on his, declines Dee's and approves Dan's.
        assertTrue(asha.approve(first.id).ok)
        assertTrue(omar.decline(first.id, "Dan's idea is better").ok)
        assertTrue(omar.approve(second.id).ok)
        for (order in listOf(listOf(asha to omar, asha to dee, omar to dan), listOf(dan to omar, dee to asha, omar to asha))) {
            for ((a, b) in order) sync(a, b)
        }
        for (round in 1..2) for (p in listOf(omar, dee, dan, hal)) sync(asha, p)
        val phones = listOf(asha, omar, dee, dan, hal)
        assertEquals(1, phones.map { it.item(sofa) }.toSet().size)
        assertEquals(1, phones.map { it.proposal(first.id) }.toSet().size)
        assertEquals(1, phones.map { it.proposal(second.id) }.toSet().size)
        // The later decision stands, and only its Decider has the go-ahead.
        assertEquals(Disposition.DONATE, asha.item(sofa).disposition)
        assertEquals(omar.me!!.id, asha.item(sofa).decisionBy.by)
        assertFalse(asha.data.value.mayCarryOut(dee.me, asha.item(sofa)))
        assertTrue(asha.data.value.mayCarryOut(dan.me, asha.item(sofa)))
        // An Organiser's later word on a suggestion wins over an earlier one.
        assertEquals(ProposalState.DECLINED, asha.proposal(first.id).state)
        assertEquals(ProposalState.APPROVED, asha.proposal(second.id).state)
    }

    @Test
    fun whatAnOrganiserDecidedStaysAfterTheyAreDemotedButNothingTheyDoAfterwardsCounts() {
        assertTrue(omar.decide(sofa, Disposition.SELL, 70.0).ok)
        // Asha makes Omar a Helper before his phone has synced. His phone does not know yet and lets him go on.
        assertTrue(asha.setRole(omar.me!!.id, Role.HELPER).ok)
        assertTrue(omar.decide(lamp, Disposition.BIN).ok)
        assertEquals(Disposition.BIN, omar.item(lamp).disposition)
        // A phone that knows nothing of any of this hears it all at once, from Asha's phone and from his.
        sync(asha, omar)
        sync(asha, dee)
        sync(hal, omar)
        sync(asha, hal)
        for (phone in listOf(asha, omar, dee, hal)) {
            assertEquals(Role.HELPER, phone.data.value.person(omar.me!!.id)!!.role)
            assertEquals(Disposition.SELL, phone.item(sofa).disposition) // decided while he was an Organiser
            assertEquals(70.0, phone.item(sofa).price!!, 0.0)
            assertEquals(Disposition.UNDECIDED, phone.item(lamp).disposition) // decided after he no longer was
        }
        assertEquals(Role.HELPER, omar.myRole)
        assertNotNull(omar.decide(lamp, Disposition.BIN).reason)
        // The same when he is removed altogether.
        assertTrue(asha.removePerson(omar.me!!.id).ok)
        sync(asha, dee)
        assertEquals(Disposition.SELL, dee.item(sofa).disposition)
        assertEquals(omar.me!!.id, dee.item(sofa).decisionBy.by)
    }

    @Test
    fun aSecondRunOfTheSameSyncChangesNothing() {
        dee.suggestDecision(sofa, Disposition.SELL)
        omar.decide(lamp, Disposition.STORE)
        hal.ask(lamp, "Which room?")
        for (round in 1..2) for (p in listOf(omar, dee, hal)) sync(asha, p)
        val before = listOf(asha, omar, dee, hal).map { it.data.value }
        for (p in listOf(omar, dee, hal)) sync(asha, p)
        sync(dee, hal)
        sync(omar, dee)
        assertEquals(before, listOf(asha, omar, dee, hal).map { it.data.value })
        // Merging gives the same records whichever phone does it (only their order in the file can differ).
        fun records(d: AppData) = listOf(d.move, d.items.toSet(), d.tasks.toSet(), d.expenses.toSet(), d.proposals.toSet(), d.people.toSet(), d.photos.toSet(), d.activity.toSet(), d.boxes.toSet())
        assertEquals(records(merge(asha.data.value, hal.data.value)), records(merge(hal.data.value, asha.data.value)))
        assertEquals(records(asha.data.value), records(merge(asha.data.value, asha.data.value)))
    }

    /** Many phones doing allowed things in a random order, syncing at random: once all have synced they hold the same data. */
    @Test
    fun randomWorkAndRandomSyncsAlwaysConverge() {
        for (seed in 1..12) {
            if (seed > 1) aMove()
            val rnd = Random(seed.toLong())
            val phones = listOf(asha, omar, dee, hal)
            val items = listOf(sofa, lamp)
            repeat(60) {
                val p = phones[rnd.nextInt(phones.size)]
                val item = items[rnd.nextInt(items.size)]
                val d = p.data.value
                when (rnd.nextInt(12)) {
                    0 -> p.decide(item, Disposition.entries[rnd.nextInt(Disposition.entries.size)], rnd.nextInt(100).toDouble())
                    1 -> p.suggestDecision(item, Disposition.entries[rnd.nextInt(Disposition.entries.size)])
                    2 -> d.waitingProposals().firstOrNull()?.let { if (it.kind == ProposalKind.QUESTION) p.answer(it.id, Disposition.MOVE) else p.approve(it.id) }
                    3 -> d.waitingProposals().lastOrNull()?.let { p.decline(it.id, "no") }
                    4 -> p.carryOut(item, gone = rnd.nextBoolean(), listed = rnd.nextBoolean())
                    5 -> p.put(p.item(item).copy(notes = "note ${rnd.nextInt(9)}", disposition = Disposition.BIN, stage = Stage.entries[rnd.nextInt(4)]))
                    6 -> p.ask(item, "what about this")
                    7 -> p.put(Task(id = "t${rnd.nextInt(4)}", title = "Task ${rnd.nextInt(9)}", assignedTo = phones[rnd.nextInt(4)].me!!.id))
                    8 -> d.liveTasks.firstOrNull()?.let { p.tick(it.id, rnd.nextBoolean()) }
                    9 -> p.setRole(phones[rnd.nextInt(4)].me!!.id, Role.entries[rnd.nextInt(3)])
                    10 -> p.setMove { it.copy(name = "Move ${rnd.nextInt(9)}", labelsPrinted = it.labelsPrinted + 1) }
                    else -> sync(phones[rnd.nextInt(4)], phones[rnd.nextInt(4)])
                }
                if (rnd.nextInt(3) == 0) sync(phones[rnd.nextInt(4)], phones[rnd.nextInt(4)])
            }
            // Everyone meets everyone, twice round.
            for (round in 1..3) for (a in phones) for (b in phones) if (a !== b) sync(a, b)
            // A Helper's phone is not sent costs; everything else must be identical.
            fun view(s: Store) = s.data.value.let { d -> listOf(d.move, d.items.toSet(), d.tasks.toSet(), d.proposals.toSet(), d.people.toSet(), d.rooms.toSet(), d.activity.toSet()) }
            val views = phones.map { view(it) }
            for (v in views) assertEquals("seed $seed", views[0], v)
        }
    }
}
