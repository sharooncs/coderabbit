package app.housemove.sync

import app.housemove.data.Action
import app.housemove.data.Admission
import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.HIDDEN_FROM_HELPERS
import app.housemove.data.MoveIdentity
import app.housemove.data.Outcome
import app.housemove.data.Person
import app.housemove.data.Role
import app.housemove.data.SCHEMA
import app.housemove.data.SYNC_PREFIX
import app.housemove.data.Store
import app.housemove.data.admission
import app.housemove.data.isRetired
import app.housemove.data.isSafeFileName
import app.housemove.data.phoneEntry
import app.housemove.data.refusal
import app.housemove.data.roleOfPeer
import app.housemove.data.sentTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import app.housemove.data.AtomicInteger
import app.housemove.data.CountDownLatch
import app.housemove.data.EOFException
import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.Lock
import app.housemove.data.awaitMillis
import app.housemove.data.countDown
import app.housemove.data.currentTimeMillis
import app.housemove.data.decodeUtf8
import app.housemove.data.decrementAndGet
import app.housemove.data.delete
import app.housemove.data.exists
import app.housemove.data.incrementAndGet
import app.housemove.data.isFile
import app.housemove.data.length
import app.housemove.data.outputStream
import app.housemove.data.renameTo
import app.housemove.data.secureRandom
import app.housemove.data.startThread
import app.housemove.data.synchronized
import app.housemove.data.toUtf8
import app.housemove.data.urlDecode
import app.housemove.data.urlEncode
import app.housemove.data.write
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

