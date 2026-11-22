package app.housemove.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
}

/** Where new ids come from. A test that has to repeat a run exactly swaps in a seeded source; the app never does. */
@Volatile
internal var idSource: () -> String = { UUID.randomUUID().toString() }

fun newId(): String = idSource()

/**
 * This phone's own settings for one move, never synced. [letter] and [nextSeq] are kept beside the
 * phone ID outside Android's backup (see [Codes]); [bookLookup] is the same in every move on the
 * phone when the move was opened through [Moves] (see [PhoneSettings]).
 */
@Serializable
data class DeviceInfo(
    val name: String = "",
    /** Prefix for handwritten box codes. Each phone must use a different letter. */
    val letter: String = "A",
    /** Next number for a handwritten code on this phone. */
    val nextSeq: Int = 1,
    /** Old-home room currently being worked in (walk-through, packing). */
    val currentRoomId: String? = null,
    val currentTripId: String? = null,
    val lastSyncAt: Long = 0,
    val lastBackupAt: Long = 0,
    /** May book titles be fetched online from their barcode? Null until the person has been asked. */
    val bookLookup: Boolean? = null,
    /**
     * A helper's phone: Home shows only packing, loading, unloading and search.
     * Since 2.0 this follows the role of the phone's person: it is true exactly when they are a Helper.
     * Setting it still works as the old "helper mode" switch while the phone is the only one in the move.
     */
    val helper: Boolean = false,
    /** The [Person] this phone belongs to. [name] and [letter] are kept the same as that person's. */
    val personId: String = "",
    /** The phone that wrote these settings. Another phone's means they came back from a backup or a copy of that phone. */
    val phoneId: String = "",
    /** Every change up to this stamp is on another phone: the last complete sync sent it (see [Store.unshared]). */
    val sharedUpTo: Long = 0,
    /** The same, for what Helpers are not sent: up to here it is on an Organiser's or Decider's phone too. */
    val sharedAllUpTo: Long = 0,
)

/** What happened when something was scanned into a box. */
sealed class ScanIn {
    abstract val entry: BoxEntry
    data class Added(override val entry: BoxEntry) : ScanIn()
    data class AlreadyHere(override val entry: BoxEntry) : ScanIn()
    data class Moved(override val entry: BoxEntry, val fromLabel: String?) : ScanIn()
}

/**
 * Holds the whole dataset in memory and persists it as one JSON file.
 * The dataset for a house move is small (hundreds of records), so this is
 * simpler and easier to merge and back up than a database. Writes are atomic:
 * a temp file is written, the previous file is kept as .bak, then renamed.
 *
 * The store is also where the rules about who may do what are applied to
 * changes made on this phone (see [can] and Rules.kt). The plain `put` and
 * `update…` functions never fail: a protected part the phone's person may not
 * change is simply left as it was. The functions that return an [Outcome] say
 * why when they refuse.
 *
 * A Store holds one move. [Moves] keeps several on a phone, each in its own folder, and gives each
 * its Store; a Store made on its own for a folder (as 2.0 kept its one move, and as tests use one)
 * keeps the phone ID and the codes in that folder.
 */
