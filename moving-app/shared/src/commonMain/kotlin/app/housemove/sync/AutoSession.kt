package app.housemove.sync

import app.housemove.data.AppJson
import app.housemove.data.IOException
import app.housemove.data.Lock
import app.housemove.data.MoveIdentity
import app.housemove.data.Person
import app.housemove.data.Store
import app.housemove.data.base64Encode
import app.housemove.data.currentTimeMillis
import app.housemove.data.decodeUtf8
import app.housemove.data.hmacSha256
import app.housemove.data.synchronized
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.data.P256Der
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

/**
 * One sync by itself between two phones of a move on the same Wi-Fi, from 2.4 (design/encryption.md, Part 2): the dialling
 * phone D knows, from the tag the answering phone A advertises, which phone A is, and from its pin which key A has.
 * They run Noise's IK handshake ([Handshake]) with the phones' own keys, then today's sync ([WifiSync]) unchanged inside
 * the encrypted channel ([SecureChannel]), then each vouches for the keys it has met ([Pins.vouches]):
 *
 * ```
 * D → A   "HMAUTO2" (writeUTF), then IK's first message as a frame:  e, es, s, ss   payload: [D's phone ID]
 * A → D   IK's second message as a frame:                           e, ee, se      payload: [A's phone ID, OK | BUSY]
 * then, in the channel: WifiSync's exchange (D says the hello, with AUTO for the code), D's vouches, A's vouches.
 * ```
 *
 * The prologue is "HouseMove sync 2" | move ID (`|` joins parts each as a four-byte length and its bytes), the ID A
 * advertises its tag with; a frame is a two-byte length and its bytes. A answers only a phone of the move (on someone's
 * list, not retired, its person not removed) that presents the key A has pinned for it: anything else, a stranger, a
 * removed phone, a phone of the move with another key than its pin, a phone of another move or something that is not
 * HouseMove, is closed without an answer (the phones say "… has a new key" from their pins: [Pins.changed],
 * [Pins.ownKeyIsNew]). One that comes while A is syncing by itself with another is told BUSY. D's static key travels encrypted to A's key; A's never travels; nothing of the move,
 * no phone ID and no long-lived key is in the clear. A takes D as proved only once D's first frame in the channel opens,
 * so a stranger who holds A's private key still cannot pose to A as D (key-compromise impersonation).
 */
internal object AutoSession {
    const val MAGIC = "HMAUTO2"
    private const val PROLOGUE = "HouseMove sync 2"

    /** A's answers in its handshake message. */
    const val OK = "OK"
    const val BUSY = "BUSY"

    /** How long each step of the handshake may take, in milliseconds. Tests shorten it. */
    @Volatile
    var stepMs = 5_000

    // ---- the tag ------------------------------------------------------------------------------------------------

    /** The hour [millis] falls in, counted from 1970 (UTC). */
    fun hourOf(millis: Long): Long = millis.floorDiv(3_600_000L)

    /**
     * The tag phone [phoneId] of the move [moveId] advertises in [hour]: a phone of the move can work out every phone's,
     * so it knows whom it dials; anyone without the move ID cannot, and cannot link one hour's tag to the next.
     */
    fun phoneTag(moveId: String, hour: Long, phoneId: String): String =
        hmacSha256("housemove-discovery-2:$moveId".toUtf8(), "phone:$hour:$phoneId".toUtf8()).toHex().take(16)

    /** The ID a phone advertises its tag with: the lowest the move is known by, so that phones that know each other's IDs agree. */
    fun advertisedMove(identity: MoveIdentity): String = identity.all.filter { it.isNotEmpty() }.min()

    /** The tag this phone ([phoneId]) advertises now. */
    fun advertisedTag(identity: MoveIdentity, phoneId: String, now: Long): String = phoneTag(advertisedMove(identity), hourOf(now), phoneId)

    private val SERVICE = Regex("HN-([0-9a-f]{16})-[a-z0-9]{1,12}")
    private val OLD_SERVICE = Regex("HM-([0-9a-f]{16})-[a-z0-9]{1,12}")

