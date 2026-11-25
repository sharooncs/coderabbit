package app.housemove.sync

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.IOException
import app.housemove.data.Lock
import app.housemove.data.MoveIdentity
import app.housemove.data.Person
import app.housemove.data.Store
import app.housemove.data.currentTimeMillis
import app.housemove.data.hmacSha256
import app.housemove.data.sameBytes
import app.housemove.data.synchronized
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

/**
 * One sync by itself between two phones of a move on the same Wi-Fi (design/auto-sync.md): the handshake that proves
 * to each that the other is a phone of the move, today's sync ([WifiSync]) on the same connection with the code check
 * replaced by that proof, and the offer of Wi-Fi keys at the end. D dials, A answers:
 *
 * ```
 * D → A   "HMAUTO1", tag, D's service name, nD (32 random bytes)
 * A → D   nA (32 random bytes)
 * D → A   n (int), then n proofs, each HMAC(k, "HMAUTO1 dialler" | tag | nD | nA | idD), sorted
 * A → D   "OK" + HMAC(k, "HMAUTO1 answerer" | tag | nD | nA | idD | idA), or "NO", or "BUSY"
 * D → A   "GO" + HMAC(k, "HMAUTO1 confirm" | tag | nD | nA | idD | idA), or "NO"
 * A → D   "SYNC", or "NO"
 * then WifiSync's own exchange (D says the hello, with AUTO for the code), then D's offer of keys, then A's.
 * ```
 *
 * Strings are writeUTF, as in code sync; `|` joins parts each as a four-byte length and its bytes. The phone IDs are
 * never sent: each side tries the phones of the move it knows. Nothing of the move is sent before both proofs.
 */
internal object AutoSession {
    const val MAGIC = "HMAUTO1"
    const val NONCE = 32
    const val PROOF = 32

    /** At most this many proofs are read from a dialling phone. */
    const val MAX_PROOFS = 64

    private const val DIALLER = "HMAUTO1 dialler"
    private const val ANSWERER = "HMAUTO1 answerer"
    private const val CONFIRM = "HMAUTO1 confirm"
    private const val PAIR = "HMAUTO1 pair"

    /** How long each step of the handshake may take, in milliseconds. Tests shorten it. */
    @Volatile
    var stepMs = 5_000

    // ---- the tag ------------------------------------------------------------------------------------------------

    /** The hour [millis] falls in, counted from 1970 (UTC). */
    fun hourOf(millis: Long): Long = millis.floorDiv(3_600_000L)

    /** The tag of the move [moveId] in [hour]: only a phone that knows the move ID can work it out. */
    fun tag(moveId: String, hour: Long): String = hmacSha256(("housemove-discovery:$moveId").toUtf8(), "tag:$hour".toUtf8()).toHex().take(16)

    /** The tag this phone advertises: of the lowest ID the move is known by, so that phones that know each other's IDs agree. */
    fun advertisedTag(identity: MoveIdentity, now: Long): String = tag(identity.all.filter { it.isNotEmpty() }.min(), hourOf(now))

    /** The tags a phone of the move may advertise now: of every ID the move is known by, in the hour before, this hour and the next. */
    fun tagsOf(identity: MoveIdentity, now: Long): Set<String> {
        val hour = hourOf(now)
        return identity.all.filter { it.isNotEmpty() }.flatMap { id -> (-1L..1L).map { tag(id, hour + it) } }.toSet()
    }

    private val SERVICE = Regex("HM-([0-9a-f]{16})-[a-z0-9]{1,12}")

    /** The name a phone advertises: `HM-<tag>-<random>`. */
    fun serviceName(tag: String, suffix: String): String = "HM-$tag-$suffix"

    /** The tag in an advertised name, or null when the name is not one of these. */
    fun tagOfService(name: String): String? = SERVICE.matchEntire(name)?.groupValues?.get(1)

    // ---- proofs -------------------------------------------------------------------------------------------------

