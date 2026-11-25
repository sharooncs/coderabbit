@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package app.housemove.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.posix.memset
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NEED_DICT
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.deflateReset
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.inflateReset
import platform.zlib.z_stream
import platform.zlib.zlibVersion
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

// The iPhone's DEFLATE: the system zlib (platform.zlib), raw (windowBits -15), level and memory as java.util.zip's
// Deflater and Inflater ask for them (Z_DEFAULT_COMPRESSION, memLevel 8, Z_DEFAULT_STRATEGY).

/** The time an entry made now gets in a zip (see [dosTime]): the phone's clock, in its time zone. */
internal fun dosTimeNow(): Long {
    val now = NSDate()
    val ms = (now.timeIntervalSince1970 * 1000.0).toLong()
    return dosTime(ms, NSTimeZone.localTimeZone.secondsFromGMTForDate(now) * 1000L)
}

/** A zlib stream, freed once: by [free], or when nothing holds it any more. */
private class ZStream(val deflating: Boolean) {
    val strm: z_stream = nativeHeap.alloc<z_stream>().also { memset(it.ptr, 0, sizeOf<z_stream>().convert()) }
    var open = false
    private var freed = false

    fun free() {
        if (freed) return
        if (open) {
            if (deflating) deflateEnd(strm.ptr) else inflateEnd(strm.ptr)
            open = false
        }
        nativeHeap.free(strm)
        freed = true
    }
}

private fun zlibError(what: String, code: Int, s: z_stream): Nothing =
    throw ZipFormatException(s.msg?.toKString() ?: "$what failed ($code)")

internal actual class RawDeflater actual constructor() {
    private val z = ZStream(deflating = true)

    @Suppress("unused")
    private val cleaner = createCleaner(z) { it.free() }
    private val chunk = ByteArray(8192)

    init {
        val r = deflateInit2_(z.strm.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt())
        if (r != Z_OK) zlibError("deflateInit2", r, z.strm)
        z.open = true
    }

    /** One call of zlib's deflate with [flush], its output to [out]; zlib's answer. */
    private fun step(flush: Int, out: (ByteArray, Int) -> Unit): Int {
        val s = z.strm
        val r = chunk.usePinned { o ->
            s.next_out = o.addressOf(0).reinterpret()
            s.avail_out = chunk.size.convert()
            deflate(s.ptr, flush)
        }
        val n = chunk.size - s.avail_out.toInt()
        s.next_out = null
        if (r != Z_OK && r != Z_STREAM_END && r != Z_BUF_ERROR) zlibError("deflate", r, s)
        if (n > 0) out(chunk, n)
        return r
    }

    actual fun deflate(b: ByteArray, off: Int, len: Int, out: (ByteArray, Int) -> Unit) {
        checkRange(b, off, len)
        if (len == 0) return
        val s = z.strm
        b.usePinned { p ->
            s.next_in = p.addressOf(off).reinterpret()
            s.avail_in = len.convert()
            try {
                // As DeflaterOutputStream.write: until zlib has taken all the input.
                while (s.avail_in > 0u) step(Z_NO_FLUSH, out)
            } finally {
                s.next_in = null
                s.avail_in = 0u
            }
        }
    }

    actual fun finish(out: (ByteArray, Int) -> Unit) {
        val s = z.strm
        s.next_in = null
        s.avail_in = 0u
        while (step(Z_FINISH, out) != Z_STREAM_END) {
            // until the stream has ended
        }
    }

    actual val bytesRead: Long get() = z.strm.total_in.toLong()
    actual val bytesWritten: Long get() = z.strm.total_out.toLong()

    actual fun reset() {
        deflateReset(z.strm.ptr)
    }

    actual fun end() = z.free()
}

internal actual class RawInflater actual constructor() {
    private val z = ZStream(deflating = false)

    @Suppress("unused")
    private val cleaner = createCleaner(z) { it.free() }

    // The input given and not yet taken, kept as java.util.zip.Inflater keeps it: the array and where it is in it.
    private var input = ByteArray(0)
    private var inputPos = 0
    private var inputLim = 0
    private var done = false
    private var needDict = false

    init {
        val r = inflateInit2_(z.strm.ptr, -15, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt())
        if (r != Z_OK) zlibError("inflateInit2", r, z.strm)
        z.open = true
    }

    actual fun setInput(b: ByteArray, off: Int, len: Int) {
        checkRange(b, off, len)
        input = b
        inputPos = off
        inputLim = off + len
    }

    actual fun inflate(b: ByteArray, off: Int, len: Int): Int {
        checkRange(b, off, len)
        if (len == 0 || done || needDict) return 0
        val s = z.strm
        val avail = inputLim - inputPos
        val r = b.usePinned { o ->
            s.next_out = o.addressOf(off).reinterpret()
            s.avail_out = len.convert()
            if (avail > 0) {
                input.usePinned { i ->
                    s.next_in = i.addressOf(inputPos).reinterpret()
                    s.avail_in = avail.convert()
                    inflate(s.ptr, Z_NO_FLUSH)
                }
            } else {
                s.next_in = null
                s.avail_in = 0u
                inflate(s.ptr, Z_NO_FLUSH)
            }
        }
        val taken = avail - s.avail_in.toInt()
        val made = len - s.avail_out.toInt()
        s.next_in = null
        s.avail_in = 0u
        s.next_out = null
        inputPos += taken
        when (r) {
            Z_STREAM_END -> done = true
            Z_OK, Z_BUF_ERROR -> Unit
            Z_NEED_DICT -> needDict = true
            else -> zlibError("inflate", r, s)
        }
        return made
    }

    actual val finished: Boolean get() = done || needDict
    actual val needsInput: Boolean get() = inputPos == inputLim
    actual val remaining: Int get() = inputLim - inputPos
    actual val bytesRead: Long get() = z.strm.total_in.toLong()
    actual val bytesWritten: Long get() = z.strm.total_out.toLong()

    actual fun reset() {
        inflateReset(z.strm.ptr)
        input = ByteArray(0)
        inputPos = 0
        inputLim = 0
        done = false
        needDict = false
    }

    actual fun end() = z.free()
}
