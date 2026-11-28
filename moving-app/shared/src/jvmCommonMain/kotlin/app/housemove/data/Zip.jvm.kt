package app.housemove.data

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

// Android and the JVM: the common zip writer and reader (Zip.kt) are not what the app uses here (it keeps
// java.util.zip's streams, Files.jvm.kt); these let them run on the JVM, where the tests check them against
// java.util.zip. They are Deflater and Inflater as ZipOutputStream and ZipInputStream make them.

internal actual class RawDeflater actual constructor() {
    private val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    private val buf = ByteArray(8192)

    actual fun deflate(b: ByteArray, off: Int, len: Int, out: (ByteArray, Int) -> Unit) {
        def.setInput(b, off, len)
        while (!def.needsInput()) {
            val n = def.deflate(buf, 0, buf.size)
            if (n > 0) out(buf, n)
        }
    }

    actual fun finish(out: (ByteArray, Int) -> Unit) {
        def.finish()
        while (!def.finished()) {
            val n = def.deflate(buf, 0, buf.size)
            if (n > 0) out(buf, n)
        }
    }

    actual val bytesRead: Long get() = def.bytesRead
    actual val bytesWritten: Long get() = def.bytesWritten

    actual fun reset() = def.reset()

    actual fun end() = def.end()
}

internal actual class RawInflater actual constructor() {
    private val inf = Inflater(true)

    actual fun setInput(b: ByteArray, off: Int, len: Int) = inf.setInput(b, off, len)

    actual fun inflate(b: ByteArray, off: Int, len: Int): Int = try {
        inf.inflate(b, off, len)
    } catch (e: DataFormatException) {
        throw ZipFormatException(e.message ?: "Invalid ZLIB data format")
    }

    actual val finished: Boolean get() = inf.finished() || inf.needsDictionary()
    actual val needsInput: Boolean get() = inf.needsInput()
    actual val remaining: Int get() = inf.remaining
    actual val bytesRead: Long get() = inf.bytesRead
    actual val bytesWritten: Long get() = inf.bytesWritten

    actual fun reset() = inf.reset()

    actual fun end() = inf.end()
}
