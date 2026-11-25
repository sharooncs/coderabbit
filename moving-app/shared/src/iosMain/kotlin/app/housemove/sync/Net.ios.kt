package app.housemove.sync

import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.data.inputStream
import app.housemove.data.write

// The iPhone: the strings and numbers of the sync wire are the common ones in Wire.kt, already real. The sockets
// (POSIX, non-blocking connect with a timeout, SO_RCVTIMEO, TCP_NODELAY) and the phone's addresses (getifaddrs,
// leaving out cellular and VPN interfaces) come in step 1b, and throw until then.

private fun notYet(): Nothing = throw NotImplementedError("step 1b")

internal actual class ServerSocket actual constructor(port: Int) {
    init {
        notYet()
    }
}

internal actual val ServerSocket.localPort: Int get() = notYet()
internal actual fun ServerSocket.accept(): Socket = notYet()
internal actual fun ServerSocket.close(): Unit = notYet()

internal actual class Socket actual constructor() : AutoCloseable {
    init {
        notYet()
    }

    override fun close(): Unit = notYet()
}

internal actual var Socket.soTimeout: Int
    get() = notYet()
    set(@Suppress("UNUSED_PARAMETER") value) = notYet()

internal actual var Socket.tcpNoDelay: Boolean
    get() = notYet()
    set(@Suppress("UNUSED_PARAMETER") value) = notYet()

internal actual fun Socket.connect(host: String, port: Int, timeoutMs: Int): Unit = notYet()
internal actual fun Socket.dataIn(): DataInputStream = notYet()
internal actual fun Socket.dataInUnbuffered(): DataInputStream = notYet()
internal actual fun Socket.dataOut(): DataOutputStream = notYet()

/** Reads [input] as java.io.DataInputStream does (Wire.kt). */
internal actual class DataInputStream(input: InputStream) {
    val wire = WireReader { b, off, len -> input.readBytes(b, off, len) }
}

internal actual fun DataInputStream.readUTF(): String = wire.readUTF()
internal actual fun DataInputStream.readInt(): Int = wire.readInt()
internal actual fun DataInputStream.readLong(): Long = wire.readLong()
internal actual fun DataInputStream.readUnsignedShort(): Int = wire.readUnsignedShort()
internal actual fun DataInputStream.readFully(b: ByteArray) = wire.readFully(b)
internal actual fun DataInputStream.read(b: ByteArray, off: Int, len: Int): Int = wire.read(b, off, len)

/** Writes to [out] as java.io.DataOutputStream does (Wire.kt). */
internal actual class DataOutputStream(private val out: OutputStream) {
    val wire = WireWriter { b -> out.write(b, 0, b.size) }

    fun flushOut() = out.flush()
}

internal actual fun DataOutputStream.writeUTF(s: String) = wire.writeUTF(s)
internal actual fun DataOutputStream.writeInt(v: Int) = wire.writeInt(v)
internal actual fun DataOutputStream.writeLong(v: Long) = wire.writeLong(v)
internal actual fun DataOutputStream.write(b: ByteArray) = wire.write(b)

internal actual fun DataOutputStream.writeFile(file: File) {
    val buf = ByteArray(8192)
    file.inputStream().use { input ->
        while (true) {
            val n = input.readBytes(buf, 0, buf.size)
            if (n < 0) break
            wire.write(buf.copyOf(n))
        }
    }
}

internal actual fun DataOutputStream.flush() = flushOut()

internal actual open class SocketException(message: String? = null) : IOException(message)

internal actual open class SocketTimeoutException(message: String? = null) : IOException(message)

internal actual fun siteLocalAddresses(): List<String> = notYet()
