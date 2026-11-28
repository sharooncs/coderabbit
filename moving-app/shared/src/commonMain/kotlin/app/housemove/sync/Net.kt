package app.housemove.sync

import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.InputStream
import app.housemove.data.OutputStream

/*
 * The network as the phone-to-phone sync uses it (step 1 of design/ios-plan.md): blocking TCP sockets with timeouts,
 * and the strings and numbers of the sync wire written as java.io.DataOutputStream writes them.
 *
 * On Android and the JVM these are java.net's and java.io's own classes (typealiases), and each function is the call
 * WifiSync made before it was shared (Net.jvm.kt). The iPhone's sockets are POSIX ones with the same behaviour, its
 * addresses come from getifaddrs (Addresses.kt picks them), and its DataInputStream and DataOutputStream are the
 * common ones in Wire.kt (Net.ios.kt).
 */

/** A listening TCP socket: java.net.ServerSocket on Android and the JVM. */
internal expect class ServerSocket(port: Int)

/**
 * A listening socket on a free port: on every address of the phone, or with [loopbackOnly] on 127.0.0.1 alone (tests,
 * and the iPhone's demo mode). Syncing by itself listens on one of these (design/auto-sync.md).
 */
internal expect fun listeningSocket(loopbackOnly: Boolean): ServerSocket

internal expect val ServerSocket.localPort: Int

/** The next phone that connects. Throws IOException once the socket has been closed. */
internal expect fun ServerSocket.accept(): Socket

internal expect fun ServerSocket.close()

/** A TCP connection: java.net.Socket on Android and the JVM. */
internal expect class Socket() : AutoCloseable

/** How long a read waits, in milliseconds, before it throws SocketTimeoutException. */
internal expect var Socket.soTimeout: Int
internal expect var Socket.tcpNoDelay: Boolean

/** Connect to [host]:[port], waiting up to [timeoutMs] milliseconds. */
internal expect fun Socket.connect(host: String, port: Int, timeoutMs: Int)

/** The bytes this socket receives, buffered, read as DataInputStream reads them. */
internal expect fun Socket.dataIn(): DataInputStream

/** The same, unbuffered: only one answer is read, as version 1.3 is asked. */
internal expect fun Socket.dataInUnbuffered(): DataInputStream

/** The bytes this socket sends, buffered: nothing goes until [flush]. */
internal expect fun Socket.dataOut(): DataOutputStream

/** [input] read as DataInputStream reads (the plain side of an encrypted channel, [SecureChannel]). */
internal expect fun dataInputOf(input: InputStream): DataInputStream

/** [out] written as DataOutputStream writes (the plain side of an encrypted channel): it sends when it is flushed. */
internal expect fun dataOutputOf(out: OutputStream): DataOutputStream

/** java.io.DataInputStream on Android and the JVM. */
internal expect class DataInputStream

/** A string as writeUTF wrote it (modified UTF-8 after a two-byte length). */
internal expect fun DataInputStream.readUTF(): String
internal expect fun DataInputStream.readInt(): Int
internal expect fun DataInputStream.readLong(): Long
internal expect fun DataInputStream.readUnsignedShort(): Int
internal expect fun DataInputStream.readFully(b: ByteArray)

/** Up to [len] bytes into [b] from [off]: how many were read, or -1 at the end. */
internal expect fun DataInputStream.read(b: ByteArray, off: Int, len: Int): Int

/** java.io.DataOutputStream on Android and the JVM. */
internal expect class DataOutputStream

internal expect fun DataOutputStream.writeUTF(s: String)
internal expect fun DataOutputStream.writeInt(v: Int)
internal expect fun DataOutputStream.writeLong(v: Long)
internal expect fun DataOutputStream.write(b: ByteArray)

/** The bytes of [file], as they are. */
internal expect fun DataOutputStream.writeFile(file: File)
internal expect fun DataOutputStream.flush()

/** java.net.SocketException on Android and the JVM: the connection failed or was closed. */
internal expect open class SocketException : IOException

/** java.net.SocketTimeoutException on Android and the JVM: nothing came within the socket's timeout. */
internal expect open class SocketTimeoutException : IOException

/** This phone's IPv4 addresses that other phones on the same network can reach (site-local, on interfaces that are up). */
internal expect fun siteLocalAddresses(): List<String>
