package app.housemove

import app.housemove.data.AppData
import app.housemove.data.AppJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The network between two phones in a test: a real localhost socket on each
 * side, joined here. The joining phone connects to [port]; everything is passed
 * on to the hosting phone at [targetPort] and back.
 *
 * A sync is a strict back-and-forth, so each change of direction is a new
 * "phase" (1 hello, 2 the host's answer, 3 the joiner's data, 4 the merged data
 * back, 5 and 6 the lists of photo files, 7 and 8 the files themselves).
 * [onPhase] is called before the first byte of a phase is passed on: at that
 * moment both phones are waiting, so a test can let either of them do something
 * "in the middle of a sync" and still get the same run every time.
 *
 * With a [cut] the connection is lost after that many bytes of that phase, as
 * when someone walks out of Wi-Fi range. The side that was receiving sees the
 * bytes that got through and then the end of the connection; what the sender
 * goes on to send is thrown away.
 */
internal class Wire(
    private val targetPort: Int,
    private val cut: Cut? = null,
    /** Phases passed on before the sync's first (the handshake of syncing by itself, AutoSession): not counted, never cut. */
    private val skip: Int = 0,
    private val onPhase: (phase: Int) -> Unit = {},
) : Closeable {
    data class Cut(val phase: Int, val after: Int)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    private val accepted = AtomicInteger(0)

    /** How many times the joining phone connected. The first is the sync; a second is its hello to an older version. */
    val connections: Int get() = accepted.get()

    /** The phase in which the connection was actually cut, or 0 when it was not. */
    @Volatile
    var cutAt = 0
        private set

    /** How many phases the sync got through. */
    @Volatile
    var phases = 0
        private set

    private val said = HashMap<Int, ByteArrayOutputStream>()
    private val talks = ArrayList<Talk>()

    init {
        thread(name = "wire", isDaemon = true) {
            while (true) {
                val joiner = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                val n = accepted.incrementAndGet()
                try {
                    val host = Socket("127.0.0.1", targetPort)
                    host.tcpNoDelay = true
                    joiner.tcpNoDelay = true
                    Talk(joiner, host, planned = n == 1).also { synchronized(talks) { talks += it } }.start()
                } catch (_: IOException) {
                    joiner.close()
                }
            }
        }
    }

    /** The bytes of [phase] that reached the other side. */
    fun bytes(phase: Int): ByteArray? = synchronized(said) { said[phase]?.toByteArray() }

    /** The dataset that crossed in phase 3 (joiner to host) or 4 (host to joiner), if all of it got through. */
    fun data(phase: Int): AppData? {
        val b = bytes(phase) ?: return null
        return try {
            val din = DataInputStream(ByteArrayInputStream(b))
            if (phase == 3 && din.readUTF() != "GO") return null
            val n = din.readInt()
            if (n < 0 || din.available() < n) return null
            AppJson.decodeFromString<AppData>(ByteArray(n).also { din.readFully(it) }.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    /** Names of the photo files that crossed whole in phase 7 (joiner to host) or 8 (host to joiner). */
    fun files(phase: Int): List<String> {
        val b = bytes(phase) ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val din = DataInputStream(ByteArrayInputStream(b))
            while (true) {
                val name = din.readUTF()
                if (name.isEmpty()) break
                val size = din.readLong()
                if (din.available() < size) break
                din.skipBytes(size.toInt())
                out += name
            }
        } catch (_: Exception) {
        }
        return out
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
        synchronized(talks) { talks.toList() }.forEach { it.hangUp() }
    }

    private inner class Talk(private val joiner: Socket, private val host: Socket, private val planned: Boolean) {
        private var phase = 0
        private var lastDir = -1
        private var inPhase = 0
        private var lost = false
        private val done = BooleanArray(2)

        fun start() {
            thread(name = "wire-out", isDaemon = true) { pump(0, joiner, host) }
            thread(name = "wire-back", isDaemon = true) { pump(1, host, joiner) }
        }

        private fun pump(dir: Int, from: Socket, to: Socket) {
            val buf = ByteArray(16 * 1024)
            try {
                val input = from.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(this) { pass(dir, buf, n, to) }
                }
            } catch (_: IOException) {
            }
            synchronized(this) {
                done[dir] = true
                // Whoever was sending has stopped: the other side is told so, and can still finish what it was sending.
                try {
                    to.shutdownOutput()
                } catch (_: IOException) {
                }
                if (done[0] && done[1]) hangUp()
            }
        }

        /** Direction changes so far, the handshake's among them. */
        private var turns = 0

        private fun pass(dir: Int, buf: ByteArray, n: Int, to: Socket) {
            if (lost) return
            if (dir != lastDir) {
                lastDir = dir
                turns++
                if (turns > skip) phase++
                inPhase = 0
                if (planned && turns > skip) {
                    phases = phase
                    if (cut != null && cut.phase < phase) return lose(to)
                    onPhase(phase)
                }
            }
            if (turns <= skip) {
                // The handshake: passed on as it is.
                try {
                    to.getOutputStream().apply { write(buf, 0, n); flush() }
                } catch (_: IOException) {
                    lost = true
                }
                return
            }
            val cutHere = planned && cut != null && cut.phase == phase
            val len = if (cutHere) minOf(n, cut!!.after - inPhase) else n
            if (len > 0) {
                try {
                    to.getOutputStream().apply { write(buf, 0, len); flush() }
                } catch (_: IOException) {
                    lost = true
                    return
                }
                inPhase += len
                if (planned) synchronized(said) { said.getOrPut(phase) { ByteArrayOutputStream() }.write(buf, 0, len) }
            }
            if (cutHere && inPhase >= cut!!.after) lose(to)
        }

        private fun lose(to: Socket) {
            lost = true
            cutAt = phase
            try {
                to.shutdownOutput()
            } catch (_: IOException) {
            }
        }

        fun hangUp() {
            for (s in listOf(joiner, host)) try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }
}