/**
 * Phone-to-phone sync over the local network, with no server or account.
 *
 * One phone hosts and shows a QR code holding its address and a one-time
 * code. The other scans it and connects. They exchange datasets (merged with
 * last-writer-wins and the rules about who may change what) and then each
 * sends the photo files the other is missing.
 * Both phones must be on the same Wi-Fi, or one on the other's hotspot.
 *
 * Nothing is said to a phone that does not have the code shown (or an invite's code): it is
 * told "NO" and the connection is closed, and anything that does not begin the way a HouseMove
 * phone does is not answered at all. Every message either phone shows is in words.
 *
 * Who may sync: phones of the same move (the same move ID, see [MoveIdentity]) whose people are
 * both in it just sync. A phone that is not in the move yet joins with an invite an Organiser made
 * for one person ([Host.inviteFor]) or with a Helper code ([Host.helperCode]); the plain code only
 * lets it in when the other phone's person may add people. Someone who has been removed is
 * refused; a retired phone is listened to once, one way ([ONE_WAY]). For an Organiser's or a
 * Decider's invite the hosting phone asks its own person to confirm the phone that came ("Joined:
 * Priya, on Priya's phone", [Host.waiting]) before anything of the move is sent.
 *
 * The exchange, after the joiner connects (strings as writeUTF):
 * ```
 * joiner: MAGIC, token, name, letter, schema (int), own Person as JSON, hello as JSON (see Hello)
 * host:   [WAIT] OK | ONEWAY | NO | VERSION (+ schema) | REMOVED | STRANGER | EXPIRED | WRONGMOVE | RETIRED | UNDONE | UNCONFIRMED,
 *         then if OK or ONEWAY: name, letter, hello as JSON (with its own Person, which is all 2.0 sent there)
 * joiner: GO | TAKE | REMOVED | STRANGER | WRONGMOVE
 * joiner: data      host: merged data      (each leaves out what the other's role is not sent)
 * joiner: file names   host: file names   joiner: files   host: files
 * ```
 * The hello's first six lines are 2.0's, so a phone on 2.0 reads all of it and answers VERSION,
 * as it does for any phone on another data layout; 2.0's last line, the person an invite is for,
 * is where 2.1 puts the rest of what it says first: the phone ID, the move and so on.
 * A phone that is refused because its person has been removed is still listened to on one
 * point: what its own person record says (see `Store.hearAbout`). A hello in the words of
 * version 1.3 is answered VERSION, or AGAIN when it comes from a phone on this version,
 * which only says it after its own hello went unanswered because the connection dropped.
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

    /** The lists went both ways, and then the connection was lost while photos were still on their way. */
    const val PHOTOS_CUT = "Everything except some of the photos was synced: the connection was lost while they were being sent. Nothing is broken: sync again to fetch the rest."

    /** What the other end sent could not be read: a damaged message, or something that is not HouseMove. */
    const val UNREADABLE = "What the other phone sent could not be read, so nothing was changed. Sync again; if it keeps happening, check both phones have the same version of HouseMove."
    const val NOT_HOUSEMOVE = "Something on the network that is not a HouseMove phone tried to connect. Nothing was exchanged."
    const val WRONG_CODE = "A phone tried to sync with a code that is not the one shown here. Nothing was exchanged."
    const val CODE_NOT_ACCEPTED = "The sync code was not accepted. Scan the QR code on the other phone again."
    const val UNREACHABLE = "Could not reach the other phone. Are both on the same Wi-Fi?"
    const val NO_NETWORK = "This phone is not on a Wi-Fi network or hotspot. Connect it to the same one as the other phone, then try again."

    /** The two phones hold different moves. Nothing was exchanged. */
    const val WRONG_MOVE = "That phone is on a different move, so nothing was exchanged. Open the same move on both phones, then sync again."

    /** The hosting phone's person said the phone that came was not the right one, or did not answer in time. */
    const val UNDONE = "The other phone said this is not the right phone, so nothing was exchanged. The invite can still be used: ask them to show it again."
    const val NOT_CONFIRMED = "Nobody confirmed this phone on the other phone in time, so nothing was exchanged. The invite can still be used."

    /** Said on a retired phone. */
    const val RETIRED_PHONE = Store.RETIRED_PHONE

    /** Said on the other phone when a retired phone came. */
    const val RETIRED_HERE = "That phone has been retired from this move. Its last work was taken in, and nothing was sent back to it."
    const val RETIRED_GONE = "That phone has been retired from this move and its work was already taken in, so it was not synced."

    /**
     * Shown on a phone that is in a move with others when it scans an invite from a phone that is not
     * one of that move's Organisers: most likely an invite to another move. [moveName] is this phone's move.
     */
    fun alreadyInAMove(moveName: String): String {
        val name = moveName.trim()
        val move = when {
            name.isEmpty() -> "a move"
            name.equals("move", ignoreCase = true) || name.endsWith(" move", ignoreCase = true) -> name
            else -> "$name move"
        }
        return "This phone is already in $move, and it joins that move only through one of its Organisers. If this is the same move, sync with an Organiser first."
    }

    /** How long an invite made for one person can be used. */
    const val INVITE_MINUTES = 10

    /** How long a Helper code can be used, by as many phones as come. */
    const val HELPER_CODE_MINUTES = 10

    /** What an invite says instead of a person, when it is a Helper code: each phone gives the name it goes by. */
    const val HELPER_CODE = "*"

    /** How long a phone waits for the other to say anything before it gives up. Tests shorten it. */
    @Volatile
    internal var timeoutMs = 30_000

    /** How long the hosting phone's person has to say whether a phone that joined is the right one. Tests shorten it. */
    @Volatile
    var confirmMs = 90_000

    /** This phone's addresses on the local network. Tests stand in a phone with no Wi-Fi. */
    @Volatile
    var addresses: () -> List<String> = { localAddresses() }

    /** At most this many phones are served at once; one more is turned away, so a flood of connections cannot use the phone up. */
    private const val MAX_PEERS = 8
    private const val MAX_JSON = 64 * 1024 * 1024
    private const val MAX_PHOTO = 40L * 1024 * 1024

    /** At most this many ids go with a hello for 2.0's same-move check. */
    private const val MAX_IDS = 1500

    // What the wire helpers throw is never shown: whatWentWrong turns every error into one of the messages above.

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
        /** The other phone has an older HouseMove: offer "Share the app" so it can get this one. */
        val shareApp: Boolean = false,
        /** The other phone's data-format version, when the versions differ and it said. */
        val peerSchema: Int = 0,
        /** A retired phone's last work was taken in, one way (on either phone). */
        val oneWay: Boolean = false,
        /** The other phone's name in its own settings. */
        val peerDevice: String = "",
    ) {
        val ok get() = error == null
    }

    /** What 2.1 says first, besides 2.0's hello. */
    @Serializable
    internal data class Hello(
        /** The person an invite is for, as 2.0 said it, or [HELPER_CODE]. Blank for a plain sync. */
        val claim: String = "",
        val phoneId: String = "",
        val deviceName: String = "",
        /** The move: its ID and the others it is known by. For an invite, the joiner says the one the invite names. */
        val moveId: String = "",
        val aliases: List<String> = emptyList(),
        /** The ID was worked out from a move from before 2.1 ([MoveIdentity.derived]): [ids] is sent for 2.0's same-move check. */
        val derived: Boolean = false,
        val ids: List<String> = emptyList(),
        val moveName: String = "",
        /** Letters the phone uses in its other moves. */
        val elsewhere: List<String> = emptyList(),
        /** The label numbers the phone prints from in its other moves, as first and last of each in turn. */
        val labelsElsewhere: List<Int> = emptyList(),
        /** The name the phone's owner goes by: given with a Helper code. */
        val name: String = "",
        /** From the host: its own person. */
        val person: Person? = null,
        /** From the host: the person the joining phone joins as. */
        val youAre: String = "",
        /** The phone's public key for update files ([PhoneKeys.publicText]) and when it was made: the admitting phone puts it on its entry. */
        val key: String = "",
        val keyAt: Long = 0,
    )

    /**
     * What the QR code holds. A plain invite is `HMSYNC:hosts:port:token`. One made for a
     * person adds `:personId:ROLE:name` (the name URL-encoded), so the joining phone can say
     * "Join as Sam, Helper" before it connects; 2.1 adds `:moveId:moveName` to both (a plain one
     * with the three person parts empty), so it can say "Join Maple Road as Sam, Helper?". A
     * Helper code has [HELPER_CODE] for the person.
     */
    data class Invite(
        val hosts: List<String>,
        val port: Int,
        val token: String,
        val personId: String? = null,
        val personName: String = "",
        val role: Role? = null,
        val moveId: String = "",
        val moveName: String = "",
    ) {
        /** Anyone may join with it as a Helper, giving the name their phone knows them by. */
        val helperCode: Boolean get() = personId == HELPER_CODE

        fun encode(): String {
            val base = SYNC_PREFIX + hosts.joinToString(",") + ":" + port + ":" + token
            val person = if (personId == null) null else personId + ":" + (role?.name ?: "") + ":" + urlEncode(personName)
            if (moveId.isEmpty()) return base + if (person == null) "" else ":$person"
            return base + ":" + (person ?: "::") + ":" + moveId + ":" + urlEncode(moveName)
        }

        /** What the joining phone asks before anything is sent. */
        fun question(): String {
            val move = moveName.trim().ifBlank { "this move" }
            return when {
                helperCode -> "Join $move as a Helper?"
                personId != null -> "Join $move as ${personName.ifBlank { "this person" }}${role?.let { ", " + it.label } ?: ""}?"
                else -> "Sync with $move?"
            }
        }

        companion object {
            fun parse(text: String): Invite? {
                val t = text.trim()
                if (!t.startsWith(SYNC_PREFIX)) return null
                val parts = t.removePrefix(SYNC_PREFIX).split(":")
                if (parts.size != 3 && parts.size != 6 && parts.size != 8) return null
                val port = parts[1].toIntOrNull() ?: return null
                val hosts = parts[0].split(",").filter { it.isNotBlank() }
                if (hosts.isEmpty() || parts[2].isBlank()) return null
                if (parts.size == 3) return Invite(hosts, port, parts[2])
                fun decoded(s: String): String? = try {
                    urlDecode(s)
                } catch (_: Exception) {
                    null
                }
                val moveId = if (parts.size == 8) parts[6] else ""
                val moveName = if (parts.size == 8) decoded(parts[7]) ?: return null else ""
                if (parts[3].isBlank()) {
                    // 2.1's plain invite: no person, only the move.
                    if (parts.size != 8 || parts[4].isNotBlank() || parts[5].isNotBlank()) return null
                    return Invite(hosts, port, parts[2], moveId = moveId, moveName = moveName)
                }
                val name = decoded(parts[5]) ?: return null
                return Invite(hosts, port, parts[2], parts[3], name, Role.entries.firstOrNull { it.name == parts[4] }, moveId, moveName)
            }
        }
    }

    /**
     * A phone that has come with an Organiser's or a Decider's invite, waiting on the hosting phone
     * for its person to say it is the right one ([Host.confirm]). [text] is what to show.
     */
    data class JoinRequest(val id: String, val personId: String, val personName: String, val role: Role, val deviceName: String) {
        val text: String get() = "Joined: ${personName.ifBlank { "someone" }}, on ${deviceName.ifBlank { "a phone" }}"
    }

    /** IPv4 addresses other phones on the same network can reach. */
    fun localAddresses(): List<String> = try {
        siteLocalAddresses()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Why this phone cannot sync right now, or null when it is on a network other phones can reach.
     * A screen can show it before offering a code to scan.
     */
    fun networkProblem(): String? = if (addresses().isEmpty()) NO_NETWORK else null

    /** An address on the local network, as opposed to this phone itself (which only tests use). */
    private fun onLocalNetwork(host: String): Boolean = host != "localhost" && !host.startsWith("127.") && host != "::1"

    private fun newToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = secureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun personJson(p: Person?): String = if (p == null) "" else AppJson.encodeToString(p)

    private fun personFrom(json: String): Person? = try {
        if (json.isBlank()) null else AppJson.decodeFromString<Person>(json)
    } catch (_: Exception) {
        null
    }

    private fun helloFrom(json: String): Hello? = try {
        if (json.startsWith("{")) AppJson.decodeFromString<Hello>(json) else null
    } catch (_: Exception) {
        null
    }

    /** What this phone says first about itself and its move. */
    private fun helloOf(store: Store, claim: String = "", moveId: String? = null, youAre: String = ""): Hello {
        val id = store.identity.value
        val d = store.data.value
        return Hello(
            claim = claim, phoneId = store.phone.id, deviceName = store.phone.deviceName,
            moveId = moveId ?: id.id, aliases = if (moveId == null) id.aliases else emptyList(), derived = moveId == null && id.derived,
            ids = if (moveId == null && id.derived) sameMoveIds(d) else emptyList(), moveName = d.move.name,
            elsewhere = store.lettersElsewhereNow().toList(), name = store.phone.settings.value.name.ifBlank { store.device.value.name },
            labelsElsewhere = store.labelsElsewhereNow().flatMap { listOf(it.first, it.last) },
            youAre = youAre, key = store.phone.keys.publicText, keyAt = store.phone.keys.madeAt,
        )
    }

    /** The label numbers a joining phone prints from in its other moves, as its hello says them. */
    private fun ranges(hello: Hello): List<IntRange> = hello.labelsElsewhere.chunked(2).filter { it.size == 2 && it[0] <= it[1] }.map { it[0]..it[1] }

    /** Some of the people and things of a move from before 2.1, for 2.0's same-move check. */
    private fun sameMoveIds(d: AppData): List<String> =
        (d.people.map { it.id } + d.rooms.map { it.id } + d.items.map { it.id } + d.boxes.map { it.id } + d.tasks.map { it.id } + d.expenses.map { it.id }).take(MAX_IDS)

    /**
     * Are this phone's move and the other's the same? The same ID, or one either knows the move by;
     * or, when both worked theirs out from a move from before 2.1, 2.0's check once: they share some
     * of their people, or (with no people) some of their things.
     */
    private fun sameMove(store: Store, other: Hello): Pair<Boolean, Boolean> {
        val mine = store.identity.value
        val them = MoveIdentity(other.moveId, other.aliases, other.derived)
        if (mine.matches(them)) return true to false
        if (!mine.derived || !other.derived) return false to false
        val theirs = other.ids.toSet()
        val d = store.data.value
        val people = d.people.map { it.id }
        val shared = if (people.isNotEmpty() && other.ids.any { id -> people.contains(id) }) true
        else sameMoveIds(d).any { it in theirs }
        return shared to shared
    }

    /** Thrown inside an exchange to end it with a message for this phone's screen. */
    private class Stop(message: String) : IOException(message)

    /**
     * What to show when an exchange ended early: always in words for the screen, never the name or
     * text of an error. [listsDone] is true once both phones have taken in each other's data, so
     * that at most some photos can still be missing.
     */
    private fun whatWentWrong(e: Exception, listsDone: Boolean = false): String = when {
        e is Stop -> e.message ?: CONNECTION_LOST
        listsDone -> PHOTOS_CUT
        e is EOFException || e is SocketException || e is SocketTimeoutException -> CONNECTION_LOST
        else -> UNREADABLE
    }

    // ---- host --------------------------------------------------------------

    class Host internal constructor(
        private val store: Store,
        private val server: ServerSocket,
        val token: String,
        private val clock: () -> Long,
        private val letNewPhonesIn: Boolean,
        private val confirmJoins: Boolean,
        private val onResult: (Result) -> Unit,
    ) {
        val port: Int get() = server.localPort
        @Volatile private var closed = false

        /** An invite made for one person: who, until when, and whether it has been used. */
        private class Personal(val personId: String, val until: Long, var used: Boolean = false, var busy: Boolean = false)

        private val personal = HashMap<String, Personal>()

        /** Taken while [personal] or [helperCodes] is read or changed. */
        private val personalLock = Lock()

        /** Helper codes and until when each can be used. */
        private val helperCodes = HashMap<String, Long>()

        private val _waiting = MutableStateFlow<List<JoinRequest>>(emptyList())

        /** Phones that came with an Organiser's or a Decider's invite, waiting for this phone's person to confirm each ([confirm]). */
        val waiting: StateFlow<List<JoinRequest>> = _waiting

        private val answers = HashMap<String, Pair<CountDownLatch, BooleanArray>>()

        /** Taken while [answers] is read or changed. */
        private val answersLock = Lock()

        private val _helpers = MutableStateFlow<List<Person>>(emptyList())

        /** Who has joined with a Helper code shown here, each for the screen to list with Remove. */
        val helpersJoined: StateFlow<List<Person>> = _helpers

        /** The plain code: for phones whose people are already in the move, or to let a new phone in as itself. */
        fun invite(hosts: List<String> = addresses()) = Invite(hosts, port, token, moveId = store.moveId, moveName = store.data.value.move.name)

        /** Phones being served right now. */
        private val serving = AtomicInteger(0)

        /**
         * An invite that makes the phone that scans it [personId], someone an Organiser
         * has added with [Store.addPerson]. It works once, and only for
         * [INVITE_MINUTES] minutes; after that make a new one.
         */
        fun inviteFor(personId: String, hosts: List<String> = addresses()): Outcome<Invite> {
            val d = store.data.value
            d.refusal(store.me, Action.MANAGE_PEOPLE)?.let { return Outcome.Refused(it) }
            val p = d.person(personId)?.takeIf { !it.removed } ?: return Outcome.Refused("That person is not in the move.")
            // An invite with nowhere to connect to cannot be scanned; say why now rather than show a code that does nothing.
            if (hosts.isEmpty()) return Outcome.Refused(NO_NETWORK)
            val code = newToken()
            synchronized(personalLock) { personal[code] = Personal(p.id, clock() + INVITE_MINUTES * 60_000L) }
            return Outcome.Ok(Invite(hosts, port, code, p.id, p.name, p.role, store.moveId, d.move.name))
        }

        /**
         * A Helper code: for [HELPER_CODE_MINUTES] minutes, any number of phones can join with it as
         * Helpers, each under the name it knows its owner by (made unique in the move). A phone of
         * someone removed, or a retired phone, is refused.
         */
        fun helperCode(hosts: List<String> = addresses()): Outcome<Invite> {
            val d = store.data.value
            d.refusal(store.me, Action.MANAGE_PEOPLE)?.let { return Outcome.Refused(it) }
            if (hosts.isEmpty()) return Outcome.Refused(NO_NETWORK)
            val code = newToken()
            synchronized(personalLock) { helperCodes[code] = clock() + HELPER_CODE_MINUTES * 60_000L }
            return Outcome.Ok(Invite(hosts, port, code, HELPER_CODE, "", Role.HELPER, store.moveId, d.move.name))
        }

        /** Say whether the phone of a [waiting] request is the right one. Undo ([right] false) leaves the invite unused. */
        fun confirm(requestId: String, right: Boolean) {
            val a = synchronized(answersLock) { answers[requestId] } ?: return
            a.second[0] = right
            a.first.countDown()
        }

        /** Wait for this phone's person to confirm [r]: true for Right, false for Undo, null when nobody answered in time. */
        private fun awaitConfirm(r: JoinRequest): Boolean? {
            val latch = CountDownLatch(1)
            val answer = BooleanArray(1)
            synchronized(answersLock) { answers[r.id] = latch to answer }
            _waiting.value = _waiting.value + r
            try {
                return if (latch.awaitMillis(confirmMs.toLong())) answer[0] else null
            } finally {
                synchronized(answersLock) { answers.remove(r.id) }
                _waiting.value = _waiting.value.filter { it.id != r.id }
            }
        }

        internal fun start() {
            startThread("sync-host") {
                while (!closed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    // Real phones come one or two at a time; a crowd of connections at once is not a sync, and is not answered.
                    if (serving.incrementAndGet() > MAX_PEERS) {
                        serving.decrementAndGet()
                        try {
                            socket.close()
                        } catch (_: IOException) {
                        }
                        continue
                    }
                    startThread("sync-peer") {
                        try {
                            serve(socket)
                        } finally {
                            serving.decrementAndGet()
                        }
                    }
                }
            }
        }

        private fun serve(socket: Socket) {
            var peer = "Other phone"
            var letter = ""
            var peerId = ""
            var device = ""
            var mine: Personal? = null
            var listsDone = false
            try {
                socket.use { s ->
                    s.soTimeout = timeoutMs
                    // Each side writes a whole message and then waits for the answer, so nothing is gained by the
                    // network holding the tail of a message back to fill a packet; it only makes every step wait.
                    s.tcpNoDelay = true
                    val din = s.dataIn()
                    val out = s.dataOut()
                    fun refuse(word: String, message: String): Nothing {
                        out.writeUTF(word); out.flush()
                        throw Stop(message)
                    }

                    // The first line is short and known. Anything else is not a HouseMove phone, and is not read any further.
                    val length = din.readUnsignedShort()
                    if (length !in 4..16) throw Stop(NOT_HOUSEMOVE)
                    val magic = ByteArray(length).also { din.readFully(it) }.decodeUtf8()
                    fun knows(code: String) = code == token || synchronized(personalLock) { personal.containsKey(code) || helperCodes.containsKey(code) }
                    if (magic == OLD_MAGIC) refuse("NO", VERSION_MISMATCH)
                    if (magic != MAGIC) {
                        if (!magic.startsWith("HMSYNC")) throw Stop(NOT_HOUSEMOVE)
                        // Another version of HouseMove. Read the rest of what 1.3 sends, so its hello is not cut short, and say so.
                        var theirLayout = -1
                        var theirToken = ""
                        try {
                            theirToken = din.readUTF()
                            repeat(2) { din.readUTF() }
                            theirLayout = din.readInt()
                        } catch (_: IOException) {
                        }
                        // Even this much is only said to a phone that has the code shown here.
                        if (!knows(theirToken)) refuse("NO", WRONG_CODE)
                        // A phone on this version only says hello the 1.3 way after its own hello went unanswered ([tellOlderPhone]).
                        // The versions are the same; it was the connection that dropped.
                        if (magic == MAGIC_1_3 && theirLayout == SCHEMA) refuse("AGAIN", CONNECTION_LOST)
                        out.writeUTF("VERSION"); out.flush()
                        throw Stop(VERSION_MISMATCH)
                    }
                    // The whole hello is read before anything is refused, so the answer is not lost
                    // to a connection closed with part of the hello unread.
                    val theirToken = din.readUTF()
                    peer = din.readUTF()
                    letter = din.readUTF()
                    val theirSchema = din.readInt()
                    val theirPerson = personFrom(din.readUTF())
                    val last = din.readUTF()
                    val invited = synchronized(personalLock) { personal[theirToken] }
                    val helperUntil = synchronized(personalLock) { helperCodes[theirToken] }
                    if (theirToken != token && invited == null && helperUntil == null) refuse("NO", WRONG_CODE)
                    if (theirSchema != SCHEMA) {
                        // The other phone may be older and not know this phone's version; it is told it, for a newer one to see.
                        out.writeUTF("VERSION"); out.writeInt(SCHEMA); out.flush()
                        throw Older(theirSchema)
                    }
                    val hello = helloFrom(last) ?: refuse("NO", UNREADABLE)
                    val claim = hello.claim
                    device = hello.deviceName
                    peerId = theirPerson?.id ?: ""

                    val before = store.data.value
                    // Which move: an invite names the move it is for; a sync is between two phones of one move.
                    var aliasesToKeep: MoveIdentity? = null
                    if (invited != null || helperUntil != null) {
                        if (hello.moveId.isNotEmpty() && !store.identity.value.matches(hello.moveId)) refuse("WRONGMOVE", WRONG_MOVE)
                    } else {
                        val (same, byFallback) = sameMove(store, hello)
                        if (!same) refuse("WRONGMOVE", WRONG_MOVE)
                        if (byFallback || !store.identity.value.matches(hello.moveId)) aliasesToKeep = MoveIdentity(hello.moveId, hello.aliases, hello.derived)
                    }
                    // A retired phone: its work is taken in once, and nothing goes back to it.
                    val retired = before.phoneEntry(hello.phoneId)?.takeIf { it.second.isRetired }
                    if (retired != null && retired.second.collected) refuse("RETIRED", RETIRED_GONE)
                    var joining: String? = null
                    // What is written into this move for the phone that came (its person, its phone), once it has said GO:
                    // a phone that turns this one down at the last moment leaves nothing behind here.
                    var onGo: () -> Unit = {}
                    if (retired != null) {
                        if (invited != null || helperUntil != null) refuse("RETIRED", RETIRED_GONE)
                    } else if (helperUntil != null) {
                        if (claim != HELPER_CODE || clock() > helperUntil) refuse("EXPIRED", INVITE_EXPIRED)
                        store.whyNotAdmitHelper(hello.phoneId)?.let { refuse(if (it == Store.RETIRED_PHONE) "RETIRED" else "REMOVED", it) }
                        // A phone already in the move comes in as who it is; any other as a new Helper, made once it says GO.
                        val known = before.phoneEntry(hello.phoneId)?.first
                        val id = known?.id ?: store.newPersonId()
                        if (known == null) onGo = {
                            store.admitHelper(hello.name.ifBlank { peer }, hello.phoneId, hello.deviceName, hello.elsewhere.toSet(), id, hello.key, hello.keyAt, ranges(hello))
                        }
                        joining = id
                        peerId = id
                    } else if (invited != null) {
                        val free = synchronized(personalLock) {
                            (!invited.used && !invited.busy && clock() <= invited.until && claim == invited.personId).also { if (it) invited.busy = true }
                        }
                        if (!free) refuse("EXPIRED", INVITE_EXPIRED)
                        mine = invited
                        val p = before.person(invited.personId)
                        if (p?.removed != false) refuse("REMOVED", "That person is no longer in the move.")
                        // A phone of someone removed does not come back in under another name.
                        before.phoneEntry(hello.phoneId)?.let { (owner, _) -> if (owner.removed) refuse("REMOVED", "That phone's person has been removed from this move.") }
                        if (confirmJoins && p.role != Role.HELPER) {
                            // Nothing of the move is sent until this phone's person has seen which phone came, and said it is the right one.
                            out.writeUTF("WAIT"); out.flush()
                            val r = JoinRequest(newToken(), p.id, p.name, p.role, hello.deviceName.ifBlank { peer })
                            when (awaitConfirm(r)) {
                                true -> Unit
                                false -> refuse("UNDONE", UNDONE)
                                null -> refuse("UNCONFIRMED", NOT_CONFIRMED)
                            }
                        }
                        onGo = { store.admitPhone(invited.personId, hello.phoneId, hello.deviceName, hello.elsewhere.toSet(), hello.key, hello.keyAt, ranges(hello)) }
                        joining = invited.personId
                        peerId = invited.personId
                    } else {
                        if (claim.isNotEmpty()) refuse("NO", WRONG_CODE)
                        when (before.admission(store.device.value, theirPerson)) {
                            Admission.REMOVED -> {
                                // Nothing is exchanged, but what their phone knows about their own removal is kept and passed on.
                                store.hearAbout(theirPerson)
                                refuse("REMOVED", "$peer has been removed from this move, so their phone was not synced.")
                            }
                            Admission.STRANGER -> refuse("STRANGER", UNKNOWN_PHONE)
                            Admission.NEWCOMER -> {
                                if (!letNewPhonesIn) refuse("STRANGER", UNKNOWN_PHONE)
                                // Let in as it is: what it says of itself counts for nothing in 2.1, so this phone's person adds it, as a Helper.
                                if (theirPerson != null && before.roleOfPeer(theirPerson) == null) onGo = { store.letIn(theirPerson) }
                            }
                            Admission.MEMBER -> Unit
                        }
                    }
                    out.writeUTF(if (retired != null) "ONEWAY" else "OK")
                    out.writeUTF(store.device.value.name)
                    out.writeUTF(store.device.value.letter)
                    // Where 2.0 sent its own Person, 2.1 sends its hello, with its Person in it.
                    out.writeUTF(AppJson.encodeToString(helloOf(store, youAre = joining ?: "").copy(person = store.me)))
                    out.flush()
                    val go = din.readUTF()
                    when (go) {
                        "GO" -> onGo()
                        "TAKE" -> Unit
                        "REMOVED" -> {
                            // The other phone knows this one's person has been removed: its card says so from now on.
                            store.noteRemoved()
                            throw Stop(REMOVED_SELF)
                        }
                        "STRANGER" -> throw Stop(NOT_ADDED)
                        "WRONGMOVE" -> throw Stop(WRONG_MOVE)
                        else -> throw Stop(UNREADABLE)
                    }

                    if (retired != null) {
                        // One way: the retired phone's data and photos come in, and nothing goes back.
                        val theirs = readData(din)
                        store.mergeIn(theirs)
                        listsDone = true
                        writeNames(out, haveFiles(store, Role.ORGANISER))
                        val received = receiveFiles(din, store)
                        store.markCollected(hello.phoneId)
                        out.writeUTF("DONE"); out.flush()
                        store.flush()
                        onResult(Result(peer, letter, 0, received, RETIRED_HERE, peerPersonId = peerId, oneWay = true, peerDevice = device))
                        return@use
                    }
                    if (go == "TAKE") {
                        // This phone has been retired: the other phone takes in its work, and sends nothing back.
                        val role = before.roleOfPeer(theirPerson) ?: Role.HELPER
                        writeData(out, store.data.value.sentTo(role, theirPerson?.id))
                        val theirFiles = readNames(din)
                        listsDone = true
                        val sent = sendFiles(out, store, wanted(store, theirFiles, role))
                        onResult(Result(peer, letter, sent, 0, RETIRED_PHONE, peerPersonId = peerId, oneWay = true, peerDevice = device))
                        return@use
                    }

                    // Data: receive theirs, merge, send back the merged set.
                    val theirs = readData(din)
                    val merged = store.mergeIn(theirs)
                    aliasesToKeep?.let { store.adoptAliases(it) }
                    val upTo = store.stampOf(merged)
                    val them = merged.person(peerId)?.takeIf { !it.removed }
                    // Someone this move has no role for is sent no more than a Helper would be.
                    val role = them?.role ?: Role.HELPER
                    val given = merged.sentTo(role, peerId)
                    writeData(out, given)

                    // Photos: the client sends first, then we send. Its list of files is sent once it has merged, so
                    // from here on both phones have each other's data and only photos can still go missing.
                    val theirFiles = readNames(din)
                    listsDone = true
                    writeNames(out, haveFiles(store, role))
                    val received = receiveFiles(din, store)
                    val sent = sendFiles(out, store, wanted(store, theirFiles, role))
                    if (joining != null) {
                        store.markJoined(joining)
                        if (invited != null) synchronized(personalLock) { invited.used = true }
                        if (helperUntil != null) store.data.value.person(joining)?.let { p -> _helpers.value = _helpers.value.filter { it.id != p.id } + p }
                    }
                    store.noteShared(upTo, everything = role != Role.HELPER)
                    // Data and photos have gone both ways: a Helper's phone that synced with an Organiser or a Decider lets go here
                    // of what Helpers are not sent.
                    store.fullSyncDone(peerId, given)
                    // A Helper an Organiser has thanked ("Done, thanks") has handed everything in: they are removed now.
                    store.thankedSynced(peerId)
                    store.flush()
                    val done = Result(peer, letter, sent, received, peerPersonId = peerId, joinedAs = if (joining != null) store.data.value.person(peerId) else null, peerDevice = device)
                    store.syncFinished()
                    onResult(done)
                }
            } catch (e: Older) {
                onResult(Result(peer, letter, 0, 0, VERSION_MISMATCH, peerPersonId = peerId, shareApp = e.schema < SCHEMA, peerSchema = e.schema, peerDevice = device))
            } catch (e: Exception) {
                // Let go of the invite before saying the join failed, so a retry made the moment the failure shows is not
                // told the invite is in use.
                mine?.let { synchronized(personalLock) { it.busy = false } }
                onResult(Result(peer, letter, 0, 0, whatWentWrong(e, listsDone), peerPersonId = peerId, peerDevice = device))
            } finally {
                mine?.let { synchronized(personalLock) { it.busy = false } }
            }
        }

        fun stop() {
            closed = true
            try {
                server.close()
            } catch (_: IOException) {
            }
            // Nobody is left to confirm a phone still waiting: it is told no.
            synchronized(answersLock) { answers.values.toList() }.forEach { it.first.countDown() }
        }
    }

    /** The other phone is on another data layout ([schema]). */
    private class Older(val schema: Int) : IOException("different versions")

    /**
     * Start hosting. [clock] times the ten minutes of an invite; tests pass their own.
     * With [letNewPhonesIn] false the plain code only works for phones whose people are already
     * in the move, so the only way in is an invite made for a person. It is true by default, as
     * before 2.0: a phone of the same move that is not in it yet comes in as itself when this
     * phone's person may add people. With [confirmJoins], a phone that comes with an Organiser's or a
     * Decider's invite waits in [Host.waiting] until this phone's person says it is the right one
     * ([Host.confirm]); a screen that shows "Joined: Priya, on Priya's phone" with Right and Undo
     * passes true. Screens from before 2.1, which show no such thing, leave it off.
     */
    fun host(
        store: Store,
        clock: () -> Long = ::currentTimeMillis,
        letNewPhonesIn: Boolean = true,
        confirmJoins: Boolean = false,
        onResult: (Result) -> Unit,
    ): Host {
        val h = Host(store, ServerSocket(0), newToken(), clock, letNewPhonesIn, confirmJoins, onResult)
        h.start()
        return h
    }

    // ---- join --------------------------------------------------------------

    /**
     * Connect to a hosting phone and sync both ways. Blocking; call off the main thread.
     * With an invite made for a person ([Invite.personId]), or a Helper code, this phone becomes
     * that person: see [Result.joinedAs]. With [letNewPhonesIn] false it only syncs with a phone
     * whose person is already in this phone's move. A phone already in a move (not on its own, or
     * [holdsCard]: it already holds the move it is joining) joins only through a phone it knows as
     * one of that move's Organisers. [onWaiting] is called when the other phone's person is asked
     * to confirm this phone. Nothing is sent before this is called: the screen asks "Join Maple
     * Road as Priya, Decider?" first ([Invite.question]).
     */
    fun join(
        store: Store,
        invite: Invite,
        connectTimeoutMs: Int = 4000,
        letNewPhonesIn: Boolean = true,
        holdsCard: Boolean = false,
        onWaiting: () -> Unit = {},
    ): Result {
        // With no Wi-Fi there is nothing to try: say so at once instead of waiting for every address to time out.
        if (invite.hosts.all { onLocalNetwork(it) } && addresses().isEmpty()) return Result("", "", 0, 0, NO_NETWORK)
        var lastError = UNREACHABLE
        for (host in invite.hosts) {
            val socket = Socket()
            try {
                socket.connect(host, invite.port, connectTimeoutMs)
            } catch (e: Exception) {
                socket.close()
                continue
            }
            var listsDone = false
            var joined: Person? = null
            var peer = ""
            var letter = ""
            var peerId = ""
            var device = ""
            try {
                socket.use { s ->
                    s.soTimeout = timeoutMs
                    s.tcpNoDelay = true
                    val din = s.dataIn()
                    val out = s.dataOut()
                    val claim = invite.personId
                    out.writeUTF(MAGIC)
                    out.writeUTF(invite.token)
                    out.writeUTF(store.device.value.name)
                    out.writeUTF(store.device.value.letter)
                    out.writeInt(SCHEMA)
                    out.writeUTF(personJson(store.me))
                    val joiningMove = if (claim != null) invite.moveId else null
                    out.writeUTF(AppJson.encodeToString(helloOf(store, claim ?: "", joiningMove)))
                    out.flush()
                    var answer = try {
                        din.readUTF()
                    } catch (e: IOException) {
                        if (e !is EOFException && e !is SocketException) throw e
                        // A phone on an older version hangs up at the first line. Say hello the way
                        // it understands, so that it too shows the "different versions" message.
                        // A phone on this version answers that hello with "AGAIN": then it was only the connection that dropped.
                        val word = tellOlderPhone(store, host, invite, connectTimeoutMs)
                        return Result(
                            "", "", 0, 0,
                            when (word) {
                                "VERSION" -> VERSION_MISMATCH
                                "NO" -> CODE_NOT_ACCEPTED
                                // "AGAIN", or no answer at all: it was the connection that dropped.
                                else -> CONNECTION_LOST
                            },
                            shareApp = word == "VERSION",
                        )
                    }
                    if (answer == "WAIT") {
                        // The other phone's person is asked whether this is the right phone. Nothing has been sent yet.
                        onWaiting()
                        s.soTimeout = timeoutMs + confirmMs
                        answer = din.readUTF()
                        s.soTimeout = timeoutMs
                    }
                    when (answer) {
                        "OK", "ONEWAY" -> Unit
                        "VERSION" -> {
                            // A phone on 2.1 or later says its version; one on 2.0 does not, and is older.
                            val theirs = try {
                                s.soTimeout = 2000
                                din.readInt()
                            } catch (_: Exception) {
                                -1
                            }
                            return Result("", "", 0, 0, VERSION_MISMATCH, shareApp = theirs < SCHEMA, peerSchema = maxOf(theirs, 0))
                        }
                        "REMOVED" -> {
                            // Only a phone of the move says so ([holdsCard] or a plain sync): a phone joining afresh has no card to mark.
                            if (claim == null || holdsCard) store.noteRemoved()
                            return Result("", "", 0, 0, REMOVED_SELF)
                        }
                        "STRANGER" -> return Result("", "", 0, 0, NOT_ADDED)
                        "EXPIRED" -> return Result("", "", 0, 0, INVITE_EXPIRED)
                        "WRONGMOVE" -> return Result("", "", 0, 0, WRONG_MOVE)
                        "RETIRED" -> return Result("", "", 0, 0, RETIRED_PHONE)
                        "UNDONE" -> return Result("", "", 0, 0, UNDONE)
                        "UNCONFIRMED" -> return Result("", "", 0, 0, NOT_CONFIRMED)
                        else -> return Result("", "", 0, 0, CODE_NOT_ACCEPTED)
                    }
                    peer = din.readUTF()
                    letter = din.readUTF()
                    // 2.0 sent its own Person here; 2.1 sends its hello, with its Person in it.
                    val said = din.readUTF()
                    val hostHello = helloFrom(said) ?: Hello()
                    val theirPerson = hostHello.person ?: personFrom(said)
                    device = hostHello.deviceName
                    peerId = theirPerson?.id ?: ""
                    val hostMove = MoveIdentity(hostHello.moveId, hostHello.aliases, hostHello.derived)
                    fun refuse(word: String, message: String): Result {
                        out.writeUTF(word); out.flush()
                        return Result(peer, letter, 0, 0, message, peerPersonId = peerId, peerDevice = device)
                    }

                    if (answer == "ONEWAY") {
                        // This phone has been retired: its last work goes to the other phone, and nothing comes back.
                        out.writeUTF("GO")
                        // As much as that phone's person may see, as this phone knows them.
                        val toRole = store.data.value.roleOfPeer(theirPerson) ?: Role.HELPER
                        writeData(out, store.data.value.sentTo(toRole, theirPerson?.id))
                        val theirFiles = readNames(din)
                        listsDone = true
                        val sent = sendFiles(out, store, wanted(store, theirFiles, toRole))
                        din.readUTF()
                        return Result(peer, letter, sent, 0, RETIRED_PHONE, peerPersonId = peerId, oneWay = true, peerDevice = device)
                    }

                    val before = store.data.value
                    var aliasesToKeep: MoveIdentity? = null
                    // The person this phone joins as: the invite's, or for a Helper code the one the other phone made for it.
                    val claimed = if (claim == HELPER_CODE) hostHello.youAre.ifEmpty { null } else claim
                    var retiredHost = false
                    if (claim == null) {
                        // A sync between phones of one move: the other phone's move is checked here too, before any data moves.
                        if (hostMove.id.isNotEmpty()) {
                            val (same, byFallback) = sameMove(store, hostHello)
                            if (!same) return refuse("WRONGMOVE", WRONG_MOVE)
                            if (byFallback || !store.identity.value.matches(hostMove.id)) aliasesToKeep = hostMove
                        }
                        retiredHost = before.phoneEntry(hostHello.phoneId)?.second?.let { it.isRetired && !it.collected } == true
                        if (before.phoneEntry(hostHello.phoneId)?.second?.let { it.isRetired && it.collected } == true) return refuse("REMOVED", RETIRED_GONE)
                        val admission = before.admission(store.device.value, theirPerson)
                        val verdict = when (admission) {
                            Admission.REMOVED -> "REMOVED" to "${peer.ifBlank { "That phone's person" }} has been removed from this move, so this phone did not sync with theirs."
                            Admission.STRANGER -> "STRANGER" to UNKNOWN_PHONE
                            Admission.NEWCOMER -> if (letNewPhonesIn) null else "STRANGER" to UNKNOWN_PHONE
                            Admission.MEMBER -> null
                        }
                        if (verdict != null && !retiredHost) {
                            if (verdict.first == "REMOVED") store.hearAbout(theirPerson)
                            return refuse(verdict.first, verdict.second)
                        }
                        // Let in as it is, as the host does: in this phone's person's name, as a Helper.
                        if (admission == Admission.NEWCOMER && !retiredHost && theirPerson != null && before.roleOfPeer(theirPerson) == null) store.letIn(theirPerson)
                    } else {
                        if (claimed == null) return refuse("STRANGER", CODE_NOT_ACCEPTED)
                        // A phone already in a move, or already holding the one it is joining, joins only through a phone it
                        // knows as an Organiser of that move.
                        // Anyone else's is an invite to another move, and taking it would mix the two moves on both phones and on
                        // from there to everyone in either. Nothing of this phone's has been sent, and nothing is.
                        if (holdsCard || !store.isAlone()) {
                            val organiser = before.admission(store.device.value, theirPerson) == Admission.MEMBER && before.roleOfPeer(theirPerson) == Role.ORGANISER
                            if (!organiser || !store.identity.value.matches(hostMove)) return refuse("STRANGER", alreadyInAMove(before.move.name))
                        }
                    }

                    if (retiredHost) {
                        // The other phone has been retired: its work is taken in, and nothing of this phone's goes to it.
                        out.writeUTF("TAKE"); out.flush()
                        val theirs = readData(din)
                        store.mergeIn(theirs)
                        listsDone = true
                        writeNames(out, haveFiles(store, Role.ORGANISER))
                        val received = receiveFiles(din, store)
                        store.markCollected(hostHello.phoneId)
                        store.flush()
                        return Result(peer, letter, 0, received, RETIRED_HERE, peerPersonId = peerId, oneWay = true, peerDevice = device)
                    }
                    out.writeUTF("GO")

                    store.flush()
                    // Whoever shows an invite is trusted to say who they are; anyone else is looked up.
                    val role = (if (claim != null) theirPerson?.role else before.roleOfPeer(theirPerson)) ?: Role.HELPER
                    // A phone joining its first move leaves behind the person it started as and what that person decided alone.
                    val mine = if (claimed != null) store.offerFor(claimed) else store.data.value
                    val given = mine.sentTo(role, theirPerson?.id)
                    writeData(out, given)
                    val merged = readData(din)
                    if (claimed != null) {
                        when (val o = store.joinAs(claimed, merged, hostMove.takeIf { it.id.isNotEmpty() })) {
                            is Outcome.Ok -> joined = o.value
                            is Outcome.Refused -> return Result(peer, letter, 0, 0, o.reason, peerPersonId = peerId, peerDevice = device)
                        }
                    } else {
                        store.mergeIn(merged)
                        aliasesToKeep?.let { store.adoptAliases(it) }
                    }
                    val upTo = store.stampOf(merged)
                    // The host merged this phone's data before it sent its own, so both now have everything but photos.
                    listsDone = true

                    writeNames(out, haveFiles(store, role))
                    val theirFiles = readNames(din)
                    val sent = sendFiles(out, store, wanted(store, theirFiles, role))
                    val received = receiveFiles(din, store)
                    store.noteShared(upTo, everything = role != Role.HELPER)
                    store.fullSyncDone(theirPerson?.id, given)
                    store.thankedSynced(theirPerson?.id)
                    store.flush()
                    store.syncFinished()
                    return Result(peer, letter, sent, received, peerPersonId = peerId, joinedAs = joined, peerDevice = device)
                }
            } catch (e: Exception) {
                lastError = whatWentWrong(e, listsDone)
                // Joined, and only photos were lost on the way: the phone is in the move all the same.
                if (joined != null) return Result(peer, letter, 0, 0, lastError, peerPersonId = peerId, joinedAs = joined, peerDevice = device)
            }
        }
        return Result("", "", 0, 0, lastError)
    }

    /** Version 1.3 answers its own hello with "VERSION" and tells its user the versions differ. Returns the answer, if there was one. */
    private fun tellOlderPhone(store: Store, host: String, invite: Invite, connectTimeoutMs: Int): String? {
        return try {
            Socket().use { s ->
                s.connect(host, invite.port, connectTimeoutMs)
                s.soTimeout = 3000
                val out = s.dataOut()
                out.writeUTF(MAGIC_1_3)
                out.writeUTF(invite.token)
                out.writeUTF(store.device.value.name)
                out.writeUTF(store.device.value.letter)
                out.writeInt(SCHEMA)
                out.flush()
                s.dataInUnbuffered().readUTF()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- wire helpers ------------------------------------------------------

    private fun writeData(out: DataOutputStream, data: AppData) {
        val bytes = AppJson.encodeToString(data).toUtf8()
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readData(din: DataInputStream): AppData {
        val n = din.readInt()
        if (n < 0 || n > MAX_JSON) throw IOException("Bad data size")
        val bytes = ByteArray(n)
        din.readFully(bytes)
        return AppJson.decodeFromString<AppData>(bytes.decodeUtf8())
    }

    /**
     * Photo files this phone actually has for records that are still alive, as far as a phone
     * whose person has [peerRole] is concerned: a Helper's phone is not sent receipts or serial labels.
     * A small copy (from an update file or a small backup) is neither offered nor passed on, so the
     * full photo comes from a phone that has it and takes its place.
     */
    private fun haveFiles(store: Store, peerRole: Role): List<String> =
        store.data.value.livePhotos
            .filter { peerRole != Role.HELPER || it.kind !in HIDDEN_FROM_HELPERS }
            .map { it.file }.filter { File(store.photoDir, it).isFile && !store.isSmallCopy(it) }

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
            out.writeFile(f)
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
            // Only files of photos this phone has on record are kept. The record came with the data just merged; a file
            // for a photo that was deleted meanwhile, or that this phone's person is not sent, is read and dropped.
            val safe = isSafeFileName(name) && store.data.value.photos.any { !it.deleted && it.file == name }
            val target = if (safe) File(store.photoDir, name) else null
            val tmp = if (safe) File(store.photoDir, "$name.part") else null
            val sink = tmp?.outputStream()
            try {
                var left = size
                while (left > 0) {
                    val n = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("the connection ended in the middle of a photo")
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
                when {
                    !target.exists() -> tmp.renameTo(target)
                    // The full photo, in place of the small copy this phone had.
                    store.isSmallCopy(name) -> {
                        if (!tmp.renameTo(target)) {
                            target.delete()
                            tmp.renameTo(target)
                        }
                        store.noteSmallCopies(listOf(name), small = false)
                    }
                    else -> tmp.delete()
                }
                count++
            }
        }
        return count
    }
}
