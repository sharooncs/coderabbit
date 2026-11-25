package app.housemove

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import app.housemove.data.Home
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.sync.MailboxSync
import app.housemove.sync.WifiSync
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalMailbox
import app.housemove.ui.LocalSyncAddresses
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sync and backup's "Internet mailbox" (design/encryption.md, "Mailbox as built"), on simulated Android: what a move with
 * others in it says before it has one, to an Organiser and to anyone else; setting it up with the laptop's code (typed in
 * the scanner's field, as a paste would be); the switch for this phone, kept in its settings; and the engine's words, from
 * "Up to date over the internet · 2 min ago" to what went wrong. The engine itself is tested in shared (MailboxCommonTest,
 * MailboxGoServerTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MailboxScreenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private val local = listOf("127.0.0.1")
    private var shown by mutableStateOf<Store?>(null)
    private var engine by mutableStateOf<MailboxSync?>(null)
    private var nav: NavHostController? = null
    private var started = false
    private val closing = ArrayList<() -> Unit>()

    private val token = "0f1e2d3c4b5a69788796a5b4c3d2e1f00123456789abcdef0123456789abcdef"
    private val code = "HMBOX:1:$token:https://example-laptop.example-tailnet.ts.net"

    @After
    fun cleanUp() = closing.forEach { it() }

    private fun newStore(name: String): Store = Store(tmp.newFolder()).also { s -> s.setDevice { it.copy(name = name) } }

    private fun asha(): Store = newStore("Asha").also { s ->
        s.setMove { it.copy(name = "Maple Road", setupDone = true) }
        s.createRoom(Home.ORIGIN, "Lounge")
    }

    private fun joins(asha: Store, name: String, role: Role): Store {
        val p = asha.addPerson(name, role).valueOrNull()!!
        val host = WifiSync.host(asha, letNewPhonesIn = false) {}.also { h -> closing += { h.stop() } }
        val phone = newStore(name)
        assertTrue(WifiSync.join(phone, host.inviteFor(p.id, local).valueOrNull()!!).ok)
        return phone
    }

    private fun show(s: Store, box: MailboxSync? = null) {
        rule.runOnUiThread {
            engine = box
            shown = s
        }
        if (!started) {
            started = true
            rule.setContent {
                CompositionLocalProvider(LocalSyncAddresses provides { local }, LocalMailbox provides engine) {
                    shown?.let { phone -> key(phone) { AppRoot(phone, onNav = { nav = it }) } }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { nav!!.navigate("sync") }
        rule.waitForIdle()
    }

    private fun see(text: String) {
        val nodes = rule.onAllNodes(hasText(text, substring = true))
        if (nodes.fetchSemanticsNodes().isEmpty()) {
            val onScreen = rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .flatMap { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }.joinToString(" | ")
            throw AssertionError("Not on the screen: $text. On the screen: $onScreen")
        }
    }

    private fun dontSee(text: String) = rule.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)

    private fun tap(text: String) {
        val node = rule.onAllNodes(hasText(text) and hasClickAction()).onFirst()
        try {
            node.performScrollTo()
        } catch (_: AssertionError) {
        }
        node.performClick()
        rule.waitForIdle()
    }

    @Test
    fun anOrganiserSetsItUpWithTheLaptopsCode() {
        val asha = asha()
        joins(asha, "Priya", Role.DECIDER)
        show(asha)
        see("Internet mailbox")
        see("No internet mailbox yet")
        see("Scan the code the mailbox laptop showed, so phones away from this Wi-Fi keep up to date.")
        see("Internet mailbox on this phone")
        // The scanner's field takes the code as a paste would.
        tap("Scan the mailbox's code")
        see("Scan the code the mailbox laptop shows")
        tap("Type the code")
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(code)
        rule.waitForIdle()
        tap("OK")
        assertEquals("https://example-laptop.example-tailnet.ts.net", asha.data.value.mailbox.url)
        rule.waitForIdle()
        dontSee("No internet mailbox yet")
        see("Scan a new code")
    }

    @Test
    fun anyoneElseIsToldAnOrganiserSetsItUp() {
        val asha = asha()
        val priya = joins(asha, "Priya", Role.DECIDER)
        show(priya)
        see("No internet mailbox yet")
        see("An Organiser can set one up, so phones away from this Wi-Fi keep up to date.")
        dontSee("Scan the mailbox's code")
    }

    @Test
    fun theSwitchIsKeptAndTheEnginesWordsAreShown() {
        val asha = asha()
        joins(asha, "Priya", Role.DECIDER)
        assertTrue(MailboxSync.setUp(asha, code).first)
        val box = MailboxSync({ asha }, { asha.phone.settings.value.mailbox })
        closing += { box.close() }
        box.showForTests(MailboxSync.Status(MailboxSync.State.UP_TO_DATE, System.currentTimeMillis() - 2 * 60_000L - 5_000L))
        show(asha, box)
        see("Up to date over the internet · 2 min ago")
        assertTrue(asha.phone.settings.value.mailbox)
        tap("Internet mailbox on this phone")
        assertFalse(asha.phone.settings.value.mailbox)
        see("Internet mailbox is off on this phone")
        tap("Internet mailbox on this phone")
        assertTrue(asha.phone.settings.value.mailbox)
        rule.runOnUiThread { box.showForTests(MailboxSync.Status(MailboxSync.State.PROBLEM, problem = MailboxSync.Problem.UNREACHABLE, tried = System.currentTimeMillis())) }
        rule.waitForIdle()
        see("Cannot reach the internet mailbox")
        see("Check this phone is online. If it keeps happening, the laptop with the mailbox may be off or asleep.")
        rule.runOnUiThread { box.showForTests(MailboxSync.Status(MailboxSync.State.PROBLEM, problem = MailboxSync.Problem.FULL)) }
        rule.waitForIdle()
        see("The internet mailbox is full")
    }

    @Test
    fun aMoveOnItsOwnHasNoMailbox() {
        show(asha())
        dontSee("Internet mailbox")
    }
}
