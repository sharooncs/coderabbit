package app.housemove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppBackground
import app.housemove.ui.AppRoot
import app.housemove.ui.HouseMarker
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.HouseSpot
import app.housemove.ui.IsoHouse
import app.housemove.ui.IsoRoom
import app.housemove.ui.IsoRooms
import app.housemove.ui.RoomState
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

    private fun seeded(): Store {
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
        s.flush()
        return s
    }

    private val routes = listOf(
        "home", "overview", "items", "item/wardrobe", "item/fridge", "item/sofa", "walk", "triage", "plan", "disposal", "electronics",
        "boxes", "box/box1", "box/box2", "box/box5", "pack", "labels", "lists", "packorder", "tasks",
        "moveday", "load", "unload", "reconcile", "sweep", "records/ORIGIN", "unpack", "budget", "reports",
        "rooms", "more", "sync", "settings", "search", "scan",
    )

    private fun capture(kind: String, seededData: Boolean = true) {
        val store = if (seededData) seeded() else Store(tmp.newFolder("empty")).also { s -> s.setMove { it.copy(setupDone = true) } }
        var nav: NavHostController? = null
        rule.setContent { AppRoot(store, startRoute = "home", onNav = { nav = it }) }
        rule.waitForIdle()
        for (route in routes) {
            rule.runOnUiThread { nav!!.navigate(route) }
            rule.waitForIdle()
            rule.onRoot().captureRoboImage("build/audit/$kind/" + route.replace('/', '_') + ".png")
        }
    }

    /** The variants of Home and the first-run screens, which the route list does not reach. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1500dp-mdpi")
    fun variants() {
        val store = seeded()
        var nav: NavHostController? = null
        rule.setContent { AppRoot(store, startRoute = "home", onNav = { nav = it }) }
        rule.waitForIdle()
        fun shot(name: String) = rule.onRoot().captureRoboImage("build/audit/tall/$name.png")
        rule.runOnUiThread { store.setDevice { it.copy(helper = true) } }
        rule.waitForIdle(); shot("home_helper")
        rule.runOnUiThread { store.setDevice { it.copy(helper = false) }; store.setMove { it.copy(kind = MoveKind.MOVERS) } }
        rule.waitForIdle(); shot("home_movers")
        rule.runOnUiThread { store.setMove { it.copy(kind = MoveKind.INVENTORY, off = partsOffFor(MoveKind.INVENTORY)) } }
        rule.waitForIdle(); shot("home_inventory")
        rule.runOnUiThread { store.setMove { it.copy(kind = MoveKind.SELF, off = emptyList(), setupDone = false) }; nav!!.navigate("setup") }
        rule.waitForIdle(); shot("setup")
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