class Store internal constructor(
    private val dir: File,
    private val clock: () -> Long,
    /** This installed copy of the app (see [Phone]). */
    val phone: Phone,
    /** Where this phone's letter, code counter and label numbers for the move are kept: outside Android's backup. */
    private val codesDir: File,
    /** Letters this phone uses in its other moves, which it does not take in this one. */
    private val lettersElsewhere: () -> Set<String>,
    /** Where the move's photo files are kept. Android's backup does not copy them. */
    photos: File = File(dir, "photos"),
) {
    /** A move kept in [dir] on its own, with the phone's ID and codes beside it. */
    constructor(dir: File, clock: () -> Long = System::currentTimeMillis) : this(dir, clock, Phone.standalone(dir), dir, { emptySet() })

    val photoDir: File = photos.apply {
        mkdirs()
        // Leftovers from a transfer or import that was cut off.
        listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".import") }?.forEach { it.delete() }
    }
    private val dataFile = File(dir, "data.json")
    private val backupFile = File(dir, "data.json.bak")
    private val deviceFile = File(dir, "device.json")
    private val moveFile = File(dir, "move.json")
    private val codesFile = File(codesDir, "codes.json")

    /** The codes live in a folder of their own, apart from the move's folder (which Android's backup copies). */
    private val codesApart = codesDir.absoluteFile != dir.absoluteFile

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "store-io").apply { isDaemon = true }
    }
    private val dirty = AtomicBoolean(false)

    /** Everything that changes the data or the device settings takes this, so each change is seen whole. */
    private val lock = Any()

    /** Set when the saved data could not be read normally at start-up; shown once on the dashboard. */
    @Volatile
    var loadWarning: String? = null
        private set

    fun clearLoadWarning() {
        loadWarning = null
    }

    private val loaded = load()

    /** Nothing was saved here yet: this phone is starting a new move. */
    private val founding = loaded.people.isEmpty() && loaded.bare()

    /** Data from before 2.0, with nobody in it: the person this phone is given is made as 2.0 would have made it. */
    private val fromBefore20 = loaded.people.isEmpty() && !loaded.bare()

    private val _data = MutableStateFlow(migrate(loaded))
    val data: StateFlow<AppData> = _data

    /** This phone's letter, counter and label numbers in the move. Changed only under [lock] (or while the store is being made). */
    @Volatile
    private var codes: Codes = Codes()

    /** The codes file was there when the move was opened. */
    private var codesFound = false

    /** These settings were written by another phone: this folder is a copy of that phone's move (Android's backup, or a cloned phone). */
    private var copied = false

    private val _device = MutableStateFlow(loadDevice())
    val device: StateFlow<DeviceInfo> = _device

    private val _identity = MutableStateFlow(MoveIdentity())

    /** Which move this is (see [MoveIdentity]). */
    val identity: StateFlow<MoveIdentity> = _identity
    val moveId: String get() = _identity.value.id

    private fun load(): AppData {
        var damaged = false
        for (f in listOf(dataFile, backupFile)) {
            if (!f.exists()) continue
            try {
                val d = AppJson.decodeFromString<AppData>(f.readText())
                if (damaged) loadWarning = "The saved data was damaged, so the previous saved copy was loaded. The last few changes may be missing."
                return d
            } catch (_: Exception) {
                // Keep the unreadable file for recovery instead of overwriting it, then try the older copy.
                damaged = true
                try {
                    f.copyTo(File(dir, f.name + ".damaged"), overwrite = true)
                } catch (_: Exception) {
                }
            }
        }
        if (damaged) loadWarning = "The saved data could not be read, so the app started empty. Restore your latest backup, or sync from another phone."
        return AppData()
    }

    private fun loadDevice(): DeviceInfo {
        var dev = try {
            if (deviceFile.exists()) AppJson.decodeFromString<DeviceInfo>(deviceFile.readText())
            else DeviceInfo(letter = randomLetter())
        } catch (_: Exception) {
            DeviceInfo()
        }
        copied = dev.phoneId.isNotEmpty() && dev.phoneId != phone.id
        val saved = try {
            if (codesFile.isFile) AppJson.decodeFromString<Codes>(codesFile.readText()) else null
        } catch (_: Exception) {
            null
        }
        codesFound = saved != null
        // A phone from 2.0 kept its letter and counter with its settings. A copy of another phone's settings does not
        // bring that phone's: it would write the same codes on boxes. This phone gets a letter of its own (ensureMyPhone).
        codes = saved ?: if (copied) Codes() else Codes(letter = dev.letter.trim().uppercase(), nextSeq = dev.nextSeq)
        dev = dev.copy(letter = codes.letter, nextSeq = codes.nextSeq, phoneId = phone.id)
        phone.settings.value.bookLookup?.let { dev = dev.copy(bookLookup = it) }
        return dev
    }

    /** A letter for a phone starting out: any single letter it does not use in another move. */
    private fun randomLetter(): String {
        val elsewhere = lettersElsewhere().map { it.uppercase() }.toSet()
        val free = ('A'..'Z').map { it.toString() }.filter { it !in elsewhere }
        return if (free.isEmpty()) "A" else free[(Math.random() * free.size).toInt().coerceIn(0, free.size - 1)]
    }

    private fun saveCodes(next: Codes) {
        if (next == codes) return
        codes = next
        try {
            io.execute {
                try {
                    codesFile.parentFile?.mkdirs()
                    writeWhole(codesFile, AppJson.encodeToString(next))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            // The store has been closed.
        }
    }

    private fun readIdentity(): MoveIdentity? = try {
        if (moveFile.isFile) AppJson.decodeFromString<MoveIdentity>(moveFile.readText()).takeIf { it.id.isNotEmpty() } else null
    } catch (_: Exception) {
        null
    }

    private fun saveIdentity(next: MoveIdentity) {
        _identity.value = next
        try {
            writeWhole(moveFile, AppJson.encodeToString(next))
        } catch (_: Exception) {
        }
    }

    private val lastStamp = AtomicLong(maxStamp(_data.value))

    /**
     * Time used to stamp changes. It never goes backwards and is always later
     * than every change this phone has already seen, including ones received
     * from other phones. So an edit made after a sync always wins over what was
     * synced, even when the phones' clocks disagree.
     */
    fun now(): Long = lastStamp.updateAndGet { last -> maxOf(clock(), last + 1) }

    private fun maxStamp(d: AppData): Long {
        var m = d.move.updatedAt
        for (list in listOf<List<Rec>>(
            d.rooms, d.items, d.boxes, d.photos, d.tasks, d.expenses, d.supplies, d.trips, d.readings, d.entries,
            d.people, d.proposals, d.activity,
        )) {
            for (r in list) if (r.updatedAt > m) m = r.updatedAt
        }
        return m
    }

    init {
        ensureMe(madeBefore21 = fromBefore20)
        val known = readIdentity()
        // A move without an ID is either new (its founder's ID is made from the founding person, who is new) or from 2.0, when each
        // phone works out the same one from the founder it knows.
        val identity = known ?: MoveIdentity(id = _data.value.derivedMoveId(), derived = !founding)
        if (known == null) saveIdentity(identity) else _identity.value = identity
        if (!codesFound) {
            val d = _data.value
            // A copy of the founder's phone is not the founder's phone: what that phone handed out after the copy was made is not known here.
            val founder = !copied && (founding || (d.people.isNotEmpty() && Roles(d.people).founder == _device.value.personId))
            saveCodes(codes.copy(founder = founder))
        }
        synchronized(lock) { ensureMyPhone() }
    }

    // ---- persistence -------------------------------------------------------

    private fun scheduleSave() {
        if (dirty.compareAndSet(false, true)) {
            io.schedule({ writeNow() }, 250, TimeUnit.MILLISECONDS)
        }
    }

    private fun writeNow() {
        dirty.set(false)
        try {
            val tmp = File(dir, "data.json.tmp")
            tmp.writeText(AppJson.encodeToString(_data.value))
            if (dataFile.exists()) dataFile.copyTo(backupFile, overwrite = true)
            if (!tmp.renameTo(dataFile)) {
                tmp.copyTo(dataFile, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            dirty.set(true)
        }
    }

    /** Blocks until everything is on disk. Call when the app goes to the background. */
    fun flush() {
        try {
            io.submit { writeNow() }.get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    /** Write everything out and stop background writes. The store must not be used afterwards. */
    fun close() {
        flush()
        io.shutdownNow() // also drops delayed writes; flush() above already saved everything
        try {
            io.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    private fun writeDevice(next: DeviceInfo) {
        _device.value = next
        if (next.letter != codes.letter || next.nextSeq != codes.nextSeq) saveCodes(codes.copy(letter = next.letter, nextSeq = next.nextSeq))
        if (next.bookLookup != null && next.bookLookup != phone.settings.value.bookLookup) phone.updateSettings { it.copy(bookLookup = next.bookLookup) }
        // The letter and counter are kept with the codes, outside Android's backup, when the move has a folder for them.
        val onDisk = if (codesApart) next.copy(letter = "", nextSeq = 1) else next
        try {
            io.execute {
                try {
                    // Written whole or not at all: this file says who the phone is, and half of it would say nobody.
                    val tmp = File(dir, "device.json.tmp")
                    tmp.writeText(AppJson.encodeToString(onDisk))
                    if (!tmp.renameTo(deviceFile)) {
                        tmp.copyTo(deviceFile, overwrite = true)
                        tmp.delete()
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            // The store has been closed.
        }
    }

    /**
     * Change this phone's own settings. The name and letter are passed on to the
     * phone's [Person]; a letter someone else already uses is not taken.
     * [DeviceInfo.helper] follows the person's role. Switching it by hand still
     * works as the old "helper mode" while this phone is the only one in the
     * move; once there are other people, roles are changed with [setRole].
     */
    fun setDevice(f: (DeviceInfo) -> DeviceInfo) = synchronized(lock) {
        val before = _device.value
        // Who the phone is, is not a setting: a caller that builds the settings afresh and so leaves it
        // out does not make the phone nobody. Only becomePerson, joinAs and starting again change it.
        val asked = f(before).let { if (it.personId.isBlank() && before.personId.isNotBlank()) it.copy(personId = before.personId) else it }
        val d = _data.value
        val me = d.person(before.personId)
        if (asked.name != before.name && asked.name.isNotBlank()) phone.updateSettings { it.copy(name = asked.name.trim()) }
        if (me != null && asked.personId == before.personId) {
            var mine: Person = me
            if (asked.name != before.name) mine = mine.copy(name = asked.name)
            val letter = asked.letter.trim().uppercase()
            val entry = me.phones.firstOrNull { it.id == phone.id }
            val free = letter !in d.lettersInUse(except = me.id) && me.phones.none { it.id != phone.id && it.letter.equals(letter, ignoreCase = true) } &&
                lettersElsewhere().none { it.equals(letter, ignoreCase = true) }
            if (asked.letter != before.letter && letter.isNotEmpty() && free) {
                // A phone that uses its person's letter changes the person's; another phone of theirs has a letter of its own.
                mine = if (entry == null || entry.letter.isBlank()) mine.copy(letter = letter)
                else mine.copy(phones = mine.phones.map { if (it.id == phone.id) it.copy(letter = letter) else it })
            }
            val t = now()
            if (asked.helper != before.helper && d.people.count { !it.removed } <= 1) {
                val role = if (asked.helper) Role.HELPER else Role.ORGANISER
                if (role != mine.role) mine = mine.copy(role = role, grants = mine.grants + grantOf(role, false, t, Actor(d, me).signature))
            }
            if (mine != me) {
                val saved = mine.copy(updatedAt = t)
                _data.value = d.copy(people = d.people.map { if (it.id == me.id) saved else it })
                scheduleSave()
            }
        }
        writeDevice(asked)
        mirrorMe()
    }

    // ---- who this phone is --------------------------------------------------

    /** The person this phone belongs to. */
    val me: Person? get() = _data.value.me(_device.value)

    /** This phone's role in the move. */
    val myRole: Role get() = _data.value.roleOf(_device.value)

    /** May this phone's person do [action]? See [AppData.can] for the table. */
    fun can(action: Action): Boolean = _data.value.can(_device.value, action)

    private fun actor(d: AppData) = Actor(d, d.person(_device.value.personId))

    /**
     * Make sure this phone has a person, so that it is never nobody. Data from
     * before 2.0 has none: the phone then becomes an Organiser, or a Helper if
     * its old helper switch was on. Nobody appointed them, so the grant is an open one.
     */
    private fun ensureMe(restoring: Boolean = false, madeBefore21: Boolean = false) = synchronized(lock) {
        val dev = _device.value
        val d = _data.value
        if (d.person(dev.personId) == null) {
            val only = d.people.singleOrNull()
            if (dev.personId.isEmpty() && only != null && only.grants.firstOrNull()?.by == "") {
                // The person was saved but not yet which phone it is: the app was closed in between.
                writeDevice(dev.copy(personId = only.id))
            } else {
                val t = now()
                // A phone that starts up holding a move with an Organiser in it, without knowing which of its people it is
                // (its settings file was lost or damaged), does not make itself one: it gets the least rights until someone
                // says who it is. Whoever restores a backup on purpose was allowed to, and comes in as before.
                val role = if (dev.helper || (!restoring && d.hasOrganiser())) Role.HELPER else Role.ORGANISER
                val wanted = dev.letter.trim().uppercase().ifBlank { "A" }
                val letter = if (wanted in d.lettersInUse()) d.freeLetter(avoid = lettersElsewhere()) else wanted
                // Data from before 2.0 being brought up to date gets its first person as 2.0 would have made it, without
                // this phone's ID: the grant is from the time before there were people (see Roles).
                val grant = if (madeBefore21) Grant(role, false, t, "") else grantOf(role, false, t, "")
                val p = Person(id = newId(), name = dev.name, role = role, letter = letter, joinedAt = t, grants = listOf(grant), updatedAt = t)
                _data.value = d.copy(people = d.people + p)
                scheduleSave()
                writeDevice(dev.copy(personId = p.id))
            }
        }
        mirrorMe()
    }

    /** A grant made on this phone: it carries the phone's ID (see [Roles]). */
    private fun grantOf(role: Role, removed: Boolean, t: Long, by: String) = Grant(role, removed, t, by, phone.id)

    /**
     * Make sure this phone is on its person's list of phones, with the name it has in its own
     * settings, its letter and its block of label numbers. A phone that comes onto the list here
     * (one from before 2.1, the founder's, or one holding a copy of another phone's move) and
     * finds the person's letter in use by another of their phones gets a letter of its own.
     */
    private fun ensureMyPhone() {
        val d = _data.value
        val p = d.person(_device.value.personId) ?: return
        if (p.removed) return
        val mine = p.phones.firstOrNull { it.id == phone.id }
        var c = codes
        val letter = when {
            mine != null -> mine.letter
            p.phones.none { it.letter.isBlank() } && !copied -> ""
            else -> c.letter.takeIf { it.isNotBlank() && !it.equals(p.letter, ignoreCase = true) && it !in d.lettersInUse() }
                ?: d.freeLetter(avoid = lettersElsewhere() + c.lettersGiven)
        }
        if (c.labelsFrom == 0) {
            val block = when {
                mine != null && mine.labelsFrom != 0 -> mine.labelsFrom..mine.labelsTo
                c.founder -> FOUNDER_LABELS
                else -> legacyLabels(letter.ifBlank { p.letter })
            }
            c = c.copy(labelsFrom = block.first, labelsTo = block.last)
            // The founder of a move from 2.0 goes on from the last label printed there.
            if (c.founder && block.first == 1) c = c.copy(nextLabel = d.labelsUsed(ownLabels(1, block.last)) + 1)
        }
        saveCodes(c)
        val next = (mine ?: PhoneEntry(id = phone.id, addedAt = now())).let {
            it.copy(name = phone.deviceName.ifBlank { it.name }, letter = letter, labelsFrom = c.labelsFrom, labelsTo = c.labelsTo)
        }
        if (next != mine) {
            val t = now()
            val saved = p.copy(phones = (p.phones.filter { it.id != phone.id } + next).sortedWith(compareBy({ it.addedAt }, { it.id })), updatedAt = t)
            _data.value = d.copy(people = d.people.map { if (it.id == p.id) saved else it })
            scheduleSave()
        }
        mirrorMe()
    }

    /** If it turns out that someone who was in the move before this phone's person has the same letter, this phone takes another. */
    private fun giveWayOnLetter() {
        val d = _data.value
        val me = d.person(_device.value.personId) ?: return
        val letter = d.letterInstead(me) ?: return
        val t = now()
        _data.value = d.copy(people = d.people.map { if (it.id == me.id) it.copy(letter = letter, updatedAt = t) else it })
        mirrorMe()
    }

    /** This phone's own settings follow its person: the name, the letter, and the old helper switch. */
    private fun mirrorMe() {
        val dev = _device.value
        val d = _data.value
        val p = d.person(dev.personId) ?: return
        val own = p.phones.firstOrNull { it.id == phone.id }?.letter?.ifBlank { null }
        val next = dev.copy(name = p.name, letter = own ?: p.letter.ifBlank { dev.letter }, helper = d.roleOf(dev) == Role.HELPER)
        if (next != dev) writeDevice(next)
    }

    // ---- mutations ---------------------------------------------------------

    /** Replace the data with [f] of it. This is the raw write: nothing is checked, stamped or logged. */
    fun update(f: (AppData) -> AppData) {
        synchronized(lock) { _data.value = f(_data.value) }
        scheduleSave()
    }

    /** One change by this phone's person, with the rules, the container logic and the activity log applied. */
    private fun change(block: Draft.() -> Unit) = update { d -> Draft(d, actor(d), now()).apply(block).finish() }

    /** Like [change], for things the rules can refuse. Nothing is saved unless the result is [Outcome.Ok]. */
    private fun <T> act(action: Action?, block: Draft.() -> Outcome<T>): Outcome<T> = synchronized(lock) {
        val d = _data.value
        val a = actor(d)
        if (action != null) a.refusal(action)?.let { return Outcome.Refused(it) }
        val draft = Draft(d, a, now())
        val out = draft.block()
        if (out.ok) {
            _data.value = draft.finish()
            scheduleSave()
            mirrorMe()
        }
        out
    }

    private fun <T : Rec> List<T>.upsert(x: T): List<T> {
        val i = indexOfFirst { it.id == x.id }
        return if (i < 0) this + x else toMutableList().also { it[i] = x }
    }

    /** Change the move settings. Only an Organiser's change is taken; anyone may move [MoveInfo.labelsPrinted] on. */
    fun setMove(f: (MoveInfo) -> MoveInfo) = update { d ->
        val t = now()
        d.copy(move = actor(d).guard(d.move, f(d.move), t).copy(updatedAt = t))
    }

    /** [setMove] that says why when it is refused. */
    fun changeMove(f: (MoveInfo) -> MoveInfo): Outcome<MoveInfo> = act(Action.MOVE_SETTINGS) {
        d = d.copy(move = actor.guard(d.move, f(d.move), t).copy(updatedAt = t))
        Outcome.Ok(d.move)
    }

    /** Save a room. Deleting one this phone's person may not delete ([mayDelete]) saves nothing. */
    fun put(x: Room) = update { d ->
        val a = actor(d)
        val old = d.rooms.firstOrNull { it.id == x.id }
        if (a.refusesDeleting(old, x)) d else d.copy(rooms = d.rooms.upsert(a.guard(old, x, now())))
    }

    /**
     * Save an item. The decision, the room it goes to and whether it has gone are only
     * changed when this phone's person may change them (see Rules.kt); the rest is always saved.
     */
    fun put(x: Item) = change { saveItems(listOf(x)) }

    /** Save a container. Moving one that is inside another moves the outer one. */
    fun put(x: Box) = change { saveBoxes(listOf(x)) }
    /** Save a photo record. Deleting one this phone's person may not delete saves nothing. */
    fun put(x: Photo) = update { d ->
        val a = actor(d)
        val old = d.photos.firstOrNull { it.id == x.id }
        if (a.refusesDeleting(old, x)) d else d.copy(photos = d.photos.upsert(a.guard(old, x, now())))
    }

    /** Save a task. Only an Organiser can add or change one; whoever it is assigned to can tick it off. */
    fun put(x: Task) = update { d ->
        val t = now()
        val saved = actor(d).guard(d.tasks.firstOrNull { it.id == x.id }, x, t) ?: return@update d
        closeProposals(d.copy(tasks = d.tasks.upsert(saved.copy(updatedAt = t))))
    }

    /** Save a cost. Only an Organiser can add or change one. */
    fun put(x: Expense) = update { d ->
        val t = now()
        val saved = actor(d).guard(d.expenses.firstOrNull { it.id == x.id }, x, t) ?: return@update d
        closeProposals(d.copy(expenses = d.expenses.upsert(saved.copy(updatedAt = t))))
    }
    /**
     * [put] for a task that says what happened: Ok with the task as saved, or why nothing was saved.
     * Creating or changing a task is an Organiser's; ticking it is also the assignee's ([tick]).
     * Nothing is saved unless all of it may be. A Decider uses [saveOrSuggest] to make it a suggestion.
     */
    fun saveTask(x: Task): Outcome<Task> = act(null) {
        val old = d.tasks.firstOrNull { it.id == x.id }
        val saved = actor.guard(old, x, t) ?: return@act Outcome.Refused(actor.refusal(Action.DECIDE) ?: "Only an Organiser can add a task.")
        if (saved.planSum() != x.planSum()) return@act Outcome.Refused(actor.refusal(Action.DECIDE) ?: "Only an Organiser can change a task.")
        if (saved.done != x.done) return@act Outcome.Refused("Only an Organiser, or the person a task is assigned to, can tick it off.")
        if (old != null && saved.copy(updatedAt = old.updatedAt) == old.stamped()) return@act Outcome.Ok(old)
        if (x.assignedTo != null && x.assignedTo != old?.assignedTo && d.person(x.assignedTo)?.removed != false) return@act Outcome.Refused("That person is not in the move.")
        val next = saved.copy(updatedAt = t)
        d = d.copy(tasks = d.tasks.upsert(next))
        Outcome.Ok(next)
    }

    /**
     * [put] for a cost that says what happened: Ok with the cost as saved, or why nothing was saved.
     * Creating or changing a cost is an Organiser's; marking it paid follows [markPaid]'s rule.
     * Nothing is saved unless all of it may be. A Decider uses [saveOrSuggest] to make it a suggestion.
     */
    fun saveCost(x: Expense): Outcome<Expense> = act(null) {
        val old = d.expenses.firstOrNull { it.id == x.id }
        val saved = actor.guard(old, x, t) ?: return@act Outcome.Refused(actor.refusal(Action.DECIDE) ?: "Only an Organiser can add a cost.")
        if (saved.costSum() != x.costSum()) return@act Outcome.Refused(actor.refusal(Action.DECIDE) ?: "Only an Organiser can change a cost.")
        if (saved.planned != x.planned) return@act Outcome.Refused(actor.refusal(Action.CARRY_OUT) ?: "An Organiser has to approve your suggestion for this first.")
        if (old != null && saved.copy(updatedAt = old.updatedAt) == old.stamped()) return@act Outcome.Ok(old)
        val next = saved.copy(updatedAt = t)
        d = d.copy(expenses = d.expenses.upsert(next))
        if (old != null && old.planned && !next.planned) events.say("marked a cost as paid", next.id)
        Outcome.Ok(next)
    }

    /**
     * Save a task if this phone's person may, or else put it forward as a suggestion when they may
     * suggest (a Decider). Ok holds the saved [Task] or the waiting [Proposal]; Refused says why
     * neither could be done (a Helper may only tick a task given to them).
     */
    fun saveOrSuggest(x: Task, note: String = ""): Outcome<Rec> = synchronized(lock) {
        val direct = saveTask(x)
        if (direct.ok || !actor(_data.value).may(Action.SUGGEST)) return direct
        val old = _data.value.task(x.id)
        // Only the plan is put forward: ticking is not something to suggest.
        if (old != null && old.copy(done = x.done).planSum() == x.planSum()) return direct
        suggestTask(x.copy(done = old?.done ?: false), note)
    }

    /** The same as [saveOrSuggest] for a cost: saved for an Organiser, suggested for a Decider. */
    fun saveOrSuggest(x: Expense, note: String = ""): Outcome<Rec> = synchronized(lock) {
        val direct = saveCost(x)
        if (direct.ok || !actor(_data.value).may(Action.SUGGEST)) return direct
        val old = _data.value.expense(x.id)
        if (old != null && old.costSum() == x.copy(planned = old.planned).costSum()) return direct
        suggestCost(x, note)
    }

    fun put(x: Supply) = update { it.copy(supplies = it.supplies.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Trip) = update { it.copy(trips = it.trips.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Reading) = update { it.copy(readings = it.readings.upsert(x.copy(updatedAt = now()))) }
    fun put(x: BoxEntry) = update { d -> d.copy(entries = d.entries.upsert(actor(d).guard(d.entries.firstOrNull { it.id == x.id }, x, now()))) }

    // ---- deleting ------------------------------------------------------------
    //
    // An Organiser may delete anything; anyone else only an item, container, room or photo they added
    // themselves that nobody else has had a hand in since (see Deleting.kt and [mayDelete]). The plain
    // delete calls do nothing when that is refused, as the plain saves leave a protected part alone;
    // tryDelete says why. Either way it is the stored record that is deleted, not the caller's copy of it.

    fun delete(x: Room) {
        tryDelete(x, withPhotos = false)
    }

    fun delete(x: Item) {
        tryDelete(x, withPhotos = false)
    }

    fun delete(x: BoxEntry) {
        tryDelete(x)
    }

    /**
     * Deleting a container also removes the list of things scanned into it.
     * Catalogued items and other containers that were inside are let go, not deleted.
     */
    fun delete(x: Box) {
        tryDelete(x, withPhotos = false)
    }

    fun delete(x: Task) {
        tryDelete(x)
    }

    fun delete(x: Expense) {
        tryDelete(x)
    }

    fun delete(x: Supply) = put(x.copy(deleted = true))
    fun delete(x: Trip) = put(x.copy(deleted = true))
    fun delete(x: Reading) = put(x.copy(deleted = true))
    fun delete(x: Photo) {
        tryDelete(x)
    }

    /** The photos of [ownerId] this person may delete, deleted with the thing they show. */
    private fun Draft.deletePhotosOf(ownerId: String) {
        val doomed = d.photos.filter { !it.deleted && it.ownerId == ownerId && d.mayDelete(actor.me, it) == null }.map { it.id }.toSet()
        if (doomed.isEmpty()) return
        d = d.copy(photos = d.photos.map { if (it.id in doomed) actor.guard(it, it.copy(deleted = true), t) else it })
    }

    /**
     * Delete an item, saying why when this phone's person may not ([mayDelete]). With [withPhotos]
     * the photos of it that they may delete go too (their files once the change is saved).
     */
    fun tryDelete(x: Item, withPhotos: Boolean = true): Outcome<Item> = act(null) {
        val old = d.item(x.id) ?: return@act Outcome.Refused("That item is no longer here.")
        d.mayDelete(actor.me, old)?.let { return@act Outcome.Refused(it) }
        if (withPhotos) deletePhotosOf(old.id)
        saveItems(listOf(old.copy(deleted = true)))
        Outcome.Ok(d.items.first { it.id == old.id })
    }.also { if (it.ok) forgetDeletedPhotos() }

    /** Delete a container ([delete]), saying why when this phone's person may not. */
    fun tryDelete(x: Box, withPhotos: Boolean = true): Outcome<Box> = act(null) {
        val old = d.box(x.id) ?: return@act Outcome.Refused("That container is no longer here.")
        d.mayDelete(actor.me, old)?.let { return@act Outcome.Refused(it) }
        if (withPhotos) deletePhotosOf(old.id)
        saveBoxes(listOf(old.copy(deleted = true)))
        Outcome.Ok(d.boxes.first { it.id == old.id })
    }.also { if (it.ok) forgetDeletedPhotos() }

    /** Delete a room, saying why when this phone's person may not. Things in it stay, with no room. */
    fun tryDelete(x: Room, withPhotos: Boolean = true): Outcome<Room> = act(null) {
        val old = d.room(x.id) ?: return@act Outcome.Refused("That room is no longer here.")
        d.mayDelete(actor.me, old)?.let { return@act Outcome.Refused(it) }
        if (withPhotos) deletePhotosOf(old.id)
        val saved = actor.guard(old, old.copy(deleted = true), t)
        d = d.copy(rooms = d.rooms.upsert(saved))
        Outcome.Ok(saved)
    }.also { if (it.ok) forgetDeletedPhotos() }

    /** Delete a photo and its file, saying why when this phone's person may not. */
    fun tryDelete(x: Photo): Outcome<Photo> = act(null) {
        val old = d.photos.firstOrNull { it.id == x.id && !it.deleted } ?: return@act Outcome.Refused("That photo is no longer here.")
        d.mayDelete(actor.me, old)?.let { return@act Outcome.Refused(it) }
        val saved = actor.guard(old, old.copy(deleted = true), t)
        d = d.copy(photos = d.photos.upsert(saved))
        Outcome.Ok(saved)
    }.also { if (it.ok) forgetDeletedPhotos() }

    /** Delete a task. Organisers only. */
    fun tryDelete(x: Task): Outcome<Task> = act(null) {
        val old = d.task(x.id) ?: return@act Outcome.Refused("That task is no longer here.")
        d.mayDelete(actor.me, old)?.let { return@act Outcome.Refused(it) }
        val saved = (actor.guard(old, old.copy(deleted = true), t) ?: old).copy(updatedAt = t)
        d = d.copy(tasks = d.tasks.upsert(saved))
        Outcome.Ok(saved)
    }

    /** Delete a cost. Organisers only; a Decider suggests it with [suggestCost] of the cost marked deleted, or [askToDelete]. */
    fun tryDelete(x: Expense): Outcome<Expense> = act(null) {
        val old = d.expense(x.id) ?: return@act Outcome.Refused("That cost is no longer here.")
        d.mayDelete(actor.me, old)?.let { return@act Outcome.Refused(it) }
        val saved = (actor.guard(old, old.copy(deleted = true), t) ?: old).copy(updatedAt = t)
        d = d.copy(expenses = d.expenses.upsert(saved))
        Outcome.Ok(saved)
    }

    /** Remove something scanned into a container. Part of the work: anyone may. */
    fun tryDelete(x: BoxEntry): Outcome<BoxEntry> = act(Action.WORK) {
        val old = d.entries.firstOrNull { it.id == x.id && !it.deleted } ?: return@act Outcome.Refused("That is no longer in the container.")
        val saved = actor.guard(old, old.copy(deleted = true), t)
        d = d.copy(entries = d.entries.upsert(saved))
        Outcome.Ok(saved)
    }

    /** Apply one change to many items / boxes in a single write. */
    fun updateItems(ids: Collection<String>, f: (Item) -> Item) = change {
        val set = ids.toSet()
        saveItems(d.items.filter { it.id in set && !it.deleted }.map(f))
    }

    fun updateBoxes(ids: Collection<String>, f: (Box) -> Box) = change {
        val set = ids.toSet()
        saveBoxes(d.boxes.filter { it.id in set && !it.deleted }.map(f))
    }

    // ---- creating things ---------------------------------------------------

    /** New box with this label. Its destination defaults to the room its old-home room maps to. */
    fun createBox(label: String, originRoomId: String? = _device.value.currentRoomId, kind: ContainerKind = ContainerKind.CARTON): Box {
        val box = Box(
            id = newId(), label = label.trim(), originRoomId = originRoomId,
            destRoomId = _data.value.room(originRoomId)?.mapsTo, packedBy = _device.value.name,
            kind = kind, travelsWithMe = kind.travelsWithMe,
        )
        put(box)
        return _data.value.box(box.id) ?: box
    }

    /**
     * New container with nothing typed: its destination, category, kind and size
     * are those of the last one packed in the same room ([packDefaults]) and it
     * gets a name such as "Kitchen box 4". Pass [kind] to choose the kind.
     */
    fun createContainer(label: String, originRoomId: String? = _device.value.currentRoomId, kind: ContainerKind? = null): Box {
        val d = _data.value
        val like = d.packDefaults(originRoomId)
        val k = kind ?: like.kind
        val box = Box(
            id = newId(), label = label.trim(), originRoomId = originRoomId, destRoomId = like.destRoomId,
            kind = k, size = if (k == like.kind) like.size else suggestContainer(d.room(originRoomId)?.name, category = like.category).size,
            category = like.category, name = d.defaultBoxName(k, originRoomId), travelsWithMe = k.travelsWithMe, packedBy = _device.value.name,
        )
        put(box)
        return _data.value.box(box.id) ?: box
    }

    /** New catalogued item in an old-home room, destination defaulted the same way. */
    fun createItem(name: String = "", originRoomId: String? = _device.value.currentRoomId): Item {
        val item = Item(id = newId(), name = name, originRoomId = originRoomId, destRoomId = _data.value.room(originRoomId)?.mapsTo)
        put(item)
        return _data.value.item(item.id) ?: item
    }

    /** Add a room with the next free colour and a unique short code. */
    fun createRoom(home: Home, name: String): Room {
        val existing = _data.value.roomsOf(home)
        var code = suggestRoomCode(name)
        var n = 2
        while (existing.any { it.code == code }) code = suggestRoomCode(name) + n++
        val usedColors = existing.map { it.color }.toSet()
        val color = ROOM_COLORS.indices.firstOrNull { it !in usedColors } ?: existing.size
        val room = Room(id = newId(), home = home, name = name.trim(), code = code, color = color, sort = (existing.maxOfOrNull { it.sort } ?: 0) + 1)
        put(room)
        return room
    }

    /**
     * Record something in a box by its own barcode, or by a typed [title] when it has none.
     * A book (ISBN) exists once, so scanning it into another box moves it there.
     * Other products can be in several boxes, since identical ones share a barcode.
     * A name given to a barcode before is reused.
     */
    fun scanInto(boxId: String, code: String, title: String = "", author: String = ""): ScanIn {
        val c = code.trim()
        val d = _data.value
        if (c.isNotEmpty()) {
            d.liveEntries.firstOrNull { it.code == c && it.boxId == boxId }?.let { return ScanIn.AlreadyHere(it) }
            if (isIsbn(c)) {
                d.liveEntries.firstOrNull { it.code == c }?.let { elsewhere ->
                    val moved = elsewhere.copy(boxId = boxId)
                    put(moved)
                    return ScanIn.Moved(moved, d.box(elsewhere.boxId)?.label)
                }
            }
        }
        val known = if (c.isEmpty()) null else d.entries.lastOrNull { it.code == c && it.title.isNotBlank() }
        val entry = BoxEntry(
            id = newId(), boxId = boxId, code = c,
            title = title.trim().ifBlank { known?.title ?: "" },
            author = author.trim().ifBlank { known?.author ?: "" },
        )
        put(entry)
        return ScanIn.Added(entry)
    }

    // ---- containers ----------------------------------------------------------

    /** Pack a catalogued item into a container. From then on it travels with the container. */
    fun putInto(itemId: String, boxId: String): Outcome<Item> = act(Action.WORK) {
        val item = d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
        d.box(boxId) ?: return@act Outcome.Refused("That container is no longer here.")
        saveItems(listOf(item.copy(boxId = boxId)))
        Outcome.Ok(mirrorContainers(d, t).item(itemId) ?: item)
    }

    /** Take an item out of its container. It stays where the container was. */
    fun takeOut(itemId: String): Outcome<Item> = act(Action.WORK) {
        val item = d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
        saveItems(listOf(item.copy(boxId = null)))
        Outcome.Ok(d.item(itemId) ?: item)
    }

    /**
     * Put container [boxId] inside [parentId], or take it out again with null.
     * Containers go one inside another and no deeper; loops are refused.
     */
    fun nest(boxId: String, parentId: String?): Outcome<Box> = act(Action.WORK) {
        val box = d.box(boxId) ?: return@act Outcome.Refused("That container is no longer here.")
        if (parentId != null) d.whyNotNest(boxId, parentId)?.let { return@act Outcome.Refused(it) }
        saveBoxes(listOf(box.copy(parentId = parentId)))
        Outcome.Ok(mirrorContainers(d, t).box(boxId) ?: box)
    }

    // ---- photos ------------------------------------------------------------

    /** A fresh file in the photo directory for the camera to write into. */
    fun newPhotoFile(): File = File(photoDir, newId() + ".jpg")

    /**
     * Register a photo file already saved in [photoDir]. A receipt for a cost is part of
     * carrying that cost out: from someone who may not, it is not kept ([attachReceipt] says why).
     */
    fun addPhoto(ownerId: String, kind: PhotoKind, file: File, note: String = ""): Photo {
        val d = _data.value
        val a = actor(d)
        val p = Photo(id = newId(), ownerId = ownerId, kind = kind, file = file.name, note = note, by = a.signature)
        val cost = if (kind == PhotoKind.RECEIPT) d.expense(ownerId) else null
        if (cost != null && !d.mayPay(a.me, cost)) {
            file.delete()
            return p
        }
        put(p)
        return p
    }

    fun photoFile(p: Photo): File = File(photoDir, p.file)

    // ---- labels ------------------------------------------------------------

    /** Next handwritten code for this phone, e.g. "A7". Skips codes already in use. */
    fun nextHandCode(): String {
        val d = _data.value
        val used = (d.liveBoxes.map { it.label } + d.liveItems.mapNotNull { it.label }).toSet()
        var n = _device.value.nextSeq
        val letter = _device.value.letter.ifBlank { "A" }
        while ("$letter$n" in used) n++
        setDevice { it.copy(nextSeq = n + 1) }
        return "$letter$n"
    }

    // ---- people --------------------------------------------------------------

    private fun Draft.savePerson(p: Person): Person {
        val saved = p.copy(updatedAt = t)
        d = d.copy(people = if (d.people.any { it.id == p.id }) d.people.map { if (it.id == p.id) saved else it } else d.people + saved)
        return saved
    }

    private fun Draft.grant(p: Person, role: Role, removed: Boolean): Person =
        savePerson(p.copy(role = role, removed = removed, grants = p.grants + grantOf(role, removed, t, actor.signature)))

    /**
     * The next letter from this phone's own block (see [nextInBlock]), never one it has handed out
     * before, nor one in [avoid]. Noted as handed out.
     */
    private fun allotLetter(d: AppData, avoid: Set<String> = emptySet()): String {
        val c = codes
        val own = _device.value.letter
        val letter = d.nextInBlock(own, c.founder, c.lettersGiven.toSet() + avoid) ?: d.freeLetter(avoid = c.lettersGiven.toSet() + avoid)
        saveCodes(c.copy(lettersGiven = c.lettersGiven + letter))
        return letter
    }

    /** Would changing [p] leave the move with nobody to run it? */
    private fun Draft.lastOrganiser(p: Person): Boolean =
        p.role == Role.ORGANISER && !p.removed && d.people.none { it.id != p.id && it.role == Role.ORGANISER && !it.removed }

    /**
     * Add someone to the move, before their phone has joined. Their letter for
     * handwritten codes is the next free one. Show them an invite made with
     * WifiSync's `inviteFor` to bring their phone in. A blank [name] becomes "Helper 2" and the like.
     */
    fun addPerson(name: String, role: Role): Outcome<Person> = act(Action.MANAGE_PEOPLE) {
        val shown = name.trim().ifBlank { role.label + " " + (d.people.count { it.role == role } + 1) }
        val p = savePerson(Person(id = newId(), name = shown, role = role, letter = allotLetter(d), grants = listOf(grantOf(role, false, t, actor.signature))))
        events.say("added $shown as ${article(role)}", p.id)
        Outcome.Ok(p)
    }

    private fun article(role: Role) = (if (role == Role.ORGANISER) "an " else "a ") + role.label

    /** Change someone's role. The last Organiser cannot be made anything else. */
    fun setRole(personId: String, role: Role): Outcome<Person> = act(Action.MANAGE_PEOPLE) {
        val p = d.person(personId)?.takeIf { !it.removed } ?: return@act Outcome.Refused("That person is not in the move.")
        if (p.role == role) return@act Outcome.Ok(p)
        if (lastOrganiser(p)) return@act Outcome.Refused("A move needs at least one Organiser. Make someone else an Organiser first.")
        val saved = grant(p, role, false)
        events.say("made ${p.name.ifBlank { "someone" }} ${article(role)}", p.id)
        Outcome.Ok(saved)
    }

    /** Remove someone from the move. Their phone is refused when it next tries to sync. What they did stays. */
    fun removePerson(personId: String): Outcome<Person> = act(Action.MANAGE_PEOPLE) {
        val p = d.person(personId)?.takeIf { !it.removed } ?: return@act Outcome.Refused("That person is not in the move.")
        if (lastOrganiser(p)) return@act Outcome.Refused("A move needs at least one Organiser. Make someone else an Organiser first.")
        val saved = grant(p, p.role, true)
        events.say("removed ${p.name.ifBlank { "someone" }}", p.id)
        Outcome.Ok(saved)
    }

    /** Let a removed person back in, with the role they had. */
    fun restorePerson(personId: String): Outcome<Person> = act(Action.MANAGE_PEOPLE) {
        val p = d.person(personId) ?: return@act Outcome.Refused("That person is not in the move.")
        if (!p.removed) return@act Outcome.Ok(p)
        val saved = grant(p, p.role, false)
        events.say("added ${p.name.ifBlank { "someone" }} back", p.id)
        Outcome.Ok(saved)
    }

    /** Change someone's name: an Organiser can for anyone, anyone can for themselves. */
    fun renamePerson(personId: String, name: String): Outcome<Person> = act(null) {
        val p = d.person(personId) ?: return@act Outcome.Refused("That person is not in the move.")
        if (p.id != actor.id) actor.refusal(Action.MANAGE_PEOPLE)?.let { return@act Outcome.Refused(it) }
        Outcome.Ok(savePerson(p.copy(name = name.trim())))
    }

    /** Change someone's letter for handwritten codes. It must be one nobody else has. */
    fun setLetter(personId: String, letter: String): Outcome<Person> = act(null) {
        val p = d.person(personId) ?: return@act Outcome.Refused("That person is not in the move.")
        if (p.id != actor.id) actor.refusal(Action.MANAGE_PEOPLE)?.let { return@act Outcome.Refused(it) }
        val l = letter.trim().uppercase().filter { it.isLetter() }.take(2)
        if (l.isEmpty()) return@act Outcome.Refused("Choose a letter.")
        d.people.firstOrNull { it.id != p.id && it.letter.equals(l, ignoreCase = true) }?.let {
            return@act Outcome.Refused("${it.name.ifBlank { "Someone else" }} already uses the letter $l.")
        }
        Outcome.Ok(savePerson(p.copy(letter = l)))
    }

    /** True while this phone is the only one in its move: nobody else has ever been in its data. */
    fun isAlone(): Boolean = _data.value.people.let { it.size == 1 && it[0].id == _device.value.personId }

    /**
     * Make this phone the phone of [personId], someone already in the move (for
     * example after restoring a backup onto a new phone). The person the phone
     * started as, if it made that one up itself, steps out of the move.
     */
    fun becomePerson(personId: String): Outcome<Person> = synchronized(lock) {
        val d = _data.value
        val target = d.person(personId) ?: return Outcome.Refused("That person is not in the move.")
        if (target.removed) return Outcome.Refused("${target.name.ifBlank { "That person" }} has been removed from the move.")
        val dev = _device.value
        if (dev.personId == personId) return Outcome.Ok(target)
        val t = now()
        val old = d.person(dev.personId)
        val selfMade = old != null && !old.removed && old.grants.all { it.by.isEmpty() || it.by == old.id }
        val saved = target.copy(joinedAt = t, updatedAt = t)
        _data.value = d.copy(
            people = settlePeople(
                d.people.map {
                    when {
                        it.id == personId -> saved
                        selfMade && it.id == old!!.id -> it.copy(grants = it.grants + grantOf(it.role, true, t, Actor(d, old).signature), updatedAt = t)
                        else -> it
                    }
                }
            ),
        )
        scheduleSave()
        writeDevice(dev.copy(personId = personId))
        mirrorMe()
        ensureMyPhone()
        Outcome.Ok(_data.value.person(personId) ?: saved)
    }

    /**
     * Take in the data of a move this phone has just been invited into and
     * become [personId] in it. A phone that was on its own until now leaves the
     * person it started as behind, so what it decided alone does not count as
     * an Organiser's decision in the move it joins. [identity] is the joined
     * move's, as the hosting phone gave it; without one it is worked out from
     * the move's founder. Joining another move than the one held here starts
     * this phone's letter, counter and label numbers afresh, from what the
     * admitting phone gave it.
     */
    fun joinAs(personId: String, remote: AppData, identity: MoveIdentity? = null): Outcome<Person> = synchronized(lock) {
        val incoming = distrustUnstamped(remote)
        val target = incoming.person(personId) ?: return Outcome.Refused("That person is not in the move.")
        if (target.removed) return Outcome.Refused("${target.name.ifBlank { "That person" }} has been removed from the move.")
        val dev = _device.value
        val seen = maxStamp(incoming)
        lastStamp.updateAndGet { maxOf(it, seen) }
        val merged = merge(distrustUnstamped(offerFor(personId)), incoming)
        val t = now()
        _data.value = merged.copy(people = merged.people.map { if (it.id == personId) it.copy(joinedAt = t, updatedAt = t) else it })
        val joined = identity ?: MoveIdentity(id = remote.derivedMoveId())
        val another = !joined.matches(_identity.value)
        if (another) {
            saveIdentity(joined)
            saveCodes(Codes())
        } else if (joined != _identity.value) saveIdentity(_identity.value.withAliasesOf(joined))
        scheduleSave()
        writeDevice(if (another) dev.forAnotherMove(personId) else dev.copy(personId = personId))
        mirrorMe()
        ensureMyPhone()
        forgetDeletedPhotos()
        Outcome.Ok(_data.value.person(personId) ?: target)
    }

    /**
     * This phone's data as it goes into a move it is joining as [personId]. A phone that was on its
     * own until now leaves its own move behind ([leavingOwnMove]); one that is already in the move
     * offers what it has.
     */
    internal fun offerFor(personId: String): AppData = synchronized(lock) {
        val d = _data.value
        val mine = _device.value.personId
        when {
            isAlone() && mine != personId -> leavingOwnMove(d)
            // A stand-in the phone gave itself when it turned out to be nobody ([ensureMe]) is not taken along either.
            mine != personId && d.person(mine)?.let { p -> p.joinedAt != 0L && p.grants.size == 1 && p.grants[0].by.isEmpty() && p.role == Role.HELPER } == true ->
                d.copy(people = d.people.filter { it.id != mine })
            else -> d
        }
    }

    /**
     * Take in what another phone says about its own person, when the two phones are not going to
     * sync: someone who has been removed. They may know more about that than this phone does (an
     * Organiser who took themselves out of the move has nobody else to tell), and it must reach the
     * rest of the move. As in a merge, every grant either phone knows is kept and each is checked.
     */
    internal fun hearAbout(peer: Person?) {
        if (peer == null) return
        synchronized(lock) {
            val d = _data.value
            if (d.people.none { it.id == peer.id }) return
            val people = mergePeople(d.people, listOf(peer))
            if (people == d.people) return
            val seen = maxOf(peer.updatedAt, peer.grants.maxOfOrNull { it.at } ?: 0L)
            lastStamp.updateAndGet { maxOf(it, seen) }
            _data.value = d.copy(people = people)
            ensureMe()
        }
        scheduleSave()
    }

    /** Note that [personId]'s phone has joined. Called on the phone that showed the invite. */
    fun markJoined(personId: String) = update { d ->
        val t = now()
        d.copy(people = d.people.mapChanged { if (it.id == personId && it.joinedAt == 0L) it.copy(joinedAt = t, updatedAt = t) else it })
    }

    // ---- decisions -------------------------------------------------------------

    private fun Draft.replace(x: Item) {
        d = d.copy(items = d.items.map { if (it.id == x.id) x else it })
    }

    private fun Draft.replace(x: Proposal) {
        d = d.copy(proposals = d.proposals.map { if (it.id == x.id) x else it })
    }

    /** Set an item's decision in this person's name, even when it is the same as before: that is what an approval is. */
    private fun Draft.decideItem(item: Item, disposition: Disposition, price: Double?): Item {
        val was = item.stamped()
        val next = was.copy(disposition = disposition, price = price, updatedAt = t, hands = actor.handsAfter(was.hands, true))
            .let { it.copy(decisionBy = actor.stamp(t, it.decisionSum())) }
        replace(next)
        return next
    }

    /** Decide what happens to an item, and the asking price if it is to be sold. Organisers only; others use [suggestDecision]. */
    fun decide(itemId: String, disposition: Disposition, price: Double? = _data.value.item(itemId)?.price): Outcome<Item> = act(Action.DECIDE) {
        val item = d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
        saveItems(listOf(item.copy(disposition = disposition, price = price)))
        Outcome.Ok(d.item(itemId) ?: item)
    }

    /** Choose the new-home room an item goes to. Organisers only; others use [suggestRoom]. */
    fun planRoom(itemId: String, roomId: String?): Outcome<Item> = act(Action.DECIDE) {
        val item = d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
        saveItems(listOf(item.copy(destRoomId = roomId)))
        Outcome.Ok(d.item(itemId) ?: item)
    }

    /**
     * Carry out the decision on an item: mark it listed for sale, gone, or what it sold for.
     * Null leaves that part as it is. An Organiser may; a Decider may once a suggestion of
     * theirs for this item has been approved and until the decision changes ([mayCarryOut]).
     */
    fun carryOut(itemId: String, listed: Boolean? = null, gone: Boolean? = null, soldFor: Double? = null): Outcome<Item> = act(null) {
        val item = d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
        if (!d.mayCarryOut(actor.me, item)) {
            return@act Outcome.Refused(actor.refusal(Action.CARRY_OUT) ?: "An Organiser has to approve your suggestion for this first.")
        }
        saveItems(listOf(item.copy(listed = listed ?: item.listed, gone = gone ?: item.gone, soldFor = soldFor ?: item.soldFor)))
        Outcome.Ok(d.item(itemId) ?: item)
    }

    /** Mark a cost paid (or not paid after all). The same rule as [carryOut], for costs ([mayPay]). */
    fun markPaid(expenseId: String, paid: Boolean = true): Outcome<Expense> = act(null) {
        val e = d.expense(expenseId) ?: return@act Outcome.Refused("That cost is no longer here.")
        if (!d.mayPay(actor.me, e)) {
            return@act Outcome.Refused(actor.refusal(Action.CARRY_OUT) ?: "An Organiser has to approve your suggestion for this first.")
        }
        val saved = (actor.guard(e, e.copy(planned = !paid), t) ?: e).copy(updatedAt = t)
        d = d.copy(expenses = d.expenses.map { if (it.id == e.id) saved else it })
        if (paid && e.planned) events.say("marked a cost as paid", e.id)
        Outcome.Ok(saved)
    }

    /** Attach the photo of a receipt to a cost. The same rule as [markPaid]. The file is deleted when refused. */
    fun attachReceipt(expenseId: String, file: File, note: String = ""): Outcome<Photo> = act(null) {
        val e = d.expense(expenseId) ?: return@act Outcome.Refused("That cost is no longer here.")
        if (!d.mayPay(actor.me, e)) {
            file.delete()
            return@act Outcome.Refused(actor.refusal(Action.CARRY_OUT) ?: "An Organiser has to approve your suggestion for this first.")
        }
        val p = actor.guard(null, Photo(id = newId(), ownerId = e.id, kind = PhotoKind.RECEIPT, file = file.name, note = note, by = actor.signature, createdAt = t), t)
        d = d.copy(photos = d.photos + p)
        Outcome.Ok(p)
    }

    /** Tick a task off or untick it: an Organiser, or the person it is assigned to. */
    fun tick(taskId: String, done: Boolean = true): Outcome<Task> = act(null) {
        val task = d.task(taskId) ?: return@act Outcome.Refused("That task is no longer here.")
        if (!d.mayTick(actor.me, task)) return@act Outcome.Refused("Only an Organiser, or the person a task is assigned to, can tick it off.")
        val saved = (actor.guard(task, task.copy(done = done), t) ?: task).copy(updatedAt = t)
        d = d.copy(tasks = d.tasks.map { if (it.id == task.id) saved else it })
        Outcome.Ok(saved)
    }

    /** Give a task to someone (or to nobody in particular with null). Organisers only. */
    fun assign(taskId: String, personId: String?): Outcome<Task> = act(Action.DECIDE) {
        val task = d.task(taskId) ?: return@act Outcome.Refused("That task is no longer here.")
        if (personId != null && d.person(personId)?.removed != false) return@act Outcome.Refused("That person is not in the move.")
        val saved = (actor.guard(task, task.copy(assignedTo = personId), t) ?: task).copy(updatedAt = t)
        d = d.copy(tasks = d.tasks.map { if (it.id == task.id) saved else it })
        Outcome.Ok(saved)
    }

    // ---- suggestions and approvals -------------------------------------------------

    private fun Draft.propose(p: Proposal, said: String): Outcome<Proposal> {
        val saved = p.copy(id = newId(), by = actor.id, state = ProposalState.WAITING, reason = "", createdAt = t, updatedAt = t)
            .let { it.copy(decided = Stamp(sum = it.stateSum())) }
        d = d.copy(proposals = d.proposals + saved)
        events.say(said, saved.id)
        return Outcome.Ok(saved)
    }

    /**
     * Suggest what should happen to an item, with an asking price if it is to be sold.
     * It waits until an Organiser approves or declines it. [answers] is the id of the
     * question this is an answer to, if any.
     */
    fun suggestDecision(itemId: String, disposition: Disposition, price: Double? = null, note: String = "", answers: String? = null): Outcome<Proposal> =
        act(Action.SUGGEST) {
            d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
            val p = Proposal(id = "", kind = ProposalKind.ITEM_DECISION, targetId = itemId, disposition = disposition, price = price, note = note.trim(), answers = answers)
            propose(p, "suggested: " + d.describe(p))
        }

    /** Suggest the new-home room an item should go to. */
    fun suggestRoom(itemId: String, roomId: String?, note: String = ""): Outcome<Proposal> = act(Action.SUGGEST) {
        d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
        val p = Proposal(id = "", kind = ProposalKind.ITEM_ROOM, targetId = itemId, roomId = roomId, note = note.trim())
        propose(p, "suggested: " + d.describe(p))
    }

    /**
     * Suggest a new cost, or a change to an existing one (same id). The cost appears, or changes, when approved.
     * An existing cost marked deleted is a suggestion to delete it.
     */
    fun suggestCost(expense: Expense, note: String = ""): Outcome<Proposal> =
        suggestCostChange(expense, note, if (expense.deleted) "suggested deleting a cost" else "suggested a cost")

    private fun suggestCostChange(expense: Expense, note: String, said: String): Outcome<Proposal> = act(Action.SUGGEST) {
        val gone = expense.deleted && d.expense(expense.id) != null
        if (expense.deleted && !gone) return@act Outcome.Refused("That cost is no longer here.")
        val clean = expense.copy(costBy = Stamp(), paidBy = Stamp(), deleted = gone, updatedAt = 0)
        propose(Proposal(id = "", kind = ProposalKind.COST, targetId = expense.id, expense = clean, note = note.trim()), said)
    }

    /** Suggest a new task, or a change to an existing one (same id). An existing task marked deleted is a suggestion to delete it. */
    fun suggestTask(task: Task, note: String = ""): Outcome<Proposal> = act(Action.SUGGEST) {
        val gone = task.deleted && d.task(task.id) != null
        if (task.deleted && !gone) return@act Outcome.Refused("That task is no longer here.")
        val clean = task.copy(planBy = Stamp(), doneBy = Stamp(), deleted = gone, updatedAt = 0)
        propose(Proposal(id = "", kind = ProposalKind.TASK, targetId = task.id, task = clean, note = note.trim()), (if (gone) "suggested deleting a task: " else "suggested a task: ") + task.title.take(60))
    }

    /** Ask the Organisers what should happen to an item. Anyone may ask. */
    fun ask(itemId: String, note: String = ""): Outcome<Proposal> = act(Action.ASK) {
        val item = d.item(itemId) ?: return@act Outcome.Refused("That item is no longer here.")
        propose(Proposal(id = "", kind = ProposalKind.QUESTION, targetId = itemId, note = note.trim()), "asked what to do with " + item.title())
    }

    /**
     * Approve a waiting suggestion: the change is made in the approver's name,
     * other waiting suggestions for the same thing become NOT_CHOSEN, and the
     * person who suggested it may now carry it out.
     */
    fun approve(proposalId: String): Outcome<Proposal> = act(Action.APPROVE) {
        val p = d.proposal(proposalId) ?: return@act Outcome.Refused("That suggestion is no longer here.")
        if (p.state != ProposalState.WAITING) return@act Outcome.Refused("That suggestion is no longer waiting.")
        when (p.kind) {
            ProposalKind.ITEM_DECISION -> {
                val item = d.item(p.targetId) ?: return@act Outcome.Refused("The item it was about is no longer here.")
                val before = item.disposition
                val next = decideItem(item, p.disposition ?: item.disposition, p.price ?: item.price)
                if (next.disposition != before) events.add(decisionVerb(next.disposition), next.title(), next.id)
            }
            ProposalKind.ITEM_ROOM -> {
                val item = d.item(p.targetId)?.stamped() ?: return@act Outcome.Refused("The item it was about is no longer here.")
                replace(item.copy(destRoomId = p.roomId, updatedAt = t, hands = actor.handsAfter(item.hands, true)).let { it.copy(roomBy = actor.stamp(t, it.roomSum())) })
            }
            ProposalKind.COST -> {
                val proposed = p.expense ?: return@act Outcome.Refused("That suggestion has no cost in it.")
                val old = d.expenses.firstOrNull { it.id == p.targetId }?.stamped()
                // A suggestion to delete a cost deletes it; one that is no longer there stays gone.
                if (proposed.deleted && old?.deleted != false) return@act Outcome.Refused("That cost is no longer here.")
                val next = (
                    if (old == null) proposed.copy(id = p.targetId, deleted = false).let { it.copy(paidBy = actor.stamp(t, it.paidSum())) }
                    else old.copy(title = proposed.title, amount = proposed.amount, category = proposed.category, day = proposed.day, deleted = proposed.deleted)
                    ).copy(updatedAt = t).let { it.copy(costBy = actor.stamp(t, it.costSum())) }
                d = d.copy(expenses = d.expenses.upsert(next))
            }
            ProposalKind.TASK -> {
                val proposed = p.task ?: return@act Outcome.Refused("That suggestion has no task in it.")
                val old = d.tasks.firstOrNull { it.id == p.targetId }?.stamped()
                if (proposed.deleted && old?.deleted != false) return@act Outcome.Refused("That task is no longer here.")
                val next = (
                    if (old == null) proposed.copy(id = p.targetId, deleted = false, done = false).let { it.copy(doneBy = Stamp(sum = it.doneSum())) }
                    else old.copy(
                        title = proposed.title, offsetDays = proposed.offsetDays, dueDay = proposed.dueDay, category = proposed.category,
                        notes = proposed.notes, assignedTo = proposed.assignedTo, deleted = proposed.deleted,
                    )
                    ).copy(updatedAt = t).let { it.copy(planBy = actor.stamp(t, it.planSum())) }
                d = d.copy(tasks = d.tasks.upsert(next))
            }
            ProposalKind.QUESTION -> {
                if (!p.deletes) return@act Outcome.Refused("A question is answered, not approved.")
                deleteForGood(p.targetId)?.let { return@act Outcome.Refused(it) }
            }
        }
        val done = p.copy(state = ProposalState.APPROVED, reason = "", updatedAt = t).let { it.copy(decided = actor.stamp(t, it.stateSum())) }
        replace(done)
        events.say(if (p.kind == ProposalKind.COST) "approved a suggested cost" else "approved: " + d.describe(p), p.id)
        Outcome.Ok(done)
    }.also { if (it.ok) forgetDeletedPhotos() }

    /** Delete whatever [id] names, as an Organiser does when approving a request to delete it. Null when done, else why not. */
    private fun Draft.deleteForGood(id: String): String? {
        d.item(id)?.let {
            deletePhotosOf(id)
            saveItems(listOf(it.copy(deleted = true)))
            return null
        }
        d.box(id)?.let {
            deletePhotosOf(id)
            saveBoxes(listOf(it.copy(deleted = true)))
            return null
        }
        d.room(id)?.let {
            deletePhotosOf(id)
            d = d.copy(rooms = d.rooms.upsert(actor.guard(it, it.copy(deleted = true), t)))
            return null
        }
        d.photos.firstOrNull { it.id == id && !it.deleted }?.let {
            d = d.copy(photos = d.photos.upsert(actor.guard(it, it.copy(deleted = true), t)))
            return null
        }
        d.task(id)?.let {
            d = d.copy(tasks = d.tasks.upsert((actor.guard(it, it.copy(deleted = true), t) ?: it).copy(updatedAt = t)))
            return null
        }
        d.expense(id)?.let {
            d = d.copy(expenses = d.expenses.upsert((actor.guard(it, it.copy(deleted = true), t) ?: it).copy(updatedAt = t)))
            return null
        }
        return "What it was about is no longer here."
    }

    /**
     * Ask an Organiser to delete something this phone's person may not delete themselves (see
     * [mayDelete]): an item, container, room, photo or task. It waits like a question, and an
     * Organiser approves it (the thing is deleted) or declines it. Anyone may ask. A Decider asking
     * about a cost suggests deleting it instead ([suggestCost]), since costs are not shown to Helpers.
     * Asking again about the same thing gives back the request already waiting.
     */
    fun askToDelete(targetId: String, note: String = ""): Outcome<Proposal> = synchronized(lock) {
        val d0 = _data.value
        d0.expense(targetId)?.let { e ->
            actor(d0).refusal(Action.SUGGEST)?.let { return Outcome.Refused("Only an Organiser can delete a cost.") }
            if (actor(d0).may(Action.DELETE)) return Outcome.Refused("You can delete it yourself.")
            return suggestCost(e.copy(deleted = true), note)
        }
        act(Action.ASK) {
            val target: Rec = d.item(targetId) ?: d.box(targetId) ?: d.room(targetId) ?: d.task(targetId)
                ?: d.photos.firstOrNull { it.id == targetId && !it.deleted } ?: return@act Outcome.Refused("That is no longer here.")
            if (d.mayDelete(actor.me, target) == null) return@act Outcome.Refused("You can delete it yourself.")
            d.liveProposals.firstOrNull { it.deletes && it.targetId == targetId && it.by == actor.id && it.state == ProposalState.WAITING }?.let { return@act Outcome.Ok(it) }
            val p = Proposal(id = "", kind = ProposalKind.QUESTION, targetId = targetId, note = note.trim(), deletes = true)
            propose(p, "asked to delete " + d.nameOf(targetId))
        }
    }

    /**
     * Withdraw a suggestion or question of one's own while it is still waiting. It is then in no
     * list on any phone. If an Organiser approved or declined it on another phone in the meantime,
     * that stands instead once the phones have synced.
     */
    fun withdraw(proposalId: String): Outcome<Proposal> = act(null) {
        val p = d.proposal(proposalId) ?: return@act Outcome.Refused("That suggestion is no longer here.")
        if (p.by != actor.id || actor.id.isEmpty()) return@act Outcome.Refused("Only the person who made a suggestion can withdraw it.")
        if (p.state != ProposalState.WAITING) return@act Outcome.Refused("That suggestion has already been dealt with, so it cannot be withdrawn.")
        val done = p.copy(deleted = true, updatedAt = t).let { it.copy(withdrawn = Stamp(actor.id, t, it.withdrawnSum())) }
        replace(done)
        events.say(if (p.kind == ProposalKind.QUESTION && !p.deletes) "withdrew a question" else "withdrew a suggestion", p.id)
        Outcome.Ok(done)
    }

    /** Decline a waiting suggestion, or dismiss a question, with the reason to show whoever made it. */
    fun decline(proposalId: String, reason: String = ""): Outcome<Proposal> = act(Action.APPROVE) {
        val p = d.proposal(proposalId) ?: return@act Outcome.Refused("That suggestion is no longer here.")
        if (p.state != ProposalState.WAITING) return@act Outcome.Refused("That suggestion is no longer waiting.")
        val done = p.copy(state = ProposalState.DECLINED, reason = reason.trim(), updatedAt = t).let { it.copy(decided = actor.stamp(t, it.stateSum())) }
        replace(done)
        events.say(if (p.kind == ProposalKind.COST) "declined a suggested cost" else "declined: " + d.describe(p), p.id)
        Outcome.Ok(done)
    }

    /**
     * Answer a question about an item. An Organiser's answer is the decision and
     * closes the question (the question, APPROVED, is returned). A Decider's
     * answer becomes a suggestion for an Organiser to approve (that suggestion is
     * returned); approving it closes the question too.
     */
    fun answer(questionId: String, disposition: Disposition, price: Double? = null, note: String = ""): Outcome<Proposal> = synchronized(lock) {
        val d0 = _data.value
        val q = d0.proposal(questionId)?.takeIf { it.kind == ProposalKind.QUESTION } ?: return Outcome.Refused("That question is no longer here.")
        if (q.state != ProposalState.WAITING) return Outcome.Refused("That question has already been dealt with.")
        if (q.deletes) return Outcome.Refused("That is a request to delete something: approve or decline it.")
        if (!actor(d0).may(Action.DECIDE)) return suggestDecision(q.targetId, disposition, price, note, answers = q.id)
        act(Action.DECIDE) {
            val item = d.item(q.targetId) ?: return@act Outcome.Refused("The item it was about is no longer here.")
            val before = item.disposition
            val next = decideItem(item, disposition, price ?: item.price)
            if (next.disposition != before) events.add(decisionVerb(next.disposition), next.title(), next.id)
            val done = q.copy(state = ProposalState.APPROVED, reason = note.trim(), updatedAt = t).let { it.copy(decided = actor.stamp(t, it.stateSum())) }
            replace(done)
            Outcome.Ok(done)
        }
    }

    // ---- sync / restore ----------------------------------------------------

    private fun forgetDeletedPhotos() {
        // Remove files for photos that were deleted on the other phone.
        val dead = _data.value.photos.filter { it.deleted }.map { it.file }
        dead.forEach { File(photoDir, it).delete() }
    }

    /**
     * Merge another phone's (or a backup's) dataset into this one. Returns the merged result.
     * Protected parts that arrive without a stamp, or stamped by someone whose role did not
     * allow the change, are not accepted (see [merge]).
     */
    fun mergeIn(remote: AppData): AppData {
        synchronized(lock) {
            val seen = maxStamp(remote)
            lastStamp.updateAndGet { maxOf(it, seen) }
            val was = _data.value
            val dev = _device.value
            var merged = merge(distrustUnstamped(was), distrustUnstamped(remote))
            fun AppData.helperHere() = hasOrganiser() && roleOf(dev) == Role.HELPER
            if (was.helperHere() && merged.helperHere()) merged = merged.withHelpersShareOf(was)
            _data.value = merged
            // What arrived can show that this phone's person was never rightly in the move (whoever added them had
            // already lost the right to). The phone is not left as nobody: it goes on as a Helper nobody has added yet.
            ensureMe()
            giveWayOnLetter()
            ensureMyPhone()
        }
        scheduleSave()
        forgetDeletedPhotos()
        return _data.value
    }

    /**
     * Replace everything with [other] (used for "restore and overwrite" and for erasing).
     * This is the raw replacement and is not checked against the rules: screens use [erase],
     * and Backup checks before restoring. A phone whose person is not in [other] starts as a new one.
     */
    fun replaceAll(other: AppData) {
        synchronized(lock) {
            val seen = maxStamp(other)
            lastStamp.updateAndGet { maxOf(it, seen) }
            val mine = _data.value.person(_device.value.personId)
            var next = migrate(other)
            // An older copy of the move does not know what this phone's person has been granted since.
            // Whoever restores it stays who they are, so a phone cannot restore itself out of its own move.
            if (mine != null && next.person(mine.id) != null) next = next.copy(people = mergePeople(next.people, listOf(mine)))
            _data.value = next
            ensureMe(restoring = true)
        }
        scheduleSave()
    }

    /** Does this phone share its move with anyone: is anybody but its own person in it? */
    private fun AppData.sharedHere(): Boolean = people.any { it.id != _device.value.personId }

    /** Is [backup] of the same move as [d]: does it have any of the same people, or (from before 2.0, with no people) any of the same things? */
    private fun sameMove(d: AppData, backup: AppData): Boolean {
        if (backup.people.isNotEmpty()) {
            val ids = d.people.map { it.id }.toSet()
            return backup.people.any { it.id in ids }
        }
        val ids = HashSet<String>()
        for (list in listOf<List<Rec>>(d.items, d.boxes, d.rooms, d.tasks, d.expenses)) for (r in list) ids += r.id
        return listOf<List<Rec>>(backup.items, backup.boxes, backup.rooms, backup.tasks, backup.expenses).any { l -> l.any { it.id in ids } }
    }

    /**
     * Why [backup] should not be restored on this phone, or null when it may be. Only an Organiser
     * restores ([Action.RESTORE_BACKUP]). A phone on its own takes in any backup: that is how a move
     * comes onto a new phone, or how two phones that cannot reach each other are combined. A phone
     * that shares its move with others only takes in a backup of that same move, so that two moves
     * are never mixed and passed on to everyone in this one.
     */
    fun whyNotRestore(backup: AppData, identity: MoveIdentity? = null): String? {
        val d = _data.value
        d.refusal(me, Action.RESTORE_BACKUP)?.let { return it }
        if (!d.sharedHere() || isSameMove(backup, identity)) return null
        val name = backup.move.name.trim().let { if (it.isEmpty()) "" else " (“$it”)" }
        return "That backup is of a different move$name. This phone shares its move with others, so the two are not mixed. " +
            "Restore it on a phone that is not in a move yet, or erase this phone first."
    }

    /**
     * Is [backup] of this move? A backup that says which move it is ([identity], from 2.1) is when
     * that is this move's ID or one of its aliases; one from before goes by 2.0's check, that it has
     * some of the same people, or (from before 2.0) some of the same things.
     */
    fun isSameMove(backup: AppData, identity: MoveIdentity?): Boolean =
        if (identity != null && identity.id.isNotEmpty()) identity.matches(_identity.value) else sameMove(_data.value, backup)

    /**
     * Restore a backup, already checked with [whyNotRestore] and brought up to date with [migrate].
     *
     * Who the phone is afterwards: a phone whose own person is in the backup stays itself. A phone on
     * its own leaves its own move behind, as when joining one (its things go in, its person does
     * not), and becomes the person whose phone made the backup ([madeBy]), or a Helper when that
     * person is not in it or has been removed. A phone that shares its move with others stays itself,
     * and people the backup brings whom this phone does not know and whose only grants are open ones
     * are not taken in: nobody ever let them into this move.
     *
     * Merged in, nothing on the phone is lost. With [replace] the phone goes back to the backup, but
     * who is in the move and every role change and removal since are kept, so a restore never gives
     * anyone back rights they have lost. A backup from a Helper's phone ([helpersShare]) holds none of
     * what Helpers are not sent, so this phone keeps its own costs, cost suggestions, and receipt and
     * serial-label photos. [identity] is the backup's move, when it says (2.1 and later).
     *
     * A phone on its own whose person is not in the backup takes the backup's move for its own
     * ([restoreOntoLonePhone]) when the backup has someone to become or an Organiser; with neither
     * (a backup from before 2.0, say), the backup's move simply becomes this phone's, as before:
     * there is nobody in it who could be misled. A phone that shares its move takes in nobody from
     * a backup who gave themselves their role ([withoutTheSelfMade]).
     */
    internal fun restore(backup: AppData, replace: Boolean, helpersShare: Boolean = false, madeBy: String = "", identity: MoveIdentity? = null) {
        synchronized(lock) {
            val cur = _data.value
            if (backup.person(_device.value.personId) == null && isAlone()) {
                val maker = backup.person(madeBy)?.takeIf { !it.removed }
                if (maker != null || backup.hasOrganiser()) {
                    restoreOntoLonePhone(backup, replace, maker?.id, identity)
                    return
                }
            }
            val incoming = if (cur.sharedHere()) backup.withoutTheSelfMade(cur) else backup
            // The same move, known by another ID on the phone that made the backup (both worked out from a move from 2.0).
            identity?.let { other -> if (other.id.isNotEmpty() && other.id != moveId) saveIdentity(_identity.value.withAliasesOf(other)) }
            if (!replace) {
                mergeIn(incoming)
                return
            }
            var next = incoming
            if (sameMove(cur, incoming) && cur.people.isNotEmpty()) {
                next = next.copy(people = mergePeople(next.people, cur.people))
                if (helpersShare) {
                    next = next.copy(
                        expenses = cur.expenses,
                        photos = next.photos.filter { it.kind !in HIDDEN_FROM_HELPERS } + cur.photos.filter { it.kind in HIDDEN_FROM_HELPERS },
                        proposals = next.proposals.filter { it.kind != ProposalKind.COST } + cur.proposals.filter { it.kind == ProposalKind.COST },
                    )
                }
            }
            replaceAll(next)
        }
    }

    /**
     * A phone that was on its own takes on the move of [backup], whose people do not include it, as
     * a phone does when it joins a move: it leaves its own move behind ([leavingOwnMove]), so what
     * it decided alone does not count there and what it deleted alone stays deleted, and it becomes
     * [personId], someone in the backup. With nobody to become, it goes on as a Helper nobody has
     * added yet ([ensureMe]), for the backup's move has an Organiser, who can add it. With [replace]
     * nothing of the phone's own is kept. The move's ID is the backup's ([identity]); a backup from
     * before 2.1 has its worked out from its founder.
     */
    private fun restoreOntoLonePhone(backup: AppData, replace: Boolean, personId: String?, identity: MoveIdentity?) {
        val dev = _device.value
        val seen = maxStamp(backup)
        lastStamp.updateAndGet { maxOf(it, seen) }
        val next = if (replace) backup else merge(distrustUnstamped(leavingOwnMove(_data.value)), distrustUnstamped(backup))
        if (personId != null) {
            val t = now()
            _data.value = next.copy(people = next.people.map { if (it.id == personId) it.copy(joinedAt = t, updatedAt = t) else it })
        } else {
            _data.value = next
        }
        val joined = identity?.takeIf { it.id.isNotEmpty() } ?: MoveIdentity(id = backup.derivedMoveId(), derived = true)
        val another = !joined.matches(_identity.value)
        if (another) {
            saveIdentity(joined)
            saveCodes(Codes())
        }
        val me = personId ?: dev.personId
        writeDevice(if (another) dev.forAnotherMove(me) else dev.copy(personId = me))
        ensureMe()
        giveWayOnLetter()
        mirrorMe()
        ensureMyPhone()
        scheduleSave()
        forgetDeletedPhotos()
    }

    /**
     * This backup as it goes into [d], a move this phone shares, without anyone this phone has not
     * heard of whom nobody else gave a role: every grant of theirs is open or their own. Such a
     * person counts as whatever they first said they were, an Organiser even (a phone that took in
     * another move's backup while on its own, on version 2.0), and the merge would pass them on to
     * everyone in this move. Anyone really in the move reaches this phone with the next sync.
     */
    private fun AppData.withoutTheSelfMade(d: AppData): AppData {
        val known = d.people.mapTo(HashSet()) { it.id }
        val kept = people.filter { p -> p.id in known || p.grants.any { it.by.isNotEmpty() && it.by != p.id } }
        return if (kept.size == people.size) this else copy(people = kept)
    }

    /** Erase everything on this phone, photos included, and start again. Organisers only. */
    fun erase(): Outcome<Unit> = synchronized(lock) {
        actor(_data.value).refusal(Action.ERASE)?.let { return Outcome.Refused(it) }
        photoDir.listFiles()?.forEach { it.delete() }
        replaceAll(AppData())
        setDevice { it.copy(nextSeq = 1, currentRoomId = null, currentTripId = null, lastSyncAt = 0, lastBackupAt = 0, sharedUpTo = 0, sharedAllUpTo = 0) }
        // What is left is a new move of this phone's own.
        saveIdentity(MoveIdentity(id = _data.value.derivedMoveId()))
        saveCodes(Codes(letter = codes.letter, founder = true, labelsFrom = FOUNDER_LABELS.first, labelsTo = FOUNDER_LABELS.last))
        ensureMyPhone()
        Outcome.Ok(Unit)
    }

    // ---- phones, letters and labels -------------------------------------------------

    /** The short tag printed in this move's label QR codes. */
    val moveTag: String get() = _identity.value.tag

    /** The printed label numbers this phone prints in the move, from its own block (see [Codes]). */
    fun labelRange(): IntRange = ownLabels(codes.labelsFrom, codes.labelsTo)

    /**
     * The number the next printed label gets on this phone: after the last one it printed, and
     * after any code from its range already on something, so no code is printed twice.
     */
    fun nextLabelNumber(): Int {
        val range = labelRange()
        if (range.isEmpty()) return maxOf(_data.value.move.labelsPrinted, _data.value.labelsUsed(1..Int.MAX_VALUE)) + 1
        return maxOf(codes.nextLabel, range.first, _data.value.labelsUsed(range) + 1)
    }

    /** Note that labels up to [last] have been printed on this phone. */
    fun labelsPrinted(last: Int) {
        synchronized(lock) { if (last + 1 > codes.nextLabel) saveCodes(codes.copy(nextLabel = last + 1)) }
        setMove { it.copy(labelsPrinted = maxOf(it.labelsPrinted, last)) }
    }

    /**
     * Retire one of someone's phones ("Retire Sam's old phone"), when it is lost or replaced.
     * Organisers only, and not the phone in hand. Once more, its work is taken in when it syncs,
     * and nothing is sent back to it; after that it is refused.
     */
    fun retirePhone(personId: String, phoneId: String): Outcome<Person> = act(Action.MANAGE_PEOPLE) {
        val p = d.person(personId) ?: return@act Outcome.Refused("That person is not in the move.")
        val ph = p.phones.firstOrNull { it.id == phoneId } ?: return@act Outcome.Refused("That phone is not one of ${p.name.ifBlank { "theirs" }}.")
        if (phoneId == phone.id) return@act Outcome.Refused("This is the phone in your hand. Retire it from another Organiser's phone.")
        if (ph.isRetired) return@act Outcome.Ok(p)
        val saved = savePerson(p.copy(phones = p.phones.map { if (it.id == phoneId) it.copy(retired = actor.stamp(t, retiredSum(phoneId))) else it }))
        events.say("retired ${p.name.ifBlank { "someone" }}'s phone ${ph.name.ifBlank { "" }}".trim(), p.id)
        Outcome.Ok(saved)
    }

    /**
     * Let in a phone of this move whose person nobody added (one that restored a backup of it while
     * on its own, say), when this phone's person may add people and shows the plain code with new
     * phones let in: as a Helper, in this person's name, since what it says of itself counts for
     * nothing (see [Roles]).
     */
    internal fun letIn(peer: Person): Outcome<Person> = act(Action.MANAGE_PEOPLE) {
        val base = d.person(peer.id) ?: peer
        val saved = savePerson(base.copy(role = Role.HELPER, grants = base.grants + grantOf(Role.HELPER, false, t, actor.signature)))
        events.say("let ${peer.name.ifBlank { "a phone" }} in as a Helper", peer.id)
        Outcome.Ok(saved)
    }

    /** Note that a retired phone's last work has been taken in: it is refused from now on. */
    internal fun markCollected(phoneId: String) = synchronized(lock) {
        val d = _data.value
        val (p, _) = d.phoneEntry(phoneId) ?: return@synchronized
        val t = now()
        val saved = p.copy(phones = p.phones.map { if (it.id == phoneId) it.copy(collected = true) else it }, updatedAt = t)
        _data.value = d.copy(people = d.people.map { if (it.id == p.id) saved else it })
        scheduleSave()
    }

    /**
     * On the phone that showed an invite or a Helper code, when [phoneId] joins as [personId]: the
     * phone goes on the person's list with its own name, and is given a block of label numbers from
     * this phone's own blocks. A person's first phone uses the letter they were given, unless that
     * phone uses it in another move ([avoid]); another phone of theirs gets a letter of its own.
     */
    internal fun admitPhone(personId: String, phoneId: String, deviceName: String, avoid: Set<String>): Outcome<Person> = synchronized(lock) {
        val d = _data.value
        val p = d.person(personId)?.takeIf { !it.removed } ?: return Outcome.Refused("That person is not in the move.")
        if (phoneId.isEmpty() || p.phones.any { it.id == phoneId }) return Outcome.Ok(p)
        val t = now()
        var person = p
        var letter = ""
        if (p.phones.isEmpty()) {
            if (avoid.any { it.equals(p.letter, ignoreCase = true) } || p.letter.isBlank()) person = person.copy(letter = allotLetter(d, avoid))
        } else letter = allotLetter(d, avoid)
        val block = givenBlock(codes.labelsFrom, codes.labelsTo, codes.blocksGiven)
        if (block != null) saveCodes(codes.copy(blocksGiven = codes.blocksGiven + 1))
        val entry = PhoneEntry(id = phoneId, name = deviceName.trim(), letter = letter, labelsFrom = block?.first ?: 0, labelsTo = block?.last ?: 0, addedAt = t)
        person = person.copy(phones = person.phones + entry, updatedAt = t)
        _data.value = d.copy(people = d.people.map { if (it.id == p.id) person else it })
        scheduleSave()
        Outcome.Ok(person)
    }

    /**
     * On the phone showing a Helper code: [phoneId] joins as a new Helper under [name], the name
     * that phone remembers ([PhoneSettings.name]), made unique in the move ("Sam 2"). A phone of
     * someone removed, or a retired one, is refused; one already in the move comes in as who it is.
     */
    internal fun admitHelper(name: String, phoneId: String, deviceName: String, avoid: Set<String>): Outcome<Person> = synchronized(lock) {
        val d = _data.value
        d.refusal(me, Action.MANAGE_PEOPLE)?.let { return Outcome.Refused(it) }
        d.phoneEntry(phoneId)?.let { (p, ph) ->
            if (ph.isRetired) return Outcome.Refused(RETIRED_PHONE)
            if (p.removed) return Outcome.Refused("${p.name.ifBlank { "That person" }} has been removed from the move.")
            return Outcome.Ok(p)
        }
        val base = name.trim().ifBlank { "Helper" }
        val names = d.people.filter { !it.removed }.map { it.name.trim().lowercase() }.toSet()
        var shown = base
        var n = 2
        while (shown.lowercase() in names) shown = "$base ${n++}"
        val added = addPerson(shown, Role.HELPER).valueOrNull() ?: return Outcome.Refused("Only an Organiser can add people, change roles or remove people.")
        return admitPhone(added.id, phoneId, deviceName, avoid)
    }

    /** Has anyone but this phone's person ever joined the move? Then it cannot be deleted, only removed from this phone. */
    fun everShared(): Boolean {
        val mine = _device.value.personId
        return _data.value.people.any { it.id != mine && (it.joinedAt != 0L || it.phones.isNotEmpty()) }
    }

    /** Is this phone's person the move's only Organiser, while there are others in it? Then it cannot be removed from this phone. */
    fun lastOrganiserOfShared(): Boolean {
        val d = _data.value
        val mine = _device.value.personId
        val me = d.person(mine) ?: return false
        return me.role == Role.ORGANISER && !me.removed && d.people.none { it.id != mine && it.role == Role.ORGANISER && !it.removed } &&
            d.people.any { it.id != mine && !it.removed }
    }

    /**
     * What this phone holds that no other phone has: changes made since its last complete sync, and
     * the photos among them. A phone that synced only with Helpers counts what Helpers are not sent
     * as not shared ([DeviceInfo.sharedAllUpTo]).
     */
    fun unshared(): Unshared {
        val d = _data.value
        val dev = _device.value
        val upTo = dev.sharedUpTo
        val upToAll = maxOf(dev.sharedAllUpTo, 0)
        var changes = 0
        val things = ArrayList<String>()
        fun count(list: List<Rec>, hidden: (Rec) -> Boolean = { false }, name: (Rec) -> String? = { null }) {
            for (r in list) if (r.updatedAt > (if (hidden(r)) upToAll else upTo)) {
                changes++
                name(r)?.let { if (things.size < 10) things += it }
            }
        }
        count(d.items, name = { (it as Item).title() })
        count(d.boxes, name = { (it as Box).title() })
        count(d.rooms)
        count(d.tasks)
        count(d.expenses, hidden = { true })
        count(d.supplies)
        count(d.trips)
        count(d.readings)
        count(d.entries)
        count(d.proposals, hidden = { (it as Proposal).kind == ProposalKind.COST })
        if (d.move.updatedAt > upTo) changes++
        var photos = 0
        for (p in d.photos) {
            val limit = if (p.kind in HIDDEN_FROM_HELPERS) upToAll else upTo
            if (!p.deleted && maxOf(p.updatedAt, p.createdAt) > limit && File(photoDir, p.file).isFile) photos++
            else if (p.updatedAt > limit) changes++
        }
        return Unshared(changes, photos, things)
    }

    /** The newest change in [d], for [noteShared]. */
    internal fun stampOf(d: AppData): Long = maxStamp(d)

    /** A sync has finished: everything up to [upTo] is on another phone too, all of it when that phone's person is no Helper ([everything]). */
    internal fun noteShared(upTo: Long, everything: Boolean) = synchronized(lock) {
        val dev = _device.value
        val next = dev.copy(sharedUpTo = maxOf(dev.sharedUpTo, upTo), sharedAllUpTo = if (everything) maxOf(dev.sharedAllUpTo, upTo) else dev.sharedAllUpTo)
        if (next != dev) writeDevice(next)
    }

    /** After meeting a phone of the same move that knew it by another ID (see [MoveIdentity]): both are kept, and the one worked out from the move's founder as it is now known is used. */
    internal fun adoptAliases(other: MoveIdentity) = synchronized(lock) {
        val cur = _identity.value
        var next = cur.withAliasesOf(other)
        if (cur.derived) {
            val id = _data.value.derivedMoveId()
            if (id != next.id) next = next.copy(id = id, aliases = (next.aliases + next.id).filter { it != id }.distinct())
        }
        if (next != cur) saveIdentity(next)
    }

    /** The letters this phone uses in this move: its own, and those it has handed out, for keeping its other moves apart. */
    fun lettersHere(): Set<String> = (listOf(_device.value.letter) + codes.lettersGiven).filter { it.isNotBlank() }.map { it.uppercase() }.toSet()

    /** Letters this phone uses in its other moves. */
    fun lettersElsewhereNow(): Set<String> = lettersElsewhere().map { it.uppercase() }.toSet()

    /** This phone's letter and label numbers as kept outside Android's backup (for tests and the move list). */
    internal val codesNow: Codes get() = codes

    companion object {
        const val RETIRED_PHONE = "This phone has been retired from the move. Its work was passed on; use the move on your new phone."
    }
}

/** What a phone holds that no other phone has (see [Store.unshared]). [things] names some of what changed. */
data class Unshared(val changes: Int, val photos: Int, val things: List<String>) {
    val nothing: Boolean get() = changes == 0 && photos == 0
}

/** The settings for one move as a phone starts out in another one. */
internal fun DeviceInfo.forAnotherMove(personId: String) = copy(
    personId = personId, letter = "", nextSeq = 1, currentRoomId = null, currentTripId = null,
    lastSyncAt = 0, lastBackupAt = 0, sharedUpTo = 0, sharedAllUpTo = 0,
)
