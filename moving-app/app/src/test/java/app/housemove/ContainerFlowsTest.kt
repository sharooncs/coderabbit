package app.housemove

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import app.housemove.data.*
import app.housemove.pdf.Pdf
import app.housemove.pdf.Share
import app.housemove.pdf.TagSheet
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalSpeech
import app.housemove.ui.Speech
import app.housemove.ui.screens.LocalTagPrinter
import app.housemove.ui.screens.TagPrinter
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
 * Packing and containers, driven through the real screens the way a person would: by
 * tapping, speaking and scanning, never by typing. Where a person would scan a label
 * the code is typed into the scanner's own "or type the code" field, since there is no
 * camera here; speech comes from a stand-in for the phone's speech service.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ContainerFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var store: Store
    private var nav: NavHostController? = null
    private val data get() = store.data.value

    private lateinit var oldKitchen: Room
    private lateinit var oldBedroom: Room
    private lateinit var newKitchen: Room
    private lateinit var newBedroom: Room

    /** What the stand-in speech service hears next. */
    private var heard = ""
    private val speech = Speech { _, onText -> onText(heard) }

    /** What was last sent to be printed as hang tags. */
    private var tagCodes: List<String>? = null
    private var tagSheet: TagSheet? = null
    private val printer = TagPrinter { context, codes, sheet ->
        tagCodes = codes
        tagSheet = sheet
        // Android's PDF writer is not there on the JVM: draw the first page the way the PDF draws it.
        val bmp = Bitmap.createBitmap(sheet.pageW, sheet.pageH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        Pdf.drawTagPage(Canvas(bmp), sheet, codes.take(sheet.perPage))
        Share.outFile(context, "housemove-hang-tags.pdf").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun newStore(): Store {
        val s = Store(tmp.newFolder())
        s.setDevice { it.copy(name = "Sam", letter = "S", nextSeq = 1) }
        s.setMove { it.copy(name = "Test move", moveDay = today() + 10, setupDone = true) }
        return s
    }

    /** A move that is set up, with two rooms in each home linked by name. */
    private fun start(route: String = "home", prepared: Store? = null) {
        store = prepared ?: newStore()
        if (prepared == null) rooms()
        rule.setContent {
            CompositionLocalProvider(LocalSpeech provides speech, LocalTagPrinter provides printer) {
                AppRoot(store, startRoute = "home", onNav = { nav = it })
            }
        }
        settle()
        if (route != "home") go(route)
    }

    private fun rooms() {
        newKitchen = store.createRoom(Home.DEST, "Kitchen")
        newBedroom = store.createRoom(Home.DEST, "Bedroom")
        oldKitchen = store.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = newKitchen.id).also { store.put(it) }
        oldBedroom = store.createRoom(Home.ORIGIN, "Bedroom").copy(mapsTo = newBedroom.id).also { store.put(it) }
    }

    private fun settle() = rule.waitForIdle()

    private fun go(route: String) {
        rule.runOnUiThread { nav!!.navigate(route) }
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

    /**
     * Tap something further down the page than the screen shows, inside a row that scrolls
     * sideways: a person scrolls down to it first, which scrolling to a node cannot do here.
     */
    private fun tapBelow(text: String) {
        rule.onAllNodes(hasText(text) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** Tap one tile of a set of picture tiles. */
    private fun pick(text: String, index: Int = 0) = click(rule.onAllNodes(hasText(text) and isSelectable())[index])

    private fun picked(text: String) = rule.onAllNodes(hasText(text) and isSelectable()).onFirst().assertIsSelected()

    /** Open one of the two pickers under the ticket ("Kind" or "Contents") and choose a tile; choosing closes it. */
    private fun choose(picker: String, tile: String) {
        tap(picker)
        pick(tile)
    }

    /** What one of the two pickers under the ticket is set to, read off its tiles. */
    private fun chosen(picker: String, tile: String) {
        tap(picker)
        picked(tile)
        tap(picker)
    }

    /** Choose from one of the filter chips of the list that open a menu. */
    private fun filter(chip: String, choice: String) {
        tapDesc(chip)
        tap(choice)
    }

    /** Stand in for the scanner: the code goes into its "or type the code" field. */
    private fun scan(code: String) {
        val node = rule.onAllNodes(hasSetTextAction() and hasText("Or type the code")).onFirst()
        node.performTextReplacement(code)
        settle()
        tap("OK")
    }

    private fun say(words: String, button: String) {
        heard = words
        tapDesc(button)
    }

    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)

    /** The whole point: nothing on the screen asks for the keyboard. */
    private fun noKeyboardNeeded() = rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

    private fun box(label: String) = data.liveBoxes.first { it.label == label }

    private fun movingItem(name: String, room: Room, label: String? = null): Item {
        val i = store.createItem(name, room.id)
        store.put(data.item(i.id)!!.copy(disposition = Disposition.MOVE, label = label))
        return data.item(i.id)!!
    }

    // ---- packing ---------------------------------------------------------------------

    @Test
    fun aBoxIsPackedFromLabelToSealWithoutTheKeyboard() {
        start("pack")
        noKeyboardNeeded()
        tap("Kitchen")
        tap("No sticker? Use a handwritten code")
        assertEquals("box/{id}", route())

        // The container is already there, filled in from the room it is packed in. Its name is automatic.
        var b = box("S1")
        assertEquals("Kitchen box 1", b.name)
        assertEquals(ContainerKind.CARTON, b.kind)
        assertEquals("Kitchen", b.category)
        assertEquals(newKitchen.id, b.destRoomId)
        see("Kitchen box 1")
        see("Write it on the top and one side")
        // The suggested kind and category are the ones set, and the photo, the chips and Seal are all here.
        chosen("Kind", "Box")
        chosen("Contents", "Kitchen")
        rule.onAllNodes(hasContentDescription("Take a photo of what's inside") and hasClickAction()).onFirst().assertExists()
        rule.onAllNodes(hasText("Seal box") and hasClickAction()).onFirst().assertIsDisplayed()
        noKeyboardNeeded()

        // One tap a thing; tapping again takes it off.
        tap("Plates"); tap("Mugs")
        assertEquals("Plates, Mugs", box("S1").contents)
        tap("Mugs")
        assertEquals("Plates", box("S1").contents)
        // Said in one breath, it becomes separate lines.
        say("glasses, pans and the big pot", "Say what's inside")
        assertEquals("Plates, Glasses, Pans, The big pot", box("S1").contents)
        see("The big pot")
        dontSee("Still to add")

        tap("Seal box")
        b = box("S1")
        assertTrue(b.sealed)
        assertEquals("sealed S1", data.latestActivity(1).single().text)
        noKeyboardNeeded()

        // The next one from the same room starts the same as the last; here its label is scanned.
        tap("Pack the next box")
        assertEquals("pack", route())
        tap("Scan a printed label")
        scan("L007")
        assertEquals("box/{id}", route())
        b = box("L007")
        assertEquals("Kitchen box 2", b.name)
        assertEquals("Kitchen", b.category)
        assertEquals(oldKitchen.id, b.originRoomId)
        see("Stick one on top, one on a side")
        noKeyboardNeeded()
        // Whoever wants to type still can: the field is behind Edit, with what is there already in it.
        tap("Bowls")
        tapDesc("Edit what's inside")
        rule.onAllNodes(hasSetTextAction() and hasText("Bowls")).onFirst().assertExists()
    }

    @Test
    fun aSuitcaseTravelsWithYouUnlessToldOtherwise() {
        start("pack")
        tap("Bedroom")
        tap("No sticker? Use a handwritten code")
        chosen("Kind", "Box")
        chosen("Contents", "Clothes") // what a bedroom suggests
        choose("Kind", "Suitcase")
        var b = box("S1")
        assertEquals(ContainerKind.SUITCASE, b.kind)
        assertEquals("Suitcase 1", b.name) // the automatic name follows the kind
        assertTrue(b.travelsWithMe)
        see("Suitcase 1")
        see("Write it on a tag for the handle")
        tap("T-shirts"); tap("Shoes")
        tap("Seal suitcase")
        b = box("S1")
        assertTrue(b.sealed && b.travelsWithMe)
        assertEquals("T-shirts, Shoes", b.contents)
        noKeyboardNeeded()

        // It is on the list of things that stay with you, and loading treats it that way.
        go("lists")
        see("Travels with me (1)")
        see("Suitcase 1")
        go("box/${b.id}")
        tap("Packing details") // rarely changed, so it is folded away
        tap("Travels with me")
        assertFalse(box("S1").travelsWithMe)
        // As another kind it gets that kind's name, and goes on the van like any bag.
        choose("Kind", "Bag")
        assertEquals("Bag 1", box("S1").name)
        assertFalse(box("S1").travelsWithMe)
        tap("Travels with me") // this bag is to stay with us: said by the person, so it is kept
        choose("Kind", "Crate or tub")
        assertTrue(box("S1").travelsWithMe)
        assertEquals("Crate 1", box("S1").name)

        // A name someone chose is left alone when the kind changes.
        store.put(box("S1").copy(name = "Holiday things"))
        settle()
        choose("Kind", "Bag")
        assertEquals("Holiday things", box("S1").name)
        // The next thing packed in the bedroom starts as what the last one was.
        go("pack")
        tap("No sticker? Use a handwritten code")
        assertEquals(ContainerKind.BAG, box("S2").kind)
        assertEquals("Bag 2", box("S2").name)
    }

    // ---- what is inside ----------------------------------------------------------------

    @Test
    fun itemsGoInByTheirLabelOrFromThePicturesAndComeOutAgain() {
        start()
        val b = store.createContainer("L001", oldKitchen.id)
        val camera = movingItem("Camera", oldKitchen)
        val lamp = movingItem("Lamp", oldKitchen, label = "L050")
        val sofa = movingItem("Sofa", oldBedroom, label = "L060")
        go("box/${b.id}")

        // Loose things from the same room are offered as pictures; one tap puts one in.
        see("loose in Kitchen")
        see("Lamp")
        dontSee("Sofa")
        tapBelow("Camera")
        assertEquals(b.id, data.item(camera.id)!!.boxId)
        see("Items inside (1)")
        assertFalse(data.movables().any { it.id == camera.id }) // it now travels with the box
        dontSee("Still to add")

        // Anything else goes in by its own label, whichever room it is from.
        tapDesc("Scan an item's label to put it in")
        scan("L060")
        assertEquals(b.id, data.item(sofa.id)!!.boxId)
        tapDesc("Scan an item's label to put it in")
        scan("l050")
        assertEquals(listOf("Camera", "Lamp", "Sofa"), data.itemsIn(b.id).map { it.name })
        see("Items inside (3)")

        // A label nothing has, and the box's own, are answered in words.
        tapDesc("Scan an item's label to put it in")
        scan("L999")
        see("Nothing here has the label L999")
        tapDesc("Scan an item's label to put it in")
        scan("L001")
        see("own label")

        tapDesc("Take Camera out")
        assertNull(data.item(camera.id)!!.boxId)
        assertEquals(listOf(lamp.id, sofa.id), data.itemsIn(b.id).map { it.id })
        assertTrue(data.movables().any { it.id == camera.id })
        see("Items inside (2)")
        noKeyboardNeeded()

        // Where the box goes, what is in it goes: opening and unpacking are the data layer's.
        store.updateBoxes(listOf(b.id)) { it.copy(sealed = true, stage = Stage.DELIVERED) }
        assertEquals(Stage.DELIVERED, data.item(sofa.id)!!.stage)
        store.updateItems(listOf(sofa.id)) { it.copy(stage = Stage.DONE) } // put in its place: it is out of the box
        settle()
        assertNull(data.item(sofa.id)!!.boxId)
        see("Items inside (1)")
        assertEquals(lamp.id, data.itemsIn(b.id).single().id)
    }

    @Test
    fun oneContainerGoesInsideAnotherAndARefusalSaysWhy() {
        start()
        val suitcase = store.createContainer("B1", oldBedroom.id, ContainerKind.SUITCASE)
        val bag = store.createContainer("B2", oldBedroom.id, ContainerKind.BAG)
        val carton = store.createContainer("L001", oldKitchen.id)
        val chair = movingItem("Chair", oldKitchen, label = "L070")

        go("box/${bag.id}")
        tap("Put in another container")
        scan("B1")
        assertEquals(suitcase.id, data.parentOf(box("B2"))!!.id)
        see("Inside Suitcase 1 (B1)")
        dontSee("Put in another container")
        // It is no longer a separate thing to load.
        assertEquals(setOf("B1", "L001", "L070"), data.movables().map { it.label }.toSet())

        // The outer one shows what is in it, and cannot itself go inside something.
        go("box/${suitcase.id}")
        see("Containers inside (1)")
        see("B2")
        dontSee("Put in another container")

        // One inside another and no deeper: the reason is the Store's.
        go("box/${carton.id}")
        tap("Put in another container")
        scan("B2")
        see("Bag 1 (B2) is already inside Suitcase 1 (B1). Containers only go one inside another.")
        assertNull(box("L001").parentId)
        tap("Put in another container")
        scan("L001")
        see("A container cannot go inside itself.")
        tap("Put in another container")
        scan("ZZ9")
        see("No container has the label ZZ9.")
        tap("Put in another container")
        scan("L070")
        see("Chair is an item, not a container.")
        tapDesc("Dismiss")
        dontSee("is an item")
        noKeyboardNeeded()

        // Taking it out again.
        go("box/${bag.id}")
        tap("Take out")
        assertNull(box("B2").parentId)
        see("Put in another container")
        assertEquals(4, data.movables().size)

        // From the outer side: scanning a container's label where items are scanned puts it in.
        go("box/${suitcase.id}")
        tap("Put an item in") // nothing loose in this room to tap: one line, which opens the scanner
        scan("B2")
        assertEquals(suitcase.id, data.parentOf(box("B2"))!!.id)
        tapDesc("Take Bag 1 out")
        assertNull(box("B2").parentId)
    }

    @Test
    fun aLongListOfContentsIsCutShortAndARefusedCameraOffersAWayForward() {
        start()
        val b = store.createContainer("L001", oldKitchen.id)
        val things = (1..30).map { "Thing $it" }
        store.put(data.box(b.id)!!.copy(contents = things.joinToString(", ")))
        go("box/${b.id}")
        // Thirty things written down: the first few show with three of the usual things to add,
        // and the rest wait behind "more", so the items and Seal stay in reach.
        see("Thing 1"); see("Thing 6")
        dontSee("Thing 7")
        rule.onAllNodes(hasText("Seal box") and hasClickAction()).onFirst().assertIsDisplayed()
        tap("33 more") // 24 more of its own, and 9 of the usual kitchen things
        see("Thing 30"); see("Tea towels")
        tap("Fewer")
        dontSee("Thing 30")
        tap("Thing 3") // one tap takes a thing off again
        assertEquals(29, contentsLines(box("L001").contents).size)

        // The camera is not allowed on this phone. The notice says so and offers what can be done instead.
        tapDesc("Take a photo of what's inside")
        see("The camera is switched off for HouseMove")
        see("Choose photos instead")
        see("Open the phone's settings")
        tap("Not now")
        dontSee("The camera is switched off")
        noKeyboardNeeded()
    }

    // ---- the list ----------------------------------------------------------------------

    @Test
    fun theListFiltersByKindAndCategoryAndCountsNothingTwice() {
        start()
        val a = store.createContainer("L001", oldKitchen.id)
        store.put(data.box(a.id)!!.copy(contents = "Plates", sealed = true))
        store.createContainer("L002", oldKitchen.id)
        val suitcase = store.createContainer("B1", oldBedroom.id, ContainerKind.SUITCASE)
        val bag = store.createContainer("B2", oldBedroom.id, ContainerKind.BAG)
        assertTrue(store.nest(bag.id, suitcase.id).ok)
        assertTrue(store.putInto(movingItem("Camera", oldBedroom).id, suitcase.id).ok)
        go("boxes")

        // What is carried is counted once; what is inside something is counted apart.
        see("All 3")
        see("Open 2"); see("Sealed 1")
        see("Inside another 1")
        assertEquals(data.progress().sealed.total, 3)
        // What kind and what is inside are one chip each, which opens a list with the counts.
        tapDesc("Choose a kind of container")
        see("Boxes 2"); see("Suitcases 1"); see("Bags 1")
        tap("All kinds")
        tapDesc("Choose what is inside")
        see("Kitchen 2"); see("Clothes 2")
        tap("All contents")
        // Each row says what kind it is with a picture, and the bag sits under its suitcase.
        rule.onAllNodes(hasContentDescription("Suitcase")).onFirst().assertExists()
        rule.onAllNodes(hasContentDescription("Inside suitcase B1")).onFirst().assertExists()
        // The suitcase carries the camera and the bag: counted as everywhere else, each thing once.
        see("B2"); see("2 things inside"); see("Plates")

        filter("Choose a kind of container", "Bags 1")
        see("B2"); see("Inside suitcase B1")
        dontSee("L001"); dontSee("L002")
        filter("Choose a kind of container", "All kinds")
        see("L001")

        filter("Choose a kind of container", "Suitcases 1")
        see("B1"); see("2 things inside")
        dontSee("L002"); dontSee("B2")
        filter("Choose a kind of container", "All kinds")

        filter("Choose what is inside", "Kitchen 2")
        see("L001"); see("L002"); dontSee("B1")
        filter("Choose what is inside", "All contents")
        tap("Sealed 1")
        see("L001"); dontSee("L002"); dontSee("B1")
        tap("All 3")

        tap("Inside another 1")
        see("B2"); see("Inside suitcase B1"); dontSee("L001")
        filter("Choose a kind of container", "Boxes 2") // no cartons are inside anything
        see("Nothing matches")
        tap("Show everything")
        see("L002"); see("B1")
        noKeyboardNeeded()

        // A row opens its container; the row under a container opens the one inside.
        tap("B2")
        assertEquals("box/{id}", route())
        see("Inside Suitcase 1 (B1)")
    }

    @Test
    fun theOverviewHomeAndMoveDayCountAContainerInsideAnotherOnceWithItsCarrier() {
        start()
        val suitcase = store.createContainer("B1", oldBedroom.id, ContainerKind.SUITCASE)
        val bag = store.createContainer("B2", oldBedroom.id, ContainerKind.BAG)
        assertTrue(store.nest(bag.id, suitcase.id).ok)
        assertTrue(store.putInto(movingItem("Camera", oldBedroom).id, bag.id).ok)
        store.put(data.box(suitcase.id)!!.copy(sealed = true, travelsWithMe = false))
        settle()

        // The bag is sealed and carried with the suitcase: the bedroom has one container, and it is sealed.
        see("1/1") // boxes sealed, on Home
        go("overview")
        rule.onAllNodes(hasContentDescription("Bedroom: 1 of 1 boxes sealed", substring = true)).onFirst().assertExists()
        see("All sealed")
        go("boxes")
        see("All 1"); see("Inside another 1")
        go("load")
        see("0 of 1 loaded"); see("2 things inside")

        // Delivered, it is one thing in, one thing to unpack, with the bag and the camera to come out of it.
        store.updateBoxes(listOf(suitcase.id)) { it.copy(stage = Stage.DELIVERED) }
        go("unload")
        see("1 of 1 delivered")
        go("reconcile")
        see("Everything is accounted for"); see("1 delivered")
        go("unpack")
        see("Boxes (1)"); see("Furniture (0)"); see("2 things to come out")
    }

    // ---- hang tags ---------------------------------------------------------------------

    @Test
    fun hangTagsArePrintedForSuitcasesAndBags() {
        start()
        store.setMove { it.copy(labelsPrinted = 42) }
        store.createContainer("S1", oldBedroom.id, ContainerKind.SUITCASE) // a handwritten code: it gets a proper tag
        store.createContainer("L040", oldBedroom.id, ContainerKind.BAG) // already has a printed code
        store.createContainer("S2", oldKitchen.id) // a carton takes a sticker, not a tag
        go("labels")
        see("Hang tags")
        see("8 tags on 1 page")
        see("L043 – L049")
        noKeyboardNeeded()

        tap("A4")
        tap("Open to print", index = 1)
        rule.waitUntil(10_000) { tagCodes != null && data.move.labelsPrinted == 49 }
        settle()
        assertEquals(listOf("S1", "L043", "L044", "L045", "L046", "L047", "L048", "L049"), tagCodes)
        assertEquals(TagSheet.A4_TAGS, tagSheet)
        val made = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "out/housemove-hang-tags.pdf")
        assertTrue(made.length() > 1000)
        // The numbers are used up, so the stickers carry on after them.
        see("L050 – ")
        // The same tags again until told otherwise (print, then share); one tap moves on.
        see("L043 – L049")
        tap("These are made. Next: from L050", sub = true)
        see("L050 – L056")

        tap("Letter")
        tagCodes = null
        tap("Share", index = 1)
        rule.waitUntil(10_000) { tagCodes != null && data.move.labelsPrinted == 56 }
        assertEquals(TagSheet.LETTER_TAGS, tagSheet)
        assertEquals("S1", tagCodes!!.first())
        assertEquals("L056", tagCodes!!.last())
        noKeyboardNeeded()
    }

    // ---- people ------------------------------------------------------------------------

    @Test
    fun aHelperPacksLikeAnyoneElseAndNothingSaysSo() {
        // Asha runs the move; this phone is Hal's, a Helper.
        val asha = newStore()
        asha.setDevice { it.copy(name = "Asha", letter = "A") }
        val hal = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        val phone = Store(tmp.newFolder())
        store = asha
        rooms()
        val suitcase = asha.createContainer("B1", oldBedroom.id, ContainerKind.SUITCASE)
        val item = asha.createItem("Camera", oldKitchen.id)
        asha.put(asha.data.value.item(item.id)!!.copy(disposition = Disposition.MOVE))
        assertTrue(phone.joinAs(hal.id, asha.data.value).ok)
        assertEquals(Role.HELPER, phone.myRole)
        start("pack", prepared = phone)

        tap("Kitchen")
        tap("No sticker? Use a handwritten code")
        val b = data.liveBoxes.first { it.id != suitcase.id }
        assertEquals("Hal", b.packedBy)
        tap("Plates")
        tapBelow("Camera")
        assertEquals(b.id, data.item(item.id)!!.boxId)
        tap("Put in another container")
        scan("B1")
        assertEquals(suitcase.id, data.box(b.id)!!.parentId)
        tap("Seal box")
        assertTrue(data.box(b.id)!!.sealed)
        // Nothing was refused, and nothing on the way mentioned roles.
        for (word in listOf("Helper", "Organiser", "Decider", "Only an")) dontSee(word)
        // One thing is left out for him, without a word about why: taking the box off everyone's list.
        tap("More")
        see("Change label")
        dontSee("Delete this box")
        go("boxes"); for (word in listOf("Helper", "Organiser", "Decider")) dontSee(word)
    }

    // ---- pictures for design review ------------------------------------------------------

    /** A stand-in photograph: a coloured room with an object in it, different for every seed. */
    private fun picture(file: File, seed: Int) {
        val w = 480
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hues = listOf(0xFFB9C7D6, 0xFFD9C6A5, 0xFFC7D8C0, 0xFFE2C4C0, 0xFFCFC7E0, 0xFFE6DDB8)
        val objs = listOf(0xFF6B4F3A, 0xFF2F4858, 0xFF8A3B2E, 0xFF3F6B4E, 0xFF55407A, 0xFF7A6A2E)
        c.drawColor(hues[seed % hues.size].toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33000000
        c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), p)
        p.color = objs[(seed / 2) % objs.size].toInt()
        val ow = w * (0.35f + (seed % 3) * 0.1f)
        val oh = h * (0.4f + (seed % 4) * 0.08f)
        c.drawRoundRect(RectF((w - ow) / 2, h * 0.8f - oh, (w + ow) / 2, h * 0.8f), 14f, 14f, p)
        p.color = 0x22FFFFFF
        c.drawRoundRect(RectF((w - ow) / 2 + 12, h * 0.8f - oh + 12, (w + ow) / 2 - 12, h * 0.8f - oh * 0.55f), 8f, 8f, p)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    /**
     * The states the standard audit data lacks: suitcases, bags and crates among the
     * cartons, a bag inside a suitcase, catalogued items inside a container, a container
     * in the middle of being packed, and luggage waiting for hang tags.
     */
    private val ids = HashMap<String, String>()

    private fun mixed(): Store {
        val s = newStore()
        store = s
        var n = 0
        fun photo(owner: String, kind: PhotoKind = PhotoKind.CONTENTS) {
            val f = s.newPhotoFile()
            picture(f, n++)
            s.addPhoto(owner, kind, f)
        }
        s.setMove { it.copy(name = "Maple Road move", labelsPrinted = 42) }
        val dKitchen = s.createRoom(Home.DEST, "Kitchen")
        val dBed = s.createRoom(Home.DEST, "Main bedroom")
        val dLiving = s.createRoom(Home.DEST, "Living room")
        val dOffice = s.createRoom(Home.DEST, "Office")
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = dKitchen.id).also { s.put(it) }
        val bed = s.createRoom(Home.ORIGIN, "Main bedroom").copy(mapsTo = dBed.id).also { s.put(it) }
        val living = s.createRoom(Home.ORIGIN, "Living room").copy(mapsTo = dLiving.id).also { s.put(it) }
        s.createRoom(Home.ORIGIN, "Loft").copy(mapsTo = dOffice.id).also { s.put(it) }
        s.setDevice { it.copy(currentRoomId = kitchen.id) }
        val trip = Trip(id = newId(), name = "Trip 1", sort = 1).also { s.put(it) }

        fun container(key: String, label: String, room: Room, kind: ContainerKind? = null, f: (Box) -> Box = { it }) {
            val made = s.createContainer(label, room.id, kind)
            s.put(f(made))
            ids[key] = made.id
        }
        container("k3", "L003", kitchen, ContainerKind.CRATE) { it.copy(category = "Food", contents = "Tins, Pasta, Spices", sealed = true) }
        container("k1", "L001", kitchen, ContainerKind.CARTON) { it.copy(category = "Kitchen", contents = "Kettle, Mugs, Tea, Cutlery", priority = Priority.FIRST_NIGHT, sealed = true, fragile = true) }
        container("k2", "L002", kitchen) { it.copy(contents = "Pans, Baking trays", heavy = true) }
        container("b1", "S1", bed, ContainerKind.SUITCASE) { it.copy(contents = "T-shirts, Shoes, Jumpers") }
        container("b2", "S2", bed, ContainerKind.BAG) { it.copy(category = "Bathroom", contents = "Toiletries, Medicines", sealed = true) }
        container("b3", "L004", bed, ContainerKind.BAG) { it.copy(category = "Bedding", contents = "Duvets, Pillows", sealed = true, stage = Stage.LOADED, tripId = trip.id, priority = Priority.FIRST_NIGHT) }
        container("b4", "L005", bed, ContainerKind.WARDROBE) { it.copy(category = "Clothes", contents = "Coats, Dresses", sealed = true, stage = Stage.DELIVERED) }
        container("l1", "L006", living, ContainerKind.CARTON) { it.copy(category = "Books", contents = "Novels, Cookbooks", sealed = true, stage = Stage.DONE) }
        container("l2", "L007", living) { it.copy(category = "Electronics", contents = "Cables, Router, Chargers", thisWayUp = true) }
        for (key in listOf("k1", "k2", "b1", "b4", "l1", "l2")) photo(ids.getValue(key))
        s.nest(ids.getValue("b2"), ids.getValue("b1"))
        s.scanInto(ids.getValue("l1"), "9780140328721", title = "Fantastic Mr. Fox", author = "Roald Dahl")
        s.scanInto(ids.getValue("l1"), "", title = "Photo albums")

        fun item(id: String, name: String, room: Room, f: (Item) -> Item = { it }) {
            s.put(f(Item(id = id, name = name, originRoomId = room.id, destRoomId = room.mapsTo, disposition = Disposition.MOVE)))
            photo(id, PhotoKind.GENERAL)
        }
        item("camera", "Camera", bed) { it.copy(label = "L050", travelsWithMe = true) }
        item("lamp", "Bedside lamp", bed)
        item("mirror", "Mirror", bed) { it.copy(fragile = true) }
        item("clock", "Alarm clock", bed)
        item("toaster", "Toaster", kitchen)
        s.putInto("camera", ids.getValue("b1"))
        s.putInto("clock", ids.getValue("b1"))
        s.put(Box(id = "paint", label = "S3", originRoomId = living.id, kind = ContainerKind.CRATE, category = "Tools", name = "Crate 2", contents = "Paint, Brushes", hazardous = true))
        s.flush()
        return s
    }

    private fun shots(kind: String) {
        start(prepared = mixed())
        fun shot(name: String) {
            settle()
            Thread.sleep(150) // photos are decoded on another thread
            settle()
            rule.onRoot().captureRoboImage("build/audit/$kind/$name.png")
        }
        go("boxes"); shot("c_boxes")
        filter("Choose a kind of container", "Bags 2"); shot("c_boxes_bags")
        filter("Choose a kind of container", "All kinds")
        go("pack"); shot("c_pack")
        // A container the moment it has been created, before anything is tapped.
        tap("No sticker? Use a handwritten code"); shot("c_box_new")
        tap("Plates"); tap("Glasses"); say("the good knives and tea towels", "Say what's inside"); shot("c_box_packing")
        go("box/${ids["b1"]}"); shot("c_box_suitcase")
        go("box/${ids["b2"]}"); shot("c_box_inside")
        go("box/${ids["l1"]}"); shot("c_box_sealed")
        // The scanner for books and products opens over the page, in a window of its own.
        tapDesc("Scan barcodes of books and products")
        settle()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/audit/$kind/c_scanin.png")
        tap("Done")
        go("labels"); shot("c_labels")
        go("lists"); shot("c_lists")
        go("packorder"); shot("c_packorder")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun picturesAsAPhoneShowsThem() = shots("fold")

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h2300dp-mdpi")
    fun picturesOfWholeScreens() = shots("tall")

    /** The same screens on a 360dp phone, the most common small size: what is inside must not push the items under Seal. */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
    fun picturesOnA360dpPhone() = shots("c-360")
}
