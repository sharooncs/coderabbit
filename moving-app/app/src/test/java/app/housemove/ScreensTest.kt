package app.housemove

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens every screen with a realistic mid-move dataset. Any crash while
 * composing fails the test; a screenshot of each screen is written to
 * build/screens for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private fun jpeg(file: File, color: Int) {
        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    private fun seeded(): Store {
        val s = Store(tmp.newFolder("store"))
        s.setMove { it.copy(name = "Maple Road move", moveDay = today() + 9, setupDone = true, currency = "£") }
        s.setDevice { it.copy(name = "Sam", letter = "S") }
        val destKitchen = s.createRoom(Home.DEST, "Kitchen").copy(doorWidthCm = 76.0, floor = "Ground floor")
        val destBed = s.createRoom(Home.DEST, "Main bedroom").copy(doorWidthCm = 70.0, floor = "Upstairs, first left")
        val destLiving = s.createRoom(Home.DEST, "Living room")
        s.put(destKitchen); s.put(destBed)
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = destKitchen.id)
        val bed = s.createRoom(Home.ORIGIN, "Main bedroom").copy(mapsTo = destBed.id, packed = true)
        val loft = s.createRoom(Home.ORIGIN, "Loft")
        s.put(kitchen); s.put(bed)
        s.setDevice { it.copy(currentRoomId = kitchen.id) }

        val trip = Trip(id = newId(), name = "Trip 1", sort = 1).also { s.put(it) }
        s.setDevice { it.copy(currentTripId = trip.id) }

        val wardrobe = Item(
            id = "wardrobe", name = "Oak wardrobe", originRoomId = bed.id, destRoomId = destBed.id, disposition = Disposition.MOVE,
            widthCm = 120.0, depthCm = 74.0, heightCm = 205.0, value = 450.0, twoPerson = true, label = "L010",
        )
        s.put(wardrobe)
        s.put(Item(id = "fridge", name = "Fridge-freezer", originRoomId = kitchen.id, destRoomId = destKitchen.id, disposition = Disposition.MOVE,
            widthCm = 60.0, depthCm = 65.0, heightCm = 180.0, stage = Stage.LOADED, tripId = trip.id))
        s.put(Item(id = "bed", name = "King bed", originRoomId = bed.id, destRoomId = destBed.id, disposition = Disposition.MOVE,
            needsDisassembly = true, firstNight = true, stage = Stage.DELIVERED, damaged = true, damageNote = "Scratch on headboard"))
        s.put(Item(id = "sofa", name = "Old sofa", originRoomId = loft.id, disposition = Disposition.SELL, price = 80.0, listed = true, deadlineDay = today() + 3))
        s.put(Item(id = "books", name = "Paperbacks", originRoomId = loft.id, disposition = Disposition.DONATE))
        s.put(Item(id = "lamp", name = "", originRoomId = loft.id))
        s.put(Item(id = "desk", name = "Desk", originRoomId = bed.id, disposition = Disposition.MOVE))

        s.put(Box(id = "box1", label = "L001", originRoomId = kitchen.id, destRoomId = destKitchen.id, contents = "Kettle, mugs, tea, cutlery",
            priority = Priority.FIRST_NIGHT, sealed = true, fragile = true))
        s.put(Box(id = "box2", label = "L002", originRoomId = kitchen.id, destRoomId = destKitchen.id, contents = "Pans and baking trays", heavy = true))
        s.put(Box(id = "box3", label = "S1", originRoomId = bed.id, destRoomId = destBed.id, contents = "Bedding", sealed = true,
            stage = Stage.LOADED, tripId = trip.id, priority = Priority.FIRST_NIGHT))
        s.put(Box(id = "box4", label = "L004", originRoomId = bed.id, destRoomId = destBed.id, contents = "Winter clothes", sealed = true, stage = Stage.DELIVERED))
        s.put(Box(id = "box5", label = "L005", originRoomId = loft.id, destRoomId = destLiving.id, contents = "Christmas decorations", sealed = true, stage = Stage.DONE))
        s.put(Box(id = "box6", label = "L006", originRoomId = loft.id, contents = "Passports, documents", travelsWithMe = true, sealed = true))

        for ((owner, kind) in listOf("wardrobe" to PhotoKind.GENERAL, "wardrobe" to PhotoKind.CONDITION, "box1" to PhotoKind.CONTENTS, "bed" to PhotoKind.DAMAGE, "lamp" to PhotoKind.GENERAL)) {
            val f = s.newPhotoFile()
            jpeg(f, 0xFF8899AA.toInt())
            s.addPhoto(owner, kind, f)
        }
        seedTasks().forEach { s.put(it) }
        s.put(Task(id = "late", title = "Book the lift", dueDay = today() - 2))
        s.put(Expense(id = "e1", title = "Van hire", amount = 180.0, category = "Van", day = today(), planned = true))
        s.put(Expense(id = "e2", title = "Boxes and tape", amount = 62.5, category = "Supplies", day = today() - 4))
        s.put(Supply(id = "s1", name = "Medium boxes", need = 30, have = 12))
        s.put(Reading(id = "r1", home = Home.ORIGIN, kind = "Electricity", value = "48213", day = today()))
        s.flush()
        return s
    }

    private val routes = listOf(
        "home", "items", "item/wardrobe", "item/bed", "item/sofa", "item/missing", "walk", "triage", "plan", "disposal",
        "boxes", "box/box1", "box/box3", "box/missing", "pack", "labels", "lists", "packorder", "tasks",
        "moveday", "load", "unload", "reconcile", "sweep", "records/ORIGIN", "records/DEST", "unpack", "budget", "reports",
        "rooms", "more", "sync", "settings", "search", "scan",
    )

    @Test
    fun everyScreenOpensWithData() {
        val store = seeded()
        var nav: NavHostController? = null
        rule.setContent { AppRoot(store, startRoute = "home", onNav = { nav = it }) }
        rule.waitForIdle()
        for (route in routes) {
            rule.runOnUiThread { nav!!.navigate(route) }
            rule.waitForIdle()
            rule.onRoot().assertExists()
            rule.onRoot().captureRoboImage("build/screens/" + route.replace('/', '_') + ".png")
        }
    }

    /** A small, cheap phone in dark mode with the system font enlarged: nothing may crash or become unusable. */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-night-xhdpi")
    fun everyScreenOpensOnASmallDarkPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        val store = seeded()
        var nav: NavHostController? = null
        rule.setContent { AppRoot(store, startRoute = "home", onNav = { nav = it }) }
        rule.waitForIdle()
        for (route in routes) {
            rule.runOnUiThread { nav!!.navigate(route) }
            rule.waitForIdle()
            rule.onRoot().assertExists()
            if (route in setOf("home", "box/box1", "unload", "item/wardrobe", "load", "pack")) {
                rule.onRoot().captureRoboImage("build/screens/small-dark-" + route.replace('/', '_') + ".png")
            }
        }
    }

    @Test
    fun everyScreenOpensEmpty() {
        val store = Store(tmp.newFolder("empty"))
        var nav: NavHostController? = null
        rule.setContent { AppRoot(store, startRoute = "setup", onNav = { nav = it }) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/setup.png")
        for (route in routes) {
            rule.runOnUiThread { nav!!.navigate(route) }
            rule.waitForIdle()
            rule.onRoot().assertExists()
        }
        assertEquals(0, store.data.value.liveBoxes.size)
    }
}
