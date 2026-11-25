@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.sync

import app.housemove.data.BufferedInput
import app.housemove.data.BufferedOutput
import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.InputStream
import app.housemove.data.Lock
import app.housemove.data.OutputStream
import app.housemove.data.checkRange
import app.housemove.data.errnoText
import app.housemove.data.inputStream
import app.housemove.data.synchronized
import app.housemove.data.write
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_pton
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.ECONNABORTED
import platform.posix.ECONNRESET
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EPIPE
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_RUNNING
import platform.posix.IFF_UP
import platform.posix.IPPROTO_TCP
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.TCP_NODELAY
import platform.posix.timeval
import kotlin.time.TimeSource

// The iPhone's sockets (step 1b of design/ios-plan.md): POSIX, blocking, IPv4, with the behaviour of the java.net
// sockets the sync was written against (Net.jvm.kt is those sockets themselves):
//
// - every socket has SO_NOSIGPIPE, so writing to a phone that has gone is a SocketException and not a signal that ends
//   the app; TCP_NODELAY as WifiSync asks for it;
// - a read waits at most soTimeout milliseconds (SO_RCVTIMEO, as Android sets SO_TIMEOUT) and then throws
//   SocketTimeoutException; a connect waits at most its timeout (a non-blocking connect and poll), then throws it too;
// - the end of the stream reads as -1, so the common reader (Wire.kt) throws EOFException; a reset or a broken pipe is
//   a SocketException; a connection refused or with no route is a ConnectException (a SocketException);
// - a hosting phone's listening socket has SO_REUSEADDR, as Java's has; closing it ends a wait in accept with a
//   SocketException (a pipe wakes the poll the wait is in);
// - close is safe from another thread: a socket in use there is shut down first, and its descriptor is let go when
//   the last call using it returns, so it is never used after it has been closed.

/** java.net.ConnectException: nobody answered at that address and port, or there is no way there. */
internal open class ConnectException(message: String? = null) : SocketException(message)

/** java.net.UnknownHostException: a host name that is not an address and could not be looked up. */
internal open class UnknownHostException(message: String? = null) : IOException(message)

private fun setInt(fd: Int, level: Int, option: Int, value: Int): Int = memScoped {
    val v = alloc<IntVar>()
    v.value = value
    setsockopt(fd, level, option, v.ptr, sizeOf<IntVar>().convert())
}

private fun setBlocking(fd: Int, blocking: Boolean) {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return
    fcntl(fd, F_SETFL, if (blocking) flags and O_NONBLOCK.inv() else flags or O_NONBLOCK)
}

// A sockaddr_in on Apple's systems: sin_len (1 byte), sin_family (1), sin_port (2, most significant first),
// sin_addr (4, in the order they are written), sin_zero (8).
private const val PORT_AT = 2
private const val ADDRESS_AT = 4

private fun sockaddr_in.setPort(port: Int) {
    val p = ptr.reinterpret<ByteVar>()
    p[PORT_AT] = (port ushr 8).toByte()
    p[PORT_AT + 1] = port.toByte()
}

private fun sockaddr_in.port(): Int {
    val p = ptr.reinterpret<ByteVar>()
    return ((p[PORT_AT].toInt() and 0xFF) shl 8) or (p[PORT_AT + 1].toInt() and 0xFF)
}

/** The address's four bytes, in the order dotted text writes them. */
private fun sockaddr_in.addressBytes(): ByteArray {
    val p = ptr.reinterpret<ByteVar>()
    return ByteArray(4) { p[ADDRESS_AT + it] }
}

/** [host] (dotted IPv4, or a name such as "localhost") and [port] as an IPv4 socket address in [scope]. */
private fun address(scope: MemScope, host: String, port: Int): sockaddr_in {
    val a = scope.alloc<sockaddr_in>()
    a.sin_len = sizeOf<sockaddr_in>().convert()
    a.sin_family = AF_INET.convert()
    a.setPort(port)
    if (inet_pton(AF_INET, host, a.sin_addr.ptr) == 1) return a
    val hints = scope.alloc<addrinfo>()
    hints.ai_family = AF_INET
    hints.ai_socktype = SOCK_STREAM
    val res = scope.alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, null, hints.ptr, res.ptr) != 0) throw UnknownHostException(host)
    try {
        val found = res.value?.pointed?.ai_addr?.reinterpret<sockaddr_in>()?.pointed ?: throw UnknownHostException(host)
        a.sin_addr.s_addr = found.sin_addr.s_addr
    } finally {
        freeaddrinfo(res.value)
    }
    return a
}

