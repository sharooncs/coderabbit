package app.housemove

import app.housemove.data.CountDownLatch
import app.housemove.data.File
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.data.awaitMillis
import app.housemove.data.deleteRecursively
import app.housemove.sync.ServerSocket

/*
 * What the common tests need of the platform they run on (jvmTest and iosTest give it): temporary folders, bytes in
 * memory as streams, a listening socket on 127.0.0.1 only, and ids from a counter for one run's threads.
 */

/** A new, empty folder for one test, in the platform's temporary folder. */
internal expect fun newTempFolder(): File

/** Runs [block] with newId() giving [next]'s ids, on this thread and on every thread started meanwhile. */
internal expect fun <T> withIds(next: () -> String, block: () -> T): T

/** A socket listening on 127.0.0.1 only, on a free port. */
internal expect fun loopbackServer(): ServerSocket

/** [b] as an input stream. */
internal expect fun bytesIn(b: ByteArray): InputStream

/** An output stream into memory. */
internal expect class BytesOut() {
    val stream: OutputStream

    fun bytes(): ByteArray
}

/**
 * On a platform whose crypto the app registers at start-up (the iPhone: CryptoKit, which Kotlin tests cannot call), puts in
 * the reference crypto ([ReferenceCrypto]) for this run, so its phones have key pairs and read and write update files as
 * JVM phones do. Elsewhere (the JCA) does nothing. Undone by the function it returns.
 */
internal expect fun useTestCryptoIfNone(): () -> Unit

/** Waits [ms] milliseconds. */
internal fun sleepMillis(ms: Long) {
    CountDownLatch(1).awaitMillis(ms)
}

/** A folder for [block], deleted afterwards. */
internal inline fun inTempFolder(block: (File) -> Unit) {
    val dir = newTempFolder()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
