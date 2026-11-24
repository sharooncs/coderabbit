package app.housemove

import app.housemove.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The records of version 2.1 and how they merge: what Helpers are not sent, handing things over, where things
 * are (with the storage unit), damage reports, contents lines and changes refused at a merge. Each merge rule
 * is tried with two phones changing the same thing apart.
 */
class RecordsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private val folders = HashMap<Store, File>()
    private fun store(): Store {
        val dir = tmp.newFolder("s" + folders.size)
        return Store(dir) { clock++ }.also { folders[it] = dir }
    }

    private fun joined(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = store()
        assertTrue(phone.joinAs(p.id, host.data.value).ok)
        host.mergeIn(phone.data.value)
        return phone
    }

    /** A sync: each phone is sent what its person is sent ([sentTo]). */
    private fun sync(a: Store, b: Store) {
        val merged = a.mergeIn(b.data.value.sentTo(a.myRole, a.me?.id))
        b.mergeIn(merged.sentTo(b.myRole, b.me?.id))
    }

    /** A sync that went all the way through: both phones are told so, as WifiSync tells them. */
    private fun fullSync(a: Store, b: Store) {
        sync(a, b)
        a.fullSyncDone(b.me?.id)
        b.fullSyncDone(a.me?.id)
    }

    private fun Store.item(id: String) = data.value.item(id)!!
    private fun Store.box(id: String) = data.value.box(id)!!
    private fun Store.hidden(id: String) = data.value.hiddenOf(id)

    private lateinit var asha: Store // Organiser
    private lateinit var dee: Store // Decider
    private lateinit var hal: Store // Helper
    private lateinit var sam: Store // Helper
    private lateinit var sofa: String
    private lateinit var tv: String
    private lateinit var box: String

    @Before
    fun aMove() {
        asha = store()
        asha.setDevice { it.copy(name = "Asha") }
        asha.setMove { it.copy(name = "Our move", setupDone = true) }
        val lounge = asha.createRoom(Home.DEST, "Lounge")
        val old = asha.createRoom(Home.ORIGIN, "Lounge").copy(mapsTo = lounge.id).also { asha.put(it) }
        sofa = asha.createItem("Sofa", old.id).id
        tv = asha.createItem("TV", old.id).id
        asha.decide(tv, Disposition.MOVE)
        box = asha.createBox("L001", old.id).id
        dee = joined(asha, "Dee", Role.DECIDER)
        hal = joined(asha, "Hal", Role.HELPER)
        sam = joined(asha, "Sam", Role.HELPER)
        for (p in listOf(dee, hal, sam)) sync(asha, p)
    }

    private fun everyone() = listOf(asha, dee, hal, sam)

    private fun allSync(rounds: Int = 2) {
        repeat(rounds) { for (p in listOf(dee, hal, sam)) sync(asha, p) }
    }

    // ---- what Helpers are not sent --------------------------------------------------------

    @Test
    fun valuesPricesAndSerialsAreNotSentToHelpers() {
        assertTrue(asha.setDetails(tv) { it.copy(value = 400.0, make = "Sony", model = "Bravia", serial = "SN-1", warrantyDay = 20_000) }.ok)
        assertTrue(asha.decide(sofa, Disposition.SELL, 50.0).ok)
        assertTrue(asha.carryOut(sofa, gone = true, soldFor = 45.0).ok)
        allSync()
        for (p in listOf(asha, dee)) {
            assertEquals("SN-1", p.hidden(tv)!!.serial)
            assertEquals(400.0, p.hidden(tv)!!.value)
            assertEquals(50.0, p.data.value.priceOf(sofa))
            assertEquals(45.0, p.hidden(sofa)!!.soldFor)
        }
        for (p in listOf(hal, sam)) {
            assertTrue(p.data.value.hidden.isEmpty())
            // What is decided and done is everyone's to know.
            assertEquals(Disposition.SELL, p.item(sofa).disposition)
            assertTrue(p.item(sofa).gone)
            // Nothing of it rides along on the items they hold either.
            assertNull(p.item(sofa).price)
            assertEquals("", p.item(tv).serial)
            assertNull(p.data.value.findByAnyCode("SN-1"))
        }
        assertEquals(tv, asha.data.value.findByAnyCode("SN-1")?.id)
        val sent = asha.data.value.sentTo(Role.HELPER, hal.me!!.id)
        assertTrue(sent.hidden.isEmpty() && sent.expenses.isEmpty() && sent.refused.isEmpty())
        // The screens and the reports read the old fields: filled in from the hidden record where the phone holds it.
        assertEquals("SN-1", asha.data.value.filledIn().item(tv)!!.serial)
        assertEquals("", hal.data.value.filledIn().item(tv)!!.serial)
        // The log, which Helpers are sent, says what was suggested and approved without the price.
        val s = dee.suggestDecision(tv, Disposition.SELL, 300.0).valueOrNull()!!
        allSync()
        assertTrue(asha.approve(s.id).ok)
        allSync()
        assertEquals(300.0, asha.data.value.priceOf(tv))
        assertNull(hal.data.value.priceOf(tv))
        assertTrue(hal.data.value.activity.any { it.text == "suggested: Sell TV" })
        assertTrue(hal.data.value.activity.none { it.text.contains("300") })
    }

    @Test
    fun aHelpersNewerCopyOfAnItemNeverBlanksValueOrSerialElsewhere() {
        assertTrue(asha.setDetails(tv) { it.copy(value = 400.0, serial = "SN-1", make = "Sony") }.ok)
        allSync()
        // Offline: the Helper works on the TV after the Organiser's last change, so his copy of the item is newer.
        hal.put(hal.item(tv).copy(notes = "Screen wrapped", fragile = true, serial = "", value = null))
        hal.updateItems(listOf(tv)) { it.copy(keepUpright = true) }
        assertTrue(hal.item(tv).updatedAt > asha.item(tv).updatedAt)
        allSync()
        for (p in listOf(asha, dee)) {
            assertEquals("Screen wrapped", p.item(tv).notes)
            assertEquals("SN-1", p.hidden(tv)!!.serial)
            assertEquals(400.0, p.hidden(tv)!!.value)
            assertEquals("Sony", p.hidden(tv)!!.make)
        }
        assertTrue(everyone().map { it.item(tv) }.toSet().size == 1)
    }

    @Test
    fun helpersNeverWriteTheHiddenRecord() {
        assertEquals(HELPERS_DETAILS, hal.setDetails(tv) { it.copy(serial = "X") }.reason)
        assertTrue(dee.setDetails(tv) { it.copy(serial = "SN-D") }.ok) // a Decider may
        assertTrue(asha.decide(sofa, Disposition.SELL, 50.0).ok)
        allSync()
        // A Helper's phone that writes one anyway, in its own name, is not believed anywhere.
        val me = hal.me!!.id
        hal.update { d ->
            val t = clock++
            val forged = Hidden(sofa, serial = "FORGED", price = 1.0).let { h ->
                h.copy(detailsBy = Stamp(me, t, h.detailsSum()), priceBy = Stamp(me, t, h.priceSum()), saleBy = Stamp(sum = h.saleSum()), updatedAt = t)
            }
            d.copy(hidden = d.hidden + forged)
        }
        allSync()
        for (p in listOf(asha, dee)) {
            assertEquals(50.0, p.data.value.priceOf(sofa))
            assertEquals("", p.hidden(sofa)!!.serial)
            assertEquals("SN-D", p.hidden(tv)!!.serial)
        }
        val refused = asha.data.value.refusedChanges().filter { it.by == me }
        assertEquals(setOf("details", "price"), refused.map { it.what }.toSet())
        val words = refused.map { asha.data.value.describe(it) }
        assertTrue(words.toString(), words.contains("Hal's phone tried to set the asking price of Sofa; Hal is a Helper."))
        assertTrue(words.none { it.contains("1.0") || it.contains("FORGED") })
    }

    @Test
    fun aMoveFrom20KeepsItsDecisionsAndSalesOnEveryPhone() {
        assertTrue(asha.decide(sofa, Disposition.SELL, 50.0).ok)
        val decided = asha.item(sofa).decisionBy
        assertTrue(asha.setDetails(tv) { it.copy(serial = "SN-1", value = 400.0) }.ok)
        assertTrue(asha.reportDamage(box, "Corner crushed").ok)
        assertTrue(asha.setContents(box, "Plates, bowls and the big pans").ok)
        allSync()
        // The Helper's 2.0 copy was saved before the sale; everyone else's after it.
        val halsOld = asha.data.value
        assertTrue(asha.carryOut(sofa, gone = true, soldFor = 45.0).ok)
        val sold = asha.item(sofa).outcomeBy
        allSync()
        // Every phone goes back to 2.0's way of saving, as if it had never been updated, and then opens 2.1.
        val olds = mapOf(asha to asha.data.value, dee to dee.data.value, hal to halsOld.copy(people = hal.data.value.people), sam to asha.data.value.copy(people = sam.data.value.people))
        val ids = everyone().map { it.me!!.id }
        val reopened = olds.map { (s, d) -> reopenAs20(s, d) }
        asha = reopened[0]; dee = reopened[1]; hal = reopened[2]; sam = reopened[3]
        assertEquals(ids, everyone().map { it.me!!.id })

        // Straight after the update, before any sync: nothing has reset.
        for (p in everyone()) {
            assertEquals(Disposition.SELL, p.item(sofa).disposition)
            assertNull(p.item(sofa).price) // the old field is empty: the price is on the hidden record
            assertEquals(50.0, p.data.value.priceOf(sofa))
            assertEquals("SN-1", p.hidden(tv)!!.serial)
            assertTrue(p.item(sofa).decisionBy.by.isNotEmpty())
            assertEquals(listOf("Plates", "bowls and the big pans"), p.data.value.linesOf(box).map { it.text })
            assertTrue(p.box(box).damaged)
        }
        // Re-sealed with the same author and time.
        assertEquals(decided.by, asha.item(sofa).decisionBy.by)
        assertEquals(decided.at, asha.item(sofa).decisionBy.at)
        assertEquals(Stamp(decided.by, decided.at, seal(50.0)), asha.hidden(sofa)!!.priceBy)
        assertEquals(Stamp(sold.by, sold.at, seal(45.0)), asha.hidden(sofa)!!.saleBy)

        allSync(3)
        for (p in everyone()) {
            assertEquals(p.me!!.name, Disposition.SELL, p.item(sofa).disposition)
            assertTrue(p.me!!.name, p.item(sofa).gone)
            assertEquals(listOf("Plates", "bowls and the big pans"), p.data.value.linesOf(box).map { it.text })
            assertEquals(1, p.data.value.damageOf(box).size)
        }
        for (p in listOf(asha, dee)) {
            assertEquals(50.0, p.data.value.priceOf(sofa))
            assertEquals(45.0, p.hidden(sofa)!!.soldFor)
            assertEquals("SN-1", p.hidden(tv)!!.serial)
            assertEquals(400.0, p.hidden(tv)!!.value)
        }
        assertTrue(asha.data.value.refusedChanges().isEmpty())
        assertEquals(everyone().map { it.item(sofa) }.toSet().size, 1)

        // The Helpers' phones held it all from 2.0. They let it go once an Organiser's phone has everything they had.
        assertTrue(hal.data.value.hidden.isNotEmpty())
        fullSync(hal, sam) // two Helpers: that changes nothing
        assertTrue(hal.data.value.hidden.isNotEmpty())
        fullSync(asha, hal)
        assertTrue(hal.data.value.hidden.isEmpty())
        assertEquals(Disposition.SELL, hal.item(sofa).disposition)
        assertTrue(hal.item(sofa).gone)
        sync(asha, hal)
        assertEquals(50.0, asha.data.value.priceOf(sofa))
        assertEquals("SN-1", asha.hidden(tv)!!.serial)
    }

    @Test
    fun aTamperedOldStampStaysDistrustedAfterTheUpdate() {
        assertTrue(asha.decide(sofa, Disposition.SELL, 50.0).ok)
        allSync()
        // On the Decider's 2.0 phone the price was changed without a new stamp.
        val forged = as20(dee.data.value).let { d -> d.copy(items = d.items.map { if (it.id == sofa) it.copy(price = 5.0) else it }) }
        dee = reopen(dee, forged)
        allSync()
        for (p in everyone()) assertEquals(Disposition.SELL, p.item(sofa).disposition)
        for (p in listOf(asha, dee)) assertEquals(50.0, p.data.value.priceOf(sofa))
    }

    @Test
    fun helpersCannotMakeBackupsAndFindLeavesOutWhatTravelsWithMe() {
        assertNotNull(hal.whyNoBackup())
        assertNull(asha.whyNoBackup())
        assertNull(dee.whyNoBackup())
        asha.updateItems(listOf(tv)) { it.copy(travelsWithMe = true) }
        allSync()
        assertTrue(hal.data.value.search("TV", Role.HELPER).isEmpty())
        assertEquals(listOf(tv), asha.data.value.search("TV").map { it.movable.id })
    }

    @Test
    fun someoneMadeAHelperLosesTheHiddenDataAtTheirNextFullSyncWithAnOrganiser() {
        asha.put(Expense(id = "cost", title = "Van", amount = 100.0, planned = true))
        assertTrue(asha.setDetails(tv) { it.copy(serial = "SN-1") }.ok)
        allSync()
        assertEquals("SN-1", dee.hidden(tv)!!.serial)
        assertTrue(asha.setRole(dee.me!!.id, Role.HELPER).ok)
        // A sync with another Helper tells her, but takes nothing away: that phone may not hold it all.
        sync(asha, sam)
        fullSync(sam, dee)
        assertEquals(Role.HELPER, dee.myRole)
        assertEquals("SN-1", dee.hidden(tv)!!.serial)
        assertTrue(dee.data.value.expenses.isNotEmpty())
        fullSync(asha, dee)
        assertTrue(dee.data.value.hidden.isEmpty())
        assertTrue(dee.data.value.expenses.isEmpty())
        assertEquals("SN-1", asha.hidden(tv)!!.serial)
        assertEquals(100.0, asha.data.value.expense("cost")!!.amount, 0.0)
    }

    // ---- handed over ------------------------------------------------------------------------

    @Test
    fun aHelperHandsOverASaleAndOnlyThatHelperIsSentTheAmount() {
        assertTrue(asha.decide(sofa, Disposition.SELL, 40.0).ok)
        assertEquals("Only something to be sold or donated is handed over.", asha.arrangeHandover(tv, hal.me!!.id).reason)
        assertFalse(hal.arrangeHandover(sofa, hal.me!!.id).ok)
        assertTrue(asha.arrangeHandover(sofa, hal.me!!.id, paid = false).ok)
        allSync()
        assertEquals(40.0, hal.data.value.handoverOf(sofa)!!.amount)
        assertNull(sam.data.value.handoverOf(sofa)!!.amount)
        assertTrue(sam.data.value.collect.isEmpty())
        for (p in everyone()) assertEquals("Not paid", p.data.value.paidLabel(sofa))
        assertEquals(listOf(sofa), hal.data.value.handoversFor(hal.me!!.id).map { it.itemId })

        // The buyer pays online after all; then Sam, not Hal, hands it over. Any Helper may.
        assertTrue(asha.setPaid(sofa).ok)
        allSync()
        for (p in everyone()) assertEquals("Paid", p.data.value.paidLabel(sofa))
        assertNull(hal.data.value.handoverOf(sofa)!!.amount)
        assertTrue(sam.handOver(sofa).ok)
        allSync()
        for (p in everyone()) assertTrue(p.item(sofa).handedOver)
        assertTrue(asha.data.value.issues().none { it.text.contains("still to sell") })
        // Not something that is moving.
        assertFalse(hal.handOver(tv).ok)
    }

    @Test
    fun aHandOverOnSomethingNoLongerForSaleIsNotBelieved() {
        assertTrue(asha.decide(sofa, Disposition.SELL, 40.0).ok)
        allSync()
        // Offline: the Organiser keeps the sofa after all, while Hal marks it handed over.
        assertTrue(asha.decide(sofa, Disposition.MOVE).ok)
        assertTrue(hal.handOver(sofa).ok)
        allSync()
        for (p in everyone()) {
            assertEquals(Disposition.MOVE, p.item(sofa).disposition)
            assertFalse(p.item(sofa).handedOver)
        }
        assertEquals(listOf("Hal's phone tried to mark Sofa handed over; Hal is a Helper."), asha.data.value.refusedChanges().map { asha.data.value.describe(it) })
    }

    // ---- where things are, and the storage unit ---------------------------------------------

    @Test
    fun anUndoOfLoadingWinsOverAnOlderCopyAndAStaleCopyDoesNotUndoLoading() {
        val trip = asha.addTrip().valueOrNull()!!
        allSync()
        assertTrue(hal.loadOnto(trip.id, listOf(box)).ok)
        sync(asha, hal)
        // Offline: Hal finds it was the wrong box and undoes the loading; Asha, who saw it loaded, writes on it later.
        assertTrue(hal.putBack(listOf(box)).ok)
        asha.put(asha.box(box).copy(notes = "Heavy"))
        assertTrue(asha.box(box).updatedAt > hal.box(box).updatedAt)
        sync(asha, hal)
        for (p in listOf(asha, hal)) {
            assertEquals(Stage.AT_ORIGIN, p.box(box).stage)
            assertEquals("Heavy", p.box(box).notes)
        }
        // Sam never heard of any of it. His later change to the box does not put it anywhere.
        assertTrue(hal.loadOnto(trip.id, listOf(box)).ok)
        sam.put(sam.box(box).copy(sealed = true))
        sync(asha, hal)
        sync(asha, sam)
        sync(asha, hal)
        for (p in listOf(asha, hal, sam)) {
            assertEquals(Stage.LOADED, p.box(box).stage)
            assertTrue(p.box(box).sealed)
        }
    }

    @Test
    fun thingsGoToStorageAndComeBackFromIt() {
        val toStorage = asha.addTrip(to = Place.STORAGE).valueOrNull()!!
        assertEquals(STORAGE_TRIP_NAME, toStorage.name)
        val other = asha.createBox("L002", null).id
        allSync()
        assertTrue(hal.loadOnto(toStorage.id, listOf(box)).ok)
        assertTrue(hal.unload(listOf(box)).ok)
        assertEquals(Stage.STORED, hal.box(box).stage)
        assertEquals(Place.STORAGE, hal.data.value.placeOf(hal.box(box).toMovable()))
        assertEquals("At the storage unit", hal.data.value.whereIs(hal.box(box).toMovable()))
        val p = hal.data.value.progress() // two boxes and the TV
        assertEquals(Ratio(1, 3), p.loaded)
        assertEquals(Ratio(1, 3), p.stored)
        assertEquals(Ratio(0, 2), p.delivered) // the stored box is not counted as delivered, or as still to deliver
        assertTrue(hal.data.value.toUnpack().isEmpty())
        assertEquals(listOf(box), hal.data.value.reconcile().inStorage.map { it.id })
        assertTrue(hal.data.value.issues().none { it.text.contains("loaded but") })
        assertTrue(hal.data.value.latestActivity(5).any { it.text == "put in storage L001" })
        // The old Unload screen marks things delivered: from a trip to storage, that means in storage.
        assertTrue(hal.loadOnto(toStorage.id, listOf(other)).ok)
        hal.updateBoxes(listOf(other)) { it.copy(stage = Stage.DELIVERED) }
        assertEquals(Stage.STORED, hal.box(other).stage)
        sync(asha, hal)

        val back = asha.addTrip(from = Place.STORAGE).valueOrNull()!!
        assertEquals(BRING_BACK_TRIP_NAME, back.name)
        assertFalse(asha.addTrip(from = Place.STORAGE, to = Place.STORAGE).ok)
        val third = asha.createBox("L003", null).id
        assertEquals("Box L003 is not in storage.", asha.loadOnto(back.id, listOf(third)).reason)
        assertEquals("Box L001 is in storage: bring it back on a trip from storage.", asha.loadOnto(toStorage.id, listOf(box)).reason)
        allSync()
        // Offline: Asha brings the box back; Sam, who last saw it in storage, changes its note afterwards.
        assertTrue(asha.loadOnto(back.id, listOf(box)).ok)
        assertTrue(asha.unload(listOf(box)).ok)
        assertEquals(Stage.DELIVERED, asha.box(box).stage)
        sam.put(sam.box(box).copy(notes = "Kitchen first"))
        allSync()
        for (s in everyone()) {
            assertEquals(Stage.DELIVERED, s.box(box).stage)
            assertEquals("Kitchen first", s.box(box).notes)
        }
        assertEquals(listOf(box), asha.data.value.toUnpack().map { it.id })
        // Undo of bringing it back: onto the trip, then back into storage.
        assertTrue(asha.putBack(listOf(box)).ok)
        assertEquals(Stage.LOADED, asha.box(box).stage)
        assertTrue(asha.putBack(listOf(box)).ok)
        assertEquals(Stage.STORED, asha.box(box).stage)
    }

    // ---- damage reports ---------------------------------------------------------------------

    @Test
    fun damageReportedWhileAnotherPhoneClearsAnOlderReportStays() {
        val first = hal.reportDamage(box, "Corner crushed").valueOrNull()!!
        allSync()
        assertTrue(asha.box(box).damaged)
        // Offline: the Organiser clears the first report; meanwhile Sam finds a new tear.
        assertTrue(asha.clearDamage(first.id).ok)
        assertTrue(sam.reportDamage(box, "Side torn").ok)
        allSync()
        for (p in everyone()) {
            assertEquals(listOf("Side torn"), p.data.value.damageOf(box).map { it.note })
            assertTrue(p.box(box).damaged)
            assertEquals("Side torn", p.box(box).damageNote)
            assertTrue(p.data.value.damageOf(box, open = false).first { it.id == first.id }.cleared)
        }
        // A Helper cannot clear one, by the switch or by forging it.
        assertFalse(hal.clearDamage(sam.data.value.damageOf(box)[0].id).ok)
        hal.put(hal.box(box).copy(damaged = false))
        assertTrue(hal.box(box).damaged)
        val me = hal.me!!.id
        hal.update { d ->
            val t = clock++
            d.copy(damage = d.damage.map { x -> if (x.cleared) x else x.copy(cleared = true, updatedAt = t).let { it.copy(clearedBy = Stamp(me, t, it.clearedSum())) } })
        }
        allSync()
        for (p in everyone()) assertTrue(p.box(box).damaged)
        assertTrue(asha.data.value.refusedChanges().any { it.what == "cleared" && it.by == me })
        // The Organiser's switch clears what is open; the old way of reporting still works.
        asha.put(asha.box(box).copy(damaged = false))
        assertFalse(asha.box(box).damaged)
        asha.updateItems(listOf(tv)) { it.copy(damaged = true, damageNote = "Screen cracked") }
        assertEquals("Screen cracked", asha.data.value.damageOf(tv).single().note)
        assertEquals(1, asha.data.value.issues().count { it.text == "1 damaged in the move" })
    }

    // ---- contents lines ---------------------------------------------------------------------

    @Test
    fun aRemovedLineNeverComesBackAndNothingIsCountedTwice() {
        assertTrue(asha.setContents(box, "Plates, bowls").ok)
        allSync()
        // Offline: Asha takes the bowls out, and she and Sam both add mugs; then Hal, on his older copy, adds cups and seals the box.
        assertTrue(asha.removeLine(box, "bowls").ok)
        assertTrue(asha.addLines(box, "Mugs").ok)
        assertTrue(sam.addLines(box, "mugs").ok)
        assertTrue(hal.addLines(box, "Cups").ok)
        hal.put(hal.box(box).copy(sealed = true))
        assertTrue(hal.box(box).updatedAt > asha.box(box).updatedAt) // his copy of the box is the newer one, bowls and all
        allSync()
        for (p in everyone()) {
            assertEquals(listOf("plates", "mugs", "cups"), p.data.value.linesOf(box).map { it.text.lowercase() })
            assertTrue(p.box(box).sealed)
            assertFalse(p.box(box).contents.contains("bowls"))
        }
        // The old way of writing the whole text still works, line by line.
        asha.put(asha.box(box).copy(contents = asha.box(box).contents + ", Tea towels"))
        assertEquals(4, asha.data.value.linesOf(box).size)
        assertTrue(asha.data.value.search("towels").any { it.movable.id == box })
    }

    @Test
    fun contentsSavedBy20BecomeLinesFromTheNewestText() {
        // Two phones saved different text for the box under 2.0: the older still had cups.
        val d = asha.data.value
        val older = d.copy(boxes = d.boxes.map { if (it.id == box) it.copy(contents = "Plates, cups", updatedAt = 5_000) else it })
        val newer = d.copy(boxes = d.boxes.map { if (it.id == box) it.copy(contents = "Plates", updatedAt = 6_000) else it })
        val merged = merge(migrate(newer), migrate(older))
        assertEquals(listOf("Plates"), merged.linesOf(box).map { it.text })
        assertEquals("Plates", merged.box(box)!!.contents)
        assertEquals(merged, merge(merged, migrate(older)))
    }

    // ---- refused changes ----------------------------------------------------------------------

    @Test
    fun aRefusedChangeBecomesAnItemForOrganisersWithoutCostDetails() {
        asha.put(Expense(id = "cost", title = "Storage unit deposit", amount = 250.0, planned = true))
        assertTrue(asha.decide(sofa, Disposition.SELL, 40.0).ok)
        allSync()
        val sid = sam.me!!.id
        val forgedAt = clock++
        fun forge() = sam.update { d ->
            d.copy(items = d.items.map { i -> if (i.id == sofa) i.copy(gone = true, updatedAt = forgedAt).let { it.copy(outcomeBy = Stamp(sid, forgedAt, it.outcomeSum())) } else i })
        }
        forge()
        val did = dee.me!!.id
        dee.update { d ->
            val t = clock++
            d.copy(expenses = d.expenses.map { e -> if (e.id == "cost") e.copy(amount = 1.0, updatedAt = t).let { it.copy(costBy = Stamp(did, t, it.costSum())) } else e })
        }
        allSync()
        for (p in everyone()) assertFalse(p.item(sofa).gone)
        assertEquals(250.0, asha.data.value.expense("cost")!!.amount, 0.0)
        val words = asha.data.value.refusedChanges().map { asha.data.value.describe(it) }.toSet()
        assertEquals(setOf("Sam's phone tried to mark Sofa sold; Sam is a Helper.", "Dee's phone tried to change a cost; Dee is a Decider."), words)
        assertTrue(words.none { it.contains("250") || it.contains("1.0") || it.contains("deposit") })
        // Helpers are not sent them; the same refusal found again on another phone is one item.
        for (p in listOf(hal, sam)) assertTrue(p.data.value.refused.isEmpty())
        assertEquals(asha.data.value.refused.toSet(), dee.data.value.refused.toSet())
        // Dismissed, it stays dismissed even when the forged copy turns up again.
        for (r in asha.data.value.refusedChanges()) assertTrue(asha.dismissRefused(r.id).ok)
        assertFalse(dee.dismissRefused(dee.data.value.refused.first().id).ok)
        allSync()
        forge()
        allSync()
        for (p in everyone()) assertFalse(p.item(sofa).gone)
        assertTrue(asha.data.value.refusedChanges().isEmpty())
        assertTrue(dee.data.value.refusedChanges().isEmpty())
    }

    // ---- going back to 2.0's way of saving, for the update tests --------------------------------

    /** [d] as 2.0 saved it: hidden details, damage and contents on the item and box, the price and sale in the decision's and outcome's check values. */
    private fun as20(d: AppData): AppData {
        val h = d.hidden.associateBy { it.id }
        val dmg = d.damage.filter { !it.cleared && !it.deleted }.groupBy { it.thingId }
        val items = d.items.map { i ->
            val x = h[i.id]
            val v = i.copy(
                price = x?.price, soldFor = x?.soldFor, value = x?.value, make = x?.make.orEmpty(), model = x?.model.orEmpty(), serial = x?.serial.orEmpty(),
                boughtDay = x?.boughtDay, warrantyDay = x?.warrantyDay, damaged = dmg[i.id] != null, damageNote = dmg[i.id]?.joinToString("; ") { it.note }.orEmpty(),
                whereBy = Stamp(), handoverBy = Stamp(), handedOverBy = Stamp(),
            )
            v.copy(
                decisionBy = if (i.decisionBy.sum == i.decisionSum()) i.decisionBy.copy(sum = v.decisionSum20()) else i.decisionBy,
                outcomeBy = if (i.outcomeBy.sum == i.outcomeSum()) i.outcomeBy.copy(sum = v.outcomeSum20()) else i.outcomeBy,
            )
        }
        return d.copy(
            items = items, boxes = d.boxes.map { it.copy(whereBy = Stamp()) },
            hidden = emptyList(), damage = emptyList(), lines = emptyList(), collect = emptyList(), refused = emptyList(),
        )
    }

    private fun reopen(s: Store, saved: AppData): Store {
        val dir = folders.getValue(s)
        s.close()
        File(dir, "data.json").writeText(AppJson.encodeToString(saved))
        File(dir, "data.json.bak").delete()
        return Store(dir) { clock++ }.also { folders[it] = dir }
    }

    private fun reopenAs20(s: Store, d: AppData): Store = reopen(s, as20(d))
}
