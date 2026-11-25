package app.housemove

import app.housemove.data.AtomicInteger
import app.housemove.data.CountDownLatch
import app.housemove.data.Lock
import app.housemove.data.SerialWorker
import app.housemove.data.awaitMillis
import app.housemove.data.countDown
import app.housemove.data.decrementAndGet
import app.housemove.data.incrementAndGet
import app.housemove.data.set
import app.housemove.data.compareAndSet
import app.housemove.data.startThread
import app.housemove.data.synchronized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The store's writer, the latch and threads (Threads.kt) on every platform: on the JVM java.util.concurrent's, on the
 * iPhone a serial dispatch queue, a dispatch semaphore and NSThreads. Each keeps the ordering the shared code relies on.
 */
class ThreadsCommonTest {
    private class Log {
        private val lock = Lock()
        private val items = ArrayList<String>()

        fun add(s: String) = synchronized(lock) { items += s }

        fun list(): List<String> = synchronized(lock) { items.toList() }
    }

    @Test
    fun theWriterRunsTasksOneAtATimeInTheOrderGiven() {
        val w = SerialWorker("test-io")
        val log = Log()
        val running = AtomicInteger(0)
        var overlap = false
        for (i in 0 until 200) {
            w.execute {
                if (running.incrementAndGet() != 1) overlap = true
                log.add("t$i")
                running.decrementAndGet()
            }
        }
        w.runAndWait(10_000) { log.add("waited") }
        assertEquals((0 until 200).map { "t$it" } + "waited", log.list())
        assertFalse(overlap, "two tasks ran at once")
        w.shutdownNow()
        w.awaitTermination(5_000)
    }

    @Test
    fun aDelayedTaskRunsAfterWhatIsGivenMeanwhile() {
        val w = SerialWorker("test-io")
        val log = Log()
        val start = TimeSource.Monotonic.markNow()
        val done = CountDownLatch(1)
        w.schedule(300) {
            log.add("late")
            done.countDown()
        }
        w.execute { log.add("now") }
        w.runAndWait(5_000) { log.add("flushed") }
        assertTrue(done.awaitMillis(10_000))
        assertTrue(start.elapsedNow().inWholeMilliseconds >= 290, "the delayed task ran early")
        assertEquals(listOf("now", "flushed", "late"), log.list())
        w.shutdownNow()
    }

    @Test
    fun waitingForATaskSaysWhenItFailedOrWasLate() {
        val w = SerialWorker("test-io")
        assertFailsWith<Exception> { w.runAndWait(5_000) { throw IllegalStateException("no space") } }
        // A task that fails in execute is dropped, and the writer goes on.
        w.execute { throw IllegalStateException("dropped") }
        val gate = CountDownLatch(1)
        w.execute { gate.awaitMillis(2_000) }
        assertFailsWith<Exception> { w.runAndWait(100) {} }
        gate.countDown()
        var ran = false
        w.runAndWait(5_000) { ran = true }
        assertTrue(ran)
        w.shutdownNow()
    }

    @Test
    fun shuttingDownDropsWhatHasNotStartedAndTakesNothingMore() {
        val w = SerialWorker("test-io")
        val log = Log()
        val started = CountDownLatch(1)
        val gate = app.housemove.data.AtomicBoolean(false)
        w.execute {
            started.countDown()
            // Waiting without a wait the JVM's shutdownNow could interrupt (it interrupts the task running; the
            // store's writes are not interruptible either).
            val begun = TimeSource.Monotonic.markNow()
            while (!gate.compareAndSet(true, true) && begun.elapsedNow().inWholeMilliseconds < 5_000) {
                // spin
            }
            log.add("running")
        }
        assertTrue(started.awaitMillis(5_000))
        w.execute { log.add("queued") }
        w.schedule(50) { log.add("delayed") }
        w.shutdownNow()
        assertFailsWith<Exception> { w.execute { log.add("after") } }
        assertFailsWith<Exception> { w.schedule(10) { log.add("after") } }
        gate.set(true)
        w.awaitTermination(5_000)
        sleepMillis(200)
        assertEquals(listOf("running"), log.list())
    }

    @Test
    fun aLatchOpensOnceAndStaysOpen() {
        val latch = CountDownLatch(2)
        assertFalse(latch.awaitMillis(50))
        latch.countDown()
        assertFalse(latch.awaitMillis(50))
        val through = AtomicInteger(0)
        val all = CountDownLatch(3)
        repeat(3) {
            startThread("waiter") {
                if (latch.awaitMillis(10_000)) through.incrementAndGet()
                all.countDown()
            }
        }
        sleepMillis(50)
        latch.countDown()
        latch.countDown()
        assertTrue(all.awaitMillis(10_000))
        assertEquals(4, through.incrementAndGet())
        assertTrue(latch.awaitMillis(0))
        assertTrue(CountDownLatch(0).awaitMillis(0))
    }

    @Test
    fun whatAThreadWroteBeforeCountingDownIsSeenAfterTheWait() {
        repeat(50) { round ->
            val answer = BooleanArray(1)
            val latch = CountDownLatch(1)
            startThread("answer") {
                answer[0] = round % 2 == 0
                latch.countDown()
            }
            assertTrue(latch.awaitMillis(10_000))
            assertEquals(round % 2 == 0, answer[0])
        }
    }
}
