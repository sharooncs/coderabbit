package app.housemove.data

import app.housemove.sync.WifiSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.concurrent.Volatile

/** One move on the phone, as the list of moves shows it. */
@Serializable
data class MoveCard(
    /** The move's ID (see [MoveIdentity]). */
    val moveId: String,
    /** Its folder, under moves/ (and photos/, and outside Android's backup for its codes). */
    val folder: String,
    val name: String = "",
    /** This phone's role in it, as last seen. */
    val role: Role? = null,
    /** When it was last opened. */
    val lastUsed: Long = 0,
    /** Put away under "Earlier moves": kept, but not opened at start. */
    val archived: Boolean = false,
    /** Other IDs the move is known by (see [MoveIdentity.aliases]). */
    val aliases: List<String> = emptyList(),
    /** This phone has been taken out of the move ([Store.removedFromMove]): the card says so, and offers to send its last work. */
    val removed: Boolean = false,
) {
    fun matches(id: String): Boolean = id.isNotEmpty() && (id == moveId || id in aliases)
    val tags: Set<String> get() = (listOf(moveId) + aliases).map { moveTag(it) }.toSet()
}

@Serializable
private data class Registry(val cards: List<MoveCard> = emptyList(), val current: String? = null)

/** What a scanned invite says, read before anything is sent to the other phone (see [Moves.readInvite]). */
data class InviteCheck(
    val invite: WifiSync.Invite,
    /** "Join Maple Road as Priya, Decider?" */
    val question: String,
    /** The card this phone already holds for that move: joining goes into it. */
    val card: MoveCard?,
    /** When offered, how many things this phone's own move holds that could come along ("Bring your 23 things into Maple Road?"). */
    val bringOffer: Int?,
)

/** What removing a move from this phone would lose, and whether it may be removed (see [Moves.removing]). */
data class Removal(val unshared: Unshared, val refusal: String?)

/**
 * The moves on this phone. Each has a card in a small registry and a folder of its own: its data,
 * its settings for this phone and its photos, with this phone's letter, code counter and label
 * numbers for it kept apart, outside Android's backup ([noBackupDir], which also holds the phone
 * ID). The settings that are the same in every move (the name you go by, book look-up) are in
 * [phone].
 *
 * A phone that had HouseMove 2.0 has its one move in [filesDir] itself; the first time this is
 * made, that move is moved into a folder of its own and becomes the first card. That is done so a
 * crash at any point loses nothing: the old files stay until the new ones are complete, and the
 * next start finishes the job.
 */
