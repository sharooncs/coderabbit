@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.ui

import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.OutputStream
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite
import kotlinx.cinterop.CPointer

// The iPhone: File is the shared module's own (a path), and these are POSIX and Foundation calls.

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
actual val File.path: String get() = this.path

actual fun File.delete(): Boolean = NSFileManager.defaultManager.removeItemAtPath(path, error = null)

actual fun File.outputStream(): OutputStream {
    val f = fopen(path, "wb") ?: throw IOException("could not write $path")
    return PosixOutput(f, path)
}

private class PosixOutput(private var f: CPointer<FILE>?, private val path: String) : OutputStream() {
    override fun writeBytes(b: ByteArray, off: Int, len: Int) {
        val file = f ?: throw IOException("$path is closed")
        if (len == 0) return
        val written = b.usePinned { fwrite(it.addressOf(off), 1u, len.toULong(), file) }
        if (written.toLong() != len.toLong()) throw IOException("could not write $path")
    }

    override fun flush() {
        f?.let { fflush(it) }
    }

    override fun close() {
        f?.let { fclose(it) }
        f = null
    }
}
