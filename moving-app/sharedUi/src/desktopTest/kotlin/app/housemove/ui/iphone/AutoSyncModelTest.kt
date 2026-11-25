package app.housemove.ui.iphone

import app.housemove.sync.AutoSync
import app.housemove.sync.LocalLan
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The iPhone syncing by itself (design/auto-sync.md) with an "Android phone" on 2.3, both in this JVM and finding each
 * other through the tests' Bonjour (LocalLan): after joining, fresh data comes with no code, "Get fresh data" says "Up to
 * date with Sam's phone", and the switch stops it.
 */
class AutoSyncModelTest {
    private val root = newRoot()
    private val lan = LocalLan()
    private val android = AndroidPhone(root, lan)
    private val hooks = DesktopHooks(File(root, "iphone"), lan = lan)
    private val model = AppModel(hooks, "Test build 0.1 (1)", mainScope(), localNetworkWaitMs = 2_000)

    @AfterTest
    fun cleanUp() {
        model.autoSync?.close()
        android.close()
        root.deleteRecursively()
    }

    @Test
    fun freshDataComesWithNoCode() {
        val engine = assertNotNull(model.autoSync)
        model.use(android.invite())
        model.yes()
        waitFor("the join") { model.store != null || model.join is JoinStep.Failed }
        val s = assertNotNull(model.store, "joined: ${(model.join as? JoinStep.Failed)?.report}")
        // The join was a meeting: the two phones swap keys by themselves, then a change on the Android phone comes over.
        android.store.createItem("Garden chair", null)
        waitFor("the chair, with no code", 30_000) { s.data.value.liveItems.any { it.name == "Garden chair" } }
        waitFor("up to date", 30_000) { engine.status.value.state == AutoSync.State.UP_TO_DATE }
        val now = Words.nowMillis()
        assertTrue(s.device.value.lastSyncAt > 0)
        assertEquals("Sam", s.device.value.lastSyncWith)
        val line = engine.status.value.copy(lastWith = s.device.value.lastSyncWith, lastAt = s.device.value.lastSyncAt).line(now)
        assertTrue(line.startsWith("Up to date with Sam's phone · "), line)
        // The iPhone still changes nothing of the move but its own phone entry.
        assertTrue(android.store.data.value.liveItems.none { it.name !in s.data.value.liveItems.map { i -> i.name } })

        // Switched off: nothing more comes by itself.
        model.setAutoSync(false)
        waitFor("off") { engine.status.value.state == AutoSync.State.OFF }
        assertEquals(false, model.autoSyncOn)
        // A sync already under way when the switch went off finishes; none starts after it.
        Thread.sleep(1_000)
        android.store.createItem("Parasol", null)
        Thread.sleep(2_500)
        assertTrue(s.data.value.liveItems.none { it.name == "Parasol" })
        model.setAutoSync(true)
        waitFor("the parasol once it is on again", 30_000) { s.data.value.liveItems.any { it.name == "Parasol" } }
    }

    @Test
    fun theAppLeavingTheScreenStopsIt() {
        val engine = assertNotNull(model.autoSync)
        model.use(android.invite())
        model.yes()
        waitFor("the join") { model.store != null }
        val s = model.store!!
        waitFor("keys swapped", 30_000) { s.device.value.lastSyncWith.isNotEmpty() && engine.status.value.state == AutoSync.State.UP_TO_DATE }
        model.background()
        Thread.sleep(1_000)
        android.store.createItem("Lantern", null)
        Thread.sleep(2_500)
        assertTrue(s.data.value.liveItems.none { it.name == "Lantern" }, "nothing syncs while the app is in the background")
        model.foreground()
        waitFor("the lantern, back at the front", 30_000) { s.data.value.liveItems.any { it.name == "Lantern" } }
    }
}
