package app.housemove.sync

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.Lock
import app.housemove.data.MoveIdentity
import app.housemove.data.Person
import app.housemove.data.Role
import app.housemove.data.delete
import app.housemove.data.isFile
import app.housemove.data.isRetired
import app.housemove.data.phoneEntry
import app.housemove.data.readText
import app.housemove.data.synchronized
import app.housemove.data.writeWhole
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

/**
 * The key this phone accepts for another phone of the move (design/encryption.md, "The trust root: pins"): [key] is that
 * phone's public key as its entry carries it (X.509, Base64), and [how] says how it was learnt: [MET] in a code sync or
 * invite join with that phone (the code was seen on its screen, in person), or vouched for by a phone met in one; [MOVE]
 * first seen on the phone's entry in the move's data (trust on first use). [v2] is set once the two phones have synced
 * encrypted: that phone has HouseMove 2.4 or later, and is never again taken without encryption.
 */
@Serializable
internal data class Pin(val phone: String, val key: String, val how: String = MOVE, val at: Long = 0, val v2: Boolean = false) {
    companion object {
        const val MET = "met"
        const val MOVE = "move"
    }
}

/** A phone's key as another phone vouches for it after a sync: a pin it has [Pin.MET]. */
@Serializable
internal data class Vouch(val phone: String, val key: String)

@Serializable
private data class PinsSaved(
    val move: String = "",
    val pins: List<Pin> = emptyList(),
    val older: Map<String, Long> = emptyMap(),
    /** This phone's own key as the other phones last took it; [newKey] once it has made another, until one takes that. */
    val mine: String = "",
    val newKey: Boolean = false,
)

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

/** The key on [phoneId]'s entry in the move, when it is one a handshake can use. */
internal fun AppData.entryKey(phoneId: String): String? = phoneEntry(phoneId)?.second?.key?.takeIf { pointOfKeyText(it) != null }

/**
 * One move's pins on this phone (`pins.json` in the move's no-backup folder, beside the codes; never in a backup, an
 * update file or a sync), which replace 2.3's key ring: 2.3's `ring.json` beside it ([legacy]) is deleted when the move is
 * opened, so its Wi-Fi keys and pair keys are never used again. A pin is used only for a phone of the move as the data
 * has it now ([memberPhone]); the phones of someone removed, and retired phones, are unpinned as soon as this phone
 * learns of it. A pin is never replaced silently: a phone whose entry shows another key than its pin is not synced with
 * by itself until a code sync (or a phone met in one, vouching) pins its new key. Pins saved for another move count as none.
 */
internal class Pins(private val file: File?, legacy: File?, private val identity: () -> MoveIdentity, private val clock: () -> Long) {
    private val lock = Lock()
    private var saved: PinsSaved

    init {
        // 2.3's Wi-Fi keys and pair keys travelled in the clear: from 2.4 they open nothing.
        try {
            if (legacy != null && legacy.isFile) legacy.delete()
        } catch (_: Exception) {
        }
        saved = read()
    }

    /** Counts up at every change of a pin, so that syncing by itself tries every phone afresh. */
    @Volatile
    var version = 0
        private set

    private fun read(): PinsSaved = try {
        file?.takeIf { it.isFile }?.let { AppJson.decodeFromString<PinsSaved>(it.readText()) } ?: PinsSaved()
    } catch (_: Exception) {
        PinsSaved()
    }

    /** What is saved, when it is this move's. Call holding [lock]. */
    private fun current(): PinsSaved = if (saved.move.isNotEmpty() && identity().matches(saved.move)) saved else PinsSaved()

    private fun write(next: PinsSaved, changed: Boolean) {
        val stamped = next.copy(move = identity().id, pins = next.pins.sortedBy { it.phone })
        if (stamped == saved) return
        saved = stamped
        if (changed) version++
        try {
            file?.let { writeWhole(it, AppJson.encodeToString(stamped)) }
        } catch (_: Exception) {
            // Kept in memory; written again at the next change.
        }
    }

    /**
     * Brings the pins up to date with the move [d], for phone [me]: every phone of the move with a usable key on its entry
     * and no pin is pinned from the move; every pin of a phone that is not one of the move (removed, retired, gone) is
     * dropped. Returns the pins of the phones of the move, by phone ID.
     */
    fun learn(d: AppData, me: String): Map<String, Pin> = synchronized(lock) {
        val c = current()
        val members = d.memberPhones() - me
        val kept = c.pins.filter { it.phone in members }
        val have = kept.map { it.phone }.toSet()
        val fresh = members.filter { it !in have }.mapNotNull { id -> d.entryKey(id)?.let { Pin(id, it, Pin.MOVE, clock()) } }
        val next = kept + fresh
        if (next.size != c.pins.size || fresh.isNotEmpty() || c.move.isEmpty()) write(c.copy(pins = next), changed = fresh.isNotEmpty() || kept.size != c.pins.size)
        next.associateBy { it.phone }
    }

    /** The key (a point) this phone accepts for [phoneId] now, as [d] has the move: none for a phone that is not one of it. */
    fun keyOf(phoneId: String, d: AppData, me: String): ByteArray? = learn(d, me)[phoneId]?.let { pointOfKeyText(it.key) }

