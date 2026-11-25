package app.housemove

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import app.housemove.data.Home
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalDiscovery
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalAutoSync
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
 * Sync and backup's line and switch for syncing by itself (design/auto-sync.md), on simulated Android: what a move with
 * others in it says, the switch kept in the phone's settings, and "Up to date with Priya's phone" after two phones
 * found each other (through the tests' stand-in for Bonjour) and synced over real sockets with no code. What only real
 * phones can show (NsdManager across a router, Wi-Fi that keeps devices apart) is on the owner's list in design/ios-plan.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AutoSyncScreenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private val local = listOf("127.0.0.1")
    private var shown by mutableStateOf<Store?>(null)
    private var engine by mutableStateOf<AutoSync?>(null)
    private var nav: NavHostController? = null
    private var started = false
    private val closing = ArrayList<() -> Unit>()

    @After
    fun cleanUp() = closing.forEach { it() }

    private fun newStore(name: String): Store = Store(tmp.newFolder()).also { s -> s.setDevice { it.copy(name = name) } }

    private fun asha(): Store = newStore("Asha").also { s ->
        s.setMove { it.copy(name = "Maple Road", setupDone = true) }
        s.createRoom(Home.ORIGIN, "Lounge")
    }

    /** Priya's phone joins Asha's move with an invite, as a Decider: a meeting, from which the two may sync by themselves. */
    private fun priyaJoins(asha: Store): Store {
        val p = asha.addPerson("Priya", Role.DECIDER).valueOrNull()!!
        val host = WifiSync.host(asha, letNewPhonesIn = false) {}.also { h -> closing += { h.stop() } }
        val priya = newStore("Priya")
        assertTrue(WifiSync.join(priya, host.inviteFor(p.id, local).valueOrNull()!!).ok)
        return priya
    }

    private fun show(s: Store, auto: AutoSync? = null) {
        rule.runOnUiThread {
            engine = auto
            shown = s
        }
        if (!started) {
            started = true
            rule.setContent {
                CompositionLocalProvider(LocalSyncAddresses provides { local }, LocalAutoSync provides engine) {
                    shown?.let { phone -> key(phone) { AppRoot(phone, onNav = { nav = it }) } }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { nav!!.navigate("sync") }
        rule.waitForIdle()
    }

    private fun see(text: String) = rule.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()
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

    private fun waitFor(text: String) = try {
        rule.waitUntil(30_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        val onScreen = rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }.joinToString(" | ")
        throw AssertionError("Timed out waiting to see: $text. On the screen: $onScreen", e)
    }

    @Test
    fun aMoveWithOthersSaysWhatItIsDoingAndTheSwitchIsKept() {
        val asha = asha()
        priyaJoins(asha)
        // Synced with Priya's phone three hours ago, as the screens note a sync.
        asha.setDevice { it.copy(lastSyncAt = System.currentTimeMillis() - 3 * 3_600_000L - 60_000L, lastSyncWith = "Priya") }
        show(asha)
        // Away from the other phones: still looking, it says when it last synced and with whom, and the switch is on.
        see("Looking for the family's phones on this Wi-Fi")
        see("Last synced with Priya's phone 3 hours ago.")
        see("Sync by itself on Wi-Fi")
        see("Show sync code")
        assertTrue(asha.phone.settings.value.autoSync)
        tap("Sync by itself on Wi-Fi")
        assertFalse(asha.phone.settings.value.autoSync)
        see("Sync by itself is off")
        see("Phones sync when one shows its code and the other scans it.")
        tap("Sync by itself on Wi-Fi")
        assertTrue(asha.phone.settings.value.autoSync)
        dontSee("Sync by itself is off")
    }

    @Test
    fun aMoveOnItsOwnHasNothingToSyncWith() {
        show(asha())
        dontSee("Sync by itself on Wi-Fi")
        see("Not synced yet")
    }

    @Test
    fun twoPhonesOnTheSameWifiSyncByThemselves() {
        val lan = LocalLan()
        val asha = asha()
        val priya = priyaJoins(asha)
        val engines = listOf(asha, priya).map { s -> AutoSync(LocalDiscovery(lan), { s }, { s.phone.settings.value.autoSync }, loopbackOnly = true) }
        closing += { engines.forEach { it.close() } }
        show(asha, engines[0])
        engines.forEach { it.resume() }
        // The join was a meeting: they swap keys and sync, with no code shown or scanned.
        priya.createItem("Lamp", null)
        rule.waitUntil(30_000) { asha.data.value.liveItems.any { it.name == "Lamp" } }
        waitFor("Up to date with Priya's phone · ")
        assertEquals("Priya", asha.device.value.lastSyncWith)
    }
}
