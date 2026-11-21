package app.housemove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalSpeech
import app.housemove.ui.Speech
import app.housemove.ui.screens.LocalMdScans
import app.housemove.ui.screens.MdScans
import app.housemove.ui.screens.mdCurrentStep
import app.housemove.ui.screens.mdTold
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Move day, costs and tasks, driven through the real screens the way a person would:
 * by scanning, tapping and speaking, never by typing. Several phones take part where
 * roles matter; [pickUp] says whose phone is being looked at.
 *
 * There is no camera or speech service here: [scan] hands a code to the screen as the
 * camera would, and [say] is what the phone's speech sheet hears next.
 * Pictures of the states the standard audit data lacks are saved to build/audit/moveday.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MoveDayFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    /** The phone in hand. */
    private var store by mutableStateOf<Store?>(null)
    private var started = false
    private var nav: NavHostController? = null
    private val data get() = store!!.data.value

    private var scanner: ((String) -> Unit)? = null
    private val heard = ArrayDeque<String>()

    private lateinit var oldKitchen: Room
    private lateinit var oldBedroom: Room
    private lateinit var newKitchen: Room
    private lateinit var newBedroom: Room

    // ---- people and phones ------------------------------------------------------------

    /** Asha's phone: a move that is set up, with two rooms in each home. She is its Organiser. */
    private fun organiser(): Store {
        val s = Store(tmp.newFolder("asha"))
        s.setDevice { it.copy(name = "Asha", letter = "A", nextSeq = 1) }
        s.setMove { it.copy(name = "Test move", moveDay = today() + 10, setupDone = true, currency = "£") }
        newKitchen = s.createRoom(Home.DEST, "Kitchen")
        newBedroom = s.createRoom(Home.DEST, "Bedroom")
        oldKitchen = s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = newKitchen.id).also { s.put(it) }
        oldBedroom = s.createRoom(Home.ORIGIN, "Bedroom").copy(mapsTo = newBedroom.id).also { s.put(it) }
        return s
    }

    /** Another phone, joined to [host]'s move. It is sent what its role is sent: a Helper's gets no costs. */
    private fun joined(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = Store(tmp.newFolder("phone-" + name.lowercase()))
        assertTrue(phone.joinAs(p.id, host.data.value.visibleTo(role)).ok)
        host.mergeIn(phone.data.value)
        return phone
    }

    /** What a sync does to the data, both ways. */
    private fun sync(host: Store, phone: Store) {
        rule.runOnUiThread {
            val role = host.data.value.person(phone.me!!.id)?.role
            phone.mergeIn(host.mergeIn(phone.data.value).visibleTo(role))
        }
        settle()
    }

    /** Look at [phone], on [route]. Everything on screen starts afresh, as when another person's phone is picked up. */
    private fun pickUp(phone: Store, route: String = "home") {
        if (!started) {
            started = true
            rule.setContent {
                val shown = store
                if (shown != null) key(shown) {
                    CompositionLocalProvider(
                        LocalSpeech provides Speech { _, onText -> heard.removeFirstOrNull()?.let(onText) },
                        LocalMdScans provides MdScans { scanner = it },
                    ) { AppRoot(shown, startRoute = "home", onNav = { nav = it }) }
                }
            }
        }
        rule.runOnUiThread { store = phone }
        settle()
        if (route != "home") go(route)
    }

    // ---- hands, eyes and voice -----------------------------------------------------------

    private fun settle() = rule.waitForIdle()

    private fun go(route: String) {
        rule.runOnUiThread { nav!!.navigate(route) }
        settle()
    }

    private fun click(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
            // not inside a scrolling container
        }
        node.performClick()
        settle()
    }

    private fun tap(text: String, index: Int = 0, sub: Boolean = false) =
        click(rule.onAllNodes(hasText(text, substring = sub) and hasClickAction())[index])

    private fun tapDesc(description: String) =
        click(rule.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst())

    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)
    private fun top(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().fetchSemanticsNode().boundsInRoot.top

    /** Nothing on the screen can be typed into. */
    private fun noKeyboardNeeded() = rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

    /** What the camera would report on reading a label. */
    private fun scan(code: String) {
        rule.runOnUiThread { scanner!!.invoke(code) }
        settle()
    }

    /** What the phone's speech sheet hears the next time a microphone button is tapped. */
    private fun say(words: String) {
        heard += words
    }

    private fun shot(name: String) {
        Thread.sleep(150) // photos are decoded off the test clock
        settle()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/audit/moveday/$name.png")
    }

    /** A stand-in photograph, different for every seed. */
    private var pictures = 0
    private fun photo(s: Store, owner: String, kind: PhotoKind = PhotoKind.GENERAL) {
        val seed = pictures++
        val file = s.newPhotoFile()
        val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(listOf(0xFFB9C7D6, 0xFFD9C6A5, 0xFFC7D8C0, 0xFFE2C4C0, 0xFFCFC7E0, 0xFFE6DDB8)[seed % 6].toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33000000
        c.drawRect(0f, 260f, 480f, 360f, p)
        p.color = listOf(0xFF6B4F3A, 0xFF2F4858, 0xFF8A3B2E, 0xFF3F6B4E, 0xFF55407A, 0xFF7A6A2E)[(seed / 2) % 6].toInt()
        val w = 170f + (seed % 3) * 48f
        val h = 144f + (seed % 4) * 29f
        c.drawRoundRect(RectF(240f - w / 2, 288f - h, 240f + w / 2, 288f), 14f, 14f, p)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        s.addPhoto(owner, kind, file)
    }

    // ---- things packed inside things --------------------------------------------------------

    private lateinit var carton: Box
    private lateinit var suitcase: Box
    private lateinit var bag: Box
    private lateinit var lamp: Item
    private lateinit var wallet: Item
    private lateinit var sofa: Item

    /**
     * Three things to carry, six records: a carton with a lamp in it, a suitcase holding
     * a bag that holds a wallet, and a sofa. The lamp and the wallet have labels of their own.
     */
    private fun packed(s: Store) {
        fun item(name: String, room: Room, label: String?) =
            s.createItem(name, room.id).let { s.put(it.copy(label = label, disposition = Disposition.MOVE)); s.data.value.item(it.id)!! }
        carton = s.createBox("L012", oldKitchen.id).let { s.put(it.copy(contents = "Pans", sealed = true)); s.data.value.box(it.id)!! }
        lamp = item("Lamp", oldKitchen, "L900")
        assertTrue(s.putInto(lamp.id, carton.id).ok)
        suitcase = s.createBox("B2", oldBedroom.id, ContainerKind.SUITCASE).let { s.put(it.copy(travelsWithMe = false, sealed = true)); s.data.value.box(it.id)!! }
        bag = s.createBox("G1", oldBedroom.id, ContainerKind.BAG)
        assertTrue(s.nest(bag.id, suitcase.id).ok)
        wallet = item("Passport wallet", oldBedroom, "L901")
        assertTrue(s.putInto(wallet.id, bag.id).ok)
        sofa = item("Sofa", oldKitchen, null)
        for (id in listOf(carton.id, lamp.id, suitcase.id, wallet.id, sofa.id)) photo(s, id)
    }

    private fun stageOf(id: String): Stage = data.box(id)?.stage ?: data.item(id)!!.stage

    @Test
    fun scanningSomethingInsideAContainerLoadsTheContainerAndSaysSo() {
        val asha = organiser()
        packed(asha)
        pickUp(asha, "load")
        // Each thing once: two containers and the sofa, whatever is inside them.
        see("0 of 3 loaded")
        see("To load (3)")
        see("1 thing inside")
        see("2 things inside")
        noKeyboardNeeded()

        tap("Scan labels as they go on")
        scan("HM:L900") // the label on the lamp, which is in the carton
        see("Lamp is inside L012: loaded L012")
        assertEquals(Stage.LOADED, stageOf(carton.id))
        assertEquals(Stage.LOADED, stageOf(lamp.id))
        assertEquals(Stage.AT_ORIGIN, stageOf(suitcase.id))
        see("1 of 3 loaded")
        shot("load-carrier")

        scan("L901") // the wallet, in the bag, in the suitcase
        see("Passport wallet is inside suitcase B2: loaded suitcase B2")
        for (id in listOf(suitcase.id, bag.id, wallet.id)) assertEquals(Stage.LOADED, stageOf(id))
        assertEquals(data.box(suitcase.id)!!.tripId, data.item(wallet.id)!!.tripId)
        see("2 of 3 loaded")

        scan("G1") // the bag's own label, once the suitcase is on
        see("Already on the van")
        see("Bag G1 is inside suitcase B2")
        scan("L404")
        see("Unknown label L404")
        assertEquals(Stage.AT_ORIGIN, stageOf(sofa.id))
        assertEquals(Ratio(2, 3), data.progress().loaded)
    }

    @Test
    fun unloadingAndReconcilingCountAContainerAndWhatIsInItOnce() {
        val asha = organiser()
        packed(asha)
        asha.updateBoxes(listOf(carton.id, suitcase.id)) { it.copy(stage = Stage.LOADED) }
        asha.updateItems(listOf(sofa.id)) { it.copy(stage = Stage.LOADED) }
        pickUp(asha, "reconcile")
        see("3 still on the van")
        see("0/3")
        see("2 things inside")

        go("unload") // opens with the scanner running
        see("0 of 3 delivered")
        scan("L901")
        see("Passport wallet is inside suitcase B2: delivered suitcase B2")
        see("Carry it to")
        see("Bedroom")
        see("1 of 3 delivered")
        for (id in listOf(suitcase.id, bag.id, wallet.id)) assertEquals(Stage.DELIVERED, stageOf(id))
        shot("unload-carrier")
        tap("Here")
        tap("Here")
        for (id in listOf(carton.id, lamp.id, suitcase.id, bag.id, wallet.id, sofa.id)) assertEquals(Stage.DELIVERED, stageOf(id))
        see("3 of 3 delivered")

        go("reconcile")
        see("Everything is accounted for")
        see("3 delivered")
        see("3/3")
        dontSee("still on the van")
        shot("reconcile-all-in")
    }

    @Test
    fun unpackingAContainerShowsWhatShouldComeOutOfIt() {
        val asha = organiser()
        packed(asha)
        asha.updateBoxes(listOf(carton.id, suitcase.id)) { it.copy(stage = Stage.DELIVERED) }
        asha.updateItems(listOf(sofa.id)) { it.copy(stage = Stage.DELIVERED) }
        pickUp(asha, "unpack")
        // The bag is in the suitcase and the lamp in the carton: they come out with those, not on their own.
        see("Boxes (2)")
        see("Furniture (1)")
        see("Suitcase B2")
        see("2 things to come out")
        see("Bag G1")
        see("Passport wallet")
        see("1 thing to come out")
        see("Lamp")
        shot("unpack-container")

        tap("Unpacked")
        tap("Unpacked")
        for (id in listOf(carton.id, lamp.id, suitcase.id, bag.id, wallet.id)) assertEquals(Stage.DONE, stageOf(id))
        tap("Furniture (1)")
        see("Sofa")
        dontSee("Lamp")
        tap("In place")
        assertEquals(Ratio(3, 3), data.progress().unpacked)
    }

    // ---- costs ----------------------------------------------------------------------------------

    @Test
    fun aCostIsAddedWithoutTypingAndTheNextOneStartsTheSame() {
        val asha = organiser()
        pickUp(asha, "budget")
        tap("Costs")
        tap("Add a cost")
        noKeyboardNeeded()
        see("Choose an amount")
        tap("Fuel")
        tap("50")
        tap("+10")
        tap("−1")
        see("£ 59")
        see("Photograph the receipt") // in place of a description
        shot("cost-editor")
        tap("Add cost · £ 59")
        val first = data.liveExpenses.single()
        assertEquals("Fuel", first.category)
        assertEquals(59.0, first.amount, 0.0)
        assertEquals("", first.title)
        assertFalse(first.planned)
        assertEquals(today(), first.day)
        see("Fuel") // named by what it is

        // The same again is two taps. This one is a quote.
        tap("Add a cost")
        see("Add cost · £ 59")
        tap("Not paid yet")
        tap("Add cost · £ 59")
        assertEquals(2, data.liveExpenses.size)
        assertEquals(1, data.liveExpenses.count { it.planned })
        tap("Mark paid")
        assertTrue(data.liveExpenses.none { it.planned })
        noKeyboardNeeded()
        // On her own in the move, nothing about suggesting or approving is on the screen.
        dontSee("Suggest")
        dontSee("Waiting for approval")
        // The keyboard is there for an odd sum, behind a tap.
        tap("Add a cost")
        tap("Other amount")
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("180.50")
        settle()
        tap("Add cost · £ 180.5")
        assertTrue(data.liveExpenses.any { it.amount == 180.5 })
    }

    @Test
    fun aDecidersCostWaitsForAnOrganiserAndIsThenTheirsToPay() {
        val asha = organiser()
        val dee = joined(asha, "Dee", Role.DECIDER)
        pickUp(dee, "budget")
        tap("Costs")
        dontSee("Add a cost")
        tap("Suggest a cost")
        tap("Storage")
        tap("100")
        dontSee("Photograph the receipt") // not hers to add until it is approved
        noKeyboardNeeded()
        tap("Suggest cost · £ 100")
        val p = data.waitingProposals().single()
        assertEquals(ProposalKind.COST, p.kind)
        assertNull(data.expense(p.targetId)) // not a cost yet
        see("Waiting for approval")
        see("You suggested this")
        dontSee("Approve")
        dontSee("Mark paid")
        shot("cost-decider-waiting")

        // On the Organiser's phone the same tag has the answer right there.
        sync(asha, dee)
        pickUp(asha, "budget")
        tap("Costs")
        see("Waiting for approval")
        see("Suggested by Dee")
        shot("cost-organiser-approves")
        tap("Approve")
        assertEquals(100.0, data.expense(p.targetId)!!.amount, 0.0)
        assertTrue(data.expense(p.targetId)!!.planned)
        dontSee("Waiting for approval")

        // Approved: now it is Dee's to pay and to attach the receipt to.
        sync(asha, dee)
        pickUp(dee, "budget")
        tap("Costs")
        see("Not paid yet")
        tap("Mark paid")
        assertFalse(data.expense(p.targetId)!!.planned)
        dontSee("Mark paid")
        tap("Storage")
        see("Photograph the receipt")
        see("Suggest this change") // changing it is still a suggestion
        shot("cost-decider-approved")
        val receipt = dee.newPhotoFile().apply { writeBytes(ByteArray(64) { 5 }) }
        assertTrue(dee.attachReceipt(p.targetId, receipt).ok)
        sync(asha, dee)
        assertFalse(asha.data.value.expense(p.targetId)!!.planned)
        assertEquals(1, asha.data.value.photosOf(p.targetId, PhotoKind.RECEIPT).size)
    }

    @Test
    fun anOrganiserDeclinesASuggestionWithAReasonToTap() {
        val asha = organiser()
        val dee = joined(asha, "Dee", Role.DECIDER)
        val p = dee.suggestCost(Expense(id = newId(), amount = 30.0, category = "Other", day = today(), planned = true), "pizza for the helpers").valueOrNull()!!
        sync(asha, dee)
        pickUp(asha, "budget")
        tap("Costs")
        see("pizza for the helpers")
        tap("Decline")
        noKeyboardNeeded()
        tap("Not needed")
        assertEquals(ProposalState.DECLINED, data.proposal(p.id)!!.state)
        assertEquals("Not needed", data.proposal(p.id)!!.reason)
        dontSee("Waiting for approval")
        assertTrue(data.liveExpenses.isEmpty())
    }

    @Test
    fun aHelperSeesNoCostsAnywhere() {
        val asha = organiser()
        asha.put(Expense(id = "e1", title = "Van hire", amount = 180.0, category = "Van", day = today()))
        asha.put(Supply(id = "s1", name = "Medium boxes", need = 30, have = 12))
        asha.put(Task(id = "van", title = "Book the van", dueDay = today() + 3))
        val dee = joined(asha, "Dee", Role.DECIDER)
        dee.suggestCost(Expense(id = newId(), title = "Skip hire", amount = 220.0, category = "Fees", day = today(), planned = true))
        sync(asha, dee)
        val hal = joined(asha, "Hal", Role.HELPER)
        assertTrue(hal.data.value.liveExpenses.isEmpty()) // his phone was never sent them

        pickUp(hal, "budget")
        see("Medium boxes")
        dontSee("Costs")
        dontSee("costs")
        dontSee("Van hire")
        dontSee("Skip hire")
        dontSee("Add a cost")
        dontSee("Suggest a cost")
        shot("budget-helper")
        tapDesc("Have one more: Medium boxes") // the shopping list is anyone's to keep
        assertEquals(13, data.liveSupplies.single().have)

        go("reports")
        see("Damaged")
        dontSee("Total paid")

        // Jobs are shown, but adding, changing and ticking other people's is not his.
        go("tasks")
        see("Book the van")
        dontSee("New job")
        dontSee("Suggest a job")
        rule.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    // ---- tasks ----------------------------------------------------------------------------------

    @Test
    fun aTaskIsGivenToSomeoneByTappingTheirNameAndTheyTickItOff() {
        val asha = organiser()
        val hal = joined(asha, "Hal", Role.HELPER)
        asha.put(Task(id = "keys", title = "Collect the keys", dueDay = today() + 1))
        asha.put(Task(id = "van", title = "Book the van", dueDay = today() + 3))
        pickUp(asha, "tasks")
        tap("Book the van")
        see("Who does it")
        noKeyboardNeeded()
        tap("Hal")
        assertEquals(hal.me!!.id, data.task("van")!!.assignedTo)
        shot("tasks-given")

        // On Hal's phone his own job comes first, and it is the only one he can tick.
        sync(asha, hal)
        pickUp(hal, "tasks")
        see("Yours")
        assertTrue(top("Yours") < top("Next 7 days"))
        assertTrue(top("Book the van") < top("Collect the keys"))
        rule.onAllNodes(isToggleable()).assertCountEquals(1)
        shot("tasks-helper")
        click(rule.onAllNodes(isToggleable()).onFirst())
        assertTrue(data.task("van")!!.done)
        assertFalse(data.task("keys")!!.done)
        sync(asha, hal)
        assertTrue(asha.data.value.task("van")!!.done)
        assertEquals(hal.me!!.id, asha.data.value.task("van")!!.doneBy.by)
    }

    @Test
    fun theChecklistIsOneTapAndAJobOfYourOwnCanBeSpoken() {
        val asha = organiser()
        pickUp(asha, "tasks")
        tap("Add the checklist")
        assertTrue(data.liveTasks.size > 40)
        dontSee("Add the checklist") // cannot be added twice
        noKeyboardNeeded()
        say("return the neighbour's ladder")
        tapDesc("Say a new job")
        val mine = data.liveTasks.single { it.title == "Return the neighbour's ladder" }
        noKeyboardNeeded()
        // On her own in the move: no names, no "Yours", every job hers to tick.
        dontSee("Yours")
        click(rule.onAllNodes(isToggleable()).onFirst())
        assertEquals(1, data.liveTasks.count { it.done })
        assertFalse(data.task(mine.id)!!.done)
        assertNull(data.task(mine.id)!!.assignedTo)
    }

    @Test
    fun aDecidersJobAndTheirChangesAreSuggestionsUntilApproved() {
        val asha = organiser()
        val dee = joined(asha, "Dee", Role.DECIDER)
        pickUp(dee, "tasks")
        see("Suggest a job")
        dontSee("Add the checklist")
        say("book a skip")
        tapDesc("Say a new job")
        assertTrue(data.liveTasks.isEmpty()) // not a job yet
        val p = data.waitingProposals().single()
        assertEquals(ProposalKind.TASK, p.kind)
        see("Book a skip")
        see("Waiting for approval")
        dontSee("Approve")

        sync(asha, dee)
        pickUp(asha, "tasks")
        see("Suggested by Dee")
        tap("Approve")
        assertEquals("Book a skip", data.task(p.targetId)!!.title)

        // Dee takes it on herself: her taps are gathered and sent as one suggestion.
        sync(asha, dee)
        pickUp(dee, "tasks")
        rule.onAllNodes(isToggleable()).assertCountEquals(0) // not given to her yet
        tap("Book a skip")
        tap("Dee (you)")
        assertNull(data.task(p.targetId)!!.assignedTo)
        tap("Suggest this change")
        val change = data.waitingProposals().single()
        assertEquals(dee.me!!.id, change.task!!.assignedTo)
        see("Give it to you")
        shot("tasks-decider-change")
        sync(asha, dee)
        assertTrue(asha.approve(change.id).ok)
        sync(asha, dee)
        rule.onAllNodes(isToggleable()).assertCountEquals(1) // hers to tick now
    }

    // ---- records and damage -----------------------------------------------------------------------

    @Test
    fun damageIsAPhotoAndASpokenNoteAndAMeterNeedsNoNumber() {
        val asha = organiser()
        val box = asha.createBox("L001", oldKitchen.id)
        asha.updateBoxes(listOf(box.id)) { it.copy(sealed = true, stage = Stage.LOADED) }
        pickUp(asha, "unload")
        tap("Here")
        tap("Report damage")
        see("Photograph it") // the photo comes first
        noKeyboardNeeded()
        say("corner crushed")
        tapDesc("Say what is damaged")
        see("corner crushed")
        shot("damage")
        tap("Save")
        assertTrue(data.box(box.id)!!.damaged)
        assertEquals("corner crushed", data.box(box.id)!!.damageNote)
        see("Damage noted")

        // Meters: four tiles to photograph, and no number asked for.
        go("records/ORIGIN")
        for (kind in METER_KINDS) see(kind)
        noKeyboardNeeded()
        // What a photo of the gas meter leaves behind: a reading with no number.
        val reading = Reading(id = newId(), home = Home.ORIGIN, kind = "Gas", day = today()).also { asha.put(it) }
        photo(asha, reading.id, PhotoKind.METER)
        settle()
        noKeyboardNeeded()
        shot("records-meters")
        // The number is there to say or to type, folded away.
        tap("Meter numbers")
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        say("4 8 2 1 3")
        tapDesc("Say the Gas reading")
        assertEquals("48213", data.liveReadings.single().value)
    }

    // ---- the hub ----------------------------------------------------------------------------------

    @Test
    fun theBigButtonOnMoveDayFollowsWhereThingsAre() {
        val asha = organiser()
        packed(asha)
        val routes = listOf("records/ORIGIN", "load", "sweep", "records/DEST", "unload", "reconcile", "unpack")
        fun now(vararg done: String) = routes[mdCurrentStep(asha.data.value, routes, routes.map { it in done })]
        assertEquals("records/ORIGIN", now()) // before the day: the first step not finished
        asha.setMove { it.copy(moveDay = today()) }
        assertEquals("load", now()) // on the day it is loading, though nobody photographed the old home
        asha.updateBoxes(listOf(carton.id)) { it.copy(stage = Stage.LOADED) }
        assertEquals("load", now())
        asha.updateBoxes(listOf(suitcase.id)) { it.copy(stage = Stage.LOADED) }
        asha.updateItems(listOf(sofa.id)) { it.copy(stage = Stage.LOADED) }
        assertEquals("sweep", now()) // everything is on: check the rooms before driving off
        assertEquals("records/DEST", now("sweep"))
        assertEquals("unload", now("sweep", "records/DEST"))
        asha.updateItems(listOf(wallet.id)) { it.copy(stage = Stage.DELIVERED) } // which brings in the suitcase it is in
        assertEquals("unload", now()) // some of it is in: get the rest off, whatever was skipped
        asha.updateBoxes(listOf(carton.id)) { it.copy(stage = Stage.DELIVERED) }
        asha.updateItems(listOf(sofa.id)) { it.copy(stage = Stage.DELIVERED) }
        assertEquals("reconcile", now("load", "unload"))
        assertEquals("unpack", now("load", "unload", "reconcile"))

        // On the screen it is the one step drawn as the big button, in its place among the others.
        asha.updateBoxes(listOf(carton.id, suitcase.id)) { it.copy(stage = Stage.LOADED) }
        pickUp(asha, "moveday")
        see("Unload")
        see("1 of 3 in") // the sofa; each container still on the van counts once, whatever is in it
        shot("moveday-unloading")
        tap("Unload")
        see("Still on the van")
    }

    @Test
    fun whenTheStoreRefusesTheReasonIsShown() {
        val asha = organiser()
        val hal = joined(asha, "Hal", Role.HELPER)
        val refused = hal.suggestTask(Task(id = newId(), title = "Tidy up"))
        assertFalse(mdTold(RuntimeEnvironment.getApplication(), refused))
        assertEquals("Helpers cannot suggest this. You can ask for a decision instead.", ShadowToast.getTextOfLatestToast())
        assertTrue(mdTold(RuntimeEnvironment.getApplication(), asha.addPerson("Kit", Role.HELPER)))
    }
}