/** A socket listening on [port], SO_REUSEADDR and non-blocking (accept is only called once poll says a phone is there, and must not wait then if it has gone). */
private fun listening(port: Int, loopbackOnly: Boolean): Int {
    val s = socket(AF_INET, SOCK_STREAM, 0)
    if (s < 0) throw SocketException(errnoText())
    try {
        setInt(s, SOL_SOCKET, SO_REUSEADDR, 1)
        setInt(s, SOL_SOCKET, SO_NOSIGPIPE, 1)
        memScoped {
            val a = address(this, if (loopbackOnly) "127.0.0.1" else "0.0.0.0", port)
            if (bind(s, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) throw SocketException("bind: ${errnoText()}")
        }
        if (listen(s, 50) != 0) throw SocketException("listen: ${errnoText()}")
        setBlocking(s, false)
    } catch (e: Throwable) {
        platform.posix.close(s)
        throw e
    }
    return s
}

private fun localPortOf(fd: Int): Int = memScoped {
    val a = alloc<sockaddr_in>()
    val len = alloc<UIntVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    if (getsockname(fd, a.ptr.reinterpret(), len.ptr) != 0) {
        val e = errnoText()
        platform.posix.close(fd)
        throw SocketException(e)
    }
    a.port()
}

/** A listening TCP socket on [port] (0: any free one), on every IPv4 address of the phone or, with [loopbackOnly], on 127.0.0.1 alone. */
internal actual class ServerSocket(port: Int, loopbackOnly: Boolean) {
    actual constructor(port: Int) : this(port, false)

    private val fd: Int = listening(port, loopbackOnly)
    val port: Int = localPortOf(fd)
    private val wakeRead: Int
    private val wakeWrite: Int
    private val lock = Lock()
    private var closed = false
    private var waiting = 0
    private var wakeClosed = false

    init {
        val fds = IntArray(2)
        val r = fds.usePinned { pipe(it.addressOf(0)) }
        if (r != 0) {
            platform.posix.close(fd)
            throw SocketException(errnoText())
        }
        wakeRead = fds[0]
        wakeWrite = fds[1]
    }

    private fun closeWake() {
        if (wakeClosed) return
        wakeClosed = true
        platform.posix.close(wakeRead)
        platform.posix.close(wakeWrite)
    }

    /** The next phone that connects; a SocketException once this has been closed, even while waiting. */
    fun acceptNext(): Socket {
        synchronized(lock) {
            if (closed) throw SocketException("Socket is closed")
            waiting++
        }
        try {
            while (true) {
                val ready = memScoped {
                    val fds = allocArray<pollfd>(2)
                    fds[0].fd = fd
                    fds[0].events = POLLIN.toShort()
                    fds[1].fd = wakeRead
                    fds[1].events = POLLIN.toShort()
                    var r: Int
                    do {
                        r = poll(fds, 2u, -1)
                    } while (r < 0 && errno == EINTR)
                    r
                }
                val taken = synchronized(lock) {
                    if (closed) throw SocketException("Socket closed")
                    if (ready < 0) throw SocketException(errnoText())
                    val c = accept(fd, null, null)
                    if (c < 0) {
                        val e = errno
                        // Gone again before it was taken, or nothing there yet: wait for the next.
                        if (e != EAGAIN && e != EWOULDBLOCK && e != ECONNABORTED && e != EINTR) throw SocketException(errnoText(e))
                    }
                    c
                }
                if (taken >= 0) {
                    // An accepted socket takes O_NONBLOCK from the listening one on Apple's systems: it is to block.
                    setBlocking(taken, true)
                    setInt(taken, SOL_SOCKET, SO_NOSIGPIPE, 1)
                    return Socket(taken)
                }
            }
        } finally {
            synchronized(lock) {
                waiting--
                if (closed && waiting == 0) closeWake()
            }
        }
    }

    fun closeSocket() {
        synchronized(lock) {
            if (closed) return
            closed = true
            platform.posix.close(fd)
            if (waiting > 0) {
                val one = byteArrayOf(1)
                one.usePinned { platform.posix.write(wakeWrite, it.addressOf(0), 1u) }
            } else {
                closeWake()
            }
        }
    }
}

internal actual val ServerSocket.localPort: Int get() = port
internal actual fun ServerSocket.accept(): Socket = acceptNext()
internal actual fun ServerSocket.close() = closeSocket()

/** A TCP connection: made by [connect], or by a [ServerSocket] for a phone that came. */
internal actual class Socket private constructor(private var fd: Int, @Suppress("UNUSED_PARAMETER") accepted: Unit) : AutoCloseable {
    actual constructor() : this(-1, Unit)

    internal constructor(fd: Int) : this(fd, Unit)

    private val lock = Lock()
    private var closed = false
    private var users = 0
    private var fdClosed = false
    var timeoutMs = 0
        set(value) {
            require(value >= 0) { "timeout can't be negative" }
            field = value
            if (fd >= 0) applyTimeout()
        }
    var noDelay = false
        set(value) {
            field = value
            if (fd >= 0) setInt(fd, IPPROTO_TCP, TCP_NODELAY, if (value) 1 else 0)
        }

    /** What comes in, unbuffered. */
    val input: InputStream = object : InputStream() {
        override fun readBytes(b: ByteArray, off: Int, len: Int): Int = receive(b, off, len)
    }

    /** What goes out, unbuffered. */
    val output: OutputStream = object : OutputStream() {
        override fun writeBytes(b: ByteArray, off: Int, len: Int) = sendAll(b, off, len)
    }

    private fun applyTimeout() = memScoped {
        val tv = alloc<timeval>()
        tv.tv_sec = (timeoutMs / 1000).convert()
        tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    }

    /** The descriptor, held for one call: it is not let go before [release]. */
    private fun claim(): Int = synchronized(lock) {
        if (closed) throw SocketException("Socket closed")
        if (fd < 0) throw SocketException("Socket is not connected")
        users++
        fd
    }

    private fun release() = synchronized(lock) {
        users--
        if (closed && users == 0) closeFd()
    }

    private fun closeFd() {
        if (fdClosed || fd < 0) return
        fdClosed = true
        platform.posix.close(fd)
    }

    fun connectTo(host: String, port: Int, timeout: Int) {
        synchronized(lock) {
            if (closed) throw SocketException("Socket is closed")
            if (fd >= 0) throw SocketException("already connected")
        }
        require(timeout >= 0) { "connect: timeout can't be negative" }
        val s = socket(AF_INET, SOCK_STREAM, 0)
        if (s < 0) throw SocketException(errnoText())
        try {
            setInt(s, SOL_SOCKET, SO_NOSIGPIPE, 1)
            setBlocking(s, false)
            memScoped {
                val a = address(this, host, port)
                if (connect(s, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                    val e = errno
                    if (e != EINPROGRESS && e != EINTR) throw connectError(e)
                    waitConnected(s, timeout)
                }
            }
            setBlocking(s, true)
        } catch (e: Throwable) {
            platform.posix.close(s)
            // As java.net.Socket: a socket whose connect failed is closed.
            synchronized(lock) { closed = true }
            throw e
        }
        val ok = synchronized(lock) {
            if (closed) false else {
                fd = s
                true
            }
        }
        if (!ok) {
            platform.posix.close(s)
            throw SocketException("Socket closed")
        }
        if (timeoutMs > 0) applyTimeout()
        if (noDelay) setInt(fd, IPPROTO_TCP, TCP_NODELAY, 1)
    }

    /** Waits for a non-blocking connect on [s] to finish, at most [timeout] milliseconds (0: as long as it takes). */
    private fun waitConnected(s: Int, timeout: Int) = memScoped {
        val p = alloc<pollfd>()
        p.fd = s
        p.events = POLLOUT.toShort()
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val left = if (timeout == 0) -1 else (timeout - start.elapsedNow().inWholeMilliseconds).toInt()
            if (timeout != 0 && left <= 0) throw SocketTimeoutException("Connect timed out")
            val r = poll(p.ptr, 1u, left)
            if (r < 0) {
                if (errno == EINTR) continue
                throw SocketException(errnoText())
            }
            if (r == 0) throw SocketTimeoutException("Connect timed out")
            val err = alloc<IntVar>()
            val len = alloc<UIntVar>()
            len.value = sizeOf<IntVar>().convert()
            if (getsockopt(s, SOL_SOCKET, SO_ERROR, err.ptr, len.ptr) != 0) throw SocketException(errnoText())
            if (err.value != 0) throw connectError(err.value)
            return@memScoped
        }
    }

    /** As java.net: "Connection refused", "No route to host" and the like, in the system's words. */
    private fun connectError(e: Int): IOException = ConnectException(errnoText(e))

    private fun receive(b: ByteArray, off: Int, len: Int): Int {
        checkRange(b, off, len)
        if (len == 0) return 0
        val f = claim()
        try {
            var e = 0
            var n: Long
            do {
                n = b.usePinned { recv(f, it.addressOf(off), len.convert(), 0) }
                if (n < 0) e = errno
            } while (n < 0 && e == EINTR)
            if (n > 0) return n.toInt()
            if (synchronized(lock) { closed }) throw SocketException("Socket closed")
            if (n == 0L) return -1
            throw when (e) {
                EAGAIN, EWOULDBLOCK -> SocketTimeoutException("Read timed out")
                ECONNRESET -> SocketException("Connection reset")
                else -> SocketException(errnoText(e))
            }
        } finally {
            release()
        }
    }

    private fun sendAll(b: ByteArray, off: Int, len: Int) {
        checkRange(b, off, len)
        if (len == 0) return
        val f = claim()
        try {
            var done = 0
            while (done < len) {
                var e = 0
                val n = b.usePinned { send(f, it.addressOf(off + done), (len - done).convert(), 0) }
                if (n < 0) e = errno
                if (n < 0 && e == EINTR) continue
                if (n < 0) {
                    if (synchronized(lock) { closed }) throw SocketException("Socket closed")
                    throw when (e) {
                        EPIPE -> SocketException("Broken pipe")
                        ECONNRESET -> SocketException("Connection reset by peer")
                        else -> SocketException(errnoText(e))
                    }
                }
                done += n.toInt()
            }
        } finally {
            release()
        }
    }

    /** Closes the connection. A read or write going on in another thread ends with a SocketException. Closing twice does nothing. */
    override fun close() {
        synchronized(lock) {
            if (closed) {
                if (users == 0) closeFd()
                return
            }
            closed = true
            if (fd < 0) return
            if (users > 0) shutdown(fd, SHUT_RDWR) else closeFd()
        }
    }
}

internal actual var Socket.soTimeout: Int
    get() = timeoutMs
    set(value) {
        timeoutMs = value
    }

internal actual var Socket.tcpNoDelay: Boolean
    get() = noDelay
    set(value) {
        noDelay = value
    }

internal actual fun Socket.connect(host: String, port: Int, timeoutMs: Int) = connectTo(host, port, timeoutMs)

/** Through an 8 KB buffer, as BufferedInputStream reads for the JVM's DataInputStream. */
internal actual fun Socket.dataIn(): DataInputStream = DataInputStream(BufferedInput(input))

internal actual fun Socket.dataInUnbuffered(): DataInputStream = DataInputStream(input)

/** Through an 8 KB buffer, as BufferedOutputStream writes for the JVM's DataOutputStream: nothing goes until it is full or flushed. */
internal actual fun Socket.dataOut(): DataOutputStream = DataOutputStream(BufferedOutput(output))

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

/** The file's bytes in pieces of 8 KB, as InputStream.copyTo gives them to the JVM's DataOutputStream. */
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

/** Every address of every interface, as getifaddrs lists them. */
internal fun interfaceAddresses(): List<InterfaceAddress> = memScoped {
    val head = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(head.ptr) != 0) throw SocketException(errnoText())
    val out = ArrayList<InterfaceAddress>()
    try {
        var cur: CPointer<ifaddrs>? = head.value
        while (cur != null) {
            val ifa = cur.pointed
            val name = ifa.ifa_name?.toKString() ?: ""
            val flags = ifa.ifa_flags.toInt()
            val sa = ifa.ifa_addr
            val ipv4 = if (sa != null && sa.pointed.sa_family.toInt() == AF_INET) sa.reinterpret<sockaddr_in>().pointed.addressBytes() else null
            out += InterfaceAddress(name, flags and IFF_UP != 0, flags and IFF_RUNNING != 0, flags and IFF_LOOPBACK != 0, ipv4)
            cur = ifa.ifa_next
        }
    } finally {
        freeifaddrs(head.value)
    }
    out
}

/** This phone's Wi-Fi and hotspot IPv4 addresses (getifaddrs, then [syncAddresses]): no cellular, VPN or AirDrop links. */
internal actual fun siteLocalAddresses(): List<String> = syncAddresses(interfaceAddresses())
