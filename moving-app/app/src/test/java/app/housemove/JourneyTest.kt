package app.housemove

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Taps through the core journey on a fresh install: set up, triage, pack, seal, load, unload, unpack. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class JourneyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun freshInstallThroughToUnpacked() {
        val store = Store(tmp.newFolder("journey"))
        store.setDevice { it.copy(name = "Sam", letter = "S", nextSeq = 1) }
        var nav: NavHostController? = null
        rule.setContent { AppRoot(store, onNav = { nav = it }) }
        fun go(route: String) {
            rule.runOnUiThread { nav!!.navigate(route) }
            rule.waitForIdle()
        }
        fun tap(text: String) {
            val node = rule.onAllNodes(hasText(text) and hasClickAction()).onFirst()
            try {
                node.performScrollTo()
            } catch (_: AssertionError) {
                // not inside a scrolling container (fixed buttons, lazy lists)
            }
            node.performClick()
            rule.waitForIdle()
        }

        // Setup wizard: one room in each home, same name, so they link automatically.
        rule.onNodeWithText("What is it for?").assertExists()
        tap("Next") // keep "Moving it myself"
        tap("Next")
        tap("Kitchen")
        tap("Next")
        tap("Kitchen")
        tap("Finish")
        val d0 = store.data.value
        assertTrue(d0.move.setupDone)
        val oldKitchen = d0.roomsOf(Home.ORIGIN).single()
        val newKitchen = d0.roomsOf(Home.DEST).single()
        assertEquals(newKitchen.id, oldKitchen.mapsTo)
        assertTrue(d0.liveTasks.size > 40)

        // Triage: an item photographed in the kitchen is marked Move and inherits the destination.
        val item = store.createItem("Fridge", oldKitchen.id)
        go("triage")
        tap("Move")
        assertEquals(Disposition.MOVE, store.data.value.item(item.id)!!.disposition)
        assertEquals(newKitchen.id, store.data.value.item(item.id)!!.destRoomId)

        // Pack: pick the room, take a handwritten code, seal.
        go("pack")
        tap("Kitchen")
        tap("No sticker? Use a handwritten code")
        val box = store.data.value.liveBoxes.single()
        assertEquals("S1", box.label)
        assertEquals(newKitchen.id, box.destRoomId)
        assertEquals("Sam", box.packedBy)
        tap("Seal box")
        assertTrue(store.data.value.box(box.id)!!.sealed)

        // Load everything from the kitchen; a trip is created on the fly.
        go("load")
        tap("Load all")
        val loaded = store.data.value
        assertEquals(Stage.LOADED, loaded.box(box.id)!!.stage)
        assertEquals(Stage.LOADED, loaded.item(item.id)!!.stage)
        assertNotNull(loaded.trip(loaded.box(box.id)!!.tripId))

        // Unload both, then unpack the box.
        go("unload")
        tap("Here")
        tap("Here")
        assertTrue(store.data.value.movables().all { it.stage == Stage.DELIVERED })
        assertTrue(store.data.value.issues().none { it.route == "reconcile" })

        go("unpack")
        tap("Unpacked")
        assertEquals(Stage.DONE, store.data.value.box(box.id)!!.stage)

        // Survives a restart.
        store.flush()
        val reopened = Store(tmp.root.resolve("journey"))
        assertEquals(Stage.DONE, reopened.data.value.box(box.id)!!.stage)
        assertEquals("Fridge", reopened.data.value.item(item.id)!!.name)
    }
}
