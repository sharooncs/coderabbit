package app.housemove

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.Store
import app.housemove.data.delete
import app.housemove.data.mkdirs
import app.housemove.data.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * The store's save retry (2.2) through the platform's writer and files: a save that fails is said and tried again
 * from the writer's own task until it works. On the iPhone that is the serial dispatch queue scheduling onto itself
 * and the POSIX file calls failing and then succeeding. Ported from NotANumberTest, which stays on the JVM.
 */
class StoreSaveCommonTest {
    private var now = 1_000_000L

    private fun onDisk(dir: File): AppData = AppJson.decodeFromString(AppData.serializer(), File(dir, "data.json").readText())

    private fun waitFor(what: String, check: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!check()) {
            if (start.elapsedNow().inWholeMilliseconds > 20_000) fail("waited 20 s for $what")
            sleepMillis(20)
        }
    }

    @Test
    fun aSaveThatFailsIsSaidKeepsTheChangesAndIsTriedAgainUntilItWorks() = inTempFolder { dir ->
        val asha = Store(dir) { now++ }
        asha.saveRetryMs = 100
        asha.setMove { it.copy(name = "Maple Road") }
        asha.flush()
        assertNull(asha.saveProblem.value)
        // The phone cannot write the file now: a folder stands where the new copy is written first (as a full disk would fail it).
        val blocker = File(dir, "data.json.tmp")
        assertTrue(blocker.mkdirs())
        val lamp = asha.createItem("Lamp", null)
        waitFor("the failed save to be said") { asha.saveProblem.value != null }
        assertEquals(Store.SAVE_FAILED, asha.saveProblem.value)
        // Nothing is lost: the change is in memory, and so is the next one, though neither is on the phone yet.
        val kettle = asha.createItem("Kettle", null)
        assertNotNull(asha.data.value.item(lamp.id))
        assertNotNull(asha.data.value.item(kettle.id))
        assertNull(onDisk(dir).item(lamp.id))
        sleepMillis(400)
        assertEquals(Store.SAVE_FAILED, asha.saveProblem.value, "it keeps failing while the phone cannot write")
        // Once the phone can write again, the next try saves everything by itself.
        assertTrue(blocker.delete())
        waitFor("the save to go through") { asha.saveProblem.value == null }
        assertNotNull(onDisk(dir).item(lamp.id))
        assertNotNull(onDisk(dir).item(kettle.id))
        asha.close()
        val again = Store(dir) { now++ }
        assertNotNull(again.data.value.item(kettle.id))
        assertNull(again.saveProblem.value)
        again.close()
    }
}
