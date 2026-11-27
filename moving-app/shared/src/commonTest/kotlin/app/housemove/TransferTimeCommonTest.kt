package app.housemove

import app.housemove.data.BackgroundTasks
import app.housemove.data.Lock
import app.housemove.data.Role
import app.housemove.data.TransferTime
import app.housemove.data.deleteRecursively
import app.housemove.data.synchronized
import app.housemove.sync.LocalLan
import app.housemove.sync.MailboxSync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Time to finish a transfer once the app has left the front ([TransferTime], design/ios-plan.md step 5): every hold the
 * shared code takes is let go of exactly once, and [BackgroundTasks] ends each of its tasks exactly once, also when iOS's
 * time runs out first ([FakeTasks] stands in for UIApplication's beginBackgroundTask and endBackgroundTask). On the JVM in
 * every test run, and on the iPhone simulator in CI. The iPhone's model (joining, code syncs both ways, syncing by
 * itself) is checked in sharedUi's desktop tests (TransferTimeModelTest).
 */
class TransferTimeCommonTest {
    private val undoCrypto = useTestCryptoIfNone()
    private val root = newTempFolder()
    private val tasks = FakeTasks()
    private val phones = ArrayList<AutoPhone>()
    private var mailbox: MailboxSync? = null

    @AfterTest
    fun cleanUp() {
        TransferTime.holder = TransferTime.NONE
        mailbox?.close()
        phones.forEach { it.close() }
        root.deleteRecursively()
        undoCrypto()
    }

    @Test
    fun aHoldIsLetGoOfHoweverTheTransferEnds() {
        TransferTime.holder = tasks.holder()
        assertEquals(42, TransferTime.during("sum") { 42 })
        assertFailsWith<IllegalStateException> { TransferTime.during("fails") { error("the socket closed") } }
        fun early(): String {
            TransferTime.during("returns") { return "early" }
        }
        assertEquals("early", early())
        assertEquals(listOf("HouseMove: sum", "HouseMove: fails", "HouseMove: returns"), tasks.names())
        tasks.assertEachEndedOnce()
    }

    @Test
    fun aHolderThatFailsNeverStopsTheTransfer() {
        TransferTime.holder = TransferTime.Holder { throw IllegalStateException("no time today") }
        assertEquals("done", TransferTime.during("anything") { "done" })
    }

    @Test
    fun iosTimeRunningOutEndsTheTaskOnce() {
        val holder = tasks.holder()
        // Out of time while the transfer runs: ended then, and not again when the transfer ends.
        TransferTime.holder = holder
        TransferTime.during("long sync") {
            tasks.expireAll()
            assertEquals(1, tasks.endsOf(1))
        }
        assertEquals(1, tasks.endsOf(1))

        // Out of time before beginBackgroundTask has even given its ID: ended as soon as the ID is known.
        tasks.expireAtOnce = true
        TransferTime.during("in the background already") {
            assertEquals(1, tasks.endsOf(2), "ended the moment the ID came")
        }
        tasks.expireAtOnce = false

        // iOS gives no time at all (UIBackgroundTaskInvalid): nothing to end.
        tasks.giveNone = true
        TransferTime.during("no time") { }
        tasks.giveNone = false
        tasks.assertEachEndedOnce()
        assertEquals(0, tasks.endsOf(FakeTasks.INVALID))
    }

    @Test
    fun codeSyncsAndJoinsAreHeld() {
        val lan = LocalLan()
        val asha = AutoPhone(root, "Asha", lan, engineOn = false).also { phones += it }
        val priya = AutoPhone(root, "Priya", lan, engineOn = false).also { phones += it }
        asha.found("Maple Road")
        TransferTime.holder = tasks.holder()
        asha.invite(priya, Role.DECIDER)
        assertTrue(asha.codeSync(priya).ok)
        // The joining phone holds "join", the phone showing the invite "sync by code" while it serves; then the code sync.
        waitUntil("every hold let go") { tasks.open() == 0 }
        val names = tasks.names()
        assertTrue("HouseMove: ${TransferTime.JOIN}" in names, names.toString())
        assertTrue(names.count { it == "HouseMove: ${TransferTime.SYNC_BY_CODE}" } >= 2, names.toString())
        tasks.assertEachEndedOnce()
    }

    @Test
    fun syncingByItselfIsHeld() {
        val lan = LocalLan()
        val asha = AutoPhone(root, "Asha", lan).also { phones += it }
        val priya = AutoPhone(root, "Priya", lan).also { phones += it }
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        TransferTime.holder = tasks.holder()
        asha.store.createItem("Lamp", null)
        waitUntil("the lamp, by itself") { priya.store.data.value.liveItems.any { it.name == "Lamp" } }
        waitUntil("every hold let go") { tasks.open() == 0 }
        assertTrue("HouseMove: ${TransferTime.SYNC_BY_ITSELF}" in tasks.names(), tasks.names().toString())
        tasks.assertEachEndedOnce()
    }

    @Test
    fun theMailboxIsHeldWhileItSendsAndReads() {
        val box = MailboxStandIn(clock = { 1_800_000_000_000L })
        val asha = AutoPhone(root, "Asha", LocalLan(), engineOn = false).also { phones += it }
        asha.found("Maple Road")
        assertTrue(MailboxSync.setUp(asha.store, box.code).first)
        val engine = MailboxSync({ asha.moves.current.value }, { true }, { 1_800_000_000_000L }).apply { http = box }
        mailbox = engine
        TransferTime.holder = tasks.holder()
        engine.step()
        assertTrue(tasks.names().all { it == "HouseMove: ${TransferTime.MAILBOX}" } && tasks.names().isNotEmpty(), tasks.names().toString())
        tasks.assertEachEndedOnce()
    }
}

/**
 * UIApplication's background tasks, as the tests see them: each begun with a new ID, which is ended once. [expireAll]
 * calls every expiry handler still waiting, as iOS does when the time is up; with [expireAtOnce] the handler is called
 * before the ID is given back; with [giveNone], no time is given at all ([INVALID]).
 */
internal class FakeTasks {
    private val lock = Lock()
    private var next = 1L
    private val begun = LinkedHashMap<Long, String>()
    private val ends = HashMap<Long, Int>()
    private val expiries = HashMap<Long, () -> Unit>()
    var expireAtOnce = false
    var giveNone = false

    fun holder() = BackgroundTasks(::begin, ::end, INVALID)

    private fun begin(name: String, expired: () -> Unit): Long {
        if (giveNone) return INVALID
        val id = synchronized(lock) {
            val id = next++
            begun[id] = name
            if (!expireAtOnce) expiries[id] = expired
            id
        }
        if (expireAtOnce) expired()
        return id
    }

    private fun end(id: Long) {
        synchronized(lock) { ends[id] = (ends[id] ?: 0) + 1 }
    }

    fun expireAll() {
        val waiting = synchronized(lock) { expiries.values.toList().also { expiries.clear() } }
        waiting.forEach { it() }
    }

    fun names(): List<String> = synchronized(lock) { begun.values.toList() }

    fun endsOf(id: Long): Int = synchronized(lock) { ends[id] ?: 0 }

    /** Tasks begun and not ended yet. */
    fun open(): Int = synchronized(lock) { begun.keys.count { (ends[it] ?: 0) == 0 } }

    fun assertEachEndedOnce() = synchronized(lock) {
        for ((id, name) in begun) assertEquals(1, ends[id] ?: 0, "task $id ($name) ended once")
        assertTrue(ends.keys.all { it in begun }, "only tasks that were begun are ended")
    }

    companion object {
        const val INVALID = 0L
    }
}
