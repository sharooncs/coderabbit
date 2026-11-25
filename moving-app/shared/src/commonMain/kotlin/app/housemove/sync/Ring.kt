package app.housemove.sync

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.Lock
import app.housemove.data.MoveIdentity
import app.housemove.data.Person
import app.housemove.data.currentTimeMillis
import app.housemove.data.hexBytes
import app.housemove.data.isFile
import app.housemove.data.isRetired
import app.housemove.data.phoneEntry
import app.housemove.data.readText
import app.housemove.data.strongRandomBytes
import app.housemove.data.synchronized
import app.housemove.data.toHex
import app.housemove.data.writeWhole
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

/**
 * A Wi-Fi key of a move (design/auto-sync.md, "The secrets"): [key] is 32 random bytes as hex, [id] 8 random bytes as
 * hex, and [out] the phones that were outside the move when it was made (retired phones, and phones of people
 * removed). A key is good only while every phone outside the move is on its list: a phone taken out after the key was
 * made may still hold it.
 */
@Serializable
internal data class WifiKey(val id: String, val key: String, val out: List<String> = emptyList(), val at: Long = 0) {
    val bytes: ByteArray? get() = hexBytes(key)

    /** Is this key still good when [outsiders] are the phones outside the move? */
    fun goodFor(outsiders: Set<String>): Boolean = outsiders.all { it in out }

    /** As a key must be: what another phone offers is taken only when it is. */
    val wellFormed: Boolean
        get() = id.length == 16 && hexBytes(id) != null && key.length == 64 && hexBytes(key) != null && out.size <= MAX_OUT && out.all { it.length in 1..64 }

    companion object {
        const val MAX_OUT = 2000
    }
}

@Serializable
private data class RingSaved(val move: String = "", val keys: List<WifiKey> = emptyList(), val pairs: Map<String, String> = emptyMap())

/** The phones outside the move: retired ones, and every phone of someone removed. */
internal fun AppData.outsiderPhones(): Set<String> {
    val out = HashSet<String>()
    for (p in people) for (ph in p.phones) if (p.removed || ph.isRetired) out += ph.id
    return out
}

/** The person whose phone [phoneId] is, when it is a phone of the move: on their list of phones, not retired, and they are not removed. */
internal fun AppData.memberPhone(phoneId: String): Person? {
    val (p, ph) = phoneEntry(phoneId) ?: return null
    return if (p.removed || ph.isRetired) null else p
}

/** Every phone of the move ([memberPhone]). */
internal fun AppData.memberPhones(): Set<String> {
    val out = LinkedHashSet<String>()
    for (p in people) if (!p.removed) for (ph in p.phones) if (!ph.isRetired) out += ph.id
    return out
}

/** Where new keys and nonces come from. A test that must repeat a run exactly puts in a seeded source; the app never does. */
internal object Strong {
    @Volatile
    var bytes: (Int) -> ByteArray = ::strongRandomBytes
}

/**
 * One move's keys for syncing by itself, on this phone (design/auto-sync.md): its Wi-Fi keys and its pair keys, kept in
 * [file] (`ring.json` in the move's no-backup folder, beside the codes) and nowhere else. A ring saved for another move
 * (the move was erased, or another one was joined into the same folder) counts as empty. [identity] is the move's.
 */
internal class Ring(private val file: File?, private val identity: () -> MoveIdentity) {
    private val lock = Lock()
    private var saved: RingSaved = read()

    /** Counts up at every change, so that the engine starts afresh with phones that refused before. */
    @Volatile
    var version = 0
        private set

    private fun read(): RingSaved = try {
        file?.takeIf { it.isFile }?.let { AppJson.decodeFromString<RingSaved>(it.readText()) } ?: RingSaved()
    } catch (_: Exception) {
        RingSaved()
    }

    /** What is saved, when it is this move's. Call holding [lock]. */
    private fun current(): RingSaved = if (saved.move.isNotEmpty() && identity().matches(saved.move)) saved else RingSaved()

    private fun write(next: RingSaved) {
        val stamped = next.copy(move = identity().id, keys = next.keys.sortedByDescending { it.id }.take(MAX_KEYS))
        if (stamped == saved) return
        saved = stamped
        version++
        try {
            file?.let { writeWhole(it, AppJson.encodeToString(stamped)) }
        } catch (_: Exception) {
            // Kept in memory; written again at the next change.
        }
    }

    /** The Wi-Fi keys that are still good as [d] has it, the one in use (the highest ID) first. A key that is not is dropped for good. */
    fun keys(d: AppData): List<WifiKey> = synchronized(lock) {
        val c = current()
        val outsiders = d.outsiderPhones()
        val good = c.keys.filter { it.goodFor(outsiders) }
        if (good.size != c.keys.size) write(c.copy(keys = good))
        good.sortedByDescending { it.id }
    }

    /** The pair keys, by the other phone's ID. */
    fun pairs(): Map<String, ByteArray> = synchronized(lock) {
        current().pairs.mapNotNull { (phone, hex) -> hexBytes(hex)?.takeIf { it.size == 32 }?.let { phone to it } }.toMap()
    }

    fun setPair(phoneId: String, key: ByteArray) = synchronized(lock) {
        val c = current()
        write(c.copy(pairs = c.pairs + (phoneId to key.toHex())))
    }

    /** Takes in the Wi-Fi keys another phone offered that are well formed and good as [d] has it. Returns whether any was new. */
    fun take(offered: List<WifiKey>, d: AppData): Boolean = synchronized(lock) {
        val c = current()
        val outsiders = d.outsiderPhones()
        val known = c.keys.map { it.id }.toSet()
        val fresh = offered.filter { it.wellFormed && it.id !in known && it.goodFor(outsiders) }.distinctBy { it.id }
        if (fresh.isEmpty()) return false
        write(c.copy(keys = (c.keys.filter { it.goodFor(outsiders) } + fresh.map { it.copy(id = it.id.lowercase(), key = it.key.lowercase()) })))
        true
    }

    /** Makes a Wi-Fi key when there is no good one as [d] has it, listing the phones outside the move now. Returns whether it made one. */
    fun mintIfNone(d: AppData): Boolean = synchronized(lock) {
        if (keys(d).isNotEmpty()) return false
        val c = current()
        val key = WifiKey(Strong.bytes(8).toHex(), Strong.bytes(32).toHex(), d.outsiderPhones().sorted(), currentTimeMillis())
        write(c.copy(keys = c.keys + key))
        true
    }

    /** Nothing to prove with but a meeting: no good Wi-Fi key and no pair key with a phone of the move. */
    fun empty(d: AppData): Boolean {
        if (keys(d).isNotEmpty()) return false
        val members = d.memberPhones()
        return pairs().keys.none { it in members }
    }

    companion object {
        /** At most this many Wi-Fi keys are kept, the highest IDs: phones that have not met since a new one was made still share one. */
        const val MAX_KEYS = 4
    }
}
