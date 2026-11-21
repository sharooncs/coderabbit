package app.housemove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
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
import app.housemove.ui.AppBackground
import app.housemove.ui.AppRoot
import app.housemove.ui.HouseMarker
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.HouseSpot
import app.housemove.ui.IsoHouse
import app.housemove.ui.IsoRoom
import app.housemove.ui.IsoRooms
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

    /** The same move on three phones: the Organiser's (Sam), a Decider's (Priya) and a Helper's (Tom). */
    private class Phones(val organiser: Store, val decider: Store, val helper: Store)

    /** Someone added to [host]'s move whose phone has joined. Returns that phone. */
    private fun joined(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = Store(tmp.newFolder())
        phone.joinAs(p.id, host.data.value.visibleTo(role))
        host.mergeIn(phone.data.value)
        return phone
    }

    private fun seeded(): Store = phones().organiser

    private fun phones(): Phones {
        val s = Store(tmp.newFolder("audit"))
        var n = 0
        fun photo(owner: String, kind: PhotoKind) {
            val f = s.newPhotoFile()
            picture(f, n++)
            s.addPhoto(owner, kind, f)
        }
        s.setMove { it.copy(name = "Maple Road move", moveDay = today() + 9, setupDone = true, currency = "£") }
        s.setDevice { it.copy(name = "Sam", letter = "S") }
        val dKitchen = s.createRoom(Home.DEST, "Kitchen").copy(doorWidthCm = 76.0, floor = "Ground floor").also { s.put(it) }
        val dBed = s.createRoom(Home.DEST, "Main bedroom").copy(doorWidthCm = 70.0, floor = "Upstairs, first left").also { s.put(it) }
        val dLiving = s.createRoom(Home.DEST, "Living room").copy(doorWidthCm = 80.0).also { s.put(it) }
        val dOffice = s.createRoom(Home.DEST, "Office")
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = dKitchen.id).also { s.put(it) }
        val bed = s.createRoom(Home.ORIGIN, "Main bedroom").copy(mapsTo = dBed.id, packed = true).also { s.put(it) }
        val living = s.createRoom(Home.ORIGIN, "Living room").copy(mapsTo = dLiving.id).also { s.put(it) }
        val loft = s.createRoom(Home.ORIGIN, "Loft")
        s.setDevice { it.copy(currentRoomId = kitchen.id) }
        val trip = Trip(id = newId(), name = "Trip 1", sort = 1).also { s.put(it) }
        s.setDevice { it.copy(currentTripId = trip.id) }

        s.put(Item(id = "wardrobe", name = "Oak wardrobe", originRoomId = bed.id, destRoomId = dBed.id, disposition = Disposition.MOVE,
            widthCm = 120.0, depthCm = 74.0, heightCm = 205.0, value = 450.0, twoPerson = true, label = "L010", prepEmptied = true, prepPhotos = true))
        s.put(Item(id = "fridge", name = "Fridge-freezer", originRoomId = kitchen.id, destRoomId = dKitchen.id, disposition = Disposition.MOVE,
            widthCm = 60.0, depthCm = 65.0, heightCm = 180.0, stage = Stage.LOADED, tripId = trip.id, make = "Bosch", model = "KGN36",
            serial = "FD9907-000123", keepUpright = true, accessories = "Shelves, ice tray"))
        s.put(Item(id = "tv", name = "Living room TV", originRoomId = living.id, destRoomId = dLiving.id, disposition = Disposition.MOVE, keepUpright = true, fragile = true))
        s.put(Item(id = "bed", name = "King bed", originRoomId = bed.id, destRoomId = dBed.id, disposition = Disposition.MOVE,
            needsDisassembly = true, firstNight = true, stage = Stage.DELIVERED, damaged = true, damageNote = "Scratch on headboard"))
        s.put(Item(id = "sofa", name = "Old sofa", originRoomId = living.id, disposition = Disposition.SELL, price = 80.0, listed = true, deadlineDay = today() + 3))
        s.put(Item(id = "books", name = "Bookcase", originRoomId = living.id, disposition = Disposition.DONATE))
        s.put(Item(id = "lamp", name = "", originRoomId = loft.id))
        s.put(Item(id = "desk", name = "Desk", originRoomId = bed.id, disposition = Disposition.MOVE))
        s.put(Item(id = "table", name = "Dining table", originRoomId = kitchen.id, destRoomId = dKitchen.id, disposition = Disposition.MOVE, needsDisassembly = true))
        s.put(Item(id = "bike", name = "Exercise bike", originRoomId = loft.id, disposition = Disposition.BIN))
        for (id in listOf("wardrobe", "fridge", "tv", "bed", "sofa", "books", "lamp", "table")) photo(id, PhotoKind.GENERAL)
        photo("wardrobe", PhotoKind.CONDITION); photo("bed", PhotoKind.DAMAGE)

        s.put(Box(id = "box1", label = "L001", originRoomId = kitchen.id, destRoomId = dKitchen.id, contents = "Kettle, mugs, tea, cutlery",
            priority = Priority.FIRST_NIGHT, sealed = true, fragile = true))
        s.put(Box(id = "box2", label = "L002", originRoomId = kitchen.id, destRoomId = dKitchen.id, contents = "Pans and baking trays", heavy = true))
        s.put(Box(id = "box3", label = "S1", originRoomId = bed.id, destRoomId = dBed.id, contents = "Bedding", sealed = true,
            stage = Stage.LOADED, tripId = trip.id, priority = Priority.FIRST_NIGHT))
        s.put(Box(id = "box4", label = "L004", originRoomId = bed.id, destRoomId = dBed.id, contents = "Winter clothes", sealed = true, stage = Stage.DELIVERED))
        s.put(Box(id = "box5", label = "L005", originRoomId = living.id, destRoomId = dLiving.id, contents = "Books", sealed = true, stage = Stage.DONE))
        s.put(Box(id = "box6", label = "L006", originRoomId = loft.id, contents = "Passports, documents", travelsWithMe = true, sealed = true))
        s.put(Box(id = "box7", label = "L007", originRoomId = living.id, destRoomId = dOffice.id, contents = "Cables, router, chargers", thisWayUp = true))
        for (id in listOf("box1", "box2", "box4", "box5", "box7")) photo(id, PhotoKind.CONTENTS)
        s.scanInto("box5", "9780140328721", title = "Fantastic Mr. Fox", author = "Roald Dahl")
        s.scanInto("box5", "", title = "Photo albums")

        seedTasks().forEachIndexed { i, t -> s.put(if (i < 14) t.copy(done = true) else t) }
        s.put(Expense(id = "e1", title = "Van hire", amount = 180.0, category = "Van", day = today(), planned = true))
        s.put(Expense(id = "e2", title = "Boxes and tape", amount = 62.5, category = "Supplies", day = today() - 4))
        s.put(Supply(id = "s1", name = "Medium boxes", need = 30, have = 12))
        s.put(Supply(id = "s2", name = "Packing tape rolls", need = 4, have = 4))
        s.put(Reading(id = "r1", home = Home.ORIGIN, kind = "Electricity", value = "48213", day = today()))

        // People: Sam organises, Priya may suggest, Tom helps, Gran has not joined yet, Alex was removed.
        s.put(Item(id = "mirror", name = "Hall mirror", originRoomId = living.id))
        photo("mirror", PhotoKind.GENERAL)
        val priya = joined(s, "Priya", Role.DECIDER)
        val tom = joined(s, "Tom", Role.HELPER)
        s.addPerson("Gran", Role.HELPER)
        s.addPerson("Alex", Role.HELPER).valueOrNull()?.let { s.removePerson(it.id) }
        // What they suggested and asked: two already decided, three still waiting.
        val toOffice = priya.suggestRoom("tv", dOffice.id).valueOrNull()!!
        val donateBike = priya.suggestDecision("bike", Disposition.DONATE).valueOrNull()!!
        priya.suggestDecision("table", Disposition.SELL, 40.0, note = "Too big for the new kitchen")
        priya.suggestCost(Expense(id = "e3", title = "20 more boxes", amount = 35.0, category = "Supplies", day = today()))
        tom.ask("mirror", note = "Is this coming with us?")
        for (phone in listOf(priya, tom)) s.mergeIn(phone.data.value)
        s.approve(toOffice.id)
        s.decline(donateBike.id, "Too worn to give away")
        for (phone in listOf(priya, tom)) {
            phone.mergeIn(s.data.value.visibleTo(phone.myRole))
            s.photoDir.listFiles()?.forEach { f -> f.copyTo(File(phone.photoDir, f.name), overwrite = true) }
            phone.flush()
        }
        s.flush()
        return Phones(s, priya, tom)
    }

    /** The app as on a phone that can listen and is on a network, so microphones and codes are in the picture. */
    @Composable
    private fun App(store: Store, onNav: (NavHostController) -> Unit) {
        CompositionLocalProvider(LocalSpeech provides Speech { _, _ -> }, LocalSyncAddresses provides { listOf("127.0.0.1") }) {
            AppRoot(store, startRoute = if (store.data.value.move.setupDone) "home" else null, onNav = onNav)
        }
    }

    private val routes = listOf(
        "home", "overview", "items", "item/wardrobe", "item/fridge", "item/sofa", "walk", "triage", "plan", "disposal", "electronics",
        "boxes", "box/box1", "box/box2", "box/box5", "pack", "labels", "lists", "packorder", "tasks",
        "moveday", "load", "unload", "reconcile", "sweep", "records/ORIGIN", "unpack", "budget", "reports",
        "rooms", "more", "sync", "settings", "search", "scan",
        "people", "people/add", "approvals", "activity",
    )

    /** Picture name and route of every screen. One person's page is added: someone still to join, with their invite showing. */
    private fun routesFor(store: Store): List<Pair<String, String>> {
        val people = store.data.value.people
        val person = people.firstOrNull { it.joinedAt == 0L && !it.removed } ?: people.first()
        return routes.map { it.replace('/', '_') to it } + ("person" to "person/${person.id}?invite=true")
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
            for (route in listOf("approvals", "people", "settings", "sync", "more")) {
                go(route)
                shot(route + "_" + who)
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
            tap("Second phone for the same move?")
            shot("setup_join")
            tap("Scan the invite")
            rule.onAllNodes(hasSetTextAction() and hasText("Or type the code")).onFirst()
                .performTextReplacement(host.inviteFor(gran.id, listOf("127.0.0.1")).valueOrNull()!!.encode())
            rule.waitForIdle()
            tap("OK")
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

        go("boxes")
        tap("All rooms")
        shot("menu")
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
