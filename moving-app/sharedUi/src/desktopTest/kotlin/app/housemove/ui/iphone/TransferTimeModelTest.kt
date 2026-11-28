package app.housemove.ui.iphone

import app.housemove.data.BackgroundTasks
import app.housemove.data.TransferTime
import app.housemove.sync.LocalLan
import app.housemove.sync.WifiSync
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The iPhone's transfers each get time to finish once HouseMove leaves the front (step 5 of design/ios-plan.md): its
 * model, as its screens drive it, against a hosting "Android phone" over 127.0.0.1, with [Tasks] standing in for iOS's
 * background tasks (UIApplication's beginBackgroundTask and endBackgroundTask, which the app gives [BackgroundTasks] in
 * iosMain TransferTasks.kt). Joining, getting fresh data by code both ways, syncing by itself, a join that cannot reach
 * the other phone, and iOS's time running out: each task begun is ended exactly once.
 */
class TransferTimeModelTest {
    private val root = newRoot()
    private val tasks = Tasks()
    private val addressesBefore = WifiSync.addresses
    private val closing = ArrayList<() -> Unit>()

    init {
        TransferTime.holder = BackgroundTasks(tasks::begin, tasks::end, Tasks.INVALID)
    }

    @AfterTest
    fun cleanUp() {
        TransferTime.holder = TransferTime.NONE
        WifiSync.addresses = addressesBefore
        closing.forEach { it() }
        root.deleteRecursively()
    }

    private fun iphone(name: String, lan: LocalLan? = null): Pair<AppModel, AndroidPhone> {
        val android = AndroidPhone(File(root, "$name-android").apply { mkdirs() }, lan)
        val model = AppModel(DesktopHooks(File(root, name), lan = lan), "Test build 0.1 (1)", mainScope(), localNetworkWaitMs = 1_000)
        closing += {
            model.stopCode()
            model.autoSync?.close()
            android.close()
        }
        return model to android
    }

    private fun AppModel.joinFrom(android: AndroidPhone) {
        use(android.invite())
        yes()
        waitFor("the join") { store != null || join is JoinStep.Failed }
        assertNotNull(store, "joined: ${(join as? JoinStep.Failed)?.report}")
    }

    @Test
    fun joiningAndCodeSyncsBothWaysAreHeldAndLetGo() {
        val (model, android) = iphone("iphone")
        model.joinFrom(android)
        tasks.allEnded()
        assertTrue("HouseMove: ${TransferTime.JOIN}" in tasks.names(), tasks.names().toString())

        // Fresh data with the Android phone's code (scanned, or through the Camera).
        val before = tasks.count()
        model.use(android.syncCode())
        waitFor("the sync") { (model.fresh as? Fresh.Idle)?.report != null }
        assertEquals("Up to date", (model.fresh as Fresh.Idle).report!!.title)
        tasks.allEnded()
        assertTrue(tasks.names().drop(before).contains("HouseMove: ${TransferTime.SYNC_BY_CODE}"), tasks.names().toString())

        // The Android phone scans this iPhone's code: the iPhone serves it in a hold of its own.
        WifiSync.addresses = { listOf("127.0.0.1") }
        model.showCode()
        val code = WifiSync.Invite.parse((model.fresh as Fresh.Showing).code)!!
        val served = tasks.count()
        assertTrue(WifiSync.join(android.store, code, letNewPhonesIn = false).ok)
        waitFor("the iPhone's report") { (model.fresh as? Fresh.Showing)?.report != null }
        tasks.allEnded()
        assertTrue(tasks.count() > served, "the iPhone's side of the sync was held")
        tasks.eachEndedOnce()
    }

    @Test
    fun aJoinThatFailsLetsGoToo() {
        val (model, android) = iphone("iphone")
        // An address that answers nobody, and iOS saying the local network is allowed: tried twice, both fail.
        WifiSync.addresses = { listOf("10.0.0.2") }
        val nowhere = WifiSync.Invite.parse(android.invite())!!.copy(hosts = listOf("0.0.0.0"), port = 9).encode()
        (model.hooks as DesktopHooks).network = LocalNetwork.ALLOWED
        model.use(nowhere)
        model.yes()
        waitFor("the failure") { model.join is JoinStep.Failed }
        tasks.allEnded()
        assertEquals(2, tasks.names().count { it == "HouseMove: ${TransferTime.JOIN}" }, tasks.names().toString())
        tasks.eachEndedOnce()
    }

    @Test
    fun iosTimeRunningOutEndsEachTaskOnceAndTheJoinGoesOn() {
        val (model, android) = iphone("iphone")
        // Every task's time runs out the moment it is begun (the phone locked as the person tapped Yes): each is ended
        // then, never again when the transfer ends, and the transfer itself is not stopped by it.
        tasks.expireAtOnce = true
        model.joinFrom(android)
        model.use(android.syncCode())
        waitFor("the sync") { (model.fresh as? Fresh.Idle)?.report != null }
        tasks.expireAtOnce = false
        tasks.allEnded()
        assertTrue(tasks.count() >= 4, tasks.names().toString())
        tasks.eachEndedOnce()
    }

    @Test
    fun syncingByItselfIsHeldAndLetGo() {
        val lan = LocalLan()
        val (model, android) = iphone("iphone-auto", lan)
        model.joinFrom(android)
        val s = model.store!!
        android.store.createItem("Garden chair", null)
        waitFor("the chair, with no code", 30_000) { s.data.value.liveItems.any { it.name == "Garden chair" } }
        tasks.allEnded()
        assertTrue("HouseMove: ${TransferTime.SYNC_BY_ITSELF}" in tasks.names(), tasks.names().toString())
        tasks.eachEndedOnce()
    }

    /** iOS's background tasks as these tests see them: an ID for each, ended once; with [expireAtOnce], out of time at once. */
    private class Tasks {
        private val next = AtomicLong(1)
        private val begun = java.util.concurrent.ConcurrentHashMap<Long, String>()
        private val order = java.util.concurrent.CopyOnWriteArrayList<Long>()
        private val ends = java.util.concurrent.ConcurrentHashMap<Long, Int>()

        @Volatile
        var expireAtOnce = false

        fun begin(name: String, expired: () -> Unit): Long {
            val id = next.getAndIncrement()
            begun[id] = name
            order += id
            if (expireAtOnce) expired()
            return id
        }

        fun end(id: Long) {
            ends.merge(id, 1, Int::plus)
        }

        fun count() = order.size

        fun names(): List<String> = order.map { begun.getValue(it) }

        /** Waits until every task begun has been ended (a sync thread may finish a moment after the screen hears). */
        fun allEnded() = waitFor("every task ended: begun ${names()}, ended ${ends.keys}") { order.all { (ends[it] ?: 0) > 0 } }

        fun eachEndedOnce() {
            for (id in order) assertEquals(1, ends[id] ?: 0, "task $id (${begun[id]}) ended once")
            assertTrue(ends.keys.all { it in begun.keys }, "only tasks begun are ended")
        }

        companion object {
            const val INVALID = 0L
        }
    }
}
