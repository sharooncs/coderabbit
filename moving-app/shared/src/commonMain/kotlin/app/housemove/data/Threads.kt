package app.housemove.data

/*
 * Locks, atomics, a background writer and threads, as the shared code uses them (step 1 of design/ios-plan.md).
 *
 * On Android and the JVM each is what the code used before it was shared (Threads.jvm.kt): a lock is a plain object
 * and synchronized is the JVM's monitor; the atomics, the latch and the writer are java.util.concurrent's. On the
 * iPhone the lock and the atomics are real (Threads.ios.kt); the writer, the latch and threads come in step 1b.
 */

/** What [synchronized] takes: on Android and the JVM a plain object (`Any()`), whose monitor is the lock. */
internal expect class Lock()

/** Runs [block] holding [lock]. Reentrant, like the JVM's synchronized, which it is on Android and the JVM. */
internal expect inline fun <T> synchronized(lock: Lock, block: () -> T): T

/** java.util.concurrent.atomic.AtomicBoolean on Android and the JVM. */
internal expect class AtomicBoolean(initialValue: Boolean)

internal expect fun AtomicBoolean.compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean
internal expect fun AtomicBoolean.set(newValue: Boolean)

/** java.util.concurrent.atomic.AtomicLong on Android and the JVM. */
internal expect class AtomicLong(initialValue: Long)

internal expect inline fun AtomicLong.updateAndGet(crossinline update: (Long) -> Long): Long

/** java.util.concurrent.atomic.AtomicInteger on Android and the JVM. */
internal expect class AtomicInteger(initialValue: Int)

internal expect fun AtomicInteger.incrementAndGet(): Int
internal expect fun AtomicInteger.decrementAndGet(): Int

/** java.util.concurrent.CountDownLatch on Android and the JVM. */
internal expect class CountDownLatch(count: Int)

internal expect fun CountDownLatch.countDown()

/** Waits up to [ms] milliseconds for the count to reach zero; false when it did not. */
internal expect fun CountDownLatch.awaitMillis(ms: Long): Boolean

/** Runs [block] on a new background (daemon) thread called [name]. */
internal expect fun startThread(name: String, block: () -> Unit)

/**
 * One background thread, called [threadName], that runs what it is given in order: on Android and the JVM a
 * single-thread ScheduledExecutorService whose thread is a daemon.
 */
internal expect class SerialWorker(threadName: String) {
    /** Runs [task] soon. Throws once [shutdownNow] has been called. */
    fun execute(task: () -> Unit)

    /** Runs [task] after [delayMs] milliseconds. Throws once [shutdownNow] has been called. */
    fun schedule(delayMs: Long, task: () -> Unit)

    /** Runs [task] and waits up to [timeoutMs] milliseconds for it to finish; throws when it failed or did not finish in time. */
    fun runAndWait(timeoutMs: Long, task: () -> Unit)

    /** Stops: what was waiting to run is dropped, and nothing more is taken. */
    fun shutdownNow()

    /** Waits up to [timeoutMs] milliseconds for what is running to end (an interrupted wait just ends). */
    fun awaitTermination(timeoutMs: Long)
}
