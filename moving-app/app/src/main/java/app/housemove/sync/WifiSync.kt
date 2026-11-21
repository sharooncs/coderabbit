package app.housemove.sync

import app.housemove.data.Action
import app.housemove.data.Admission
import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.HIDDEN_FROM_HELPERS
import app.housemove.data.Outcome
import app.housemove.data.Person
import app.housemove.data.Role
import app.housemove.data.SCHEMA
import app.housemove.data.SYNC_PREFIX
import app.housemove.data.Store
import app.housemove.data.admission
import app.housemove.data.isSafeFileName
import app.housemove.data.refusal
import app.housemove.data.roleOfPeer
import app.housemove.data.visibleTo
import kotlinx.serialization.encodeToString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Phone-to-phone sync over the local network, with no server or account.
 *
 * One phone hosts and shows a QR code holding its address and a one-time
 * code. The other scans it and connects. They exchange datasets (merged with
 * last-writer-wins and the rules about who may change what) and then each
 * sends the photo files the other is missing.
 * Both phones must be on the same Wi-Fi, or one on the other's hotspot.
 *
 * Who may sync: phones whose people are both in the move just sync. A phone
 * that is not in the move yet joins with an invite an Organiser made for one
 * person ([Host.inviteFor]); the plain code only lets it in when the other
 * phone's person may add people. Someone who has been removed is refused.
 *
 * The exchange, after the joiner connects (strings as writeUTF):
 * ```
 * joiner: MAGIC, token, name, letter, schema (int), own Person as JSON, person id claimed by the invite or ""
 * host:   OK | NO | VERSION | REMOVED | STRANGER | EXPIRED, then if OK: name, letter, own Person as JSON
 * joiner: GO | REMOVED | STRANGER
 * joiner: data      host: merged data      (each leaves out what the other's role is not sent)
 * joiner: file names   host: file names   joiner: files   host: files
 * ```
 */
object WifiSync {
    private const val MAGIC = "HMSYNC3"

    /** What version 1.3 says first. Its data layout is older, so the two cannot sync. */
    private const val MAGIC_1_3 = "HMSYNC2"
    private const val OLD_MAGIC = "HMSYNC1"
    const val VERSION_MISMATCH = "The two phones have different versions of HouseMove. Install the same version on both, then sync again."
    const val REMOVED_SELF = "You have been removed from this move, so this phone can no longer sync with it."
    const val NOT_ADDED = "This phone has not been added to that move yet. Ask an Organiser to add you and show you your invite."
    const val UNKNOWN_PHONE = "That phone has not been added to this move. Only an Organiser can add a new phone."
    const val INVITE_EXPIRED = "That invite has been used or has run out. Ask for a new one."
    const val CONNECTION_LOST = "The connection was lost before the sync finished. Nothing is broken: keep the phones close together and sync again."

    /** How long an invite made for one person can be used. */
    const val INVITE_MINUTES = 10
    private const val TIMEOUT_MS = 30_000
    private const val MAX_JSON = 64 * 1024 * 1024
    private const val MAX_PHOTO = 40L * 1024 * 1024

    data class Result(
        val peerName: String,
        val peerLetter: String,
        val photosSent: Int,
        val photosReceived: Int,
        val error: String? = null,
        /** The other phone's person, when it said who it is. */
        val peerPersonId: String = "",
        /** Set when this sync was an invite: the person the joining phone now is. */
        val joinedAs: Person? = null,
    ) {
        val ok get() = error == null
    }

