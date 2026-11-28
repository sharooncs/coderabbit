@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.strerror

// Small helpers for the iPhone's POSIX calls (files and sockets).

/** What the last failed POSIX call says, in its own words ("No such file or directory"). */
internal fun errnoText(code: Int = errno): String = strerror(code)?.toKString() ?: "error $code"

/** Runs [call] again for as long as it fails with EINTR (a signal arrived), as the JVM does around its system calls. */
internal inline fun <T : Comparable<T>> retryOnInterrupt(fail: T, call: () -> T): T {
    while (true) {
        val r = call()
        if (r == fail && errno == EINTR) continue
        return r
    }
}
