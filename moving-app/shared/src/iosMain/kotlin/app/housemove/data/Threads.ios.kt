@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class, BetaInteropApi::class)

package app.housemove.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// The iPhone: locks and atomics from Foundation and Kotlin; the latch a dispatch semaphore; threads NSThreads; the
// store's writer a serial dispatch queue. Each keeps the ordering the JVM's java.util.concurrent class gives the
// shared code (see SerialWorker below, and design/ios-step1.md).

/** A reentrant lock, as the JVM's monitors are. */
internal actual class Lock actual constructor() {
    val lock = NSRecursiveLock()
}

internal actual inline fun <T> synchronized(lock: Lock, block: () -> T): T {
    lock.lock.lock()
    try {
        return block()
    } finally {
        lock.lock.unlock()
    }
}

internal actual class AtomicBoolean actual constructor(initialValue: Boolean) {
    val value = kotlin.concurrent.atomics.AtomicBoolean(initialValue)
}

internal actual fun AtomicBoolean.compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean = value.compareAndSet(expectedValue, newValue)
internal actual fun AtomicBoolean.set(newValue: Boolean) = value.store(newValue)

internal actual class AtomicLong actual constructor(initialValue: Long) {
    val value = kotlin.concurrent.atomics.AtomicLong(initialValue)
}

/** As the JVM's updateAndGet: [update] may run more than once when another thread changes the value meanwhile. */
internal actual inline fun AtomicLong.updateAndGet(crossinline update: (Long) -> Long): Long {
    while (true) {
        val prev = value.load()
        val next = update(prev)
        if (value.compareAndSet(prev, next)) return next
    }
}

internal actual class AtomicInteger actual constructor(initialValue: Int) {
    val value = kotlin.concurrent.atomics.AtomicInt(initialValue)
}

internal actual fun AtomicInteger.incrementAndGet(): Int = value.addAndFetch(1)
internal actual fun AtomicInteger.decrementAndGet(): Int = value.addAndFetch(-1)

/** dispatch_time for [ms] milliseconds from now (none when [ms] is not above 0). */
private fun inMillis(ms: Long) = dispatch_time(DISPATCH_TIME_NOW, maxOf(0L, ms) * NSEC_PER_MSEC.toLong())

/**
 * As java.util.concurrent.CountDownLatch: [countDown] takes the count down to zero and no further; once it is zero
 * every wait ends at once. A dispatch semaphore is signalled when the count reaches zero, and each waiter that gets
 * through signals it again for the next. The semaphore orders memory as the JVM's latch does: what a thread wrote
 * before [countDown] is seen by a thread whose wait it ended.
 */
internal actual class CountDownLatch actual constructor(count: Int) {
    init {
        require(count >= 0) { "count < 0" }
    }

    val count = kotlin.concurrent.atomics.AtomicInt(count)
    val open = dispatch_semaphore_create(0)!!
}

internal actual fun CountDownLatch.countDown() {
    while (true) {
        val c = count.load()
        if (c == 0) return
        if (count.compareAndSet(c, c - 1)) {
            if (c == 1) dispatch_semaphore_signal(open)
            return
        }
    }
}

internal actual fun CountDownLatch.awaitMillis(ms: Long): Boolean {
    if (count.load() == 0) return true
    if (dispatch_semaphore_wait(open, inMillis(ms)) != 0L) return count.load() == 0
    // Through: open it again for whoever else waits.
    dispatch_semaphore_signal(open)
    return true
}

/**
 * A new NSThread called [name], running [block] (in an autorelease pool, as a thread of its own needs), with a stack
 * of 1 MB as the JVM's and Android's threads have (an NSThread's is 512 KB unless asked).
 */
internal actual fun startThread(name: String, block: () -> Unit) {
    val t = NSThread { autoreleasepool { block() } }
    t.name = name
    t.stackSize = 1UL shl 20
    t.start()
}

/**
 * The store's writer, as Android's single-thread ScheduledExecutorService: one serial dispatch queue ([threadName] is
 * its label), so tasks run one at a time, each after the one before it has ended and seeing all it did.
 *
 * - [execute] runs tasks in the order given (dispatch_async on a serial queue is first in, first out).
 * - [schedule] puts the task on the queue when its time comes (dispatch_after), so anything given meanwhile runs
 *   first, as with the JVM's executor, which orders its tasks by the time each is due.
 * - [runAndWait] waits for the task, which runs after everything given before it, as Future.get does; a task that
 *   failed or did not end in time is thrown as an exception, and one that is late still runs.
 * - [shutdownNow] drops what has not started: each queued task does nothing once the worker has shut down; the task
 *   running finishes, and [awaitTermination] waits for it. Nothing more is taken (an IllegalStateException, where
 *   the JVM throws RejectedExecutionException).
 * - A task's exception is caught and dropped, as the JVM's executor keeps it in a Future nobody reads.
 */
internal actual class SerialWorker actual constructor(threadName: String) {
    private val queue = dispatch_queue_create(threadName, null)
    private val shut = kotlin.concurrent.atomics.AtomicBoolean(false)

    private fun wrap(task: () -> Unit): () -> Unit = {
        if (!shut.load()) {
            autoreleasepool {
                try {
                    task()
                } catch (_: Throwable) {
                    // As in the JVM's executor, where it would sit in a Future nobody asks.
                }
            }
        }
    }

    private fun checkOpen() {
        if (shut.load()) throw IllegalStateException("the writer has been shut down")
    }

    actual fun execute(task: () -> Unit) {
        checkOpen()
        dispatch_async(queue, wrap(task))
    }

    actual fun schedule(delayMs: Long, task: () -> Unit) {
        checkOpen()
        dispatch_after(inMillis(delayMs), queue, wrap(task))
    }

    actual fun runAndWait(timeoutMs: Long, task: () -> Unit) {
        checkOpen()
        val done = dispatch_semaphore_create(0)!!
        val failure = AtomicReference<Throwable?>(null)
        dispatch_async(queue) {
            try {
                if (!shut.load()) autoreleasepool { task() }
            } catch (e: Throwable) {
                failure.store(e)
            } finally {
                dispatch_semaphore_signal(done)
            }
        }
        if (dispatch_semaphore_wait(done, inMillis(timeoutMs)) != 0L) throw IllegalStateException("timed out after $timeoutMs ms")
        failure.load()?.let { throw IllegalStateException("the task failed", it) }
    }

    actual fun shutdownNow() {
        shut.store(true)
    }

    actual fun awaitTermination(timeoutMs: Long) {
        // Everything queued before this does nothing now, so this marker runs once the task running has ended.
        val done = dispatch_semaphore_create(0)!!
        dispatch_async(queue) { dispatch_semaphore_signal(done) }
        dispatch_semaphore_wait(done, inMillis(timeoutMs))
    }
}
