@file:OptIn(ExperimentalAtomicApi::class)

package app.housemove.data

import platform.Foundation.NSRecursiveLock
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// The iPhone: the lock and the atomics are real; the background writer, the latch and threads come in step 1b with
// the files and sockets that need them, and throw until then.

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

private fun notYet(): Nothing = throw NotImplementedError("step 1b")

internal actual class CountDownLatch actual constructor(count: Int) {
    init {
        notYet()
    }
}

internal actual fun CountDownLatch.countDown(): Unit = notYet()
internal actual fun CountDownLatch.awaitMillis(ms: Long): Boolean = notYet()

internal actual fun startThread(name: String, block: () -> Unit): Unit = notYet()

internal actual class SerialWorker actual constructor(threadName: String) {
    init {
        notYet()
    }

    actual fun execute(task: () -> Unit): Unit = notYet()
    actual fun schedule(delayMs: Long, task: () -> Unit): Unit = notYet()
    actual fun runAndWait(timeoutMs: Long, task: () -> Unit): Unit = notYet()
    actual fun shutdownNow(): Unit = notYet()
    actual fun awaitTermination(timeoutMs: Long): Unit = notYet()
}
