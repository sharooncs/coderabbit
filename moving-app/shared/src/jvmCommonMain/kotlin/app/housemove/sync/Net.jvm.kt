// Member calls of java.net's and java.io's classes inside these extensions are meant: each is the call WifiSync made before.
@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package app.housemove.sync

import app.housemove.data.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

// Android and the JVM: java.net and java.io themselves, so the sync writes and reads exactly what it did.

internal actual typealias ServerSocket = java.net.ServerSocket

internal actual fun listeningSocket(loopbackOnly: Boolean): ServerSocket =
    if (loopbackOnly) java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")) else java.net.ServerSocket(0)

internal actual val ServerSocket.localPort: Int get() = getLocalPort()
internal actual fun ServerSocket.accept(): Socket = accept()
internal actual fun ServerSocket.close() = close()

internal actual typealias Socket = java.net.Socket

internal actual var Socket.soTimeout: Int
    get() = getSoTimeout()
    set(value) = setSoTimeout(value)

internal actual var Socket.tcpNoDelay: Boolean
    get() = getTcpNoDelay()
    set(value) = setTcpNoDelay(value)

internal actual fun Socket.connect(host: String, port: Int, timeoutMs: Int) = connect(InetSocketAddress(host, port), timeoutMs)

internal actual fun Socket.dataIn(): DataInputStream = DataInputStream(getInputStream().buffered())

internal actual fun Socket.dataInUnbuffered(): DataInputStream = DataInputStream(getInputStream())

internal actual fun Socket.dataOut(): DataOutputStream = DataOutputStream(getOutputStream().buffered())

internal actual fun dataInputOf(input: app.housemove.data.InputStream): DataInputStream = DataInputStream(input)

internal actual fun dataOutputOf(out: app.housemove.data.OutputStream): DataOutputStream = DataOutputStream(out)

internal actual typealias DataInputStream = java.io.DataInputStream

internal actual fun DataInputStream.readUTF(): String = readUTF()
internal actual fun DataInputStream.readInt(): Int = readInt()
internal actual fun DataInputStream.readLong(): Long = readLong()
internal actual fun DataInputStream.readUnsignedShort(): Int = readUnsignedShort()
internal actual fun DataInputStream.readFully(b: ByteArray) = readFully(b)
internal actual fun DataInputStream.read(b: ByteArray, off: Int, len: Int): Int = read(b, off, len)

internal actual typealias DataOutputStream = java.io.DataOutputStream

internal actual fun DataOutputStream.writeUTF(s: String) = writeUTF(s)
internal actual fun DataOutputStream.writeInt(v: Int) = writeInt(v)
internal actual fun DataOutputStream.writeLong(v: Long) = writeLong(v)
internal actual fun DataOutputStream.write(b: ByteArray) = write(b)

internal actual fun DataOutputStream.writeFile(file: File) {
    file.inputStream().use { it.copyTo(this) }
}

internal actual fun DataOutputStream.flush() = flush()

internal actual typealias SocketException = java.net.SocketException
internal actual typealias SocketTimeoutException = java.net.SocketTimeoutException

internal actual fun siteLocalAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress ?: "" }
        .filter { it.isNotBlank() }
