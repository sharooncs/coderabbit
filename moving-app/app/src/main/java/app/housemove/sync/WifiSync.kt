package app.housemove.sync

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.SYNC_PREFIX
import app.housemove.data.Store
import app.housemove.data.isSafeFileName
import kotlinx.serialization.encodeToString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Phone-to-phone sync over the local network, with no server or account.
 *
 * One phone hosts and shows a QR code holding its address and a one-time
 * code. The other scans it and connects. They exchange datasets (merged with
 * last-writer-wins) and then each sends the photo files the other is missing.
 * Both phones must be on the same Wi-Fi, or one on the other's hotspot.
 */
object WifiSync {
    private const val MAGIC = "HMSYNC1"
    private const val TIMEOUT_MS = 30_000
    private const val MAX_JSON = 64 * 1024 * 1024
    private const val MAX_PHOTO = 40L * 1024 * 1024

    data class Result(
        val peerName: String,
        val peerLetter: String,
        val photosSent: Int,
        val photosReceived: Int,
        val error: String? = null,
    ) {
        val ok get() = error == null
    }

    data class Invite(val hosts: List<String>, val port: Int, val token: String) {
        fun encode() = SYNC_PREFIX + hosts.joinToString(",") + ":" + port + ":" + token

        companion object {
            fun parse(text: String): Invite? {
                val t = text.trim()
                if (!t.startsWith(SYNC_PREFIX)) return null
                val parts = t.removePrefix(SYNC_PREFIX).split(":")
                if (parts.size != 3) return null
                val port = parts[1].toIntOrNull() ?: return null
                val hosts = parts[0].split(",").filter { it.isNotBlank() }
                if (hosts.isEmpty() || parts[2].isBlank()) return null
                return Invite(hosts, port, parts[2])
            }
        }
    }

    /** IPv4 addresses other phones on the same network can reach. */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun newToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    // ---- host --------------------------------------------------------------

