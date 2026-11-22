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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppRoot
import app.housemove.ui.ISO_MAX_ROOMS
import app.housemove.ui.IsoRoom
import app.housemove.ui.RoomState
import app.housemove.ui.layoutRooms
import app.housemove.sync.WifiSync
import app.housemove.ui.screens.syncReport
import com.github.takahirom.roborazzi.captureRoboImage
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

    // ---- home, the rooms overview and the dock -----------------------------------

    @Test
    fun homeShowsTheHouseTheNextRoomAndLeadsToTheRoomsOverview() {
        start()
        val a = store.createBox("L001", oldKitchen.id)
        store.createBox("L002", oldKitchen.id)
        store.updateBoxes(listOf(a.id)) { it.copy(sealed = true) }
        settle()
        see("Test move")
        see("days to go")
        see("1/2") // boxes sealed, as a key number
        see("Kitchen is next")
        see("1 box left")
        tapDesc("model house", sub = true)
        assertEquals("overview", route())
        see("Kitchen")
        see("1 still open")
        dontSee("Needs attention") // the overview is about rooms only

        // The marker and the callout follow the room that is tapped.
        tapDesc("Bedroom: no boxes", sub = true)
        see("No boxes yet")

        // The bar at the bottom packs in the room that is next.
        store.setDevice { it.copy(currentRoomId = null) }
        tap("Pack a box")
        assertEquals("pack", route())
        assertEquals(oldKitchen.id, store.device.value.currentRoomId)
        back()
        tapDesc("Manage rooms")
        assertEquals("rooms", route())
        back()

        // Marking the room packed moves "next" on to the other room.
        store.put(data.room(oldKitchen.id)!!.copy(packed = true))
        settle()
        see("Bedroom is next")
        tap("Home") // the dock's Home button leaves the overview
        assertEquals("home", route())
        see("Bedroom is next")
    }

    @Test
    fun dockReachesEveryDestinationAndTheBigButtonPacks() {
        start()
        for ((label, where) in listOf("Items" to "items", "Boxes" to "boxes", "Move day" to "moveday", "More" to "more", "Home" to "home")) {
            tap(label)
            assertEquals(where, route())
        }
        tap("Pack a box") // the glowing button in the middle of Home's dock
        assertEquals("pack", route())
    }

    @Test
    fun roomsDrawingLaysOutOneRoomAndManyRooms() {
        fun rooms(n: Int) = (1..n).map { IsoRoom("r$it", "Room $it", RoomState.entries[it % 4], stacks = it % 4) }
        assertTrue(layoutRooms(emptyList()).isEmpty())
        // One room has the whole floor.
        layoutRooms(rooms(1)).single().let { c -> assertEquals(listOf(0f, 0f, 8f, 6f), listOf(c.x0, c.y0, c.x1, c.y1)) }
        for (n in listOf(2, 3, 4, 5, 7, 12, 20)) {
            val cells = layoutRooms(rooms(n))
            assertEquals(minOf(n, ISO_MAX_ROOMS), cells.size) // the rest are counted in the "+N more" note
            // The cells tile the floor: no gaps, no overlaps, and each is wide enough for a box.
            assertEquals(48f, cells.sumOf { ((it.x1 - it.x0) * (it.y1 - it.y0)).toDouble() }.toFloat(), 0.01f)
            assertTrue(cells.all { it.x0 >= 0f && it.x1 <= 8f && it.y0 >= 0f && it.y1 <= 6f && it.x1 - it.x0 >= 1.3f })
            for (a in cells) for (b in cells) if (a !== b) {
                assertFalse(a.x0 < b.x1 - 0.001f && b.x0 < a.x1 - 0.001f && a.y0 < b.y1 - 0.001f && b.y0 < a.y1 - 0.001f)
            }
        }
    }

    // ---- setup --------------------------------------------------------------

    @Test
    fun setupCanBeFinishedWithNoRoomsAndAddedLater() {
        start(setUp = false)
        see("What is it for?")
        tap("Next"); tap("Next"); tap("Skip"); tap("Skip") // the rooms can wait
        tap("Do this later") // who is helping can wait too
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
        tap("Join theirs instead")
        see("Scan the invite") // the scan itself, over a real connection, is in PeopleFlowsTest
        // The first phone's data arrives (as joining with its invite delivers it).
        val first = Store(tmp.newFolder("first"))
        first.setMove { it.copy(name = "Family move", setupDone = true) }
        first.createRoom(Home.DEST, "Kitchen")
        first.createBox("L001", null)
        val me = first.addPerson("Sam", Role.DECIDER).valueOrNull()!!
        rule.runOnUiThread { store.joinAs(me.id, first.data.value) }
        settle()
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
        // The camera has the screen to itself: there is no text field until the typed way is asked for.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        tap("Type the code")
        type("Or type the code on the label", "l001") // case does not matter
        tap("Open")
        assertEquals("box/{id}", route())
        see("Seal box")

        go("scan")
        tap("Type the code")
        type("Or type the code on the label", "HM:L002") // exactly what the sticker's square code contains
        tap("Open")
        assertEquals("item/{id}", route())

        go("scan")
        tap("Type the code")
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
        tap("Type the code")
        type("Or type the code on the label", "HMSYNC:192.168.1.9:4000:ABCD2345")
        tap("Open")
        assertEquals("sync?code={code}", route()) // the sync screen, handed the code so it is not scanned twice
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

        tap("Glasses"); tap("Mugs") // the usual things for a kitchen box, one tap each
        assertEquals("Glasses, Mugs", data.box(a.id)!!.contents)
        tapDesc("Edit what's inside") // typing is still there for whatever the chips do not cover
        type("What's inside", "Glasses, mugs")
        tap("Fragile"); tap("This way up")
        tap("Packing details") // when to unpack it and its size are rarely changed, so they are folded away
        tap("First night")
        tap("Small")
        tapDesc("Going to") // the room it goes to is filled in already; this opens the others
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
        tap("More")
        tap("Change label")
        type("Or type the code", "L002")
        tap("OK")
        assertEquals("L001", data.box(a.id)!!.label)
        type("Or type the code", "L099")
        tap("OK")
        assertEquals("L099", data.box(a.id)!!.label)
        assertTrue(data.duplicateLabels().isEmpty())

        tap("Delete this box") // at the very end of the last section
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
        tapDesc("Going to") // the room it goes to is one line; this opens the others
        tap("Kitchen")
        assertEquals(newKitchen.id, it().destRoomId)

        tap("Size and handling") // sections other than the decision open when tapped
        type("Width cm", "80"); type("Depth cm", "30"); type("Height cm", "190")
        assertEquals(80.0, it().widthCm!!, 0.001)
        assertEquals(190.0, it().heightCm!!, 0.001)

        tap("Getting it ready") // which includes labelling it
        tap("Use a handwritten code")
        assertEquals("S1", it().label)
        tap("Remove label")
        assertNull(it().label)

        tap("More") // before it leaves the old home, where it is and any damage wait in the last section
        tap("Damaged in the move")
        assertTrue(it().damaged)
        see("On the move") // once damaged, they have a section of their own

        tap("Delete item") // at the very end of the last section, which is still open
        tap("Delete")
        assertNull(data.item(item.id))
        go("item/${item.id}")
        see("no longer here")
    }

    @Test
    fun deviceDetailsAreRecordedAndFoundBySerialNumber() {
        start()
        val wardrobe = store.createItem("Oak wardrobe", oldBedroom.id)
        val tv = store.createItem("Samsung TV", oldBedroom.id)
        store.put(data.item(tv.id)!!.copy(disposition = Disposition.MOVE))
        fun it() = data.item(tv.id)!!

        // Furniture is not asked for a serial number unless you switch it on.
        go("item/${wardrobe.id}")
        dontSee("Scan the serial number barcode")
        tap("More")
        tap("It has a serial number")
        see("Scan the serial number barcode")
        assertEquals(true, data.item(wardrobe.id)!!.electronic)

        // A TV is recognised by its name.
        go("item/${tv.id}")
        tap("Make, model and serial number") // each section opens when tapped
        tapDesc("Edit make, model and serial number") // typing them is behind the pencil
        type("Make", "Samsung")
        type("Model", "QE55Q60")
        type("Serial number", "0AB3HCPT500123X")
        see("What travels with it") // in the same section
        tap("Remote") // one tap each, nothing typed
        tap("Power lead")
        tap("Stand")
        tap("Bagged and marked with this item's name")
        tap("Size and handling")
        tap("Keep upright")
        tap("Getting it ready")
        tap("Cables labelled and bagged")
        assertEquals("Samsung QE55Q60", it().makeModel())
        assertEquals("0AB3HCPT500123X", it().serial)
        assertEquals("Remote, Power lead, Stand", it().accessories)
        assertTrue(it().accessoriesPacked && it().keepUpright && it().prepCablesLabelled)
        see("never flat") // the advice for moving a TV, with the steps for getting it ready

        // The barcode on the serial label can fill the field in (typed here in place of the camera).
        tap("Scan the serial number barcode")
        type("Or type the serial number", "ZX81-0042")
        tap("OK")
        assertEquals("ZX81-0042", it().serial)
        see("ZX81-0042")

        go("scan")
        tap("Type the code")
        type("Or type the code on the label", "zx81-0042")
        tap("Open")
        assertEquals("item/{id}", route())

        go("electronics")
        see("S/N ZX81-0042")
        see("No serial number") // the wardrobe that was switched on but not filled in
        go("load")
        see("Keep upright")
    }

    @Test
    fun booksAndProductsGoIntoABoxByTheirOwnBarcode() {
        start()
        val box = store.createBox("L001", oldKitchen.id)
        go("box/${box.id}")
        tapDesc("Scan barcodes of books and products")
        tap("Type it") // the camera scans and the microphone names; the keyboard is asked for
        type("A barcode number or a name", "9780140328721")
        tap("Add")
        see("Look up book titles online?") // asked once, before anything goes online
        tap("No, stay offline")
        assertEquals(false, store.device.value.bookLookup)
        assertEquals("9780140328721", data.entriesOf(box.id).single().code)

        tapDesc("Type a name for barcode 9780140328721")
        type("Name for barcode 9780140328721", "Fantastic Mr Fox")
        tap("Save")
        assertEquals("Fantastic Mr Fox", data.entriesOf(box.id).single().title)

        type("A barcode number or a name", "Photo albums")
        tap("Add")
        type("A barcode number or a name", "978-0-14-032872-1") // the same book again
        tap("Add")
        see("Already in here")
        assertEquals(2, data.entriesOf(box.id).size)
        tapDesc("Remove Photo albums")
        assertEquals(1, data.entriesOf(box.id).size)
        tap("Done")
        see("Scanned in (1)")

        go("search")
        type("What are you looking for?", "mr fox")
        see("Box L001")
        go("scan")
        tap("Type the code")
        type("Or type the code on the label", "9780140328721")
        tap("Open")
        assertEquals("box/{id}", route())
        go("scan")
        tap("Type the code")
        type("Or type the code on the label", "5012345678900") // a product nobody scanned in
        tap("Open")
        see("is not recorded")
        tap("OK")
        assertEquals(1, data.liveBoxes.size)
    }

    @Test
    fun inchesAreConvertedToCentimetres() {
        start()
        store.setMove { it.copy(useInches = true) }
        val item = store.createItem("Table", oldKitchen.id)
        store.put(data.item(item.id)!!.copy(disposition = Disposition.MOVE))
        go("item/${item.id}")
        tap("Size and handling")
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
        see("Needs a room")
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
        tap("Report damage") // opens in place of the arrival panel: the photo first, the note optional
        tapDesc("Edit: What happened?")
        type("What happened?", "Corner crushed")
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
        tap("Add the checklist")
        assertTrue(data.liveTasks.size > 40)
        click(rule.onAllNodes(isToggleable()).onFirst())
        assertEquals(1, data.liveTasks.count { it.done })
        dontSee("Add the checklist") // cannot be added twice
    }

    @Test
    @Config(sdk = [34], qualifiers = NARROW)
    fun timelineOwnTasksAddEditDelete() {
        // A job is normally spoken (MoveDayFlowsTest). This is the keyboard's way, which sits behind a tap.
        start("tasks")
        tapDesc("Add a task")
        tap("Cancel")
        assertTrue(data.liveTasks.isEmpty())
        tapDesc("Add a task")
        type("What needs doing", "Return the neighbour's ladder")
        tap("Add")
        val mine = data.liveTasks.single()
        assertEquals("Return the neighbour's ladder", mine.title)
        tap("Return the neighbour's ladder") // opens the job in its place
        tap("Edit")
        type("What needs doing", "Return the ladder")
        tap("Save")
        assertEquals("Return the ladder", data.liveTasks.single().title)
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
        // A cost is normally tapped in (MoveDayFlowsTest). This is the keyboard's way: an odd sum and a name.
        tap("Costs")
        tap("Add a cost")
        tap("Other amount")
        type("Amount", "180.50")
        tap("Details")
        type("Name", "Van hire")
        tap("Add cost", sub = true)
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
        tap("Start again")
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

    // ---- one app, different uses -------------------------------------------------

    @Test
    fun inventoryOnlyUseShowsOnlyWhatApplies() {
        start(setUp = false)
        tap("Just listing what I own")
        tap("Next")
        see("Your home")
        tap("Next")
        tap("Kitchen")
        tap("Finish") // no second home to describe
        assertEquals("home", route())
        assertEquals(MoveKind.INVENTORY, data.move.kind)
        assertTrue(data.roomsOf(Home.DEST).isEmpty())
        assertTrue(data.liveTasks.isEmpty())
        see("things listed")
        see("Start the walk-through")
        dontSee("Pack a box")
        dontSee("Boxes")
        dontSee("Move day")
        go("more")
        see("Walk-through"); see("Devices"); see("Reports")
        dontSee("Load the"); dontSee("Timeline"); dontSee("Labels and signs")
    }

    @Test
    fun moversUseSaysTruckAndGetsItsOwnChecklist() {
        start(setUp = false)
        tap("Movers are doing it")
        tap("Next"); tap("Next")
        tap("Kitchen"); tap("Next")
        tap("Kitchen"); tap("Finish")
        assertEquals(MoveKind.MOVERS, data.move.kind)
        assertTrue(data.liveTasks.any { it.title.startsWith("Book the movers") })
        assertTrue(data.liveTasks.none { it.category == "Van" || it.title.contains("Collect the van") })
        assertEquals("truck", data.vehicle())
        go("more")
        see("Load the truck")
        dontSee("Load the van")
    }

    @Test
    fun partsOfTheAppCanBeSwitchedOffAndOnAgain() {
        start()
        store.put(Task(id = "late", title = "Book the lift", dueDay = today() - 2))
        assertTrue(data.issues().any { it.route == "tasks" })
        go("settings")
        tap("Parts of the app")
        tap("Task timeline")
        assertFalse(data.uses(Part.TIMELINE))
        assertTrue(data.issues().none { it.route == "tasks" })
        assertEquals(1, data.liveTasks.size) // switched off, not deleted
        go("more")
        dontSee("Timeline")
        go("settings")
        tap("Parts of the app")
        tap("Task timeline")
        assertTrue(data.uses(Part.TIMELINE))
    }

    @Test
    fun helperPhoneShowsOnlyTheEssentials() {
        start()
        store.createBox("L001", oldKitchen.id)
        rule.runOnUiThread { store.setDevice { it.copy(helper = true) } }
        settle()
        see("Load the van"); see("Unload"); see("Find anything"); see("Sync with the other phones")
        dontSee("Needs attention"); dontSee("Walk-through")
        tap("Pack a box")
        assertEquals("pack", route())
        back()
        tap("Show everything")
        assertFalse(store.device.value.helper)
        see("Next step")
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

    @Test
    fun syncSaysPlainlyWhenThereIsNoWifiAndWhenOnlyPhotosWereCutShort() {
        // A phone with no Wi-Fi: the sync screen says so before any code is shown, in the sync's own words.
        WifiSync.addresses = { emptyList() }
        try {
            start("sync")
            see(WifiSync.NO_NETWORK)
            rule.onRoot().captureRoboImage("build/audit/flows/sync-no-wifi.png")
            // Back on a network, "Check again" takes the message away.
            WifiSync.addresses = { listOf("192.168.1.10") }
            tap("Check again")
            dontSee(WifiSync.NO_NETWORK)
        } finally {
            WifiSync.addresses = { WifiSync.localAddresses() }
        }
        // Every message has a heading of its own; photos cut short still count as a sync.
        val cut = syncReport(WifiSync.Result("Ben", "B", 3, 0, WifiSync.PHOTOS_CUT), store)
        assertTrue(cut.ok)
        assertEquals(WifiSync.PHOTOS_CUT, cut.text)
        for (message in listOf(WifiSync.NO_NETWORK, WifiSync.UNREACHABLE, WifiSync.CONNECTION_LOST, WifiSync.CODE_NOT_ACCEPTED, WifiSync.WRONG_CODE, WifiSync.NOT_HOUSEMOVE, WifiSync.UNREADABLE)) {
            val r = syncReport(WifiSync.Result("", "", 0, 0, message), store)
            assertFalse(r.ok)
            assertNotEquals("Could not sync", r.title)
            assertEquals(message, r.text)
        }
    }
}