    /** The name a phone advertises: `HN-<tag>-<random>`. 2.3 phones look only for `HM-…`, and pass it by. */
    fun serviceName(tag: String, suffix: String): String = "HN-$tag-$suffix"

    /** The tag in an advertised name, or null when the name is not one of these. */
    fun tagOfService(name: String): String? = SERVICE.matchEntire(name)?.groupValues?.get(1)

    /**
     * Which of [phones] advertises [tag], and with which of the move's IDs: worked out for every ID the move is known by,
     * in the hour before, this hour and the next (clocks disagree). Null when none does.
     */
    fun whoAdvertises(tag: String, identity: MoveIdentity, phones: Collection<String>, now: Long): Pair<String, String>? {
        val hour = hourOf(now)
        for (id in identity.all.filter { it.isNotEmpty() }) for (h in listOf(hour, hour - 1, hour + 1)) for (p in phones) {
            if (phoneTag(id, h, p) == tag) return p to id
        }
        return null
    }

    /**
     * Is [name] a 2.3 phone of this move advertising (`HM-<move tag>-…`)? 2.4 does not sync by itself with it, but says why:
     * "Syncing by itself needs HouseMove 2.4 on … too."
     */
    fun isOldPhoneOf(name: String, identity: MoveIdentity, now: Long): Boolean {
        val tag = OLD_SERVICE.matchEntire(name)?.groupValues?.get(1) ?: return false
        val hour = hourOf(now)
        return identity.all.filter { it.isNotEmpty() }.any { id -> (-1L..1L).any { oldTag(id, hour + it) == tag } }
    }

    /** 2.3's move tag (design/auto-sync.md), only to notice a 2.3 phone of the move: 2.4 never advertises it. */
    private fun oldTag(moveId: String, hour: Long): String = hmacSha256("housemove-discovery:$moveId".toUtf8(), "tag:$hour".toUtf8()).toHex().take(16)

    // ---- the parts of the messages ------------------------------------------------------------------------------