    class Host internal constructor(
        private val store: Store,
        private val server: ServerSocket,
        val token: String,
        private val onResult: (Result) -> Unit,
    ) {
        val port: Int get() = server.localPort
        @Volatile private var closed = false

        fun invite(hosts: List<String> = localAddresses()) = Invite(hosts, port, token)

        internal fun start() {
            thread(name = "sync-host", isDaemon = true) {
                while (!closed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(name = "sync-peer", isDaemon = true) { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) {
            var peer = "Other phone"
            var letter = ""
            try {
                socket.use { s ->
                    s.soTimeout = TIMEOUT_MS
                    val din = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    if (din.readUTF() != MAGIC) throw IOException("Not a HouseMove phone")
                    val theirToken = din.readUTF()
                    peer = din.readUTF()
                    letter = din.readUTF()
                    if (theirToken != token) {
                        out.writeUTF("NO"); out.flush()
                        throw IOException("Wrong sync code")
                    }
                    out.writeUTF("OK")
                    out.writeUTF(store.device.value.name)
                    out.writeUTF(store.device.value.letter)
                    out.flush()

                    // Data: receive theirs, merge, send back the merged set.
                    val theirs = readData(din)
                    val merged = store.mergeIn(theirs)
                    writeData(out, merged)

                    // Photos: the client sends first, then we send.
                    val theirFiles = readNames(din)
                    writeNames(out, haveFiles(store))
                    val received = receiveFiles(din, store)
                    val sent = sendFiles(out, store, wanted(store, theirFiles))
                    store.flush()
                    onResult(Result(peer, letter, sent, received))
                }
            } catch (e: Exception) {
                onResult(Result(peer, letter, 0, 0, e.message ?: e.javaClass.simpleName))
            }
        }

        fun stop() {
            closed = true
            try {
                server.close()
            } catch (_: IOException) {
            }
        }
    }

    fun host(store: Store, onResult: (Result) -> Unit): Host {
        val h = Host(store, ServerSocket(0), newToken(), onResult)
        h.start()
        return h
    }

    // ---- join --------------------------------------------------------------

    /** Connect to a hosting phone and sync both ways. Blocking; call off the main thread. */
    fun join(store: Store, invite: Invite, connectTimeoutMs: Int = 4000): Result {
        var lastError = "Could not reach the other phone. Are both on the same Wi-Fi?"
        for (host in invite.hosts) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, invite.port), connectTimeoutMs)
            } catch (e: Exception) {
                socket.close()
                continue
            }
            try {
                socket.use { s ->
                    s.soTimeout = TIMEOUT_MS
                    val din = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    out.writeUTF(MAGIC)
                    out.writeUTF(invite.token)
                    out.writeUTF(store.device.value.name)
                    out.writeUTF(store.device.value.letter)
                    out.flush()
                    if (din.readUTF() != "OK") return Result("", "", 0, 0, "The sync code was not accepted. Scan the QR code again.")
                    val peer = din.readUTF()
                    val letter = din.readUTF()

                    store.flush()
                    writeData(out, store.data.value)
                    val merged = readData(din)
                    store.mergeIn(merged)

                    writeNames(out, haveFiles(store))
                    val theirFiles = readNames(din)
                    val sent = sendFiles(out, store, wanted(store, theirFiles))
                    val received = receiveFiles(din, store)
                    store.flush()
                    return Result(peer, letter, sent, received)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return Result("", "", 0, 0, lastError)
    }

    // ---- wire helpers ------------------------------------------------------

    private fun writeData(out: DataOutputStream, data: AppData) {
        val bytes = AppJson.encodeToString(data).toByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readData(din: DataInputStream): AppData {
        val n = din.readInt()
        if (n < 0 || n > MAX_JSON) throw IOException("Bad data size")
        val bytes = ByteArray(n)
        din.readFully(bytes)
        return AppJson.decodeFromString<AppData>(bytes.decodeToString())
    }

    /** Photo files this phone actually has for records that are still alive. */
    private fun haveFiles(store: Store): List<String> =
        store.data.value.livePhotos.map { it.file }.filter { File(store.photoDir, it).isFile }

    /** Files we hold that the other side lacks. */
    private fun wanted(store: Store, theirs: Set<String>): List<String> =
        haveFiles(store).filter { it !in theirs }

    private fun writeNames(out: DataOutputStream, names: List<String>) {
        out.writeInt(names.size)
        names.forEach { out.writeUTF(it) }
        out.flush()
    }

    private fun readNames(din: DataInputStream): Set<String> {
        val n = din.readInt()
        if (n < 0 || n > 200_000) throw IOException("Bad photo list")
        return (0 until n).map { din.readUTF() }.toSet()
    }

    private fun sendFiles(out: DataOutputStream, store: Store, names: List<String>): Int {
        var count = 0
        for (name in names) {
            val f = File(store.photoDir, name)
            if (!f.isFile) continue
            out.writeUTF(name)
            out.writeLong(f.length())
            f.inputStream().use { it.copyTo(out) }
            count++
        }
        out.writeUTF("")
        out.flush()
        return count
    }

    private fun receiveFiles(din: DataInputStream, store: Store): Int {
        var count = 0
        val buf = ByteArray(64 * 1024)
        while (true) {
            val name = din.readUTF()
            if (name.isEmpty()) break
            val size = din.readLong()
            if (size < 0 || size > MAX_PHOTO) throw IOException("Bad photo size")
            val safe = isSafeFileName(name)
            val target = if (safe) File(store.photoDir, name) else null
            val tmp = if (safe) File(store.photoDir, "$name.part") else null
            val sink = tmp?.outputStream()
            try {
                var left = size
                while (left > 0) {
                    val n = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("Connection lost during photo transfer")
                    sink?.write(buf, 0, n)
                    left -= n
                }
            } catch (e: Exception) {
                sink?.close()
                tmp?.delete()
                throw e
            } finally {
                sink?.close()
            }
            if (tmp != null && target != null) {
                if (target.exists()) tmp.delete() else tmp.renameTo(target)
                count++
            }
        }
        return count
    }
}
