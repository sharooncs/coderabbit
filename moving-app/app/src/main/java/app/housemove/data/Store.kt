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

/** Settings that belong to this phone only and are never synced. */
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
 */
class Store(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    val photoDir: File = File(dir, "photos").apply {
        mkdirs()
        // Leftovers from a transfer or import that was cut off.
        listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".import") }?.forEach { it.delete() }
    }
    private val dataFile = File(dir, "data.json")
    private val backupFile = File(dir, "data.json.bak")
    private val deviceFile = File(dir, "device.json")

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

    private val _data = MutableStateFlow(migrate(load()))
    val data: StateFlow<AppData> = _data

    private val _device = MutableStateFlow(loadDevice())
    val device: StateFlow<DeviceInfo> = _device

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

    private fun loadDevice(): DeviceInfo = try {
        if (deviceFile.exists()) AppJson.decodeFromString<DeviceInfo>(deviceFile.readText())
        else DeviceInfo(letter = ('A' + (Math.random() * 26).toInt().coerceIn(0, 25)).toString())
    } catch (_: Exception) {
        DeviceInfo()
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
        ensureMe()
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
        try {
            io.execute {
                try {
                    // Written whole or not at all: this file says who the phone is, and half of it would say nobody.
                    val tmp = File(dir, "device.json.tmp")
                    tmp.writeText(AppJson.encodeToString(next))
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
        if (me != null && asked.personId == before.personId) {
            var mine: Person = me
            if (asked.name != before.name) mine = mine.copy(name = asked.name)
            val letter = asked.letter.trim().uppercase()
            if (asked.letter != before.letter && letter.isNotEmpty() && d.people.none { it.id != me.id && it.letter.equals(letter, ignoreCase = true) }) {
                mine = mine.copy(letter = letter)
            }
            val t = now()
            if (asked.helper != before.helper && d.people.count { !it.removed } <= 1) {
                val role = if (asked.helper) Role.HELPER else Role.ORGANISER
                if (role != mine.role) mine = mine.copy(role = role, grants = mine.grants + Grant(role, false, t, Actor(d, me).signature))
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
    private fun ensureMe(restoring: Boolean = false) = synchronized(lock) {
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
                val letter = if (d.people.any { it.letter.equals(wanted, ignoreCase = true) }) d.freeLetter() else wanted
                val p = Person(id = newId(), name = dev.name, role = role, letter = letter, joinedAt = t, grants = listOf(Grant(role, false, t, "")), updatedAt = t)
                _data.value = d.copy(people = d.people + p)
                scheduleSave()
                writeDevice(dev.copy(personId = p.id))
            }
        }
        mirrorMe()
    }

    /** This phone's own settings follow its person: the name, the letter, and the old helper switch. */
    private fun mirrorMe() {
        val dev = _device.value
        val d = _data.value
        val p = d.person(dev.personId) ?: return
        val next = dev.copy(name = p.name, letter = p.letter.ifBlank { dev.letter }, helper = d.roleOf(dev) == Role.HELPER)
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

    fun put(x: Room) = update { it.copy(rooms = it.rooms.upsert(x.copy(updatedAt = now()))) }

    /**
     * Save an item. The decision, the room it goes to and whether it has gone are only
     * changed when this phone's person may change them (see Rules.kt); the rest is always saved.
     */
    fun put(x: Item) = change { saveItems(listOf(x)) }

    /** Save a container. Moving one that is inside another moves the outer one. */
    fun put(x: Box) = change { saveBoxes(listOf(x)) }
    fun put(x: Photo) = update {
        val t = now()
        it.copy(photos = it.photos.upsert(x.copy(updatedAt = t, createdAt = if (x.createdAt == 0L) t else x.createdAt)))
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
    fun put(x: Supply) = update { it.copy(supplies = it.supplies.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Trip) = update { it.copy(trips = it.trips.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Reading) = update { it.copy(readings = it.readings.upsert(x.copy(updatedAt = now()))) }
    fun put(x: BoxEntry) = update {
        val t = now()
        it.copy(entries = it.entries.upsert(x.copy(updatedAt = t, createdAt = if (x.createdAt == 0L) t else x.createdAt)))
    }

    fun delete(x: Room) = put(x.copy(deleted = true))
    fun delete(x: Item) = put(x.copy(deleted = true))
    fun delete(x: BoxEntry) = put(x.copy(deleted = true))

    /**
     * Deleting a container also removes the list of things scanned into it.
     * Catalogued items and other containers that were inside are let go, not deleted.
     */
    fun delete(x: Box) = put(x.copy(deleted = true))
    fun delete(x: Task) = put(x.copy(deleted = true))
    fun delete(x: Expense) = put(x.copy(deleted = true))
    fun delete(x: Supply) = put(x.copy(deleted = true))
    fun delete(x: Trip) = put(x.copy(deleted = true))
    fun delete(x: Reading) = put(x.copy(deleted = true))
    fun delete(x: Photo) {
        put(x.copy(deleted = true))
        File(photoDir, x.file).delete()
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
        savePerson(p.copy(role = role, removed = removed, grants = p.grants + Grant(role, removed, t, actor.signature)))

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
        val p = savePerson(Person(id = newId(), name = shown, role = role, letter = d.letterFor(actor.me), grants = listOf(Grant(role, false, t, actor.signature))))
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
                        selfMade && it.id == old!!.id -> it.copy(grants = it.grants + Grant(it.role, true, t, Actor(d, old).signature), updatedAt = t)
                        else -> it
                    }
                }
            ),
        )
        scheduleSave()
        writeDevice(dev.copy(personId = personId))
        mirrorMe()
        Outcome.Ok(saved)
    }

    /**
     * Take in the data of a move this phone has just been invited into and
     * become [personId] in it. A phone that was on its own until now leaves the
     * person it started as behind, so what it decided alone does not count as
     * an Organiser's decision in the move it joins.
     */
    fun joinAs(personId: String, remote: AppData): Outcome<Person> = synchronized(lock) {
        val incoming = distrustUnstamped(remote)
        val target = incoming.person(personId) ?: return Outcome.Refused("That person is not in the move.")
        if (target.removed) return Outcome.Refused("${target.name.ifBlank { "That person" }} has been removed from the move.")
        val dev = _device.value
        val seen = maxStamp(incoming)
        lastStamp.updateAndGet { maxOf(it, seen) }
        val merged = merge(distrustUnstamped(offerFor(personId)), incoming)
        val t = now()
        _data.value = merged.copy(people = merged.people.map { if (it.id == personId) it.copy(joinedAt = t, updatedAt = t) else it })
        scheduleSave()
        writeDevice(dev.copy(personId = personId))
        mirrorMe()
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
        val next = was.copy(disposition = disposition, price = price, updatedAt = t).let { it.copy(decisionBy = actor.stamp(t, it.decisionSum())) }
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
        val p = Photo(id = newId(), ownerId = e.id, kind = PhotoKind.RECEIPT, file = file.name, note = note, by = actor.signature, createdAt = t, updatedAt = t)
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

    /** Suggest a new cost, or a change to an existing one (same id). The cost appears, or changes, when approved. */
    fun suggestCost(expense: Expense, note: String = ""): Outcome<Proposal> = act(Action.SUGGEST) {
        val clean = expense.copy(costBy = Stamp(), paidBy = Stamp(), deleted = false, updatedAt = 0)
        propose(Proposal(id = "", kind = ProposalKind.COST, targetId = expense.id, expense = clean, note = note.trim()), "suggested a cost")
    }

    /** Suggest a new task, or a change to an existing one (same id). */
    fun suggestTask(task: Task, note: String = ""): Outcome<Proposal> = act(Action.SUGGEST) {
        val clean = task.copy(planBy = Stamp(), doneBy = Stamp(), deleted = false, updatedAt = 0)
        propose(Proposal(id = "", kind = ProposalKind.TASK, targetId = task.id, task = clean, note = note.trim()), "suggested a task: " + task.title.take(60))
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
                replace(item.copy(destRoomId = p.roomId, updatedAt = t).let { it.copy(roomBy = actor.stamp(t, it.roomSum())) })
            }
            ProposalKind.COST -> {
                val proposed = p.expense ?: return@act Outcome.Refused("That suggestion has no cost in it.")
                val old = d.expenses.firstOrNull { it.id == p.targetId }?.stamped()
                val next = (
                    if (old == null) proposed.copy(id = p.targetId, deleted = false).let { it.copy(paidBy = actor.stamp(t, it.paidSum())) }
                    else old.copy(title = proposed.title, amount = proposed.amount, category = proposed.category, day = proposed.day, deleted = false)
                    ).copy(updatedAt = t).let { it.copy(costBy = actor.stamp(t, it.costSum())) }
                d = d.copy(expenses = d.expenses.upsert(next))
            }
            ProposalKind.TASK -> {
                val proposed = p.task ?: return@act Outcome.Refused("That suggestion has no task in it.")
                val old = d.tasks.firstOrNull { it.id == p.targetId }?.stamped()
                val next = (
                    if (old == null) proposed.copy(id = p.targetId, deleted = false, done = false).let { it.copy(doneBy = Stamp(sum = it.doneSum())) }
                    else old.copy(
                        title = proposed.title, offsetDays = proposed.offsetDays, dueDay = proposed.dueDay, category = proposed.category,
                        notes = proposed.notes, assignedTo = proposed.assignedTo, deleted = false,
                    )
                    ).copy(updatedAt = t).let { it.copy(planBy = actor.stamp(t, it.planSum())) }
                d = d.copy(tasks = d.tasks.upsert(next))
            }
            ProposalKind.QUESTION -> return@act Outcome.Refused("A question is answered, not approved.")
        }
        val done = p.copy(state = ProposalState.APPROVED, reason = "", updatedAt = t).let { it.copy(decided = actor.stamp(t, it.stateSum())) }
        replace(done)
        events.say(if (p.kind == ProposalKind.COST) "approved a suggested cost" else "approved: " + d.describe(p), p.id)
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
            _data.value = merge(distrustUnstamped(_data.value), distrustUnstamped(remote))
            // What arrived can show that this phone's person was never rightly in the move (whoever added them had
            // already lost the right to). The phone is not left as nobody: it goes on as a Helper nobody has added yet.
            ensureMe()
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

    /** Erase everything on this phone, photos included, and start again. Organisers only. */
    fun erase(): Outcome<Unit> = synchronized(lock) {
        actor(_data.value).refusal(Action.ERASE)?.let { return Outcome.Refused(it) }
        photoDir.listFiles()?.forEach { it.delete() }
        replaceAll(AppData())
        setDevice { it.copy(nextSeq = 1, currentRoomId = null, currentTripId = null, lastSyncAt = 0, lastBackupAt = 0) }
        Outcome.Ok(Unit)
    }
}