    /** Each part as a four-byte length and its bytes: no two lists of parts give the same bytes. */
    fun joined(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { 4 + it.size })
        var at = 0
        for (p in parts) {
            out[at] = (p.size ushr 24).toByte(); out[at + 1] = (p.size ushr 16).toByte(); out[at + 2] = (p.size ushr 8).toByte(); out[at + 3] = p.size.toByte()
            p.copyInto(out, at + 4)
            at += 4 + p.size
        }
        return out
    }

    /** The parts of [b] as [joined] wrote them, or null when it is not that. */
    fun parts(b: ByteArray): List<ByteArray>? {
        val out = ArrayList<ByteArray>()
        var at = 0
        while (at < b.size) {
            if (at + 4 > b.size || out.size >= 16) return null
            val n = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
            if (n < 0 || at + 4 + n > b.size) return null
            out += b.copyOfRange(at + 4, at + 4 + n)
            at += 4 + n
        }
        return out
    }

    fun prologue(moveId: String): ByteArray = joined(PROLOGUE.toUtf8(), moveId.toUtf8())

    /** A handshake message as a frame: a two-byte length and its bytes. */
    fun writeFrame(out: DataOutputStream, message: ByteArray) {
        require(message.size <= Noise.MAX_MESSAGE)
        out.write(byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()))
        out.write(message)
    }

    fun readFrame(din: DataInputStream): ByteArray = ByteArray(din.readUnsignedShort()).also { din.readFully(it) }

    /** A public key as phone entries carry it (X.509, Base64), from its point. */
    fun keyText(point: ByteArray): String = base64Encode(P256Der.of(point))

    // ---- vouching -----------------------------------------------------------------------------------------------

    @Serializable
    private class Vouches(val keys: List<Vouch> = emptyList())

    /** Sends what this phone vouches for after a sync with [peer]. */
    fun sendVouches(out: DataOutputStream, store: Store, peer: String) {
        val bytes = AppJson.encodeToString(Vouches(store.pins.vouches(store.data.value, store.phone.id, peer).take(Pins.MAX_VOUCHES))).toUtf8()
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** Reads what [peer] vouches for, and takes in what may be taken ([Pins.take]). */
    fun takeVouches(din: DataInputStream, store: Store, peer: String, meeting: Boolean) {
        val n = din.readInt()
        if (n < 0 || n > MAX_VOUCHES_BYTES) throw IOException("bad vouches")
        val bytes = ByteArray(n).also { din.readFully(it) }
        val list = try {
            AppJson.decodeFromString<Vouches>(bytes.decodeUtf8()).keys
        } catch (_: Exception) {
            emptyList()
        }
        store.pins.take(list, store.data.value, store.phone.id, peer, meeting)
    }

    private const val MAX_VOUCHES_BYTES = 1_000_000

    // ---- dialling -----------------------------------------------------------------------------------------------

    /**
     * How a dial went. [word]: OK (they synced; [result] says how), NO (not answered, refused, or nothing to dial with),
     * BUSY, or LOST (not reached, or cut off).
     */
    class Dialled(val word: String, val result: WifiSync.Result? = null, val peerPhone: String? = null)

    /**
     * Dial phone [peerPhone], advertising at [host]:[port] with its tag of the move's ID [moveId], and sync with it in an
     * encrypted channel. Blocking; call off the main thread. [onProven] is called once the other phone has proved it is
     * [peerPhone].
     */
    fun dial(
        store: Store, host: String, port: Int, peerPhone: String, moveId: String = advertisedMove(store.identity.value),
        connectTimeoutMs: Int = 3_000, onProven: (Person) -> Unit = {},
    ): Dialled {
        val crypto = noiseCrypto() ?: return Dialled("NO")
        val me = store.phone.id
        val s = store.phone.keys.noiseKey() ?: return Dialled("NO")
        val d = store.data.value
        val rs = store.pins.keyOf(peerPhone, d, me) ?: return Dialled("NO")
        val person = d.memberPhone(peerPhone) ?: return Dialled("NO")
        val socket = Socket()
        try {
            socket.connect(host, port, connectTimeoutMs)
        } catch (_: Exception) {
            socket.close()
            return Dialled("LOST")
        }
        try {
            socket.use { sock ->
                sock.soTimeout = stepMs
                sock.tcpNoDelay = true
                val din = sock.dataIn()
                val out = sock.dataOut()
                val hs = Handshake(crypto, Noise.IK, initiator = true, prologue(moveId), s, rs)
                out.writeUTF(MAGIC)
                writeFrame(out, hs.write(joined(me.toUtf8())))
                out.flush()
                val answer = try {
                    readFrame(din)
                } catch (_: IOException) {
                    // Closed without an answer: this phone is not one the other takes (or it is not who the tag said).
                    return Dialled("NO")
                }
                val said = hs.read(answer)?.let { parts(it) }
                if (said == null || said.size != 2 || said[0].decodeUtf8() != peerPhone) return Dialled("NO")
                when (said[1].decodeUtf8()) {
                    OK -> Unit
                    BUSY -> return Dialled("BUSY")
                    else -> return Dialled("NO")
                }
                // Only the phone holding the key pinned for peerPhone could have written that answer.
                onProven(person)
                val (send, receive) = hs.split()
                val channel = SecureChannel(din, out, send, receive)
                sock.soTimeout = WifiSync.timeoutMs
                val result = WifiSync.joinProven(store, sock, channel.dataIn, channel.dataOut, WifiSync.Proven(peerPhone, person.id))
                if (!result.listsSynced()) return Dialled("OK", result, peerPhone)
                store.pins.synced(peerPhone, store.phone.keys.publicText)
                try {
                    sendVouches(channel.dataOut, store, peerPhone)
                    takeVouches(channel.dataIn, store, peerPhone, meeting = false)
                } catch (_: IOException) {
                    // The sync itself went through; the keys are vouched for again next time.
                }
                return Dialled("OK", result, peerPhone)
            }
        } catch (_: Exception) {
            return Dialled("LOST")
        }
    }

    // ---- answering ----------------------------------------------------------------------------------------------

    /** How an answer went: the sync with [peerPhone] and what came of it. */
    class Answered(val result: WifiSync.Result, val peerPhone: String)

    /**
     * Answer a phone that dialled this one on [s], for [store]'s move. [slot] is taken once the phone has proved itself,
     * for the length of the sync: a phone already syncing by itself says BUSY. Returns null when nothing was synced (a
     * stranger, a phone of another move, a key that is not the pinned one, BUSY, a dropped connection).
     */
    fun answer(store: Store, s: Socket, slot: AutoSlot, onProven: (Person) -> Unit = {}): Answered? {
        s.soTimeout = stepMs
        s.tcpNoDelay = true
        val din = s.dataIn()
        val out = s.dataOut()
        // The first line is short and known; anything else is not a HouseMove phone of 2.4, and is not answered.
        val length = din.readUnsignedShort()
        if (length != MAGIC.length) return null
        if (ByteArray(length).also { din.readFully(it) }.decodeUtf8() != MAGIC) return null
        val first = readFrame(din)
        val crypto = noiseCrypto() ?: return null
        val me = store.phone.id
        val sk = store.phone.keys.noiseKey() ?: return null
        // The ID the dialling phone used is the one this phone advertises with; any other it knows the move by is tried too.
        val ids = store.identity.value.all.filter { it.isNotEmpty() }.sorted()
        var hs: Handshake? = null
        var said: List<ByteArray>? = null
        for (id in ids) {
            val h = Handshake(crypto, Noise.IK, initiator = false, prologue(id), sk)
            val p = h.read(first)?.let { parts(it) } ?: continue
            hs = h
            said = p
            break
        }
        if (hs == null || said == null || said.size != 1) return null
        val peer = said[0].decodeUtf8()
        val d = store.data.value
        // Only a phone of the move, as this phone has it now, with a pin: anyone else is closed without an answer.
        val person = d.memberPhone(peer) ?: return null
        if (peer == me) return null
        val pinned = store.pins.keyOf(peer, d, me) ?: return null
        val presented = hs.remoteStatic ?: return null
        fun reply(word: String) {
            writeFrame(out, hs.write(joined(me.toUtf8(), word.toUtf8())))
            out.flush()
        }
        // A phone of the move with another key than its pin is not taken: it needs a code sync first (design: never replaced silently).
        if (!pinned.contentEquals(presented)) return null
        if (!slot.take()) {
            reply(BUSY)
            return null
        }
        try {
            reply(OK)
            val (send, receive) = hs.split()
            val channel = SecureChannel(din, out, send, receive)
            // The dialling phone has proved its key only once its first frame opens (ss alone could be made with this phone's own key).
            if (!channel.awaitFirst()) return null
            onProven(person)
            s.soTimeout = WifiSync.timeoutMs
            val host = WifiSync.Host(store, null, "", ::currentTimeMillis, letNewPhonesIn = false, confirmJoins = false, onResult = {})
            val result = host.serveProven(channel.dataIn, channel.dataOut, WifiSync.Proven(peer, person.id))
            if (!result.listsSynced()) return Answered(result, peer)
            store.pins.synced(peer, store.phone.keys.publicText)
            try {
                takeVouches(channel.dataIn, store, peer, meeting = false)
                sendVouches(channel.dataOut, store, peer)
            } catch (_: IOException) {
                // The sync itself went through; the keys are vouched for again next time.
            }
            return Answered(result, peer)
        } finally {
            slot.release()
        }
    }
}

/** One sync by itself at a time on a phone, dialling or answering (design/auto-sync.md, "One at a time"). */
class AutoSlot {
    private val lock = Lock()
    private var busy = false

    /** Takes the slot; false when a sync by itself is already under way. */
    internal fun take(): Boolean = synchronized(lock) {
        if (busy) false else {
            busy = true
            true
        }
    }

    internal fun release() = synchronized(lock) { busy = false }
}
