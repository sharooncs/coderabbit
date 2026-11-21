package app.housemove.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
}

fun newId(): String = UUID.randomUUID().toString()

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
)

/**
 * Holds the whole dataset in memory and persists it as one JSON file.
 * The dataset for a house move is small (hundreds of records), so this is
 * simpler and easier to merge and back up than a database. Writes are atomic:
 * a temp file is written, the previous file is kept as .bak, then renamed.
 */
class Store(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    val photoDir: File = File(dir, "photos").apply { mkdirs() }
    private val dataFile = File(dir, "data.json")
    private val backupFile = File(dir, "data.json.bak")
    private val deviceFile = File(dir, "device.json")

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "store-io").apply { isDaemon = true }
    }
    private val dirty = AtomicBoolean(false)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data

    private val _device = MutableStateFlow(loadDevice())
    val device: StateFlow<DeviceInfo> = _device

    private fun load(): AppData {
        for (f in listOf(dataFile, backupFile)) {
            if (!f.exists()) continue
            try {
                return AppJson.decodeFromString<AppData>(f.readText())
            } catch (_: Exception) {
                // fall through to the backup copy
            }
        }
        return AppData()
    }

    private fun loadDevice(): DeviceInfo = try {
        if (deviceFile.exists()) AppJson.decodeFromString<DeviceInfo>(deviceFile.readText())
        else DeviceInfo(letter = ('A' + (Math.random() * 26).toInt().coerceIn(0, 25)).toString())
    } catch (_: Exception) {
        DeviceInfo()
    }

    fun now(): Long = clock()

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

    fun setDevice(f: (DeviceInfo) -> DeviceInfo) {
        _device.update(f)
        val snapshot = _device.value
        io.execute {
            try {
                deviceFile.writeText(AppJson.encodeToString(snapshot))
            } catch (_: Exception) {
            }
        }
    }

    // ---- mutations ---------------------------------------------------------

    fun update(f: (AppData) -> AppData) {
        _data.update(f)
        scheduleSave()
    }

    private fun <T : Rec> List<T>.upsert(x: T): List<T> {
        val i = indexOfFirst { it.id == x.id }
        return if (i < 0) this + x else toMutableList().also { it[i] = x }
    }

    fun setMove(f: (MoveInfo) -> MoveInfo) = update { it.copy(move = f(it.move).copy(updatedAt = now())) }

    fun put(x: Room) = update { it.copy(rooms = it.rooms.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Item) = update {
        val t = now()
        it.copy(items = it.items.upsert(x.copy(updatedAt = t, createdAt = if (x.createdAt == 0L) t else x.createdAt)))
    }
    fun put(x: Box) = update {
        val t = now()
        it.copy(boxes = it.boxes.upsert(x.copy(updatedAt = t, createdAt = if (x.createdAt == 0L) t else x.createdAt)))
    }
    fun put(x: Photo) = update {
        val t = now()
        it.copy(photos = it.photos.upsert(x.copy(updatedAt = t, createdAt = if (x.createdAt == 0L) t else x.createdAt)))
    }
    fun put(x: Task) = update { it.copy(tasks = it.tasks.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Expense) = update { it.copy(expenses = it.expenses.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Supply) = update { it.copy(supplies = it.supplies.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Trip) = update { it.copy(trips = it.trips.upsert(x.copy(updatedAt = now()))) }
    fun put(x: Reading) = update { it.copy(readings = it.readings.upsert(x.copy(updatedAt = now()))) }

    fun delete(x: Room) = put(x.copy(deleted = true))
    fun delete(x: Item) = put(x.copy(deleted = true))
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
    fun updateItems(ids: Collection<String>, f: (Item) -> Item) = update { d ->
        val t = now()
        val set = ids.toSet()
        d.copy(items = d.items.map { if (it.id in set && !it.deleted) f(it).copy(updatedAt = t) else it })
    }

    fun updateBoxes(ids: Collection<String>, f: (Box) -> Box) = update { d ->
        val t = now()
        val set = ids.toSet()
        d.copy(boxes = d.boxes.map { if (it.id in set && !it.deleted) f(it).copy(updatedAt = t) else it })
    }

    // ---- creating things ---------------------------------------------------

    /** New box with this label. Its destination defaults to the room its old-home room maps to. */
    fun createBox(label: String, originRoomId: String? = _device.value.currentRoomId): Box {
        val box = Box(
            id = newId(), label = label.trim(), originRoomId = originRoomId,
            destRoomId = _data.value.room(originRoomId)?.mapsTo, packedBy = _device.value.name,
        )
        put(box)
        return box
    }

    /** New catalogued item in an old-home room, destination defaulted the same way. */
    fun createItem(name: String = "", originRoomId: String? = _device.value.currentRoomId): Item {
        val item = Item(id = newId(), name = name, originRoomId = originRoomId, destRoomId = _data.value.room(originRoomId)?.mapsTo)
        put(item)
        return item
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

    // ---- photos ------------------------------------------------------------

    /** A fresh file in the photo directory for the camera to write into. */
    fun newPhotoFile(): File = File(photoDir, newId() + ".jpg")

    /** Register a photo file already saved in [photoDir]. */
    fun addPhoto(ownerId: String, kind: PhotoKind, file: File, note: String = ""): Photo {
        val p = Photo(id = newId(), ownerId = ownerId, kind = kind, file = file.name, note = note)
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

    // ---- sync / restore ----------------------------------------------------

    /** Merge another phone's (or a backup's) dataset into this one. Returns the merged result. */
    fun mergeIn(remote: AppData): AppData {
        update { merge(it, remote) }
        // Remove files for photos that were deleted on the other phone.
        val dead = _data.value.photos.filter { it.deleted }.map { it.file }
        dead.forEach { File(photoDir, it).delete() }
        return _data.value
    }

    /** Replace everything with [other] (used for "restore and overwrite"). */
    fun replaceAll(other: AppData) = update { other }
}
