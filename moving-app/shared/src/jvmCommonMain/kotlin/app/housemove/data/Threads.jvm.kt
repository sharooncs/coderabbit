// Member calls of the java.util.concurrent classes inside these extensions are meant: each is the call the code made before.
@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER", "NOTHING_TO_INLINE")

package app.housemove.data

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.LongUnaryOperator
import kotlin.concurrent.thread

// Android and the JVM: what the code used before it was shared.

internal actual typealias Lock = Any

internal actual inline fun <T> synchronized(lock: Lock, block: () -> T): T = kotlin.synchronized(lock, block)

internal actual typealias AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean

internal actual fun AtomicBoolean.compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean = compareAndSet(expectedValue, newValue)
internal actual fun AtomicBoolean.set(newValue: Boolean) = set(newValue)

internal actual typealias AtomicLong = java.util.concurrent.atomic.AtomicLong

internal actual inline fun AtomicLong.updateAndGet(crossinline update: (Long) -> Long): Long = updateAndGet(LongUnaryOperator { update(it) })

internal actual typealias AtomicInteger = java.util.concurrent.atomic.AtomicInteger

internal actual fun AtomicInteger.incrementAndGet(): Int = incrementAndGet()
internal actual fun AtomicInteger.decrementAndGet(): Int = decrementAndGet()

internal actual typealias CountDownLatch = java.util.concurrent.CountDownLatch

internal actual fun CountDownLatch.countDown() = countDown()
internal actual fun CountDownLatch.awaitMillis(ms: Long): Boolean = await(ms, TimeUnit.MILLISECONDS)

internal actual fun startThread(name: String, block: () -> Unit) {
    thread(name = name, isDaemon = true, block = block)
}

internal actual class SerialWorker actual constructor(threadName: String) {
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, threadName).apply { isDaemon = true }
    }

    actual fun execute(task: () -> Unit) = io.execute { task() }

    actual fun schedule(delayMs: Long, task: () -> Unit) {
        io.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
    }

    actual fun runAndWait(timeoutMs: Long, task: () -> Unit) {
        io.submit { task() }.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    actual fun shutdownNow() {
        io.shutdownNow()
    }

    actual fun awaitTermination(timeoutMs: Long) {
        try {
            io.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
    }
}