    /** Each part as a four-byte length and its bytes: no two lists of parts give the same bytes. */
    private fun joined(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { 4 + it.size })
        var at = 0
        for (p in parts) {
            out[at] = (p.size ushr 24).toByte(); out[at + 1] = (p.size ushr 16).toByte(); out[at + 2] = (p.size ushr 8).toByte(); out[at + 3] = p.size.toByte()
            p.copyInto(out, at + 4)
            at += 4 + p.size
        }
        return out
    }

    internal fun proof(key: ByteArray, label: String, tag: String, nD: ByteArray, nA: ByteArray, vararg ids: String): ByteArray =
        hmacSha256(key, joined(listOf(label.toUtf8(), tag.toUtf8(), nD, nA) + ids.map { it.toUtf8() }))

    /** Which kind of key proved a phone. The order is the one preferred when more than one would. */
    enum class Kind { PAIR, MEETING, WIFI }

    /** A key this phone proves with, and accepts proofs with: for each of [phones]. [id] names it (a Wi-Fi key's ID). */
    class Cred(val kind: Kind, val key: ByteArray, val phones: List<String>, val id: String = "")

    /**
     * Every key [store] can prove with and accept a proof with, each with the phones it counts for (design/auto-sync.md,
     * the table): its pair keys and meetings for their one phone, its Wi-Fi keys for every phone of the move.
     */
    fun creds(store: Store): List<Cred> {
        val d = store.data.value
        val me = store.phone.id
        val members = d.memberPhones() - me
        val out = ArrayList<Cred>()
        for ((phone, key) in store.ring.pairs()) if (phone in members) out += Cred(Kind.PAIR, key, listOf(phone))
        for (m in Meetings.recent(me, store.identity.value.all)) if (m.peer in members) out += Cred(Kind.MEETING, meetingKey(m.code), listOf(m.peer))
        for (k in store.ring.keys(d)) k.bytes?.let { out += Cred(Kind.WIFI, it, members.toList(), k.id) }
        return out
    }

    private fun meetingKey(code: String): ByteArray = "meeting:$code".toUtf8()

    /** The first key and phone, pair keys first, for which [matches] holds. */
    private fun search(creds: List<Cred>, matches: (Cred, String) -> Boolean): Pair<Cred, String>? {
        for (c in creds.sortedBy { it.kind.ordinal }) for (phone in c.phones) if (matches(c, phone)) return c to phone
        return null
    }

    // ---- the offer of keys --------------------------------------------------------------------------------------

    @Serializable
    private class Offer(val keys: List<WifiKey> = emptyList())

    private fun offerText(keys: List<WifiKey>): String {
        var list = keys
        while (true) {
            val text = AppJson.encodeToString(Offer(list))
            // writeUTF takes at most 65,535 bytes; keys that list very many outsiders are left out, the lowest IDs first.
            if (text.length < 60_000 || list.isEmpty()) return text
            list = list.dropLast(1)
        }
    }

    private fun offerFrom(text: String): List<WifiKey> = try {
        AppJson.decodeFromString<Offer>(text).keys.take(Ring.MAX_KEYS * 2)
    } catch (_: Exception) {
        emptyList()
    }

    /** Is the key that proved the other phone still good, and that phone still one of the move, now that the two have synced? */
    private fun stillGood(store: Store, d: AppData, cred: Cred, peer: String): Boolean =
        d.memberPhone(peer) != null && (cred.kind != Kind.WIFI || store.ring.keys(d).any { it.id == cred.id })

    private fun pairKey(cred: Cred, tag: String, nD: ByteArray, nA: ByteArray, a: String, b: String): ByteArray =
        proof(cred.key, PAIR, tag, nD, nA, minOf(a, b), maxOf(a, b))

    // ---- dialling -----------------------------------------------------------------------------------------------

    /** How a dial went. [word]: OK (they synced; [result] says how), NO (refused, or no key to prove with), BUSY, or LOST (not reached, or cut off). */
    class Dialled(val word: String, val result: WifiSync.Result? = null, val peerPhone: String? = null, val via: Kind? = null)

    /**
     * Dial the phone advertising at [host]:[port], prove this phone to it, and sync. [myName] is this phone's advertised
     * name (or blank), told to the other phone so it knows whom it synced with. Blocking; call off the main thread.
     */
    fun dial(store: Store, host: String, port: Int, myName: String = "", connectTimeoutMs: Int = 3_000, onProven: (Person) -> Unit = {}): Dialled {
        // Meetings and Wi-Fi keys first, then pair keys: at most as many proofs as a phone reads.
        val creds = creds(store).sortedBy { if (it.kind == Kind.PAIR) 1 else 0 }.take(MAX_PROOFS)
        if (creds.isEmpty()) return Dialled("NO")
        val socket = Socket()
        try {
            socket.connect(host, port, connectTimeoutMs)
        } catch (_: Exception) {
            socket.close()
            return Dialled("LOST")
        }
        try {
            socket.use { s ->
                s.soTimeout = stepMs
                s.tcpNoDelay = true
                val din = s.dataIn()
                val out = s.dataOut()
                val me = store.phone.id
                val tag = advertisedTag(store.identity.value, currentTimeMillis())
                val nD = Strong.bytes(NONCE)
                out.writeUTF(MAGIC); out.writeUTF(tag); out.writeUTF(myName); out.write(nD); out.flush()
                val nA = ByteArray(NONCE).also { din.readFully(it) }
                // Sorted, so that their order says nothing of which kinds of key this phone holds.
                val proofs = creds.map { proof(it.key, DIALLER, tag, nD, nA, me) }.sortedBy { it.toHex() }
                out.writeInt(proofs.size)
                for (p in proofs) out.write(p)
                out.flush()
                val word = din.readUTF()
                if (word != "OK") return Dialled(if (word == "BUSY") "BUSY" else "NO")
                val theirs = ByteArray(PROOF).also { din.readFully(it) }
                val found = search(creds) { c, phone -> sameBytes(proof(c.key, ANSWERER, tag, nD, nA, me, phone), theirs) }
                val person = found?.let { store.data.value.memberPhone(it.second) }
                if (found == null || person == null) {
                    out.writeUTF("NO"); out.flush()
                    return Dialled("NO")
                }
                val (cred, peer) = found
                out.writeUTF("GO"); out.write(proof(cred.key, CONFIRM, tag, nD, nA, me, peer)); out.flush()
                if (din.readUTF() != "SYNC") return Dialled("NO")
                onProven(person)
                s.soTimeout = WifiSync.timeoutMs
                val result = WifiSync.joinProven(store, s, din, out, WifiSync.Proven(peer, person.id))
                if (!result.ok) return Dialled("OK", result, peer, cred.kind)
                // The keys: this phone offers first, then takes in the other's.
                try {
                    val good = stillGood(store, store.data.value, cred, peer)
                    out.writeUTF(offerText(if (good) store.ring.keys(store.data.value) else emptyList())); out.flush()
                    val offered = offerFrom(din.readUTF())
                    if (good) {
                        store.ring.take(offered, store.data.value)
                        if (cred.kind != Kind.PAIR) store.ring.setPair(peer, pairKey(cred, tag, nD, nA, me, peer))
                        if (cred.kind == Kind.MEETING) Meetings.used(me, peer)
                    }
                } catch (_: IOException) {
                    // The sync itself went through; the keys are offered again next time.
                }
                return Dialled("OK", result, peer, cred.kind)
            }
        } catch (_: Exception) {
            return Dialled("LOST")
        }
    }

    // ---- answering ----------------------------------------------------------------------------------------------

    /** How an answer went: the sync with [peerPhone] (advertised as [peerName], or blank) and what came of it. */
    class Answered(val result: WifiSync.Result, val peerPhone: String, val peerName: String, val via: Kind)

    /**
     * Answer a phone that dialled this one on [s], for [store]'s move. [slot] is taken once the phone has proved
     * itself, for the length of the sync: a phone already syncing by itself says BUSY. Returns null when nothing was
     * synced (a stranger, a phone of another move, a refused proof, BUSY, a dropped connection).
     */
    fun answer(store: Store, s: Socket, slot: AutoSlot, onProven: (Person) -> Unit = {}): Answered? {
        s.soTimeout = stepMs
        s.tcpNoDelay = true
        val din = s.dataIn()
        val out = s.dataOut()
        // The first line is short and known; anything else is not a HouseMove phone, and is not answered.
        val length = din.readUnsignedShort()
        if (length != MAGIC.length) return null
        if (ByteArray(length).also { din.readFully(it) }.decodeToString() != MAGIC) return null
        val tag = din.readUTF()
        val theirName = din.readUTF()
        val nD = ByteArray(NONCE).also { din.readFully(it) }
        // A phone of another move, or anything that is not a phone of this one, is not answered either.
        if (tag !in tagsOf(store.identity.value, currentTimeMillis())) return null
        val nA = Strong.bytes(NONCE)
        out.write(nA); out.flush()
        val n = din.readInt()
        if (n !in 0..MAX_PROOFS) return null
        val proofs = HashSet<String>()
        repeat(n) { proofs += ByteArray(PROOF).also { din.readFully(it) }.toHex() }
        val me = store.phone.id
        val creds = creds(store)
        val found = search(creds) { c, phone -> proof(c.key, DIALLER, tag, nD, nA, phone).toHex() in proofs }
        val person = found?.let { store.data.value.memberPhone(it.second) }
        if (found == null || person == null) {
            out.writeUTF("NO"); out.flush()
            return null
        }
        if (!slot.take()) {
            out.writeUTF("BUSY"); out.flush()
            return null
        }
        try {
            val (cred, peer) = found
            out.writeUTF("OK"); out.write(proof(cred.key, ANSWERER, tag, nD, nA, peer, me)); out.flush()
            if (din.readUTF() != "GO") return null
            val confirm = ByteArray(PROOF).also { din.readFully(it) }
            if (!sameBytes(confirm, proof(cred.key, CONFIRM, tag, nD, nA, peer, me))) {
                out.writeUTF("NO"); out.flush()
                return null
            }
            out.writeUTF("SYNC"); out.flush()
            onProven(person)
            s.soTimeout = WifiSync.timeoutMs
            val host = WifiSync.Host(store, null, "", ::currentTimeMillis, letNewPhonesIn = false, confirmJoins = false, onResult = {})
            val result = host.serveProven(din, out, WifiSync.Proven(peer, person.id))
            if (!result.ok) return Answered(result, peer, theirName, cred.kind)
            // The keys: the dialling phone offers first; this one takes them in, makes one if it still has none, and offers.
            try {
                val offered = offerFrom(din.readUTF())
                val d = store.data.value
                val good = stillGood(store, d, cred, peer)
                if (good) {
                    store.ring.take(offered, d)
                    store.ring.mintIfNone(d)
                }
                out.writeUTF(offerText(if (good) store.ring.keys(d) else emptyList())); out.flush()
                if (good) {
                    if (cred.kind != Kind.PAIR) store.ring.setPair(peer, pairKey(cred, tag, nD, nA, peer, me))
                    if (cred.kind == Kind.MEETING) Meetings.used(me, peer)
                }
            } catch (_: IOException) {
                // The sync itself went through; the keys are offered again next time.
            }
            return Answered(result, peer, theirName, cred.kind)
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
