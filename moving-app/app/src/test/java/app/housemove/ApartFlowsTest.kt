package app.housemove

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalSyncAddresses
import app.housemove.ui.shareSheet
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Working apart and the safety nets, through the real screens of the app as a phone opens it: Send update and
 * Open update on the Sync screen and on a move's card, a phone taken out of its move sending its last work and
 * leaving, "Done, thanks" on a Helper's page, and what the Organiser's Home asks of a move with one Organiser.
 * The share sheet and the file picker are stand-ins; phones meet over localhost.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ApartFlowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private val local = listOf("127.0.0.1")
    private var count = 0
    private var shown by mutableStateOf<Moves?>(null)
    private var started = false
    private var nav: NavHostController? = null
    private val hosts = ArrayList<WifiSync.Host>()
    private val shared = LinkedBlockingQueue<Pair<File, String>>()

    /** The file the stand-in for the phone's file picker hands back when one is opened. */
    private var opened: File? = null

    @After
    fun hangUp() {
        hosts.forEach { it.stop() }
        shareSheet = { context, file, type -> app.housemove.pdf.Share.send(context, file, type) }
    }

    private fun phone(device: String, yourName: String): Moves {
        val root = tmp.newFolder("phone" + count++)
        return Moves(File(root, "files"), File(root, "no_backup"), { device }).also { m -> m.phone.updateSettings { it.copy(name = yourName) } }
    }

    private fun move(m: Moves, name: String, yourName: String): Store {
        val s = m.create(name, yourName = yourName)
        s.setMove { it.copy(moveDay = today() + 30, setupDone = true) }
        val dest = s.createRoom(Home.DEST, "Kitchen")
        s.put(s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = dest.id))
        return s
    }

    private fun sync(host: Store, joiner: Store) {
        val q = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(host) { q.add(it) }.also { hosts += it }
        val r = WifiSync.join(joiner, h.invite(local))
        q.poll(15, TimeUnit.SECONDS)
        assertTrue(r.error, r.ok)
    }

    /** [joiner] joins [host]'s move as a new [role] through an invite. */
    private fun joined(joiner: Moves, host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val h = WifiSync.host(host) {}.also { hosts += it }
        val r = joiner.join(joiner.readInvite(h.inviteFor(p.id, local).valueOrNull()!!.encode())!!)
        assertTrue(r.error, r.ok)
        return joiner.current.value!!
    }

    private val files = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val result: Any? = when (contract) {
                    is ActivityResultContracts.OpenDocument -> opened?.let { Uri.fromFile(it) }
                    else -> null
                }
                dispatchResult(requestCode, result)
            }
        }
    }

    private fun show(m: Moves) {
        shareSheet = { _, file, type -> shared.add(file to type) }
        if (!started) {
            started = true
            rule.setContent {
                CompositionLocalProvider(LocalSyncAddresses provides { local }, LocalActivityResultRegistryOwner provides files) {
                    shown?.let { phone -> key(phone) { AppRoot(phone, onNav = { nav = it }) } }
                }
            }
        }
        rule.runOnUiThread { shown = m }
        rule.waitForIdle()
    }

    private fun go(route: String) {
        rule.runOnUiThread { nav!!.navigate(route) }
        rule.waitForIdle()
    }

    private fun click(node: SemanticsNodeInteraction) {
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
        }
        node.performClick()
        rule.waitForIdle()
    }

    private fun tap(text: String) = click(rule.onAllNodes(hasText(text) and hasClickAction()).onFirst())
    private fun tapDesc(description: String) = click(rule.onAllNodes(hasContentDescription(description, substring = true) and hasClickAction()).onFirst())
    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)

    private fun waitFor(text: String) = try {
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        val onScreen = rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }.joinToString(" | ")
        throw AssertionError("Timed out waiting to see: $text. On the screen: $onScreen", e)
    }

    /** What went to the share sheet: made away from the screen, handed over on it. */
    private fun sharedFile(): Pair<File, String> {
        val until = System.currentTimeMillis() + 15_000
        while (shared.isEmpty() && System.currentTimeMillis() < until) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        return shared.poll() ?: throw AssertionError("nothing went to the share sheet")
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/screens/apart-$name.png")

    @Test
    fun anUpdateSentFromSyncOpensOnTheOrganisersCard() {
        val ashaPhone = phone("Asha's phone", "Asha")
        val asha = move(ashaPhone, "Maple Road", "Asha")
        val deePhone = phone("Dee's phone", "Dee")
        val dee = joined(deePhone, asha, "Dee", Role.DECIDER)
        val box = dee.createBox("D1", null)

        show(deePhone)
        go("sync")
        see("Working apart")
        tap("Send update")
        val (file, type) = sharedFile()
        assertEquals(Update.MIME, type)
        assertTrue(file.name.endsWith(".housemove"))
        waitFor("Update ready to send")
        see("Asha's phone")
        shot("sent")

        opened = file
        show(ashaPhone)
        go("moves")
        tapDesc("Back up, archive or remove Maple Road")
        tap("Open update")
        waitFor("Update taken in")
        assertNotNull(asha.data.value.box(box.id))
        shot("opened")
        // The same file again changes nothing.
        tapDesc("Back up, archive or remove Maple Road")
        tap("Open update")
        waitFor("Nothing new")
    }

    @Test
    fun aPhoneTakenOutOfItsMoveSendsItsLastWorkToAnOrganiserAndLeaves() {
        val ashaPhone = phone("Asha's phone", "Asha")
        val asha = move(ashaPhone, "Maple Road", "Asha")
        val halPhone = phone("Hal's phone", "Hal")
        val hal = joined(halPhone, asha, "Hal", Role.HELPER)
        hal.createBox("H1", null)
        assertTrue(asha.removePerson(hal.me!!.id).ok)
        val q = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(asha) { q.add(it) }.also { hosts += it }
        assertEquals(WifiSync.REMOVED_SELF, WifiSync.join(hal, h.invite(local)).error)

        show(halPhone)
        see("You are no longer in Maple Road")
        shot("removed")
        tap("Send my work")
        val (file, _) = sharedFile()
        rule.waitUntil(15_000) { halPhone.cards.value.isEmpty() }
        assertTrue(ashaPhone.phone.id in Update.peek(file.inputStream()).to)
        // On the Organiser's phone the update is from someone removed: it offers to take in their work alone.
        val r = ashaPhone.openUpdate({ file.inputStream() })
        assertEquals(hal.me!!.id, r.fromRemoved)
        assertTrue(r.mayTakeIn)
    }

    @Test
    fun doneThanksOnAHelpersPage() {
        val ashaPhone = phone("Asha's phone", "Asha")
        val asha = move(ashaPhone, "Maple Road", "Asha")
        val samPhone = phone("Sam's phone", "Sam")
        val sam = joined(samPhone, asha, "Sam", Role.HELPER)
        show(ashaPhone)
        go("person/${sam.me!!.id}")
        tap("Done, thanks Sam")
        see("Thanked.")
        shot("thanked")
        assertNotNull(asha.data.value.roles().thankedAt(sam.me!!.id))
        // At the end of Sam's next sync his phone lets go of the move, and Asha's removes him.
        sync(asha, sam)
        assertTrue(samPhone.cards.value.isEmpty())
        assertTrue(asha.data.value.person(sam.me!!.id)!!.removed)
    }

    @Test
    fun theOrganisersHomeAsksForADailyBackupAndASecondOrganiserBeforeMoveDay() {
        val ashaPhone = phone("Asha's phone", "Asha")
        val asha = move(ashaPhone, "Maple Road", "Asha")
        asha.setMove { it.copy(moveDay = today() + 3) }
        show(ashaPhone)
        waitFor("Keep a small backup each day")
        see("Add a second Organiser?")
        shot("nudges")
        tap("Not now")
        dontSee("Add a second Organiser?")
        assertTrue(asha.device.value.secondOrganiserDeclined)
        see("Choose a folder")
    }

    @Test
    fun askForDecisionsListsWhatIsUndecided() {
        val ashaPhone = phone("Asha's phone", "Asha")
        val asha = move(ashaPhone, "Maple Road", "Asha")
        val kitchen = asha.data.value.roomsOf(Home.ORIGIN).first()
        asha.createItem("Sofa", kitchen.id)
        asha.createItem("Lamp", kitchen.id)
        val dee = joined(phone("Dee's phone", "Dee"), asha, "Dee", Role.DECIDER)
        assertNotNull(dee)
        show(ashaPhone)
        go("sync")
        tap("Ask for decisions")
        see("2 things to ask about")
        see("Sofa")
        see("Tap their answers into Triage")
        shot("ask")
    }
}
