package app.housemove

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.sync.WifiSync
import app.housemove.ui.AimMarks
import app.housemove.ui.AppBackground
import app.housemove.ui.AppRoot
import app.housemove.ui.CameraControls
import app.housemove.ui.HouseMarker
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.HouseSpot
import app.housemove.ui.IsoHouse
import app.housemove.ui.IsoRoom
import app.housemove.ui.IsoRooms
import app.housemove.ui.LocalMoves
import app.housemove.ui.LocalSpeech
import app.housemove.ui.LocalSyncAddresses
import app.housemove.ui.RoomState
import app.housemove.ui.Speech
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of every screen for design review: once as a phone shows it on
 * opening ("fold") and once at full length ("tall"). Written to build/audit.
 * The move is mid-way through, with photo-like pictures, so screens look as
 * they would in real use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditShotsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    /** The sample move with what 2.1 adds (hidden details, a hand-over, storage, damage reports, a refused change). */
    private fun phones() = Sample21.enrich(AuditSampleData.phones { tmp.newFolder() })

    private fun seeded(): Store = phones().organiser

    /** The other moves on the sample phone (see AuditMoves), made from the first move shown. */
    private var shelf: Moves? = null

    /** The app as on a phone that can listen and is on a network, so microphones and codes are in the picture. */
    @Composable
    private fun App(store: Store, onNav: (NavHostController) -> Unit) {
        // A phone still starting out (the first-run pictures) has no other moves: it is shown on its own.
        val moves = remember(store) { if (!store.data.value.move.setupDone) null else shelf ?: AuditMoves.shelf(tmp.newFolder(), store).also { shelf = it } }
        CompositionLocalProvider(LocalSpeech provides Speech { _, _ -> }, LocalSyncAddresses provides { listOf("127.0.0.1") }, LocalMoves provides moves) {
            // Where the app opens is read once, as the real app does at start.
            val start = remember(store) { if (store.data.value.move.setupDone) "home" else null }
            AppRoot(store, startRoute = start, onNav = onNav)
        }
    }

    /** Picture name and route of every screen. One person's page is added: someone still to join, with their invite showing. */
    private fun routesFor(store: Store): List<Pair<String, String>> {
        val people = store.data.value.people
        val person = people.firstOrNull { it.joinedAt == 0L && !it.removed } ?: people.first()
        return AuditSampleData.ROUTES.map { it.replace('/', '_') to it } + ("person" to "person/${person.id}?invite=true")
    }

    private fun capture(kind: String, seededData: Boolean = true) {
        val store = if (seededData) seeded() else Store(tmp.newFolder("empty")).also { s -> s.setMove { it.copy(setupDone = true) } }
        var nav: NavHostController? = null
        rule.setContent { App(store, onNav = { nav = it }) }
        rule.waitForIdle()
        for ((name, route) in routesFor(store)) {
            rule.runOnUiThread { nav!!.navigate(route) }
            settle()
            rule.onRoot().captureRoboImage("build/audit/$kind/$name.png")
        }
    }

    /**
     * Photos are decoded on another thread, which the test clock knows nothing about:
     * give them a moment, then let the result reach the screen, so pictures are in the picture.
     */
    private fun settle() {
        rule.waitForIdle()
        Thread.sleep(150)
        rule.waitForIdle()
    }

    /**
     * What the route list does not reach: Home for other kinds of move, the same screens
     * on a Decider's and a Helper's phone, a move with one person, and the first-run screens
     * including a phone that has just joined with an invite.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1500dp-mdpi")
    fun variants() {
        val phones = phones()
        var shown by mutableStateOf(phones.organiser)
        var nav: NavHostController? = null
        rule.setContent { key(shown) { App(shown, onNav = { nav = it }) } }
        rule.waitForIdle()
        fun shot(name: String) {
            settle()
            rule.onRoot().captureRoboImage("build/audit/tall/$name.png")
        }
        fun go(route: String) = rule.runOnUiThread { nav!!.navigate(route) }
        fun use(store: Store) {
            rule.runOnUiThread { shown = store }
            rule.waitForIdle()
        }
        fun tap(text: String) {
            val node = rule.onAllNodes(hasText(text) and hasClickAction()).onFirst()
            try {
                node.performScrollTo()
            } catch (_: AssertionError) {
            }
            node.performClick()
            rule.waitForIdle()
        }

        // What a search finds.
        go("search")
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("kitchen")
        shot("search_results")
        // The typed way round the scanner.
        go("scan")
        tap("Type the code")
        shot("scan_typing")

        // The inbox while approving several at once, and while declining one.
        go("approvals")
        tap("Select all")
        shot("approvals_selected")
        tap("Clear")
        tap("Decline")
        shot("approvals_decline")
        // Someone who has joined, and someone removed.
        for (name in listOf("Priya", "Alex")) {
            go("person/" + phones.organiser.data.value.people.first { it.name == name }.id)
            shot("person_" + name.lowercase())
        }

        // Each role's view of the same move.
        for ((who, store) in listOf("decider" to phones.decider, "helper" to phones.helper)) {
            use(store)
            shot("home_$who")
            for (route in listOf("approvals", "people", "settings", "sync", "more", "item/sofa", "item/fridge", "disposal")) {
                go(route)
                shot(route.replace('/', '_') + "_" + who)
            }
        }

        // One person: nothing about roles.
        val alone = Store(tmp.newFolder("alone"))
        alone.setDevice { it.copy(name = "Sam", letter = "S") }
        alone.setMove { it.copy(name = "Maple Road move", moveDay = today() + 9, setupDone = true) }
        alone.createRoom(Home.ORIGIN, "Kitchen")
        alone.createRoom(Home.DEST, "Kitchen")
        use(alone)
        shot("home_alone")
        for (route in listOf("people", "settings")) {
            go(route)
            shot(route + "_alone")
        }

        // A new phone joins from the first-run screen with an invite and is told who it now is.
        val gran = phones.organiser.data.value.people.first { it.name == "Gran" }
        val host = WifiSync.host(phones.organiser, letNewPhonesIn = false) {}
        try {
            use(Store(tmp.newFolder("joining")))
            tap("Join theirs instead") // straight to the scanner
            tap("Type the code") // the typed way, which stands in for the camera here
            rule.onAllNodes(hasSetTextAction() and hasText("Or type the code")).onFirst()
                .performTextReplacement(host.inviteFor(gran.id, listOf("127.0.0.1")).valueOrNull()!!.encode())
            rule.waitForIdle()
            tap("OK")
            shot("join_ask")
            tap("Yes") // nothing is sent before Yes
            rule.waitUntil(20_000) { rule.onAllNodes(hasText("You are Gran", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            shot("setup_joined")
        } finally {
            host.stop()
        }

        // Other kinds of move, and the first-run screens.
        val store = phones.organiser
        use(store)
        rule.runOnUiThread { store.setMove { it.copy(kind = MoveKind.MOVERS) } }
        rule.waitForIdle(); shot("home_movers")
        rule.runOnUiThread { store.setMove { it.copy(kind = MoveKind.INVENTORY, off = partsOffFor(MoveKind.INVENTORY)) } }
        rule.waitForIdle(); shot("home_inventory")
        rule.runOnUiThread { store.setMove { it.copy(kind = MoveKind.SELF, off = emptyList(), setupDone = false) }; nav!!.navigate("setup") }
        rule.waitForIdle(); shot("setup")
        for (page in 2..3) {
            rule.onAllNodes(hasText("Next") and hasClickAction()).onFirst().performClick()
            rule.waitForIdle(); shot("setup_$page")
        }
        // The new home's rooms on a first phone whose old home has four: they come across in one tap.
        val fresh = Store(tmp.newFolder("fresh"))
        for (r in listOf("Kitchen", "Living room", "Main bedroom", "Bathroom")) fresh.createRoom(Home.ORIGIN, r)
        use(fresh)
        repeat(3) { rule.onAllNodes(hasText("Next") and hasClickAction()).onFirst().performClick(); rule.waitForIdle() }
        shot("setup_4")
    }

    /**
     * The two model drawings on their own: the house with 0 to 3 boxes, and the rooms
     * for 1 room up to more than fit. Written to build/audit/drawings.png.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h1260dp-mdpi")
    fun drawings() {
        val names = listOf("Kitchen", "Living room", "Main bedroom", "Study", "Bathroom", "Hall", "Loft", "Garage", "Bedroom 2", "Bedroom 3", "Utility", "Dining room", "Shed", "Porch", "Cellar")
        fun rooms(n: Int) = (0 until n).map { IsoRoom("r$it", names[it], RoomState.entries[(it * 3 + n) % 4], stacks = (it + n) % 4) }
        rule.setContent {
            HouseMoveTheme {
                AppBackground {
                    Column(Modifier.padding(8.dp)) {
                        Row {
                            for (n in 0..3) {
                                IsoHouse(Modifier.width(236.dp), boxes = n, markers = HouseSpot.entries.take(n * 2).map { HouseMarker(it, amber = it.ordinal % 2 == 1) })
                            }
                        }
                        for (counts in listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 9), listOf(10, 12, 15))) {
                            Row { counts.forEach { n -> IsoRooms(rooms(n), Modifier.width(314.dp), selectedId = "r${n / 2}") } }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/audit/drawings.png")
    }

    /**
     * Things that open on top of a screen: a confirmation, a dialog with a text field,
     * the date picker and a menu. Written to build/audit/dialogs. At 320dp wide, because
     * a dialog with a text field never settles at phone width in this runtime (see FlowsTest).
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h740dp-xhdpi")
    fun dialogs() {
        val store = seeded()
        var nav: NavHostController? = null
        rule.setContent { App(store, onNav = { nav = it }) }
        rule.waitForIdle()
        fun go(route: String) {
            rule.runOnUiThread { nav!!.navigate(route) }
            rule.waitForIdle()
        }
        fun tap(text: String, sub: Boolean = false) {
            val node = rule.onAllNodes(hasText(text, substring = sub) and hasClickAction()).onFirst()
            try {
                node.performScrollTo()
            } catch (_: AssertionError) {
            }
            node.performClick()
            rule.waitForIdle()
        }
        fun shot(name: String) = rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/audit/dialogs/$name.png")

        // What can be done with one of the moves on the phone.
        go("moves")
        rule.onAllNodes(hasContentDescription("Back up, archive or remove Gran's flat clear-out") and hasClickAction()).onFirst().performClick()
        rule.waitForIdle()
        shot("move_actions")
        tap("Close")

        go("tasks")
        rule.onAllNodes(hasContentDescription("Add a task") and hasClickAction()).onFirst().performClick()
        rule.waitForIdle()
        shot("task")
        tap("Cancel")

        go("settings")
        tap("Move day", sub = true)
        shot("date")
        tap("Cancel")
        tap("Start again")
        tap("Erase all data on this phone")
        shot("confirm")
        tap("Cancel")

        // A new-home room: its door width by a tap, the rest folded away.
        go("rooms")
        tap("New home")
        tap("Main bedroom")
        shot("room")
        tap("Done")

        go("boxes")
        tap("All rooms")
        shot("menu")
        rule.onAllNodes(hasText("All rooms") and hasClickAction()).onLast().performClick()
        rule.waitForIdle()

        // The scanner as a dialog (with no camera here it explains what it needs), and a photo opened large.
        go("sync")
        tap("Scan sync code")
        shot("scanner")
        rule.onAllNodes(hasContentDescription("Close") and hasClickAction()).onLast().performClick()
        rule.waitForIdle()
        go("item/wardrobe")
        rule.waitForIdle()
        Thread.sleep(150)
        rule.onAllNodes(hasContentDescription("Oak wardrobe") and hasClickAction()).onFirst().performClick()
        rule.waitForIdle()
        Thread.sleep(150)
        rule.waitForIdle()
        shot("photo")
    }

    /**
     * What sits on the live camera: the camera's own controls, and the scanner's marks.
     * There is no camera in these tests, so they are drawn over a still picture.
     * Written to build/audit/dialogs/camera.png and scanner_live.png.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun camera() {
        val store = seeded()
        val photo = store.photoFile(store.data.value.photosOf("wardrobe").first())
        val still = android.graphics.BitmapFactory.decodeFile(photo.path).asImageBitmap()
        var scanning by mutableStateOf(false)
        rule.setContent {
            HouseMoveTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Image(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (scanning) AimMarks(Modifier.fillMaxSize())
                    else CameraControls("Oak wardrobe", taken = 2, busy = false, torch = false, onClose = {}, onTorch = {}, onShoot = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/audit/dialogs/camera.png")
        scanning = true
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/audit/dialogs/scanner_live.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun fold() = capture("fold")

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h2300dp-mdpi")
    fun tall() = capture("tall")

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
    fun empty() = capture("empty", seededData = false)
}
