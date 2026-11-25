package app.housemove.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Settings that belong to the phone and are the same in every move on it. */
@Serializable
data class PhoneSettings(
    /** The name this phone's owner goes by. Offered for each new move, and given when joining with a Helper code. */
    val name: String = "",
    /** May book titles be fetched online from their barcode? Null until the person has been asked. */
    val bookLookup: Boolean? = null,
    /** The folder the daily small backup is written to: a tree picked once through the system's folder picker (see DailyBackup). */
    val backupFolder: String = "",
    /** "Sync by itself on Wi-Fi": phones of the same move find each other and sync while HouseMove is open (design/auto-sync.md). */
    val autoSync: Boolean = true,
)

/**
 * This installed copy of the app. [id] is its phone ID: made once, and kept where Android's own
 * backup does not copy it, so a phone restored or cloned from this one gets an ID of its own.
 * [deviceName] is the name the phone has in its own settings ("Priya's phone"), shown when it
 * joins a move. [settings] are shared by every move on the phone.
 */
class Phone internal constructor(
    val id: String,
    private val deviceNameOf: () -> String,
    private val settingsFile: File?,
    /** Where this phone's key pair for update files is kept, beside the phone ID (see [UpdateKeyPair]). Null: made afresh, kept in memory only. */
    private val keysFile: File? = null,
) {
    /**
     * This phone's key pair for update files: made the first time it is asked for, and kept from then on. None on a
     * phone without update-file crypto ([platformCrypto] is null: the iPhone before the app registers CryptoKit).
     */
    val keys: UpdateKeyPair by lazy { platformCrypto?.phoneKeys(id, keysFile) ?: NoUpdateKeys }

    val deviceName: String get() = try {
        deviceNameOf().trim()
    } catch (_: Exception) {
        ""
    }

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<PhoneSettings> = _settings

    private fun readSettings(): PhoneSettings = try {
        settingsFile?.takeIf { it.isFile }?.let { AppJson.decodeFromString<PhoneSettings>(it.readText()) } ?: PhoneSettings()
    } catch (_: Exception) {
        PhoneSettings()
    }

    /** Taken while the settings change. */
    private val settingsLock = Lock()

    fun updateSettings(f: (PhoneSettings) -> PhoneSettings) = synchronized(settingsLock) {
        val next = f(_settings.value)
        if (next == _settings.value) return@synchronized
        _settings.value = next
        settingsFile?.let { writeWhole(it, AppJson.encodeToString(next)) }
    }

    companion object {
        /** The phone ID kept in [file], made the first time it is asked for. */
        internal fun idIn(file: File): String {
            try {
                if (file.isFile) file.readText().trim().takeIf { it.length >= 8 }?.let { return it }
            } catch (_: Exception) {
            }
            val id = "p" + randomCode(20)
            file.parentFile?.mkdirs()
            writeWhole(file, id)
            return id
        }

        /** A phone for a move kept in a folder on its own (as 2.0 kept it, and as tests use one): its ID is kept in that folder. */
        fun standalone(dir: File): Phone = Phone(idIn(File(dir, "phone-id")), { "" }, null, File(dir, "phone-key.json"))
    }
}

/**
 * Which move a folder holds. [id] is made once by the founding phone and never changes; it is kept
 * outside the data, so no merge can alter it, and it is checked before any data moves. A move from
 * 2.0 or earlier had no ID: each of its phones works one out from the move's founder ([derived]).
 * Two such phones that worked out different ones (one had not yet heard of an earlier person)
 * fall back once to 2.0's check that they share people or things, and keep each other's as
 * [aliases].
 */
@Serializable
data class MoveIdentity(val id: String = "", val aliases: List<String> = emptyList(), val derived: Boolean = false) {
    fun matches(other: String): Boolean = other.isNotEmpty() && (other == id || other in aliases)
    fun matches(other: MoveIdentity): Boolean = matches(other.id) || other.aliases.any { matches(it) }

    /** Every ID this move is known by. */
    val all: List<String> get() = listOf(id) + aliases

    /** This identity, also knowing every ID [other] goes by. */
    fun withAliasesOf(other: MoveIdentity): MoveIdentity {
        val more = (aliases + other.all).filter { it.isNotEmpty() && it != id }.distinct()
        return if (more == aliases) this else copy(aliases = more)
    }

    /** The short tag printed in the QR code of a label, so a scanned label can be told to belong to this move. */
    val tag: String get() = moveTag(id)
}

/** The short tag of a move ID, printed in label QR codes. */
fun moveTag(moveId: String): String = if (moveId.isEmpty()) "" else digest("housemove tag " + moveId).take(4).uppercase()

/**
 * The move ID a move from before 2.1 gets: worked out from its founder (the first person by an open
 * grant), so every phone that knows the founder works out the same one.
 */
fun AppData.derivedMoveId(): String {
    val founder = Roles(people).founder
        ?: people.minWithOrNull(compareBy({ p -> p.grants.minOfOrNull { it.at } ?: Long.MAX_VALUE }, { it.id }))?.id
        ?: return "m" + randomCode(20)
    return "m" + digest("housemove move " + founder).take(20)
}

