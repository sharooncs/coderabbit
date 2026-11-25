package app.housemove

import app.housemove.data.File
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.data.idSource
import app.housemove.data.mkdirs
import app.housemove.data.randomUuid
import app.housemove.data.updateCryptoStandIn
import app.housemove.sync.ServerSocket
import platform.Foundation.NSTemporaryDirectory

// The iPhone side of the common tests' needs (TestPlatform.kt).

internal actual fun newTempFolder(): File = File(File(NSTemporaryDirectory()), "hm-test-" + randomUuid()).also { it.mkdirs() }

/** The tests run one at a time, so the ids of a run come from its counter on every thread while it lasts. */
internal actual fun <T> withIds(next: () -> String, block: () -> T): T {
    val before = idSource
    idSource = next
    try {
        return block()
    } finally {
        idSource = before
    }
}

internal actual fun loopbackServer(): ServerSocket = ServerSocket(0, loopbackOnly = true)

private class BytesInput(private val b: ByteArray) : InputStream() {
    private var pos = 0

    override fun readBytes(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos == this.b.size) return -1
        val n = minOf(len, this.b.size - pos)
        this.b.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }
}

internal actual fun bytesIn(b: ByteArray): InputStream = BytesInput(b)

internal actual class BytesOut actual constructor() {
    private var buf = ByteArray(1024)
    private var count = 0

    actual val stream: OutputStream = object : OutputStream() {
        override fun writeBytes(b: ByteArray, off: Int, len: Int) {
            while (count + len > buf.size) buf = buf.copyOf(buf.size * 2)
            b.copyInto(buf, count, off, off + len)
            count += len
        }
    }

    actual fun bytes(): ByteArray = buf.copyOf(count)
}

/** The iPhone has no update-file crypto: the phones of a run say their key text as it is in their folders ([KeyTextOnly]). */
internal actual fun useKeyTextIfNoCrypto(): () -> Unit {
    updateCryptoStandIn = KeyTextOnly
    return { updateCryptoStandIn = null }
}
