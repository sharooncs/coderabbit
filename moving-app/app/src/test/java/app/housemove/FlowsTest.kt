package app.housemove

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppRoot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives each flow through the real screens by tapping and typing, including
 * the awkward paths: unknown labels, labels already in use, changing your
 * mind, undoing, deleting, erasing, and joining as a second phone.
 * Codes are typed where a person would scan, since there is no camera here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var store: Store
    private var nav: NavHostController? = null
    private val data get() = store.data.value

    // Rooms created by [start]
    private lateinit var oldKitchen: Room
    private lateinit var oldBedroom: Room
    private lateinit var newKitchen: Room
    private lateinit var newBedroom: Room

    /** A move that is set up, with two rooms in each home linked by name. */
    private fun start(route: String = "home", setUp: Boolean = true) {
        store = Store(tmp.newFolder("flows"))
        store.setDevice { it.copy(name = "Sam", letter = "S", nextSeq = 1) }
        if (setUp) {
            store.setMove { it.copy(name = "Test move", moveDay = today() + 10, setupDone = true) }
            newKitchen = store.createRoom(Home.DEST, "Kitchen")
            newBedroom = store.createRoom(Home.DEST, "Bedroom")
            oldKitchen = store.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = newKitchen.id).also { store.put(it) }
            oldBedroom = store.createRoom(Home.ORIGIN, "Bedroom").copy(mapsTo = newBedroom.id).also { store.put(it) }
        }
        rule.setContent { AppRoot(store, startRoute = if (setUp) "home" else null, onNav = { nav = it }) }
        settle()
        if (route != "home" && setUp) go(route)
    }

    /** Let transitions and dialogs finish. */
    private fun settle() = rule.waitForIdle()

    private fun go(route: String) {
        rule.runOnUiThread { nav!!.navigate(route) }
        settle()
    }

    private fun back() {
        rule.runOnUiThread { nav!!.popBackStack() }
        settle()
    }

    private fun route(): String? {
        var r: String? = null
        rule.runOnUiThread { r = nav!!.currentBackStackEntry?.destination?.route }
        return r
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

    private fun tapDesc(description: String, sub: Boolean = false) =
        click(rule.onAllNodes(hasContentDescription(description, substring = sub) and hasClickAction()).onFirst())

    private fun type(label: String, value: String) {
        val node = rule.onAllNodes(hasSetTextAction() and hasText(label)).onFirst()
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
        }
        node.performTextReplacement(value)
        settle()
    }

    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)

    private fun box(label: String) = data.liveBoxes.first { it.label == label }

    /*
     * The four flows marked NARROW type into pop-up dialogs. In this JVM test runtime a
     * stock Material dialog holding a text field never finishes laying out on simulated
     * screens 340-411dp wide (it does at 320dp and 600dp, and with no app code involved),
     * so those flows run at 320dp here. DeviceTest covers them at phone width on real Android.
     */
    private companion object {
        const val NARROW = "w320dp-h891dp-xxhdpi"
    }

    // ---- setup --------------------------------------------------------------

    @Test
    fun setupCanBeFinishedWithNoRoomsAndAddedLater() {
        start(setUp = false)
        see("Your move")
        tap("Next"); tap("Next"); tap("Finish")
        assertEquals("home", route())
        assertTrue(data.move.setupDone)
        see("Add the rooms of both homes")
        tap("Add rooms")
        assertEquals("rooms", route())
        tap("Kitchen")
        assertEquals(1, data.roomsOf(Home.ORIGIN).size)
    }

    @Test
    fun secondPhoneJoinsInsteadOfSettingUpAgain() {
        start(setUp = false)
        tap("Join a move from another phone")
        assertEquals("sync", route())
        // The first phone's data arrives (as a sync would deliver it).
        val first = Store(tmp.newFolder("first"))
        first.setMove { it.copy(name = "Family move", setupDone = true) }
        first.createRoom(Home.DEST, "Kitchen")
        first.createBox("L001", null)
        rule.runOnUiThread { store.mergeIn(first.data.value) }
        settle()
        back()
        assertEquals("home", route())
        see("Family move")
        assertTrue(data.liveTasks.isEmpty()) // the checklist is not added a second time
    }

    // ---- scanning -----------------------------------------------------------

    @Test
    fun scanningOpensKnownLabelsOffersABoxForUnknownOnesAndRoutesSyncCodes() {
        start()
        store.createBox("L001", oldKitchen.id)
        val item = store.createItem("Piano", oldBedroom.id)
        store.put(data.item(item.id)!!.copy(label = "L002", disposition = Disposition.MOVE))

        go("scan")
        type("Or type the code on the label", "l001") // case does not matter
        tap("Open")
        assertEquals("box/{id}", route())
        see("Seal box")

        go("scan")
        type("Or type the code on the label", "HM:L002") // exactly what the sticker's square code contains
        tap("Open")
        assertEquals("item/{id}", route())

        go("scan")
        type("Or type the code on the label", "L777")
        tap("Open")
        see("Label L777 is not in use")
        tap("Cancel")
        assertEquals(1, data.liveBoxes.size)
        type("Or type the code on the label", "L777")
        tap("Open")
        tap("New box")
        assertEquals("box/{id}", route())
        assertNotNull(data.findByLabel("L777"))

        go("scan")
        type("Or type the code on the label", "HMSYNC:192.168.1.9:4000:ABCD2345")
        tap("Open")
        assertEquals("sync", route())
    }

    // ---- packing ------------------------------------------------------------

    @Test
    fun packingWithPrintedLabelsNeverCreatesTheSameBoxTwice() {
        start("pack")
        tap("Kitchen")
        tap("Scan a printed label")
        type("Or type the code", "L050")
        tap("OK")
        assertEquals("box/{id}", route())
        assertEquals(newKitchen.id, box("L050").destRoomId)
        assertEquals(oldKitchen.id, box("L050").originRoomId)

        back()
        tap("Scan a printed label")
        type("Or type the code", "L050") // the same sticker scanned again
        tap("OK")
        assertEquals(1, data.liveBoxes.size)
        assertEquals("box/{id}", route())

        // A phone-linking code is not a box label.
        back()
        tap("Scan a printed label")
        type("Or type the code", "HMSYNC:1.2.3.4:5:X")
        tap("OK")
        assertEquals(1, data.liveBoxes.size)
    }

    @Test
    fun boxCanBeFilledInSealedReopenedRelabelledAndDeleted() {
        start()
        val a = store.createBox("L001", oldKitchen.id)
        store.createBox("L002", oldKitchen.id)
        val photo = store.newPhotoFile().apply { writeBytes(ByteArray(500)) }
        store.addPhoto(a.id, PhotoKind.CONTENTS, photo)
        go("box/${a.id}")

        type("Contents", "Glasses, mugs")
        tap("Fragile"); tap("This way up")
        tap("First night")
        tap("Small")
        tap("Bedroom") // send it to a different room than the default
        var b = data.box(a.id)!!
        assertEquals("Glasses, mugs", b.contents)
        assertTrue(b.fragile && b.thisWayUp && !b.heavy)
        assertEquals(Priority.FIRST_NIGHT, b.priority)
        assertEquals(BoxSize.S, b.size)
        assertEquals(newBedroom.id, b.destRoomId)

        tap("Seal box")
        assertTrue(data.box(a.id)!!.sealed)
        tap("Open again")
        assertFalse(data.box(a.id)!!.sealed)

        // Relabel: a label that is already on another box is refused.
        tap("Change label")
        type("Or type the code", "L002")
        tap("OK")
        assertEquals("L001", data.box(a.id)!!.label)
        type("Or type the code", "L099")
        tap("OK")
        assertEquals("L099", data.box(a.id)!!.label)
        assertTrue(data.duplicateLabels().isEmpty())

        tapDesc("Delete this box")
        tap("Delete")
        assertNull(data.box(a.id))
        assertFalse(photo.exists())
        assertNotEquals("box/{id}", route())
        b = box("L002")
        go("box/${a.id}") // a stale link to the deleted box
        see("no longer here")
        assertNotNull(b)
    }

    // ---- items --------------------------------------------------------------

    @Test
    fun itemDecisionLabelAndDeletion() {
        start()
        val item = store.createItem("Bookcase", oldBedroom.id)
        go("item/${item.id}")
        fun it() = data.item(item.id)!!

        tap("Sell")
        assertEquals(Disposition.SELL, it().disposition)
        tap("Listed for sale")
        assertTrue(it().listed)
        tap("Move")
        assertEquals(Disposition.MOVE, it().disposition)
        assertEquals(newBedroom.id, it().destRoomId)
        tap("Kitchen", index = 1) // new-home picker; index 0 is the old-home picker
        assertEquals(newKitchen.id, it().destRoomId)

        type("Width cm", "80"); type("Depth cm", "30"); type("Height cm", "190")
        assertEquals(80.0, it().widthCm!!, 0.001)
        assertEquals(190.0, it().heightCm!!, 0.001)

        tap("Use a handwritten code")
        assertEquals("S1", it().label)
        tap("Remove label")
        assertNull(it().label)

        tap("Damaged in the move")
        assertTrue(it().damaged)

        tapDesc("Delete item")
        tap("Delete")
        assertNull(data.item(item.id))
        go("item/${item.id}")
        see("no longer here")
    }

    @Test
    fun inchesAreConvertedToCentimetres() {
        start()
        store.setMove { it.copy(useInches = true) }
        val item = store.createItem("Table", oldKitchen.id)
        store.put(data.item(item.id)!!.copy(disposition = Disposition.MOVE))
        go("item/${item.id}")
        type("Width in", "10")
        assertEquals(25.4, data.item(item.id)!!.widthCm!!, 0.01)
    }

    @Test
    fun triageSkipsDecidesAndFinishes() {
        start()
        val ids = listOf("Chair", "Lamp", "Rug").map { store.createItem(it, oldKitchen.id).id }
        go("triage")
        tap("Skip for now")
        tap("Bin")
        tap("Donate")
        tap("Store")
        assertEquals(
            setOf(Disposition.BIN, Disposition.DONATE, Disposition.STORE),
            ids.map { data.item(it)!!.disposition }.toSet(),
        )
        see("Plan the new rooms")
        assertTrue(data.issues().none { it.route == "triage" })
    }

    @Test
    fun planAssignsRoomsAndHandlesFurnitureThatWillNotFit() {
        start()
        store.put(newBedroom.copy(doorWidthCm = 70.0))
        val desk = store.createItem("Desk", null)
        store.put(data.item(desk.id)!!.copy(disposition = Disposition.MOVE))
        val wardrobe = store.createItem("Wardrobe", oldBedroom.id)
        store.put(data.item(wardrobe.id)!!.copy(disposition = Disposition.MOVE, widthCm = 120.0, depthCm = 80.0, heightCm = 200.0))
        go("plan")
        see("Needs a room (1)")
        tap("Kitchen")
        assertEquals(newKitchen.id, data.item(desk.id)!!.destRoomId)
        assertTrue(data.issues().any { it.route == "plan" && it.severe })
        tap("Mark: take apart")
        assertTrue(data.item(wardrobe.id)!!.needsDisassembly)
        assertTrue(data.issues().none { it.route == "plan" })
    }

    // ---- move day -----------------------------------------------------------

    @Test
    fun loadingTripsUndoAndThingsThatTravelWithYou() {
        start()
        val a = store.createBox("L001", oldKitchen.id)
        val docs = store.createBox("L002", oldBedroom.id)
        store.put(data.box(docs.id)!!.copy(travelsWithMe = true))
        go("load")
        tap("Load")
        assertEquals(Stage.LOADED, data.box(a.id)!!.stage)
        val trip1 = data.box(a.id)!!.tripId
        assertEquals("Trip 1", data.trip(trip1)!!.name)

        tap("With me")
        assertEquals(Stage.LOADED, data.box(docs.id)!!.stage)
        assertNull(data.box(docs.id)!!.tripId)

        tap("On Trip 1", sub = true)
        tap("Undo")
        assertEquals(Stage.AT_ORIGIN, data.box(a.id)!!.stage)

        tap("Add trip")
        assertEquals(2, data.liveTrips.size)
        tap("To load", sub = true)
        tap("Load")
        assertEquals(data.liveTrips.first { it.name == "Trip 2" }.id, data.box(a.id)!!.tripId)
    }

    @Test
    @Config(sdk = [34], qualifiers = NARROW)
    fun unloadingDamageUndoAndReconcile() {
        start()
        val a = store.createBox("L001", oldKitchen.id)
        val b = store.createBox("L002", oldBedroom.id)
        store.updateBoxes(listOf(a.id, b.id)) { it.copy(sealed = true, stage = Stage.LOADED) }

        go("reconcile")
        see("2 still on the van")

        go("unload")
        tap("Here")
        val first = data.liveBoxes.first { it.stage == Stage.DELIVERED }
        tap("Report damage")
        type("What is damaged?", "Corner crushed")
        tap("Save")
        assertTrue(data.box(first.id)!!.damaged)
        assertEquals("Corner crushed", data.box(first.id)!!.damageNote)
        assertTrue(data.issues().any { it.route == "reconcile" && it.severe })

        tap("Undo")
        assertEquals(Stage.LOADED, data.box(first.id)!!.stage)

        tap("Here"); tap("Here")
        assertTrue(data.liveBoxes.all { it.stage == Stage.DELIVERED })
        go("reconcile")
        see("Everything is accounted for")
        see("Corner crushed")
    }

    @Test
    fun unpackUndoAndFurnitureInPlace() {
        start()
        val a = store.createBox("L001", oldKitchen.id)
        store.updateBoxes(listOf(a.id)) { it.copy(sealed = true, stage = Stage.DELIVERED) }
        val bed = store.createItem("Bed", oldBedroom.id)
        store.put(data.item(bed.id)!!.copy(disposition = Disposition.MOVE, stage = Stage.DELIVERED, needsDisassembly = true))
        go("unpack")
        tap("Unpacked")
        assertEquals(Stage.DONE, data.box(a.id)!!.stage)
        tap("Unpacked boxes", sub = true)
        tap("Undo")
        assertEquals(Stage.DELIVERED, data.box(a.id)!!.stage)
        tap("Furniture (1)")
        see("Needs putting back together")
        tap("In place")
        assertEquals(Stage.DONE, data.item(bed.id)!!.stage)
    }

    @Test
    fun finalSweepWarnsAboutThingsLeftBehind() {
        start()
        store.createBox("L001", oldKitchen.id)
        go("sweep")
        see("still here")
        tap("Bedroom")
        assertTrue(data.room(oldBedroom.id)!!.swept)
        assertFalse(data.room(oldKitchen.id)!!.swept)
    }

    // ---- planning tools -----------------------------------------------------

    @Test
    fun timelineChecklistCanBeAddedAndTicked() {
        start("tasks")
        tap("Add the self-move checklist")
        assertTrue(data.liveTasks.size > 40)
        click(rule.onAllNodes(isToggleable()).onFirst())
        assertEquals(1, data.liveTasks.count { it.done })
        dontSee("Add the self-move checklist") // cannot be added twice
    }

    @Test
    @Config(sdk = [34], qualifiers = NARROW)
    fun timelineOwnTasksAddEditDelete() {
        start("tasks")
        tapDesc("Add a task")
        tap("Cancel")
        assertTrue(data.liveTasks.isEmpty())
        tapDesc("Add a task")
        type("What needs doing", "Return the neighbour's ladder")
        tap("Save")
        val mine = data.liveTasks.single()
        assertEquals("Return the neighbour's ladder", mine.title)
        tap("Return the neighbour's ladder")
        type("What needs doing", "Return the ladder")
        tap("Save")
        assertEquals("Return the ladder", data.liveTasks.single().title)
        tap("Return the ladder")
        tap("Delete this job")
        tap("Delete")
        assertTrue(data.liveTasks.isEmpty())
    }

    @Test
    @Config(sdk = [34], qualifiers = NARROW)
    fun suppliesAndCosts() {
        start("budget")
        tap("Suggest a list for my home")
        val supplies = data.liveSupplies
        assertTrue(supplies.size > 10)
        val first = supplies.first()
        tapDesc("Have one more: ${first.name}")
        assertEquals(1, data.liveSupplies.first { it.id == first.id }.have)
        tap("Costs")
        tap("Add a cost")
        type("What was it for?", "Van hire")
        type("Amount", "180.50")
        tap("Done")
        val e = data.liveExpenses.single()
        assertEquals("Van hire", e.title)
        assertEquals(180.5, e.amount, 0.001)
    }

    @Test
    @Config(sdk = [34], qualifiers = NARROW)
    fun roomsCanBeAddedLinkedAndDeletedWhileInUse() {
        start()
        val b = store.createBox("L001", oldKitchen.id)
        go("rooms")
        tap("Living room")
        assertEquals(3, data.roomsOf(Home.ORIGIN).size)
        tap("New home")
        tap("Kitchen")
        see("New-home room")
        tap("Delete room")
        tap("Delete")
        assertNull(data.room(newKitchen.id))
        // The box that was going there is flagged rather than lost.
        assertNotNull(data.box(b.id))
        assertTrue(data.issues().any { it.text.contains("no destination") })
        go("boxes")
        see("No room")
    }

    @Test
    fun specialListsShowAndRemoveFlags() {
        start()
        val docs = store.createBox("L001", oldKitchen.id)
        store.put(data.box(docs.id)!!.copy(travelsWithMe = true, contents = "Passports"))
        go("lists")
        see("Passports")
        see("Travels with me (1)")
        tapDesc("Take Box L001", sub = true)
        assertFalse(data.box(docs.id)!!.travelsWithMe)
    }

    @Test
    fun searchFindsThingsByContentsAndSaysWhereTheyAre() {
        start()
        val a = store.createBox("L001", oldKitchen.id)
        store.put(data.box(a.id)!!.copy(contents = "Kettle and toaster", stage = Stage.LOADED))
        go("search")
        type("What are you looking for?", "toaster")
        see("Box L001")
        see("On the van")
        type("What are you looking for?", "piano")
        see("Nothing matches")
    }

    // ---- starting again -----------------------------------------------------

    @Test
    fun eraseRemovesEverythingAndReturnsToSetup() {
        start()
        val a = store.createBox("L001", oldKitchen.id)
        val photo = store.newPhotoFile().apply { writeBytes(ByteArray(500)) }
        store.addPhoto(a.id, PhotoKind.CONTENTS, photo)
        go("settings")
        tap("Erase all data on this phone")
        tap("Cancel")
        assertEquals(1, data.liveBoxes.size)
        tap("Erase all data on this phone")
        tap("Erase")
        assertEquals("setup", route())
        assertTrue(data.liveBoxes.isEmpty() && data.liveRooms.isEmpty() && !data.move.setupDone)
        assertTrue(store.photoDir.listFiles()!!.isEmpty())
        store.flush()
        assertFalse(Store(File(tmp.root, "flows")).data.value.move.setupDone)
    }

    @Test
    fun damagedDataWarningIsShownOnTheDashboard() {
        val dir = tmp.newFolder("warn")
        Store(dir).apply { setMove { it.copy(setupDone = true) }; close() }
        File(dir, "data.json").writeText("not json")
        File(dir, "data.json.bak").writeText("not json either")
        store = Store(dir)
        rule.setContent { AppRoot(store, startRoute = "home", onNav = { nav = it }) }
        settle()
        see("could not be read")
        tap("Dismiss")
        dontSee("could not be read")
    }
}