    /** The pin of [phoneId], if any (a phone of the move or not). */
    fun pinOf(phoneId: String): Pin? = synchronized(lock) { current().pins.firstOrNull { it.phone == phoneId } }

    /** The phones of the move whose entry in [d] shows another key than their pin: each needs a code sync (or a vouch) first. */
    fun changed(d: AppData, me: String): List<String> = learn(d, me).values.filter { p -> d.entryKey(p.phone)?.let { it != p.key } == true }.map { it.phone }

    /**
     * Phone [phoneId] proved [keyText] in a code sync or an invite join with this one: its pin, as met, in place of any other.
     * It took this phone's key as it is now ([myKey]) too.
     */
    fun met(phoneId: String, keyText: String, myKey: String) = synchronized(lock) {
        if (pointOfKeyText(keyText) == null) return@synchronized
        val c = current()
        val was = c.pins.firstOrNull { it.phone == phoneId }
        if (was != null && was.key == keyText && was.how == Pin.MET && was.v2 && !c.newKey && c.mine == myKey) return@synchronized
        val pin = Pin(phoneId, keyText, Pin.MET, clock(), v2 = true)
        // A code sync took this phone's key as it is now, too.
        write(c.copy(pins = c.pins.filter { it.phone != phoneId } + pin, older = c.older - phoneId, newKey = false, mine = myKey), changed = was?.key != keyText || was.how != Pin.MET)
    }

    /** This phone has just synced encrypted with [phoneId]: it has 2.4 or later, and took this phone's key as it is now ([myKey]). */
    fun synced(phoneId: String, myKey: String) = synchronized(lock) {
        val c = current()
        val was = c.pins.firstOrNull { it.phone == phoneId }
        if ((was == null || was.v2) && phoneId !in c.older && !c.newKey && (c.mine == myKey || myKey.isBlank())) return@synchronized
        write(c.copy(pins = c.pins.map { if (it.phone == phoneId) it.copy(v2 = true) else it }, older = c.older - phoneId, newKey = false, mine = myKey), changed = false)
    }

    /**
     * Has this phone made a new key ([myKey], as its entry carries it) since the other phones last took one? Then their pins
     * of it are the old key, and none syncs with it by itself until a code sync (or a phone met in one, vouching): "This
     * phone has a new key." The first key seen is taken as the one the others know.
     */
    fun ownKeyIsNew(myKey: String): Boolean = synchronized(lock) {
        if (myKey.isBlank()) return@synchronized false
        val c = current()
        if (c.mine == myKey) return@synchronized c.newKey
        val fresh = c.mine.isNotEmpty() || c.newKey
        write(c.copy(mine = myKey, newKey = fresh), changed = false)
        fresh
    }

    /** Has [phoneId] ever synced encrypted with this phone? Then it has 2.4, and is never taken without encryption. */
    fun speaksV2(phoneId: String): Boolean = pinOf(phoneId)?.v2 == true

    /** This phone has just synced, without encryption, with [phoneId], whose HouseMove is older. */
    fun older(phoneId: String) = synchronized(lock) {
        val c = current()
        if (phoneId.isEmpty() || phoneId in c.older || c.pins.any { it.phone == phoneId && it.v2 }) return@synchronized
        write(c.copy(older = c.older + (phoneId to clock())), changed = false)
    }

    /** The phones of the move that last synced with this one without encryption, because their HouseMove is older. */
    fun olderPhones(d: AppData, me: String): List<String> = synchronized(lock) { current().older.keys.toList() }.filter { it != me && d.memberPhone(it) != null }

    /** What this phone vouches for after a sync with [peer]: the keys it has met, of phones of the move other than the two. */
    fun vouches(d: AppData, me: String, peer: String): List<Vouch> =
        learn(d, me).values.filter { it.how == Pin.MET && it.phone != peer }.map { Vouch(it.phone, it.key) }

    /**
     * Takes in what [peer] vouched for, as [d] has the move after the sync. In a code sync or invite join ([meeting]) every
     * vouch counts; in a sync by itself, only an Organiser's or a Decider's. A vouch is taken only for a phone of the move,
     * other than the two, whose entry carries that very key: it then pins that key as met (a new key in place of an old
     * one included). Returns whether a pin changed.
     */
    fun take(vouched: List<Vouch>, d: AppData, me: String, peer: String, meeting: Boolean): Boolean {
        val by = d.memberPhone(peer) ?: return false
        if (!meeting && by.role != Role.ORGANISER && by.role != Role.DECIDER) return false
        var changed = false
        for (v in vouched.take(MAX_VOUCHES)) {
            if (v.phone == me || v.phone == peer || d.memberPhone(v.phone) == null || d.entryKey(v.phone) != v.key) continue
            val was = pinOf(v.phone)
            if (was != null && was.key == v.key && was.how == Pin.MET) continue
            synchronized(lock) {
                val c = current()
                val pin = Pin(v.phone, v.key, Pin.MET, clock(), v2 = was?.v2 == true && was.key == v.key)
                write(c.copy(pins = c.pins.filter { it.phone != v.phone } + pin), changed = true)
            }
            changed = true
        }
        return changed
    }

    companion object {
        /** At most this many vouches are read from another phone. */
        const val MAX_VOUCHES = 500
    }
}
