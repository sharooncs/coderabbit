package app.housemove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.pdf.HelperCard
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalSpeech
import app.housemove.ui.Speech
import app.housemove.ui.screens.physicalNext
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
 * Home as each person sees it (design 2.1, section 2): the same house, numbers and dock for everyone, then
 * an Organiser's approvals, people, phases and refused changes; a Decider's things to decide, suggestions and
 * costs; a Helper's next steps for the things, jobs, hand-overs and a way to ask. Also the banner when a role
 * changes, the Helper card, and what is physically next, worked out from where things are.
 * Pictures go to build/audit/homes/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RoleHomesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private var phone by mutableStateOf<Store?>(null)
    private var started = false
    private var nav: NavHostController? = null
    private val heard = ArrayDeque<String>()

    private fun phones() = Sample21.enrich(AuditSampleData.phones { tmp.newFolder() })

    private fun show(s: Store, route: String = "home") {
        if (!started) {
            started = true
            rule.setContent {
                val shown = phone
                if (shown != null) key(shown) {
                    CompositionLocalProvider(LocalSpeech provides Speech { _, onText -> heard.removeFirstOrNull()?.let(onText) }) {
                        AppRoot(shown, startRoute = "home", onNav = { nav = it })
                    }
                }
            }
        }
        rule.runOnUiThread { phone = s }
        settle()
        if (route != "home") {
            rule.runOnUiThread { nav!!.navigate(route) }
            settle()
        }
    }

    private fun settle() = rule.waitForIdle()

    private fun click(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
        }
        node.performClick()
        settle()
    }

    private fun tap(text: String, index: Int = 0) = click(rule.onAllNodes(hasText(text) and hasClickAction())[index])
    private fun tapDesc(description: String) = click(rule.onAllNodes(hasContentDescription(description) and hasClickAction()).onFirst())
    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)
    private fun canTap(text: String) = rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

    private fun shot(name: String) {
        Thread.sleep(150)
        settle()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/audit/homes/$name.png")
    }

    // ---- each role's Home --------------------------------------------------------------------

    @Test
    fun anOrganiserSeesWhatWaitsThePeopleThePhasesAndWhatWasRefused() {
        val p = phones()
        show(p.organiser)
        shot("organiser")
        see("Maple Road move")
        see("days to go") // the numbers stay as they are
        see("waiting") // approvals waiting, between the house and the dock
        see("Not accepted at a sync")
        see("Tom's phone tried to mark Old sofa sold; Tom is a Helper.")
        see("People")
        see("still to join")
        see("You are here") // the six phases
        see("Sync with the other phones")
        dontSee("Next for the things")
        dontSee("Your suggestions")
        // Dismissed, it goes, here and from the data.
        while (canTap("Dismiss")) tap("Dismiss")
        dontSee("Tom's phone tried")
        dontSee("Not accepted at a sync")
        assertTrue(p.organiser.data.value.refusedChanges().isEmpty())
    }

    @Test
    fun aDeciderSeesWhatWaitsForADecisionTheirSuggestionsAndCosts() {
        val p = phones()
        show(p.decider)
        shot("decider")
        see("Maple Road move")
        see("Priya · Decider")
        // Joining is news once: what the role does, until it is read.
        see("You joined as a Decider")
        see("Suggests what to keep")
        see("Waiting for a decision")
        see("Hall mirror") // one of the things nobody has decided
        see("Your suggestions")
        see("Approved")
        see("Declined")
        see("“Too worn to give away”")
        see("Suggest a cost")
        see("Sync with the other phones")
        dontSee("Not accepted at a sync") // refused changes are an Organiser's
        dontSee("Tom's phone tried")
        dontSee("Next for the things")
        tap("Got it")
        dontSee("You joined as a Decider")
        tap("Suggest", 0)
        assertEquals("triage", nav!!.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun aHelperSeesWhatIsPhysicallyNextTheirHandOversAndCanAsk() {
        val p = phones()
        val tom = p.helper
        show(tom)
        shot("helper")
        see("Tom · Helper")
        see("days to go") // the same numbers and house as everyone
        see("Next for the things")
        see("Unload") // the fridge and a box of bedding are on the van
        see("Your hand-overs")
        see("Old sofa")
        see("Not paid · collect £ 80") // the amount, for the Helper who hands it over
        see("Bookcase")
        see("Donation")
        see("Ask a question")
        see("Sync with the other phones")
        dontSee("Not accepted at a sync")
        dontSee("Waiting for a decision")
        dontSee(Sample21.SERIAL)

        // Handed over, from Home.
        tap("Handed over", 1)
        assertTrue(tom.data.value.items.any { it.handedOver })

        // Ask a question about a thing, with a word said first: no keyboard.
        val before = tom.data.value.liveProposals.size
        tap("Ask a question")
        heard += "Does this go to the tip"
        tapDesc("Say what you want to ask")
        see("Does this go to the tip")
        click(rule.onAllNodes(hasText("Desk") and hasClickAction()).onLast())
        assertEquals(before + 1, tom.data.value.liveProposals.size)
        val q = tom.data.value.liveProposals.first { it.targetId == "desk" }
        assertEquals(ProposalKind.QUESTION, q.kind)
        assertEquals("Does this go to the tip", q.note)
    }

    @Test
    fun aHelperIsToldWhenJobsAreGivenToThemToday() {
        val p = phones()
        val tom = p.helper
        val job = Task(id = newId(), title = "Defrost the freezer", dueDay = today(), assignedTo = tom.me!!.id)
        assertTrue(p.organiser.saveTask(job).ok)
        Sample21.sync(p.organiser, tom)
        show(tom)
        see("Your jobs today")
        see("Defrost the freezer")
        tap("Defrost the freezer")
        assertTrue(tom.data.value.task(job.id)!!.done)
        dontSee("Your jobs today")
    }

    // ---- roles that change -----------------------------------------------------------------------

    @Test
    fun aHelperMadeADeciderIsToldAndCostsArriveAtTheNextSyncWithAnOrganiser() {
        val p = phones()
        val sam = p.organiser
        val tom = p.helper
        assertTrue(tom.data.value.expenses.isEmpty())
        assertTrue(sam.setRole(tom.me!!.id, Role.DECIDER).ok)
        // The news reaches Tom by way of a Helper's phone, which has no costs to give.
        tom.mergeIn(sam.data.value.sentTo(Role.HELPER, tom.me!!.id))
        assertEquals(Role.DECIDER, tom.myRole)
        show(tom)
        shot("promoted")
        see("You are now a Decider")
        see("Costs and decisions arrive at your next sync with an Organiser or Decider.")
        tap("Got it")
        dontSee("You are now a Decider")
        // Until this phone holds them, it still says so.
        see("Costs and decisions arrive at your next sync")
        rule.runOnUiThread { Sample21.fullSync(sam, tom) }
        settle()
        assertTrue(tom.data.value.expenses.isNotEmpty())
        dontSee("Costs and decisions arrive")
        see("Waiting for a decision") // a Decider's Home now
    }

    @Test
    fun nobodyAloneIsToldAboutRoles() {
        val alone = AuditSampleData.alone(tmp.newFolder())
        show(alone)
        dontSee("You joined")
        dontSee("You are now")
        dontSee("Costs and decisions arrive")
        dontSee("Sync with the other phones")
        dontSee("Not accepted at a sync")
        see("You are here")
    }

    // ---- what is physically next ------------------------------------------------------------

    @Test
    fun whatIsPhysicallyNextFollowsWhereThingsAreNotTheDate() {
        val s = Store(tmp.newFolder())
        s.setMove { it.copy(name = "M", setupDone = true, moveDay = today() + 300) }
        val dest = s.createRoom(Home.DEST, "Kitchen")
        val old = s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = dest.id).also { s.put(it) }
        val box = s.createBox("L001", old.id)
        fun next() = s.data.value.physicalNext(old.id).map { it.route }
        assertEquals("pack", next().first()) // open, far from the date
        s.updateBoxes(listOf(box.id)) { it.copy(sealed = true) }
        s.put(s.data.value.room(old.id)!!.copy(packed = true))
        assertEquals("load", next().first()) // everything packed: loading, though the day is months away
        val trip = s.addTrip().valueOrNull()!!
        assertTrue(s.loadOnto(trip.id, listOf(box.id)).ok)
        assertEquals(listOf("records/ORIGIN", "sweep", "unload", "records/DEST"), next())
        assertTrue(s.unload(listOf(box.id)).ok)
        assertTrue("unpack" in next())
        // To the storage unit and back: what is in storage is brought back, never unpacked.
        assertTrue(s.putBack(listOf(box.id)).ok)
        val storage = s.addTrip(Place.OLD_HOME, Place.STORAGE).valueOrNull()!!
        assertTrue(s.putBack(listOf(box.id)).ok)
        assertTrue(s.loadOnto(storage.id, listOf(box.id)).ok)
        assertTrue(s.unload(listOf(box.id)).ok)
        assertEquals(Stage.STORED, s.data.value.box(box.id)!!.stage)
        val steps = s.data.value.physicalNext(old.id)
        assertTrue(steps.any { it.bringBack })
        assertFalse(steps.any { it.route == "unpack" })
    }

    // ---- the Helper card ----------------------------------------------------------------------

    @Test
    fun theHelperCardHasFiveStepsTheDoorColoursAndWhatToWriteOnABox() {
        val s = AuditSampleData.alone(tmp.newFolder())
        val d = s.data.value
        val steps = HelperCard.steps(d)
        assertEquals(5, steps.size)
        assertEquals("KIT", HelperCard.exampleCode(d))
        assertTrue(steps.any { it.how.contains("Write the room's code on two sides: KIT") })
        val signs = HelperCard.doorSigns(d)
        assertEquals(d.roomsOf(Home.DEST).size, signs.size)
        for (paper in HelperCard.Paper.entries) {
            val bmp = Bitmap.createBitmap(paper.w * 2, paper.h * 2, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val c = Canvas(bmp)
            c.scale(2f, 2f)
            HelperCard.drawPage(c, paper.w, paper.h, d)
            File("build/audit/homes").mkdirs()
            File("build/audit/homes/helper-card-${paper.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Each door colour is on both halves of the page.
            val colours = signs.map { it.first.argb.toInt() and 0xFFFFFF }.toSet()
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            for (half in 0..1) {
                val from = half * px.size / 2
                val found = (from until from + px.size / 2).map { px[it] and 0xFFFFFF }.toSet()
                assertTrue("${paper.name} half $half has every door colour", colours.all { it in found })
            }
        }
    }
}
