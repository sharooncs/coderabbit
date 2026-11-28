package app.housemove

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.JcaCrypto
import app.housemove.data.toHex
import app.housemove.sync.CipherState
import app.housemove.sync.Noise
import app.housemove.sync.SecureChannel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The keys of the encrypted sessions made in this process, by the initiator's one-use public key, as [Noise.keyLog] gives
 * them (as TLS's SSLKEYLOGFILE): only so that the tests' relays can read what crossed, to check each merge as they do for a
 * plain sync. [install] puts the log in; the app never has one.
 */
internal object SessionKeys {
    private val keys = ConcurrentHashMap<String, Pair<ByteArray, ByteArray>>()

    fun install() {
        Noise.keyLog = { e, i2r, r2i -> keys[e.toHex()] = i2r to r2i }
    }

    fun uninstall() {
        Noise.keyLog = null
        keys.clear()
    }

    fun of(ephemeral: ByteArray): Pair<ByteArray, ByteArray>? = keys[ephemeral.toHex()]

    /** Every key logged so far: the sniffing test looks for them on the wire too. */
    fun all(): List<ByteArray> = keys.values.flatMap { listOf(it.first, it.second) }
}

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
 *
 * An encrypted sync (2.4: a code sync that opens with HMSYNC4, or a sync by itself with HMAUTO2) is seen at its first
 * bytes: the handshake's two turns before it are passed on and not counted (its third message goes with the hello, in
 * phase 1), phases 9 and 10 are the phones vouching for keys, and [bytes] gives what each phase carried as the phones
 * read it, decrypted with the session's keys from [SessionKeys] (a frame cut short gives nothing). [raw] is every byte
 * that went either way, as anyone on the network would see it.
 */
internal class Wire(
    private val targetPort: Int,
    private val cut: Cut? = null,
    /** Phases passed on before the sync's first (2.3's handshake of syncing by itself): not counted, never cut. */
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

    /** The sync was encrypted (it opened with a 2.4 handshake); [xx]: a code sync's (XXpsk0), else one by itself (IK). */
    @Volatile
    var encrypted = false
        private set

    @Volatile
    var xx = false
        private set

    private val said = HashMap<Int, ByteArrayOutputStream>()
    private val rawOut = ByteArrayOutputStream()
    private val rawBack = ByteArrayOutputStream()
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

    /** Every byte that went from the joining phone (0) or the hosting one (1), handshakes and all, on every connection. */
    fun raw(direction: Int): ByteArray = synchronized(said) { (if (direction == 0) rawOut else rawBack).toByteArray() }

    /** What crossed in [phase], as the receiving phone read it (decrypted, for an encrypted sync). */
    fun bytes(phase: Int): ByteArray? {
        val seen = synchronized(said) { said[phase]?.toByteArray() } ?: return null
        return if (encrypted) decrypted()[phase] else seen
    }

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

    /** Each phase's frames opened, in order, with the session's keys; a phase whose frames do not open gives what opened before. */
    private fun decrypted(): Map<Int, ByteArray> {
        val first = raw(0)
        // writeUTF of the magic (2 + 7 bytes), the first message's length (2), then its one-use key (65 bytes).
        if (first.size < 11 + 65) return emptyMap()
        val keys = SessionKeys.of(first.copyOfRange(11, 11 + 65)) ?: return emptyMap()
        val phasesSeen = synchronized(said) { said.keys.sorted() }
        val out = HashMap<Int, ByteArray>()
        for (dir in 0..1) {
            val cipher = CipherState(JcaCrypto).apply { initializeKey(if (dir == 0) keys.first else keys.second) }
            var frames = 0L
            var opened = true
            for (phase in phasesSeen.filter { (it % 2 == 1) == (dir == 0) }) {
                val b = synchronized(said) { said[phase]!!.toByteArray() }
                var at = 0
                // The third message of XXpsk0 goes before the joiner's first frame.
                if (dir == 0 && phase == 1 && xx && b.size >= 2) at = 2 + (((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff))
                val plain = ByteArrayOutputStream()
                while (opened && at + 2 <= b.size) {
                    val len = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
                    if (at + 2 + len > b.size) break
                    val p = cipher.decryptWithAd(ByteArray(0), b.copyOfRange(at + 2, at + 2 + len))
                    if (p == null) {
                        opened = false
                        break
                    }
                    plain.write(p)
                    at += 2 + len
                    frames++
                    if (frames % SecureChannel.REKEY_EVERY == 0L) cipher.rekey()
                }
                out[phase] = plain.toByteArray()
            }
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

        /** The turns of a 2.4 handshake before the sync's first phase, once its first bytes are seen. */
        private var handshake = 0

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
                if (planned && turns == 1 && dir == 0 && n >= 9) {
                    val magic = String(buf, 2, 7, Charsets.ISO_8859_1)
                    if (buf[0] == 0.toByte() && buf[1] == 7.toByte() && (magic == "HMSYNC4" || magic == "HMAUTO2")) {
                        encrypted = true
                        xx = magic == "HMSYNC4"
                        handshake = 2
                    }
                }
                if (turns > skip + handshake) phase++
                inPhase = 0
                if (planned && turns > skip + handshake) {
                    phases = phase
                    if (cut != null && cut.phase < phase) return lose(to)
                    onPhase(phase)
                }
            }
            if (planned) synchronized(said) { (if (dir == 0) rawOut else rawBack).write(buf, 0, n) }
            if (turns <= skip + handshake) {
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