    /**
     * What the QR code holds. A plain invite is `HMSYNC:hosts:port:token`. One made for a
     * person adds `:personId:ROLE:name` (the name URL-encoded), so the joining phone can say
     * "Join as Sam, Helper" before it connects.
     */
    data class Invite(
        val hosts: List<String>,
        val port: Int,
        val token: String,
        val personId: String? = null,
        val personName: String = "",
        val role: Role? = null,
    ) {
        fun encode() = SYNC_PREFIX + hosts.joinToString(",") + ":" + port + ":" + token +
            if (personId == null) "" else ":" + personId + ":" + (role?.name ?: "") + ":" + URLEncoder.encode(personName, "UTF-8")

        companion object {
            fun parse(text: String): Invite? {
                val t = text.trim()
                if (!t.startsWith(SYNC_PREFIX)) return null
                val parts = t.removePrefix(SYNC_PREFIX).split(":")
                if (parts.size != 3 && parts.size != 6) return null
                val port = parts[1].toIntOrNull() ?: return null
                val hosts = parts[0].split(",").filter { it.isNotBlank() }
                if (hosts.isEmpty() || parts[2].isBlank()) return null
                if (parts.size == 3) return Invite(hosts, port, parts[2])
                if (parts[3].isBlank()) return null
                val name = try {
                    URLDecoder.decode(parts[5], "UTF-8")
                } catch (_: Exception) {
                    return null
                }
                return Invite(hosts, port, parts[2], parts[3], name, Role.entries.firstOrNull { it.name == parts[4] })
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

    private fun personJson(p: Person?): String = if (p == null) "" else AppJson.encodeToString(p)

    private fun personFrom(json: String): Person? = try {
        if (json.isBlank()) null else AppJson.decodeFromString<Person>(json)
    } catch (_: Exception) {
        null
    }

    /** Thrown inside an exchange to end it with a message for this phone's screen. */
    private class Stop(message: String) : IOException(message)

    /** What to show when an exchange ended early. A dropped connection is said in words, not as the name of an error. */
    private fun whatWentWrong(e: Exception): String = when (e) {
        is Stop -> e.message ?: CONNECTION_LOST
        is EOFException, is SocketException, is java.net.SocketTimeoutException -> CONNECTION_LOST
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---- host --------------------------------------------------------------

    class Host internal constructor(
        private val store: Store,
        private val server: ServerSocket,
        val token: String,
        private val clock: () -> Long,
        private val letNewPhonesIn: Boolean,
        private val onResult: (Result) -> Unit,
    ) {
        val port: Int get() = server.localPort
        @Volatile private var closed = false

        /** An invite made for one person: who, until when, and whether it has been used. */
        private class Personal(val personId: String, val until: Long, var used: Boolean = false, var busy: Boolean = false)

        private val personal = HashMap<String, Personal>()

        /** The plain code: for phones whose people are already in the move, or to let a new phone in as itself. */
        fun invite(hosts: List<String> = localAddresses()) = Invite(hosts, port, token)

        /**
         * An invite that makes the phone that scans it [personId], someone an Organiser
         * has added with [Store.addPerson]. It works once, and only for
         * [INVITE_MINUTES] minutes; after that make a new one.
         */
        fun inviteFor(personId: String, hosts: List<String> = localAddresses()): Outcome<Invite> {
            val d = store.data.value
            d.refusal(store.me, Action.MANAGE_PEOPLE)?.let { return Outcome.Refused(it) }
            val p = d.person(personId)?.takeIf { !it.removed } ?: return Outcome.Refused("That person is not in the move.")
            val code = newToken()
            synchronized(personal) { personal[code] = Personal(p.id, clock() + INVITE_MINUTES * 60_000L) }
            return Outcome.Ok(Invite(hosts, port, code, p.id, p.name, p.role))
        }

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
            var peerId = ""
            var mine: Personal? = null
            try {
                socket.use { s ->
                    s.soTimeout = TIMEOUT_MS
                    // Each side writes a whole message and then waits for the answer, so nothing is gained by the
                    // network holding the tail of a message back to fill a packet; it only makes every step wait.
                    s.tcpNoDelay = true
                    val din = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    fun refuse(word: String, message: String): Nothing {
                        out.writeUTF(word); out.flush()
                        throw Stop(message)
                    }

                    val magic = din.readUTF()
                    if (magic == OLD_MAGIC) refuse("NO", VERSION_MISMATCH)
                    if (magic != MAGIC) {
                        if (!magic.startsWith("HMSYNC")) throw IOException("Not a HouseMove phone")
                        // Another version of HouseMove. Read the rest of what 1.3 sends, so its hello is not cut short, and say so.
                        var theirLayout = -1
                        try {
                            repeat(3) { din.readUTF() }
                            theirLayout = din.readInt()
                        } catch (_: IOException) {
                        }
                        // A phone on this version only says hello the 1.3 way after its own hello went unanswered ([tellOlderPhone]).
                        // The versions are the same; it was the connection that dropped.
                        if (magic == MAGIC_1_3 && theirLayout == SCHEMA) refuse("AGAIN", CONNECTION_LOST)
                        refuse("VERSION", VERSION_MISMATCH)
                    }
                    // The whole hello is read before anything is refused, so the answer is not lost
                    // to a connection closed with part of the hello unread.
                    val theirToken = din.readUTF()
                    peer = din.readUTF()
                    letter = din.readUTF()
                    val theirSchema = din.readInt()
                    val theirPerson = personFrom(din.readUTF())
                    val claim = din.readUTF()
                    val invited = synchronized(personal) { personal[theirToken] }
                    if (theirToken != token && invited == null) refuse("NO", "Wrong sync code")
                    if (theirSchema != SCHEMA) refuse("VERSION", VERSION_MISMATCH)
                    peerId = theirPerson?.id ?: ""

                    val before = store.data.value
                    if (invited != null) {
                        val free = synchronized(personal) {
                            (!invited.used && !invited.busy && clock() <= invited.until && claim == invited.personId).also { if (it) invited.busy = true }
                        }
                        if (!free) refuse("EXPIRED", INVITE_EXPIRED)
                        mine = invited
                        if (before.person(invited.personId)?.removed != false) refuse("REMOVED", "That person is no longer in the move.")
                        peerId = invited.personId
                    } else {
                        if (claim.isNotEmpty()) refuse("NO", "Wrong sync code")
                        when (before.admission(store.device.value, theirPerson)) {
                            Admission.REMOVED -> {
                                // Nothing is exchanged, but what their phone knows about their own removal is kept and passed on.
                                store.hearAbout(theirPerson)
                                refuse("REMOVED", "$peer has been removed from this move, so their phone was not synced.")
                            }
                            Admission.STRANGER -> refuse("STRANGER", UNKNOWN_PHONE)
                            Admission.NEWCOMER -> if (!letNewPhonesIn) refuse("STRANGER", UNKNOWN_PHONE)
                            Admission.MEMBER -> Unit
                        }
                    }
                    out.writeUTF("OK")
                    out.writeUTF(store.device.value.name)
                    out.writeUTF(store.device.value.letter)
                    out.writeUTF(personJson(store.me))
                    out.flush()
                    when (din.readUTF()) {
                        "GO" -> Unit
                        "REMOVED" -> throw Stop(REMOVED_SELF)
                        "STRANGER" -> throw Stop(NOT_ADDED)
                        else -> throw IOException("Not a HouseMove phone")
                    }

                    // Data: receive theirs, merge, send back the merged set.
                    val theirs = readData(din)
                    val merged = store.mergeIn(theirs)
                    val them = merged.person(peerId)?.takeIf { !it.removed }
                    // Someone this move has no role for is sent no more than a Helper would be.
                    val role = them?.role ?: Role.HELPER
                    writeData(out, merged.visibleTo(role))

                    // Photos: the client sends first, then we send.
                    val theirFiles = readNames(din)
                    writeNames(out, haveFiles(store, role))
                    val received = receiveFiles(din, store)
                    val sent = sendFiles(out, store, wanted(store, theirFiles, role))
                    if (invited != null) {
                        store.markJoined(invited.personId)
                        synchronized(personal) { invited.used = true }
                    }
                    store.flush()
                    onResult(Result(peer, letter, sent, received, peerPersonId = peerId, joinedAs = if (invited != null) store.data.value.person(peerId) else null))
                }
            } catch (e: Exception) {
                onResult(Result(peer, letter, 0, 0, whatWentWrong(e), peerPersonId = peerId))
            } finally {
                mine?.let { synchronized(personal) { it.busy = false } }
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

    /**
     * Start hosting. [clock] times the ten minutes of an invite; tests pass their own.
     * With [letNewPhonesIn] false the plain code only works for phones whose people are already
     * in the move, so the only way in is an invite made for a person. It is true by default, as
     * before 2.0: a phone that is not in the move comes in as itself when this phone's person may
     * add people.
     */
    fun host(store: Store, clock: () -> Long = System::currentTimeMillis, letNewPhonesIn: Boolean = true, onResult: (Result) -> Unit): Host {
        val h = Host(store, ServerSocket(0), newToken(), clock, letNewPhonesIn, onResult)
        h.start()
        return h
    }

    // ---- join --------------------------------------------------------------

    /**
     * Connect to a hosting phone and sync both ways. Blocking; call off the main thread.
     * With an invite made for a person ([Invite.personId]) this phone becomes that person:
     * see [Result.joinedAs]. With [letNewPhonesIn] false it only syncs with a phone whose
     * person is already in this phone's move.
     */
    fun join(store: Store, invite: Invite, connectTimeoutMs: Int = 4000, letNewPhonesIn: Boolean = true): Result {
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
                    s.tcpNoDelay = true
                    val din = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    val claim = invite.personId
                    out.writeUTF(MAGIC)
                    out.writeUTF(invite.token)
                    out.writeUTF(store.device.value.name)
                    out.writeUTF(store.device.value.letter)
                    out.writeInt(SCHEMA)
                    out.writeUTF(personJson(store.me))
                    out.writeUTF(claim ?: "")
                    out.flush()
                    val answer = try {
                        din.readUTF()
                    } catch (e: IOException) {
                        if (e !is EOFException && e !is SocketException) throw e
                        // A phone on an older version hangs up at the first line. Say hello the way
                        // it understands, so that it too shows the "different versions" message.
                        // A phone on this version answers that hello with "AGAIN": then it was only the connection that dropped.
                        val word = tellOlderPhone(store, host, invite, connectTimeoutMs)
                        return Result("", "", 0, 0, if (word == "AGAIN") CONNECTION_LOST else VERSION_MISMATCH)
                    }
                    when (answer) {
                        "OK" -> Unit
                        "VERSION" -> return Result("", "", 0, 0, VERSION_MISMATCH)
                        "REMOVED" -> return Result("", "", 0, 0, REMOVED_SELF)
                        "STRANGER" -> return Result("", "", 0, 0, NOT_ADDED)
                        "EXPIRED" -> return Result("", "", 0, 0, INVITE_EXPIRED)
                        else -> return Result("", "", 0, 0, "The sync code was not accepted. Scan the QR code again.")
                    }
                    val peer = din.readUTF()
                    val letter = din.readUTF()
                    val theirPerson = personFrom(din.readUTF())
                    val peerId = theirPerson?.id ?: ""

                    val before = store.data.value
                    if (claim == null) {
                        val verdict = when (before.admission(store.device.value, theirPerson)) {
                            Admission.REMOVED -> "REMOVED" to "${peer.ifBlank { "That phone's person" }} has been removed from this move, so this phone did not sync with theirs."
                            Admission.STRANGER -> "STRANGER" to UNKNOWN_PHONE
                            Admission.NEWCOMER -> if (letNewPhonesIn) null else "STRANGER" to UNKNOWN_PHONE
                            Admission.MEMBER -> null
                        }
                        if (verdict != null) {
                            if (verdict.first == "REMOVED") store.hearAbout(theirPerson)
                            out.writeUTF(verdict.first); out.flush()
                            return Result(peer, letter, 0, 0, verdict.second, peerPersonId = peerId)
                        }
                    }
                    out.writeUTF("GO")

                    store.flush()
                    // Whoever shows an invite is trusted to say who they are; anyone else is looked up.
                    val role = (if (claim != null) theirPerson?.role else before.roleOfPeer(theirPerson)) ?: Role.HELPER
                    // A phone joining its first move leaves behind the person it started as and what that person decided alone.
                    val mine = if (claim != null) store.offerFor(claim) else store.data.value
                    writeData(out, mine.visibleTo(role))
                    val merged = readData(din)
                    var joined: Person? = null
                    if (claim != null) {
                        when (val o = store.joinAs(claim, merged)) {
                            is Outcome.Ok -> joined = o.value
                            is Outcome.Refused -> return Result(peer, letter, 0, 0, o.reason, peerPersonId = peerId)
                        }
                    } else store.mergeIn(merged)

                    writeNames(out, haveFiles(store, role))
                    val theirFiles = readNames(din)
                    val sent = sendFiles(out, store, wanted(store, theirFiles, role))
                    val received = receiveFiles(din, store)
                    store.flush()
                    return Result(peer, letter, sent, received, peerPersonId = peerId, joinedAs = joined)
                }
            } catch (e: Exception) {
                lastError = whatWentWrong(e)
            }
        }
        return Result("", "", 0, 0, lastError)
    }

    /** Version 1.3 answers its own hello with "VERSION" and tells its user the versions differ. Returns the answer, if there was one. */
    private fun tellOlderPhone(store: Store, host: String, invite: Invite, connectTimeoutMs: Int): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, invite.port), connectTimeoutMs)
                s.soTimeout = 3000
                val out = DataOutputStream(s.getOutputStream().buffered())
                out.writeUTF(MAGIC_1_3)
                out.writeUTF(invite.token)
                out.writeUTF(store.device.value.name)
                out.writeUTF(store.device.value.letter)
                out.writeInt(SCHEMA)
                out.flush()
                DataInputStream(s.getInputStream()).readUTF()
            }
        } catch (_: Exception) {
            null
        }
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

    /**
     * Photo files this phone actually has for records that are still alive, as far as a phone
     * whose person has [peerRole] is concerned: a Helper's phone is not sent receipts or serial labels.
     */
    private fun haveFiles(store: Store, peerRole: Role): List<String> =
        store.data.value.livePhotos
            .filter { peerRole != Role.HELPER || it.kind !in HIDDEN_FROM_HELPERS }
            .map { it.file }.filter { File(store.photoDir, it).isFile }

    /** Files we hold that the other side lacks. */
    private fun wanted(store: Store, theirs: Set<String>, peerRole: Role): List<String> =
        haveFiles(store, peerRole).filter { it !in theirs }

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