class Moves(
    private val filesDir: File,
    private val noBackupDir: File,
    deviceName: () -> String = { "" },
    private val clock: () -> Long = ::currentTimeMillis,
) {
    val phone: Phone

    private val registryFile = File(filesDir, "moves.json")
    private val movesDir = File(filesDir, "moves")
    private val photosDir = File(filesDir, "photos")
    private val codesRoot = File(noBackupDir, "moves")
    private val lock = Lock()
    private val open = HashMap<String, Store>()

    private val _cards = MutableStateFlow<List<MoveCard>>(emptyList())

    /** Every move on the phone, most recently used first. */
    val cards: StateFlow<List<MoveCard>> = _cards

    private val _current = MutableStateFlow<Store?>(null)

    /** The move in use, or null on a phone with no move yet (first open: "Start a new move" or "Join a move"). */
    val current: StateFlow<Store?> = _current

    private var currentFolder: String? = null

    init {
        filesDir.mkdirs()
        noBackupDir.mkdirs()
        phone = Phone(Phone.idIn(File(noBackupDir, "phone-id")), deviceName, File(filesDir, "phone.json"), File(noBackupDir, "phone-key.json"))
        upgradeFrom20()
        val reg = readRegistry()
        var cards = reg.cards.filter { File(movesDir, it.folder).isDirectory }
        // A folder the list does not know of (the list was lost): its move is still there, so it gets a card again.
        val known = cards.map { it.folder }.toSet()
        movesDir.listFiles()?.filter { it.isDirectory && it.name !in known && !it.name.endsWith(".new") && File(it, "move.json").isFile }?.forEach { f ->
            val id = readIdentity(f) ?: return@forEach
            cards = cards + MoveCard(moveId = id.id, folder = f.name, aliases = id.aliases, name = readName(f))
        }
        // A join or restore cut off before its move was in the list leaves a folder that is not a move yet.
        movesDir.listFiles()?.filter { it.isDirectory && it.name.endsWith(".new") }?.forEach { removeFolder(it.name) }
        _cards.value = sorted(cards)
        currentFolder = reg.current?.takeIf { f -> cards.any { it.folder == f } }
            ?: cards.filter { !it.archived }.maxByOrNull { it.lastUsed }?.folder
        saveRegistry()
        currentFolder?.let { _current.value = storeIn(it) }
        refresh()
    }

    // ---- the list --------------------------------------------------------------------

    private fun sorted(list: List<MoveCard>) = list.sortedWith(compareBy<MoveCard>({ it.archived }, { -it.lastUsed }, { it.name }, { it.folder }))

    private fun readRegistry(): Registry = try {
        if (registryFile.isFile) AppJson.decodeFromString<Registry>(registryFile.readText()) else Registry()
    } catch (_: Exception) {
        Registry()
    }

    private fun saveRegistry() {
        try {
            writeWhole(registryFile, AppJson.encodeToString(Registry(_cards.value, currentFolder)))
        } catch (_: Exception) {
        }
    }

    private fun readIdentity(folder: File): MoveIdentity? = try {
        AppJson.decodeFromString<MoveIdentity>(File(folder, "move.json").readText()).takeIf { it.id.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun readName(folder: File): String = try {
        AppJson.decodeFromString<AppData>(File(folder, "data.json").readText()).move.name
    } catch (_: Exception) {
        ""
    }

    /** Bring every card up to date with its move: name, role and IDs. The screens call this when they come back into view. */
    fun refresh() = synchronized(lock) {
        val next = _cards.value.map { c ->
            val s = open[c.folder] ?: return@map c
            val id = s.identity.value
            c.copy(moveId = id.id, aliases = id.aliases, name = s.data.value.move.name, role = s.myRole, removed = s.removedFromMove())
        }
        if (next != _cards.value) {
            _cards.value = sorted(next)
            saveRegistry()
        }
    }

    internal fun card(moveId: String): MoveCard? = _cards.value.firstOrNull { it.matches(moveId) }

    private fun cardOf(store: Store): MoveCard? = synchronized(lock) { open.entries.firstOrNull { it.value === store }?.key }?.let { f -> _cards.value.firstOrNull { it.folder == f } }

    private fun update(folder: String, f: (MoveCard) -> MoveCard) {
        _cards.value = sorted(_cards.value.map { if (it.folder == folder) f(it) else it })
        saveRegistry()
    }

    // ---- stores ----------------------------------------------------------------------

    /** Letters this phone uses in its moves other than the one in [folder]: no two of its moves have the same. */
    private fun lettersOutside(folder: String): Set<String> {
        val out = HashSet<String>()
        for (c in _cards.value) {
            if (c.folder == folder) continue
            val s = synchronized(lock) { open[c.folder] }
            if (s != null) out += s.lettersHere()
            else try {
                val f = File(File(codesRoot, c.folder), "codes.json")
                if (f.isFile) AppJson.decodeFromString<Codes>(f.readText()).let { k -> out += (listOf(k.letter) + k.lettersGiven + k.lettersUsed).filter { it.isNotBlank() }.map { it.uppercase() } }
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** The label numbers this phone prints from in its moves other than the one in [folder]: no two of its moves share any. */
    private fun labelsOutside(folder: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (c in _cards.value) {
            if (c.folder == folder) continue
            val s = synchronized(lock) { open[c.folder] }
            if (s != null) out += s.labelRange()
            else try {
                val f = File(File(codesRoot, c.folder), "codes.json")
                if (f.isFile) AppJson.decodeFromString<Codes>(f.readText()).let { k -> out += ownLabels(k.labelsFrom, k.labelsTo) }
            } catch (_: Exception) {
            }
        }
        return out.filter { !it.isEmpty() }
    }

    internal fun storeIn(folder: String): Store = synchronized(lock) {
        open.getOrPut(folder) {
            Store(
                dir = File(movesDir, folder).apply { mkdirs() },
                clock = clock,
                phone = phone,
                codesDir = File(codesRoot, folder).apply { mkdirs() },
                lettersElsewhere = { lettersOutside(folder) },
                photos = File(photosDir, folder),
                listed = true,
                labelsElsewhere = { labelsOutside(folder) },
            ).also { s ->
                s.onSynced = { synced(it) }
                // An erased move is a new one, with an ID of its own: its card follows at once, so it is never opened, scanned or
                // joined by the ID it had.
                s.onErased = { refresh() }
            }
        }
    }

    /** The Store of a move on the phone, opened if need be, without making it the one in use. */
    fun store(moveId: String): Store? = card(moveId)?.let { storeIn(it.folder) }

    /** Make [moveId] the move in use, and return its Store. An archived move comes out of the archive. */
    fun open(moveId: String): Store? = synchronized(lock) {
        val c = card(moveId) ?: return null
        val s = storeIn(c.folder)
        currentFolder = c.folder
        update(c.folder) { it.copy(lastUsed = clock(), archived = false) }
        _current.value = s
        refresh()
        s
    }

    /**
     * Start a new move with this phone as its founder and Organiser. [yourName] is offered from the
     * phone's settings. It becomes the move in use.
     */
    fun create(name: String = "", yourName: String = phone.settings.value.name): Store = synchronized(lock) {
        val folder = newFolder()
        val s = storeIn(folder)
        if (yourName.isNotBlank()) s.setDevice { it.copy(name = yourName.trim()) }
        if (name.isNotBlank()) s.setMove { it.copy(name = name.trim()) }
        s.flush()
        addCard(folder, s)
        s
    }

    private fun newFolder(): String {
        var f: String
        do f = "m" + randomCode(10) while (File(movesDir, f).exists() || File(movesDir, "$f.new").exists())
        return f
    }

    private fun addCard(folder: String, s: Store, current: Boolean = true) {
        val id = s.identity.value
        val card = MoveCard(moveId = id.id, folder = folder, name = s.data.value.move.name, role = s.myRole, lastUsed = clock(), aliases = id.aliases)
        _cards.value = sorted(_cards.value.filter { it.folder != folder } + card)
        if (current) {
            currentFolder = folder
            _current.value = s
        }
        saveRegistry()
    }

    /** The move in use, or a new one when the phone has none yet. For screens that need a Store whatever happens. */
    fun currentOrNew(): Store = _current.value ?: create()

    /** Put a move away under "Earlier moves", or bring it back. An archived move in use stops being the one in use. */
    fun archive(moveId: String, archived: Boolean = true) = synchronized(lock) {
        val c = card(moveId) ?: return@synchronized
        update(c.folder) { it.copy(archived = archived) }
        if (archived && currentFolder == c.folder) {
            currentFolder = _cards.value.firstOrNull { !it.archived }?.folder
            _current.value = currentFolder?.let { storeIn(it) }
            saveRegistry()
        }
    }

    /** Write every open move to disk. Call when the app goes to the background. */
    fun flush() {
        val stores = synchronized(lock) { open.values.toList() }
        stores.forEach { it.flush() }
    }

    // ---- removing and deleting --------------------------------------------------------

    /**
     * What removing [moveId] from this phone would lose (what no other phone has, for the screen to
     * offer Sync, Send update or Back up first), and why it may not be removed at all: the last
     * Organiser cannot remove a move others share.
     */
    fun removing(moveId: String): Removal? {
        val s = store(moveId) ?: return null
        val refusal = if (s.lastOrganiserOfShared()) "You are this move's only Organiser, and others are in it. Make someone else an Organiser first, so the move does not depend on this phone." else null
        return Removal(s.unshared(), refusal)
    }

    /**
     * Remove a move from this phone; the other phones in it keep it. Refused while it holds work no
     * other phone has, unless [evenIfUnshared] (the person has seen [removing] and chose to).
     */
    fun remove(moveId: String, evenIfUnshared: Boolean = false): Outcome<Unit> {
        val r = removing(moveId) ?: return Outcome.Refused("That move is not on this phone.")
        r.refusal?.let { return Outcome.Refused(it) }
        if (!r.unshared.nothing && !evenIfUnshared) {
            return Outcome.Refused("This phone has work no other phone has yet. Sync, send an update or back up first, or remove it anyway.")
        }
        drop(moveId)
        return Outcome.Ok(Unit)
    }

    /** Why [moveId] cannot be deleted, or null: only a move nobody else has ever joined can be. */
    fun whyNotDelete(moveId: String): String? {
        val s = store(moveId) ?: return "That move is not on this phone."
        if (s.everShared()) return "Others have joined this move, so it is not yours alone to delete. You can remove it from this phone."
        return null
    }

    /** Delete a move nobody else has ever joined, with everything in it. */
    fun delete(moveId: String): Outcome<Unit> {
        whyNotDelete(moveId)?.let { return Outcome.Refused(it) }
        drop(moveId)
        return Outcome.Ok(Unit)
    }

    private fun drop(moveId: String) = synchronized(lock) {
        val c = card(moveId) ?: return@synchronized
        open.remove(c.folder)?.close()
        _cards.value = _cards.value.filter { it.folder != c.folder }
        if (currentFolder == c.folder) {
            currentFolder = _cards.value.firstOrNull { !it.archived }?.folder
            _current.value = currentFolder?.let { storeIn(it) }
        }
        saveRegistry()
        removeFolder(c.folder)
    }

    private fun removeFolder(folder: String) {
        File(movesDir, folder).deleteRecursively()
        // A move still being joined keeps its photos and codes under the name it will have.
        val name = folder.removeSuffix(".new")
        File(photosDir, name).deleteRecursively()
        File(codesRoot, name).deleteRecursively()
    }

    // ---- labels -----------------------------------------------------------------------

    /**
     * The moves on this phone a scanned label belongs to. A label printed by 2.1 carries its move's
     * tag; an older one does not, and may be in more than one move, when the screen asks which.
     */
    fun movesHolding(scanned: String): List<MoveCard> {
        val label = readLabel(scanned)
        if (label.code.isEmpty()) return emptyList()
        if (label.tag != null) return _cards.value.filter { label.tag in it.tags }
        return _cards.value.filter { c -> storeIn(c.folder).data.value.findByLabel(label.code) != null }
    }

    // ---- joining ----------------------------------------------------------------------

    /**
     * Read a scanned invite before anything is sent: what to ask ("Join Maple Road as Priya,
     * Decider?"), the card this phone already holds for that move, and whether to offer to bring
     * this phone's own things. Null when the text is not an invite.
     */
    fun readInvite(text: String): InviteCheck? {
        val inv = WifiSync.Invite.parse(text) ?: return null
        val held = inv.moveId.takeIf { it.isNotEmpty() }?.let { card(it) }
        val mine = _current.value
        val offer = if (held == null && mine != null) bringable(mine) else null
        return InviteCheck(inv, inv.question(), held, offer)
    }

    /**
     * How many things this phone's own move could bring into one it joins: offered only when it has
     * things, nobody else is in it, and it is not a move for just listing what you own or for storage.
     */
    private fun bringable(s: Store): Int? {
        val d = s.data.value
        if (!s.isAlone()) return null
        if (d.move.kind == MoveKind.INVENTORY || d.move.kind == MoveKind.STORAGE) return null
        val n = d.liveItems.size + d.liveBoxes.size
        return n.takeIf { it > 0 }
    }

    /**
     * Join the move of a checked invite, once the person has said Yes. It goes into the card this
     * phone already holds for that move (and then only through a host it knows as an Organiser of
     * it), or into a new card. With [bringThings] (when offered, default No), what this phone's own
     * move holds goes in with it and that move is archived, not deleted. [onWaiting] is called when
     * the other phone's person is asked to confirm. The new move becomes the one in use.
     */
    fun join(check: InviteCheck, bringThings: Boolean = false, connectTimeoutMs: Int = 4000, onWaiting: () -> Unit = {}): WifiSync.Result {
        val held = check.card ?: check.invite.moveId.takeIf { it.isNotEmpty() }?.let { card(it) }
        if (held != null) {
            val s = storeIn(held.folder)
            val r = WifiSync.join(s, check.invite, connectTimeoutMs, letNewPhonesIn = false, holdsCard = true, onWaiting = onWaiting)
            if (r.ok) open(held.moveId)
            return r
        }
        val own = synchronized(lock) { currentFolder?.let { f -> _cards.value.firstOrNull { it.folder == f } } }
        val bring = bringThings && own != null && bringable(storeIn(own.folder)) != null
        val folder = newFolder()
        val staging = "$folder.new"
        if (bring) copyMove(own!!.folder, staging, folder)
        val dir = File(movesDir, staging)
        val s = Store(
            dir = dir.apply { mkdirs() }, clock = clock, phone = phone, codesDir = File(codesRoot, folder).apply { mkdirs() },
            lettersElsewhere = { lettersOutside(folder) }, photos = File(photosDir, folder), listed = true,
            labelsElsewhere = { labelsOutside(folder) },
        )
        val r = WifiSync.join(s, check.invite, connectTimeoutMs, letNewPhonesIn = false, holdsCard = false, onWaiting = onWaiting)
        val joined = r.joinedAs != null
        s.close()
        if (!joined) {
            File(movesDir, staging).deleteRecursively()
            File(photosDir, folder).deleteRecursively()
            File(codesRoot, folder).deleteRecursively()
            return r
        }
        File(movesDir, staging).renameTo(File(movesDir, folder))
        val store = storeIn(folder)
        addCard(folder, store)
        if (bring) archive(own!!.moveId)
        refresh()
        return r
    }

    /** Copy a move's data and photos into a new folder: the move itself stays as it was. */
    private fun copyMove(from: String, toDir: String, toFolder: String) {
        storeIn(from).flush()
        val src = File(movesDir, from)
        val dst = File(movesDir, toDir).apply { mkdirs() }
        for (name in listOf("data.json", "device.json")) File(src, name).takeIf { it.isFile }?.copyTo(File(dst, name), overwrite = true)
        val pSrc = File(photosDir, from)
        val pDst = File(photosDir, toFolder).apply { mkdirs() }
        pSrc.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(pDst, it.name), overwrite = true) }
    }

    // ---- restoring --------------------------------------------------------------------

    /**
     * Restore a backup file: it goes into the card of its move when the phone holds one (by its
     * move ID, or for a backup from before 2.1 by 2.0's same-move check), or into a new card, where
     * the phone becomes the person whose phone made the backup (see [Store.restore]). [read] opens
     * the file; it is read twice. Refused in words, changing nothing, as [Backup.import] is.
     */
    fun restore(read: () -> InputStream, replace: Boolean = false): Outcome<Backup.Result> {
        val about = try {
            read().use { Backup.peek(it) }
        } catch (e: IllegalArgumentException) {
            return Outcome.Refused(e.message ?: Backup.NOT_A_BACKUP)
        } catch (_: Exception) {
            return Outcome.Refused(Backup.NOT_A_BACKUP)
        }
        val held = if (about.moveId.isNotEmpty()) card(about.moveId) ?: about.aliases.firstNotNullOfOrNull { card(it) }
        else _cards.value.firstOrNull { c -> storeIn(c.folder).let { s -> s.everShared() && s.isSameMove(about.data, null) } }
        if (held != null) {
            val s = storeIn(held.folder)
            return try {
                Outcome.Ok(read().use { Backup.import(s, it, replace) }).also { open(held.moveId); refresh() }
            } catch (e: IllegalArgumentException) {
                Outcome.Refused(e.message ?: Backup.NOT_A_BACKUP)
            }
        }
        val folder = newFolder()
        val s = storeIn(folder)
        return try {
            val result = read().use { Backup.import(s, it, replace = true, newCard = true) }
            s.flush()
            addCard(folder, s)
            refresh()
            Outcome.Ok(result)
        } catch (e: Exception) {
            synchronized(lock) { open.remove(folder)?.close() }
            removeFolder(folder)
            Outcome.Refused(if (e is IllegalArgumentException) e.message ?: Backup.NOT_A_BACKUP else Backup.NOT_A_BACKUP)
        }
    }

    // ---- working apart and safety nets --------------------------------------------------
    // Opening an update file into the move it is for is Moves.openUpdate, in Update.kt: Android and the JVM only.

    /** Where the daily small backup goes ([DailyBackup]): the folder this phone's person picked once, or null while there is none. The app sets this. */
    @Volatile
    var backupPlace: () -> BackupPlace? = { null }

    private val _farewells = MutableStateFlow<List<String>>(emptyList())

    /** What to tell the person about moves this phone let go of by itself ([tidy]), until the screen has said it ([farewellsSaid]). */
    val farewells: StateFlow<List<String>> = _farewells

    fun farewellsSaid() {
        _farewells.value = emptyList()
    }

    /**
     * Let go of the copies of moves this phone no longer needs: a Helper's, once an Organiser's or a Decider's phone
     * has all their work, when they have been thanked or a few days after the move was marked done
     * ([Store.whyLetGo]). Only moves already open are looked at. Returns what to tell the person, also kept in
     * [farewells].
     */
    fun tidy(today: Long = localToday()): List<String> = synchronized(checking) {
        val gone = ArrayList<String>()
        for ((folder, s) in synchronized(lock) { open.toMap() }) {
            val why = s.whyLetGo(today) ?: continue
            val c = _cards.value.firstOrNull { it.folder == folder } ?: continue
            val name = s.data.value.move.name.ifBlank { "The move" }
            drop(c.moveId)
            gone += when (why) {
                LetGo.THANKED -> "$name is done on this phone: an Organiser has all your work, so it was taken off this phone. Thank you for helping."
                LetGo.EXPIRED -> "$name was marked done, and an Organiser or Decider has all your work, so it was taken off this phone."
            }
        }
        if (gone.isNotEmpty()) _farewells.value = _farewells.value + gone
        gone
    }

    /** Taken by [tidy] and [dailyBackups]: the app's start, a sync and going to the background can come at once. */
    private val checking = Lock()

    /**
     * Write today's small backup of each open move that needs one ([DailyBackup.due]: a move with a single
     * Organiser, on that Organiser's phone, not yet today) to [backupPlace]. Returns how many were written.
     */
    fun dailyBackups(today: Long = localToday()): Int {
        val place = try {
            backupPlace()
        } catch (_: Exception) {
            null
        } ?: return 0
        var n = 0
        synchronized(checking) {
            for (s in synchronized(lock) { open.values.toList() }) if (DailyBackup.due(s, today) && DailyBackup.run(s, place, today)) n++
        }
        return n
    }

    /** When the app opens and when it goes to the background: the daily backup, and letting go of what is no longer needed. */
    fun checkIn(today: Long = localToday()) {
        try {
            dailyBackups(today)
        } catch (_: Exception) {
        }
        tidy(today)
        refresh()
    }

    /** After a sync of [s] has finished, the same as [checkIn]. */
    private fun synced(@Suppress("UNUSED_PARAMETER") s: Store) = checkIn()

    // ---- the move from 2.0 ------------------------------------------------------------

    /**
     * Move 2.0's one move, kept in [filesDir] itself, into a folder of its own, as the first card.
     * A note says which folder; the data files are copied there whole, the photo files moved one by
     * one, the card written, and only then the old files removed. Started again after a crash at any
     * step, it carries on where it was, and nothing is lost.
     */
    private fun upgradeFrom20() {
        val note = File(filesDir, "upgrade-2.1.json")
        val old = listOf("data.json", "data.json.bak", "device.json").map { File(filesDir, it) }
        val oldPhotos = photosDir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (!note.isFile && old.none { it.isFile }) return
        val folder = try {
            if (note.isFile) note.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) {
            null
        } ?: newFolder().also { writeWhole(note, it) }
        step(1)
        val target = File(movesDir, folder).apply { mkdirs() }
        val reg = readRegistry()
        val listed = reg.cards.any { it.folder == folder }
        if (!listed) {
            // 1. The data files, each copied whole. The originals stay until the end.
            for (f in old) if (f.isFile) {
                writeCopy(f, File(target, f.name))
                step(2)
            }
            // 2. The photos, moved one at a time: each is either in the old place or the new one.
            val newPhotos = File(photosDir, folder).apply { mkdirs() }
            for (p in oldPhotos) {
                val to = File(newPhotos, p.name)
                if (to.exists()) p.delete()
                else if (!p.renameTo(to)) {
                    writeCopy(p, to)
                    p.delete()
                }
                step(3)
            }
            // 3. Which move it is, and its card.
            val data = try {
                AppJson.decodeFromString<AppData>(File(target, "data.json").readText())
            } catch (_: Exception) {
                AppData()
            }
            if (!File(target, "move.json").isFile) writeWhole(File(target, "move.json"), AppJson.encodeToString(MoveIdentity(id = data.derivedMoveId(), derived = true)))
            step(4)
            val id = readIdentity(target) ?: MoveIdentity(id = data.derivedMoveId(), derived = true)
            val card = MoveCard(moveId = id.id, folder = folder, name = data.move.name, lastUsed = clock())
            writeWhole(registryFile, AppJson.encodeToString(Registry(reg.cards + card, folder)))
            step(5)
        }
        // 4. Only now are the old files removed, and then the note.
        for (f in old) f.delete()
        step(6)
        note.delete()
    }

    private fun writeCopy(from: File, to: File) {
        val tmp = File(to.parentFile, to.name + ".tmp")
        from.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(to)) {
            tmp.copyTo(to, overwrite = true)
            tmp.delete()
        }
    }

    private fun step(n: Int) {
        crashAt?.let { if (it(n)) throw IllegalStateException("crash at step $n") }
    }

    companion object {
        /** Tests stop the move from 2.0 at a given step, as a crash would. */
        @Volatile
        var crashAt: ((Int) -> Boolean)? = null
    }
}