/**
 * One phone's letter for handwritten codes, its code counter and its printed label numbers in one
 * move, and what it has handed out from its own blocks to phones it admitted. Kept beside the
 * phone ID, outside Android's backup, so that restoring an older backup never hands out anything
 * twice. Nothing here is ever given back or given out again.
 */
@Serializable
internal data class Codes(
    val letter: String = "",
    val nextSeq: Int = 1,
    /** The block of label numbers this phone was given; it prints from the first part of it ([ownLabels]). */
    val labelsFrom: Int = 0,
    val labelsTo: Int = 0,
    /** The number the next printed label gets, or 0 for the first of [ownLabels]. */
    val nextLabel: Int = 0,
    /** This phone founded the move: its block of letters is the single letters (see [nextInBlock]). */
    val founder: Boolean = false,
    /** Every letter this phone has handed out in the move. */
    val lettersGiven: List<String> = emptyList(),
    /** Every letter this phone has written codes with in the move, its present one and any before it: none is taken in another move. */
    val lettersUsed: List<String> = emptyList(),
    /** How many blocks of label numbers it has handed out ([givenBlock]). */
    val blocksGiven: Int = 0,
)

/** The founding phone's block of label numbers. It prints from 1 to 999 itself, as 2.0 did, and hands out the rest a thousand at a time. */
internal val FOUNDER_LABELS = 1..99_999

/**
 * The founding phone's block of label numbers, as [FOUNDER_LABELS], unless this phone prints from those in another of its moves
 * ([elsewhere]): then the first block of the same size, a million at a time further on, that it prints from in none of them.
 */
internal fun founderBlock(elsewhere: List<IntRange>): IntRange {
    var k = 0
    while (true) {
        val block = if (k == 0) FOUNDER_LABELS else k * 1_000_000 until k * 1_000_000 + FOUNDER_LABELS.last
        val own = ownLabels(block.first, block.last)
        if (elsewhere.none { it.overlaps(own) }) return block
        k++
    }
}

fun IntRange.overlaps(other: IntRange): Boolean = !isEmpty() && !other.isEmpty() && first <= other.last && other.first <= last

/** The numbers a phone prints from its block [from]..[to]: see [FOUNDER_LABELS]; half of a block of a thousand or more; a smaller block whole. */
internal fun ownLabels(from: Int, to: Int): IntRange = when {
    from <= 0 || to < from -> IntRange.EMPTY
    from == 1 -> 1..minOf(999, to)
    to - from + 1 >= 1000 -> from until from + (to - from + 1) / 2
    else -> from..to
}

/** The [n]th block (from 0) a phone with the block [from]..[to] may hand out, or null when it has no more. */
internal fun givenBlock(from: Int, to: Int, n: Int): IntRange? {
    val own = ownLabels(from, to)
    if (own.isEmpty() || own.last >= to) return null
    val size = if (from == 1) 1000 else (to - from + 1) / 10
    if (size < 100) return null
    val start = own.last + 1 + n * size
    return if (start + size - 1 > to) null else start until start + size
}

/**
 * A block of label numbers for a phone that was in the move before 2.1 and so was never given one:
 * worked out from its letter, which no other phone has, and above every block a 2.1 phone hands out.
 */
internal fun legacyLabels(letter: String): IntRange {
    val l = letter.trim().uppercase()
    val rank = when {
        l.length == 1 && l[0] in 'A'..'Z' -> l[0] - 'A'
        l.length == 2 && l.all { it in 'A'..'Z' } -> (l[0] - 'A' + 1) * 26 + (l[1] - 'A')
        else -> 702 + l.hashCode().mod(5000)
    }
    val start = 100_000 + rank * 1000
    return start until start + 1000
}

private val PRINTED = Regex("L\\d{3,9}", RegexOption.IGNORE_CASE)

/** The highest printed label number in [range] that is on something, or was printed (as 2.0 counted them), or 0. */
internal fun AppData.labelsUsed(range: IntRange): Int {
    var top = if (move.labelsPrinted in range) move.labelsPrinted else 0
    for (code in liveBoxes.map { it.label } + liveItems.mapNotNull { it.label }) {
        val c = code.trim()
        if (!PRINTED.matches(c)) continue
        val n = c.drop(1).toIntOrNull() ?: continue
        if (n in range && n > top) top = n
    }
    return top
}

/** Nothing has been saved in this move yet: no people, no things, no settings. */
internal fun AppData.bare(): Boolean =
    people.isEmpty() && rooms.isEmpty() && items.isEmpty() && boxes.isEmpty() && photos.isEmpty() && tasks.isEmpty() && expenses.isEmpty() &&
        supplies.isEmpty() && trips.isEmpty() && readings.isEmpty() && entries.isEmpty() && proposals.isEmpty() && move == MoveInfo()

// ---- small helpers -----------------------------------------------------------------

private val random = secureRandom()

/** Random letters and digits that cannot be mistaken for each other. */
internal fun randomCode(n: Int): String {
    val chars = "abcdefghjkmnpqrstuvwxyz23456789"
    return (1..n).map { chars[random.nextInt(chars.length)] }.joinToString("")
}

internal fun digest(text: String): String = sha256(text.toUtf8()).toHex()

/** Write [text] to [file] whole or not at all: a temporary file is written and then renamed over it. */
internal fun writeWhole(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }
}
