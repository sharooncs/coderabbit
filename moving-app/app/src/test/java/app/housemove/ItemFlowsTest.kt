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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalSpeech
import app.housemove.ui.Speech
import app.housemove.ui.screens.LocalWalkCamera
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
 * The item screens driven by tapping, as each kind of person sees them: an Organiser who
 * decides, a Decider whose choices are suggestions, a Helper who can only ask. Each has a
 * phone of their own here; [sync] is what bringing two phones together does.
 * The camera and the microphone are stood in for, so nothing in these flows is typed
 * unless the test says it is trying the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ItemFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private var clock = 1_000L
    private fun store(name: String) = Store(tmp.newFolder(name)) { clock++ }

    private lateinit var asha: Store // Organiser, and the only one in a move of one
    private lateinit var dee: Store // Decider
    private lateinit var hal: Store // Helper

    private lateinit var oldKitchen: Room
    private lateinit var oldLounge: Room
    private lateinit var newKitchen: Room
    private lateinit var newLiving: Room
    private lateinit var sofa: String
    private lateinit var lamp: String
    private lateinit var table: String

    /** The phone whose screen is showing. */
    private var phone by mutableStateOf<Store?>(null)
    private var nav: NavHostController? = null

    /** What the stand-in microphone hears next. */
    private val said = ArrayDeque<String>()

    /** False for a phone with no speech service: the microphone is then not shown anywhere. */
    private var canSpeak = true
    private var pictures = 0

    /** A move of one: nobody to ask, nothing about roles. The old rooms are not linked to the new ones, only named alike. */
    private fun solo() {
        asha = store("asha")
        asha.setDevice { it.copy(name = "Asha", letter = "A") }
        asha.setMove { it.copy(name = "Test move", moveDay = today() + 10, setupDone = true, currency = "£") }
        newKitchen = asha.createRoom(Home.DEST, "Kitchen")
        newLiving = asha.createRoom(Home.DEST, "Living room")
        oldKitchen = asha.createRoom(Home.ORIGIN, "Kitchen")
        oldLounge = asha.createRoom(Home.ORIGIN, "Lounge")
        sofa = asha.createItem("Sofa", oldLounge.id).id
        lamp = asha.createItem("Lamp", oldLounge.id).id
        table = asha.createItem("Dining table", oldKitchen.id).id
        rule.setContent {
            val s = phone
            if (s != null) key(s) {
                CompositionLocalProvider(
                    LocalSpeech provides if (canSpeak) Speech { _, onText -> said.removeFirstOrNull()?.let(onText) } else null,
                    LocalWalkCamera provides { file -> picture(file, pictures++) },
                ) { AppRoot(s, startRoute = "home", onNav = { nav = it }) }
            }
        }
    }

    /** The same move with a Decider and a Helper, each on a phone of their own. */
    private fun team() {
        solo()
        dee = joined("Dee", Role.DECIDER)
        hal = joined("Hal", Role.HELPER)
    }

    private fun joined(name: String, role: Role): Store {
        val p = asha.addPerson(name, role).valueOrNull()!!
        val s = store("phone-$name")
        assertTrue(s.joinAs(p.id, asha.data.value).ok)
        asha.mergeIn(s.data.value)
        return s
    }

    /** What a sync does, both ways, photos included. */
    private fun sync(a: Store, b: Store) {
        b.mergeIn(a.mergeIn(b.data.value))
        for ((from, to) in listOf(a to b, b to a)) {
            from.photoDir.listFiles()?.forEach { f -> File(to.photoDir, f.name).takeIf { !it.exists() }?.let { f.copyTo(it) } }
        }
        rule.waitForIdle()
    }

    /** Hand the test another person's phone, open at [route]. */
    private fun show(s: Store, route: String) {
        if (phone !== s) {
            rule.runOnUiThread { phone = s }
            rule.waitForIdle()
        }
        rule.runOnUiThread { nav!!.navigate(route) }
        settle()
    }

    private fun settle() = rule.waitForIdle()

    private fun click(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
            // not inside a scrolling container
        }
        node.performClick()
        settle()
    }

    private fun tap(text: String, index: Int = 0) = click(rule.onAllNodes(hasText(text) and hasClickAction())[index])
    private fun tapDesc(description: String) = click(rule.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst())
    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)
    private fun cannotTap(text: String) = rule.onAllNodes(hasText(text) and hasClickAction()).assertCountEquals(0)
    private fun noKeyboardNeeded() = rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

    private fun type(label: String, value: String) {
        val node = rule.onAllNodes(hasSetTextAction() and hasText(label)).onFirst()
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
        }
        node.performTextReplacement(value)
        settle()
    }

    private fun Store.item(id: String) = data.value.item(id)!!
    private fun Store.proposal(id: String) = data.value.proposal(id)!!
    private fun Store.waiting() = data.value.waitingProposals()

    /** A stand-in photograph: a coloured room with an object in it, different for every [seed]. */
    private fun picture(file: File, seed: Int) {
        val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val walls = listOf(0xFFB9C7D6, 0xFFD9C6A5, 0xFFC7D8C0, 0xFFE2C4C0, 0xFFCFC7E0)
        val things = listOf(0xFF6B4F3A, 0xFF2F4858, 0xFF8A3B2E, 0xFF3F6B4E, 0xFF55407A)
        c.drawColor(walls[seed % walls.size].toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33000000
        c.drawRect(0f, 260f, 480f, 360f, p)
        p.color = things[(seed * 2 + 1) % things.size].toInt()
        c.drawRoundRect(RectF(150f - seed % 3 * 20f, 110f, 330f + seed % 3 * 20f, 290f), 14f, 14f, p)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun photograph(s: Store, vararg ids: String) {
        for (id in ids) s.addPhoto(id, PhotoKind.GENERAL, s.newPhotoFile().also { picture(it, pictures++) })
    }

    // ---- walk-through ---------------------------------------------------------------

    @Test
    fun aPhotoIsEnoughInTheWalkThroughAndNamingNeedsNoKeyboard() {
        solo()
        show(asha, "walk")
        tap("Kitchen")
        tap("Start shooting")
        tap("Start shooting")
        val made = asha.data.value.liveItems.filter { it.id !in setOf(sofa, lamp, table) }.sortedBy { it.createdAt }
        // Each photo is an item of the room, saved under a name nobody had to give it.
        assertEquals(listOf("Kitchen item 2", "Kitchen item 3"), made.map { it.name })
        assertTrue(made.all { it.originRoomId == oldKitchen.id && asha.data.value.photosOf(it.id, PhotoKind.GENERAL).size == 1 })
        see("Kitchen item 3")
        noKeyboardNeeded()

        // One tap on a chip suited to the room names the newest; a spoken word names the other.
        tap("Fridge-freezer")
        assertEquals("Fridge-freezer", asha.item(made[1].id).name)
        said += "washing machine"
        tapDesc("Say the name of Kitchen item 2")
        assertEquals("Washing machine", asha.item(made[0].id).name)
        dontSee("Kitchen item")
        noKeyboardNeeded()

        // Typing is still there, behind the pencil, with the name already in the field.
        tapDesc("Edit the name of Washing machine")
        type("Washing machine", "Bosch washing machine")
        assertEquals("Bosch washing machine", asha.item(made[0].id).name)
    }

    /** The size for the doorway check is said in one go; the three fields are there for typing it. */
    @Test
    fun theSizeIsSaidInOneGo() {
        solo()
        asha.decide(sofa, Disposition.MOVE)
        show(asha, "item/$sofa")
        tap("Size and handling")
        said += "200 by 90 by 85.5"
        tapDesc("Say its size")
        val s = asha.item(sofa)
        assertEquals(listOf(200.0, 90.0, 85.5), listOf(s.widthCm, s.depthCm, s.heightCm))
        see("85.5") // the field shows what was said
        // Two numbers fill the first two; the third is kept.
        said += "180, 80"
        tapDesc("Say its size")
        assertEquals(listOf(180.0, 80.0, 85.5), asha.item(sofa).let { listOf(it.widthCm, it.depthCm, it.heightCm) })
    }

    // ---- deciding ---------------------------------------------------------------------

    @Test
    fun aDeciderSuggestsOnTheItemPageAndAnOrganiserApprovesItThere() {
        team()
        show(dee, "item/$sofa")
        // The same picture tiles, titled as a suggestion.
        see("Suggest what happens to it")
        dontSee("What happens to it?")
        tap("Sell")
        tap("£ 50")
        tapDesc("Asking price: more")
        tap("Suggest selling for £ 55")
        val mine = dee.waiting().single()
        assertEquals(ProposalKind.ITEM_DECISION, mine.kind)
        assertEquals(Disposition.SELL, mine.disposition)
        assertEquals(55.0, mine.price!!, 0.0)
        assertEquals(Disposition.UNDECIDED, dee.item(sofa).disposition) // nothing has changed yet
        see("Waiting for approval")
        see("You: Sell for £ 55")
        cannotTap("Listed for sale") // and nothing to carry out
        // Saying the same again does not pile up a second suggestion.
        tap("Sell")
        tap("Suggest selling for £ 55")
        assertEquals(1, dee.waiting().size)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("already suggested"))
        // She thinks the lamp should be given away, too.
        show(dee, "item/$lamp")
        tap("Donate")
        assertEquals(2, dee.waiting().size)

        // The Organiser finds the suggestion on the sofa itself.
        sync(asha, dee)
        show(asha, "item/$sofa")
        see("Waiting for approval")
        see("Dee: Sell for £ 55")
        tap("Approve")
        assertEquals(Disposition.SELL, asha.item(sofa).disposition)
        assertEquals(55.0, asha.item(sofa).price!!, 0.0)
        assertEquals(ProposalState.APPROVED, asha.proposal(mine.id).state)
        dontSee("Waiting for approval")
        // The lamp stays: declined, with the reason spoken.
        show(asha, "item/$lamp")
        see("Dee: Donate")
        tap("Decline")
        see("We're keeping it") // a few reasons to tap, as in the Approvals inbox
        said += "we still use it"
        tapDesc("Say why not")
        val declined = asha.data.value.proposalsFor(lamp).single()
        assertEquals(ProposalState.DECLINED, declined.state)
        assertEquals("we still use it", declined.reason)
        assertEquals(Disposition.UNDECIDED, asha.item(lamp).disposition)

        // Back on the Decider's phone: the answer to each, and the sofa is now hers to sell.
        sync(asha, dee)
        show(dee, "item/$lamp")
        see("Declined by Asha: we still use it")
        show(dee, "item/$sofa")
        see("Approved by Asha: Sell for £ 55")
        tap("Listed for sale")
        tap("Sold and gone")
        val sold = dee.item(sofa)
        assertTrue(sold.listed && sold.gone)
        assertEquals(55.0, sold.soldFor!!, 0.0) // starts at the asking price
        tapDesc("Sold for: less")
        assertEquals(50.0, dee.item(sofa).soldFor!!, 0.0)
        assertEquals(dee.me!!.id, dee.item(sofa).outcomeBy.by)
    }

    @Test
    fun aHelperSeesNoDecisionTilesAndAsksForADecision() {
        team()
        show(hal, "item/$sofa")
        see("Undecided")
        for (choice in listOf("Move", "Sell", "Donate", "Bin", "Store")) cannotTap(choice)
        said += "one leg is broken"
        tapDesc("Ask with a spoken note")
        val question = hal.waiting().single()
        assertEquals(ProposalKind.QUESTION, question.kind)
        assertEquals("one leg is broken", question.note)
        see("You asked: one leg is broken")
        cannotTap("Needs a decision") // asked once is enough

        // In triage a Helper flags things one after another, with one tap each.
        show(hal, "triage")
        see("with no decision yet")
        cannotTap("Move")
        tap("Needs a decision")
        tap("Needs a decision")
        assertEquals(setOf(sofa, lamp, table), hal.waiting().map { it.targetId }.toSet())
        see("All asked")

        // An Organiser answers by deciding, right where the question is shown.
        sync(asha, hal)
        show(asha, "item/$sofa")
        see("Hal asked")
        see("one leg is broken")
        tap("Bin")
        assertEquals(Disposition.BIN, asha.item(sofa).disposition)
        assertEquals(ProposalState.APPROVED, asha.proposal(question.id).state)
        dontSee("Hal asked")
        // The questions come first in the Organiser's triage, and can be dismissed as well.
        show(asha, "triage")
        see("Hal asked")
        tap("Not now")
        tap("Not sure yet")
        assertEquals(1, asha.waiting().size)
        sync(asha, hal)
        show(hal, "item/$sofa")
        see("Answered by Asha")
        see("Bin")
    }

    @Test
    fun triageMakesSuggestionsForADeciderAndPutsThemFirstForAnOrganiser() {
        team()
        show(dee, "triage")
        see("left to suggest")
        see("Suggest what happens to it")
        tap("Move") // the dining table, from the kitchen
        tap("Sell") // the sofa: a price first
        tap("£ 100")
        tap("Suggest selling for £ 100")
        assertEquals(listOf(table to Disposition.MOVE, sofa to Disposition.SELL), dee.waiting().map { it.targetId to it.disposition })
        assertTrue(dee.data.value.liveItems.all { it.disposition == Disposition.UNDECIDED })
        tap("Bin") // the lamp
        see("All suggested")
        see("3 waiting for an Organiser")

        sync(asha, dee)
        show(asha, "triage")
        see("left to decide")
        see("Dee: Move")
        tap("Approve")
        assertEquals(Disposition.MOVE, asha.item(table).disposition)
        see("Dee: Sell for £ 100")
        tap("Donate") // deciding otherwise overtakes the suggestion
        assertEquals(Disposition.DONATE, asha.item(sofa).disposition)
        assertEquals(ProposalState.OVERTAKEN, asha.data.value.proposalsFor(sofa).single().state)
        tap("Decline")
        tap("No reason")
        assertEquals(Disposition.UNDECIDED, asha.item(lamp).disposition)
        tap("Store") // declined, so it is still hers to decide
        see("All decided")
        assertTrue(asha.waiting().isEmpty())

        // Later the Decider would rather sell the table that is marked Move. Choosing Move again keeps it and says no.
        sync(asha, dee)
        assertTrue(dee.suggestDecision(table, Disposition.SELL, 30.0).ok)
        sync(asha, dee)
        show(asha, "triage")
        see("Now: Move")
        see("Dee: Sell for £ 30")
        tap("Move")
        assertEquals(ProposalState.DECLINED, asha.data.value.proposalsFor(table).first().state)
        assertEquals(Disposition.MOVE, asha.item(table).disposition)
        see("All decided")
    }

    // ---- carrying out -------------------------------------------------------------------

    @Test
    fun carryingOutIsOfferedOnlyOnceASuggestionIsApproved() {
        team()
        assertTrue(asha.decide(sofa, Disposition.SELL, 80.0).ok)
        assertTrue(asha.decide(lamp, Disposition.DONATE).ok)
        sync(asha, dee)
        sync(asha, hal)

        // The Organiser decided these herself, so they are not the Decider's to carry out.
        show(dee, "disposal")
        see("Asking £ 80")
        rule.onAllNodes(hasContentDescription("Gone", substring = true)).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("Listed for sale", substring = true)).assertCountEquals(0)
        assertEquals("An Organiser has to approve your suggestion for this first.", dee.carryOut(sofa, gone = true).reason)
        show(dee, "item/$sofa")
        cannotTap("Sold and gone")
        see("once an Organiser approves")
        // A Helper is not offered it anywhere either.
        show(hal, "disposal")
        rule.onAllNodes(hasContentDescription("Gone", substring = true)).assertCountEquals(0)
        tap("Asking £ 80") // for a Helper the price is not a button: the tap just opens the item
        cannotTap("£ 50")
        assertNotNull(hal.carryOut(sofa, gone = true).reason)

        // The Decider suggests a lower price from the list; once approved the sale is hers.
        show(dee, "disposal")
        tap("Asking £ 80")
        tap("£ 50")
        tap("Suggest £ 50")
        see("Waiting for approval")
        sync(asha, dee)
        show(asha, "disposal")
        see("Dee: Sell for £ 50")
        tap("Approve")
        assertEquals(50.0, asha.item(sofa).price!!, 0.0)
        sync(asha, dee)
        show(dee, "disposal")
        tapDesc("Listed for sale: Sofa")
        assertTrue(dee.item(sofa).listed)
        tapDesc("Gone: Sofa")
        assertTrue(dee.item(sofa).gone)
        assertEquals(50.0, dee.item(sofa).soldFor!!, 0.0)
        tap("Sold for £ 50")
        tap("£ 40")
        assertEquals(40.0, dee.item(sofa).soldFor!!, 0.0)
        assertEquals(dee.me!!.id, dee.item(sofa).outcomeBy.by)
        // The lamp was never hers.
        rule.onAllNodes(hasContentDescription("Gone: Lamp")).assertCountEquals(0)
        noKeyboardNeeded()

        // The Organiser carries the rest out, with prices by chip and stepper.
        sync(asha, dee)
        show(asha, "disposal")
        see("1 of 2 gone")
        see("sold £ 40")
        tapDesc("Gone: Lamp")
        assertTrue(asha.item(lamp).gone)
        see("All gone")
    }

    // ---- containers -----------------------------------------------------------------------

    @Test
    fun anItemGoesIntoAContainerByATapOrAScanAndComesOutAgain() {
        solo()
        val box = asha.createContainer("L001", oldLounge.id)
        asha.createContainer("L002", oldKitchen.id)
        show(asha, "item/$lamp")
        tap("Put in a container")
        // Open containers from the same room are offered; the kitchen's is not.
        see("Lounge box 1 (L001)")
        dontSee("L002")
        tap("Lounge box 1 (L001)")
        assertEquals(box.id, asha.item(lamp).boxId)
        see("Inside Lounge box 1 (L001)")
        cannotTap("Put in a container")
        tap("Take out")
        assertNull(asha.item(lamp).boxId)

        // Or scan the container's label (typed here in place of the camera).
        tap("Put in a container")
        tap("Scan the container's label")
        type("Or type the code", "L002")
        tap("OK")
        assertEquals("L002", asha.data.value.containerOf(asha.item(lamp))!!.label)
        tap("Take out")
        // A label that is on nothing yet starts a new container, if wanted.
        tap("Put in a container")
        tap("Scan the container's label")
        type("Or type the code", "L009")
        tap("OK")
        tap("Start it")
        val fresh = asha.data.value.containerOf(asha.item(lamp))!!
        assertEquals("L009", fresh.label)
        assertEquals(oldLounge.id, fresh.originRoomId)

        // In the list the packed item carries its container's code, and a filter tells loose from packed.
        show(asha, "items")
        see("L009")
        tap("Packed 1")
        see("Lamp")
        dontSee("Sofa")
        tap("Loose 2")
        dontSee("Lamp")
        see("Sofa")
        tap("All 3")
        see("Lamp")
        see("Sofa")
    }

    // ---- room plan ------------------------------------------------------------------------

    @Test
    fun allSuggestedRoomsAreAcceptedWithOneTapAndADecidersAreSuggestions() {
        team()
        for (id in listOf(sofa, lamp, table)) assertTrue(asha.decide(id, Disposition.MOVE).ok)
        assertTrue(asha.data.value.movingItems().all { it.destRoomId == null })
        sync(asha, dee)

        // The likely room is already picked for each thing: "Lounge" goes to "Living room".
        show(dee, "plan")
        see("Needs a room"); see("3")
        tap("Other room", index = 1) // she would rather have the lamp in the kitchen
        tap("Kitchen")
        val first = dee.waiting().single()
        assertEquals(ProposalKind.ITEM_ROOM, first.kind)
        assertEquals(lamp to newKitchen.id, first.targetId to first.roomId)
        see("You: To Kitchen")
        tap("Suggest all 2 as shown")
        assertEquals(
            setOf(lamp to newKitchen.id, sofa to newLiving.id, table to newKitchen.id),
            dee.waiting().map { it.targetId to it.roomId }.toSet(),
        )
        assertTrue(dee.data.value.movingItems().all { it.destRoomId == null }) // suggestions, not the plan
        cannotTap("Suggest all 2 as shown")

        // The Organiser approves one where it is shown, then accepts every suggested room at once.
        sync(asha, dee)
        show(asha, "plan")
        see("Dee: To Living room")
        tap("Approve")
        assertEquals(newLiving.id, asha.item(sofa).destRoomId)
        tap("Accept all suggestions")
        assertEquals(newLiving.id, asha.item(lamp).destRoomId) // her plan, not the Decider's kitchen
        assertEquals(newKitchen.id, asha.item(table).destRoomId)
        assertTrue(asha.waiting().isEmpty())
        see("Everything has a room")
        noKeyboardNeeded()
    }

    // ---- one person, and no typing ------------------------------------------------------------

    @Test
    fun withOnePersonNothingAboutRolesShowsAndNoScreenOpensOnATextField() {
        solo()
        val tv = asha.createItem("Television", oldLounge.id).id
        assertTrue(asha.decide(sofa, Disposition.SELL, 80.0).ok)
        assertTrue(asha.decide(table, Disposition.MOVE).ok)
        assertTrue(asha.decide(tv, Disposition.MOVE).ok)
        photograph(asha, sofa, table)
        for (route in listOf("items", "item/$sofa", "item/$lamp", "item/$table", "item/$tv", "walk", "triage", "plan", "disposal", "electronics")) {
            show(asha, route)
            noKeyboardNeeded()
            for (word in listOf("Suggest", "approval", "Approve", "Needs a decision", "Organiser")) dontSee(word)
        }
        // Everything is decided and carried out directly.
        show(asha, "item/$sofa")
        see("What happens to it?")
        tapDesc("Asking price: more")
        assertEquals(85.0, asha.item(sofa).price!!, 0.0)
        tap("Sold and gone")
        assertEquals(85.0, asha.item(sofa).soldFor!!, 0.0)
        // A device: the serial number is scanned, make and model are spoken.
        show(asha, "electronics")
        tapDesc("Scan the serial number of Television")
        type("Or type the serial number", "SN-123")
        tap("OK")
        assertEquals("SN-123", asha.item(tv).serial)
        show(asha, "item/$tv")
        tap("Make, model and serial number")
        noKeyboardNeeded()
        said += "samsung QE55 Q60"
        tapDesc("Say the make and model")
        assertEquals("Samsung QE55 Q60", asha.item(tv).makeModel())
    }

    // ---- what a role is not offered, and what became of a suggestion -------------------------------

    @Test
    fun deletingFollowsTheStoresRuleAndAnyoneElseCanAskAndWithdraw() {
        team()
        // Hal did not add the sofa: deleting it is not his, but asking an Organiser to is, with the reason why.
        show(hal, "item/$sofa")
        tap("More")
        see("Room in the old home")
        cannotTap("Delete item")
        see("Only an Organiser can delete something someone else added.")
        rule.onRoot().captureRoboImage("build/audit/flows/item-helper-ask-to-delete.png")
        tap("Ask to delete")
        val asked = hal.waiting().single()
        assertTrue(asked.deletes)
        see("You asked to delete it")
        cannotTap("Ask to delete") // asked once is enough
        // He thinks better of it: his own request can be taken back where it is shown.
        rule.onRoot().captureRoboImage("build/audit/flows/item-helper-asked-to-delete.png")
        tap("Withdraw")
        assertTrue(hal.waiting().isEmpty())
        tap("Ask to delete")
        assertEquals(1, hal.waiting().size)

        // Dee may delete what she added herself while nobody else has touched it.
        val rug = dee.createItem("Old rug", oldLounge.id).id
        show(dee, "item/$rug")
        tap("More")
        tap("Delete item")
        tap("Delete")
        assertNull(dee.data.value.item(rug))

        // The Organiser finds Hal's request on the sofa itself, and approving it deletes the sofa.
        sync(asha, hal)
        show(asha, "item/$sofa")
        see("Hal asked to delete it")
        tap("Approve")
        assertNull(asha.data.value.item(sofa))
    }

    @Test
    fun aDeclinedSuggestionSaysWhyOnTheListsUntilItHasBeenRead() {
        team()
        assertTrue(asha.decide(lamp, Disposition.MOVE).ok)
        sync(asha, dee)
        // Dee suggests selling the sofa, and a room for the lamp. Asha says no to both, with a reason each.
        val sell = dee.suggestDecision(sofa, Disposition.SELL, 40.0).valueOrNull()!!
        val room = dee.suggestRoom(lamp, newKitchen.id).valueOrNull()!!
        sync(asha, dee)
        assertTrue(asha.decline(sell.id, "We're keeping it").ok)
        assertTrue(asha.decline(room.id, "Won't fit there").ok)
        sync(asha, dee)

        // The room plan says why, on the lamp, until it is put away with OK.
        show(dee, "plan")
        see("Declined by Asha: Won't fit there")
        see("You suggested: To Kitchen")
        tap("OK")
        dontSee("Declined by Asha")
        // Triage brings the sofa back with the reason.
        show(dee, "triage")
        tap("Skip for now") // the dining table comes first
        see("Declined by Asha: We're keeping it")
        see("You suggested: Sell for £ 40")
        tap("OK")
        dontSee("Declined by Asha")
        // Having been read is remembered on the phone: it does not come back on the next visit.
        show(dee, "plan")
        dontSee("Declined by Asha")
        show(dee, "disposal")
        dontSee("Declined by Asha")
        // The item's own page keeps saying how her last suggestion about it ended.
        show(dee, "item/$sofa")
        see("Declined by Asha: We're keeping it")
        // Nobody else is shown it.
        show(asha, "plan")
        dontSee("Declined by")
    }

    @Test
    fun withNoSpeechOnThePhoneTheKeyboardTakesTheMicrophonesPlace() {
        canSpeak = false
        team()
        // A Helper's question: one tap still asks; the few words of why are typed where they would be said.
        show(hal, "item/$sofa")
        rule.onAllNodes(hasContentDescription("Ask with a spoken note")).assertCountEquals(0)
        noKeyboardNeeded() // the field is not there until it is asked for
        tapDesc("Ask with a typed note")
        type("What should they know about it?", "one leg is broken")
        tap("Ask")
        assertEquals("one leg is broken", hal.waiting().single().note)
        // Naming a thing: no microphone, and the pencil in the bar opens the field.
        val chair = hal.createItem(hal.data.value.defaultItemName(oldLounge.id), oldLounge.id).id
        show(hal, "item/$chair")
        rule.onAllNodes(hasContentDescription("Say the name", substring = true)).assertCountEquals(0)
        tap("Armchair") // the chips need neither
        assertEquals("Armchair", hal.item(chair).name)
        tapDesc("Edit the name")
        type("Armchair", "Blue armchair")
        assertEquals("Blue armchair", hal.item(chair).name)

        // An Organiser's own reason for declining is typed too.
        assertTrue(dee.suggestDecision(lamp, Disposition.BIN).ok)
        sync(asha, dee)
        show(asha, "item/$lamp")
        tap("Decline")
        rule.onAllNodes(hasContentDescription("Say why not")).assertCountEquals(0)
        tapDesc("Type why not")
        type("Why not?", "it was a present")
        tap("Decline")
        assertEquals("it was a present", asha.data.value.proposalsFor(lamp).single().reason)
    }

    @Test
    fun someoneWhoWasRemovedStillHasTheirNameOnWhatTheySuggested() {
        team()
        val p = dee.suggestDecision(sofa, Disposition.SELL, 60.0).valueOrNull()!!
        sync(asha, dee)
        assertTrue(asha.removePerson(dee.me!!.id).ok)
        show(asha, "item/$sofa")
        see("Dee: Sell for £ 60")
        show(asha, "items")
        see("Dee: Sell for £ 60")
        tap("Sofa")
        tap("Approve")
        assertEquals(ProposalState.APPROVED, asha.proposal(p.id).state)
    }

    // ---- pictures for review --------------------------------------------------------------------

    /**
     * The states the audit pictures cannot reach, because they depend on who is looking:
     * written to build/audit/roles for review.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1300dp-mdpi")
    fun picturesOfWhatEachRoleSees() = rolePictures("build/audit/roles", small = false)

    /** The same states on a small, cheap phone with the system font enlarged: nothing may crash or lose its buttons. */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h1100dp-xhdpi")
    fun whatEachRoleSeesFitsASmallPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        rolePictures("build/audit/roles-small", small = true)
    }

    private fun rolePictures(dir: String, small: Boolean) {
        team()
        val tv = asha.createItem("Television", oldLounge.id).id
        val chair = asha.createItem(asha.data.value.defaultItemName(oldLounge.id), oldLounge.id).id
        photograph(asha, sofa, lamp, table, tv, chair)
        asha.decide(table, Disposition.MOVE)
        asha.decide(tv, Disposition.MOVE)
        asha.planRoom(tv, newLiving.id)
        val box = asha.createContainer("L004", oldLounge.id)
        asha.putInto(lamp, box.id)
        sync(asha, dee)
        sync(asha, hal)
        fun shot(name: String) {
            Thread.sleep(150) // photos are decoded on another thread
            rule.waitForIdle()
            rule.onRoot().captureRoboImage("$dir/$name.png")
        }

        show(dee, "item/$sofa")
        tap("Sell")
        tap("£ 50")
        shot("item-decider-pricing")
        tap("Suggest selling for £ 50")
        shot("item-decider-waiting")
        show(dee, "plan")
        tap("Suggest all 1 as shown")
        shot("plan-decider")
        show(dee, "triage")
        shot("triage-decider")
        show(hal, "item/$chair")
        shot("item-helper")
        tap("Needs a decision")
        shot("item-helper-asked")
        show(hal, "triage")
        shot("triage-helper")

        sync(asha, dee)
        sync(asha, hal)
        show(asha, "items")
        shot("items-organiser")
        show(asha, "item/$sofa")
        shot("item-organiser-review")
        show(asha, "triage")
        shot("triage-organiser")
        show(asha, "plan")
        shot("plan-organiser")
        show(asha, "item/$lamp")
        shot("item-in-container")
        show(asha, "walk")
        tap("Lounge")
        shot("walk-unnamed")

        asha.approve(asha.data.value.proposalsFor(sofa).single().id)
        sync(asha, dee)
        show(dee, "disposal")
        tapDesc("Gone: Sofa")
        tap(if (small) "£ 50" else "Sold for £ 50") // a small screen says it shorter
        shot("disposal-decider-approved")
        show(dee, "item/$sofa")
        shot("item-decider-approved")
    }
}
