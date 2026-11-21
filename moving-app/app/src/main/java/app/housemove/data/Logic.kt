package app.housemove.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---- dates -----------------------------------------------------------------

fun today(): Long = LocalDate.now().toEpochDay()

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val SHORT_FMT = DateTimeFormatter.ofPattern("d MMM")

fun fmtDay(day: Long?): String = if (day == null) "No date" else LocalDate.ofEpochDay(day).format(DAY_FMT)
fun fmtDayShort(day: Long?): String = if (day == null) "–" else LocalDate.ofEpochDay(day).format(SHORT_FMT)

/** "in 12 days", "today", "3 days ago". */
fun relDay(day: Long?, now: Long = today()): String {
    if (day == null) return ""
    val d = day - now
    return when {
        d == 0L -> "today"
        d == 1L -> "tomorrow"
        d == -1L -> "yesterday"
        d > 0 -> "in $d days"
        else -> "${-d} days ago"
    }
}

fun fmtNum(v: Double?): String = when {
    v == null -> ""
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> ((v * 100).roundToInt() / 100.0).toString()
}

// ---- room colours ----------------------------------------------------------

/** Colour per destination room. Names are shown so they can be matched to coloured tape. */
data class RoomColor(val name: String, val argb: Long, val darkText: Boolean = false)

val ROOM_COLORS = listOf(
    RoomColor("Red", 0xFFD32F2F),
    RoomColor("Blue", 0xFF1976D2),
    RoomColor("Green", 0xFF2E7D32),
    RoomColor("Yellow", 0xFFFBC02D, darkText = true),
    RoomColor("Orange", 0xFFEF6C00),
    RoomColor("Purple", 0xFF7B1FA2),
    RoomColor("Pink", 0xFFE91E63),
    RoomColor("Teal", 0xFF00897B),
    RoomColor("Brown", 0xFF6D4C41),
    RoomColor("Grey", 0xFF616161),
    RoomColor("Lime", 0xFFC0CA33, darkText = true),
    RoomColor("Navy", 0xFF283593),
)

fun roomColor(index: Int): RoomColor = ROOM_COLORS[((index % ROOM_COLORS.size) + ROOM_COLORS.size) % ROOM_COLORS.size]

/** "Kitchen" -> "KIT", "Bedroom 2" -> "BED2". */
fun suggestRoomCode(name: String): String {
    val letters = name.filter { it.isLetter() }.uppercase().take(3)
    val digits = name.filter { it.isDigit() }.take(2)
    return (letters + digits).ifBlank { "RM" }
}

// ---- what is being moved ---------------------------------------------------

/** Something that physically travels: a box, or an item marked Move. */
data class Movable(
    val id: String,
    val isBox: Boolean,
    val title: String,
    val label: String?,
    val originRoomId: String?,
    val destRoomId: String?,
    val stage: Stage,
    val tripId: String?,
    val fragile: Boolean,
    val heavy: Boolean,
    val damaged: Boolean,
    val priority: Priority,
    val travelsWithMe: Boolean,
)

fun Box.title(): String = "Box $label" + if (contents.isBlank()) "" else " · " + contents.lineSequence().first().take(40)
fun Item.title(): String = name.ifBlank { "Unnamed item" }

fun Box.toMovable() = Movable(id, true, title(), label, originRoomId, destRoomId, stage, tripId, fragile, heavy, damaged, priority, travelsWithMe)
fun Item.toMovable() = Movable(
    id, false, title(), label, originRoomId, destRoomId, stage, tripId, fragile, twoPerson, damaged,
    if (firstNight) Priority.FIRST_NIGHT else Priority.WHENEVER, travelsWithMe,
)

fun AppData.movingItems(): List<Item> = liveItems.filter { it.disposition == Disposition.MOVE }
fun AppData.movables(): List<Movable> = liveBoxes.map { it.toMovable() } + movingItems().map { it.toMovable() }

/** Find the box or item carrying this label code. */
fun AppData.findByLabel(code: String): Movable? {
    val c = code.trim()
    liveBoxes.firstOrNull { it.label.equals(c, ignoreCase = true) }?.let { return it.toMovable() }
    liveItems.firstOrNull { it.label?.equals(c, ignoreCase = true) == true }?.let { return it.toMovable() }
    return null
}

/** Label codes used by more than one live box / item. */
fun AppData.duplicateLabels(): List<String> =
    (liveBoxes.map { it.label } + liveItems.mapNotNull { it.label })
        .filter { it.isNotBlank() }
        .groupingBy { it.uppercase() }.eachCount().filter { it.value > 1 }.keys.sorted()

const val QR_PREFIX = "HM:"
const val SYNC_PREFIX = "HMSYNC:"

/** Turn scanned QR text into a label code. Unknown QR text is used as-is (trimmed). */
fun labelFromQr(text: String): String = text.trim().removePrefix(QR_PREFIX).trim().take(24)

// ---- fit check -------------------------------------------------------------

enum class Fit(val label: String) { UNKNOWN("Not checked"), OK("Fits"), TIGHT("Tight fit"), NO("Won't fit assembled") }

const val DOOR_HEIGHT_CM = 198.0

/**
 * Can the item pass through the narrowest opening on the way to its room?
 * It can be turned any way round, so the smallest dimension must clear the
 * opening width and the second smallest the opening height.
 */
fun fitThrough(item: Item, doorWidthCm: Double?): Fit {
    val dims = listOfNotNull(item.widthCm, item.depthCm, item.heightCm).sorted()
    if (dims.size < 3 || doorWidthCm == null || doorWidthCm <= 0) return Fit.UNKNOWN
    val (smallest, middle) = dims
    return when {
        smallest > doorWidthCm || middle > DOOR_HEIGHT_CM -> Fit.NO
        smallest > doorWidthCm - 5 || middle > DOOR_HEIGHT_CM - 5 -> Fit.TIGHT
        else -> Fit.OK
    }
}

fun AppData.fitOf(item: Item): Fit = fitThrough(item, room(item.destRoomId)?.doorWidthCm)

// ---- volume ----------------------------------------------------------------

data class VolumeEstimate(
    val itemsM3: Double,
    val boxesM3: Double,
    val itemsWithoutSize: Int,
    val totalM3: Double,
    val trips: Int,
)

const val DEFAULT_ITEM_M3 = 0.3
const val LOAD_SLACK = 1.2

fun Item.volumeM3(): Double? {
    val w = widthCm ?: return null
    val d = depthCm ?: return null
    val h = heightCm ?: return null
    return w * d * h / 1_000_000.0
}

fun AppData.volume(): VolumeEstimate {
    val items = movingItems()
    val unsized = items.count { it.volumeM3() == null }
    val itemsM3 = items.sumOf { it.volumeM3() ?: DEFAULT_ITEM_M3 }
    val boxesM3 = liveBoxes.sumOf { it.size.m3 }
    val total = (itemsM3 + boxesM3) * LOAD_SLACK
    val cap = move.vanCapacityM3.coerceAtLeast(1.0)
    return VolumeEstimate(itemsM3, boxesM3, unsized, total, ceil(total / cap).toInt().coerceAtLeast(if (total > 0) 1 else 0))
}

// ---- tasks -----------------------------------------------------------------

fun AppData.dueDay(t: Task): Long? = t.dueDay ?: move.moveDay?.let { m -> t.offsetDays?.let { m + it } }

// ---- progress --------------------------------------------------------------

data class Ratio(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
    override fun toString() = "$done / $total"
}

data class Progress(
    val triage: Ratio,
    val destinations: Ratio,
    val roomsPacked: Ratio,
    val sealed: Ratio,
    val loaded: Ratio,
    val delivered: Ratio,
    val unpacked: Ratio,
    val tasks: Ratio,
)

fun AppData.progress(): Progress {
    val items = liveItems
    val moving = movingItems()
    val boxes = liveBoxes
    val mv = movables()
    val origin = roomsOf(Home.ORIGIN)
    val tasks = liveTasks
    return Progress(
        triage = Ratio(items.count { it.disposition != Disposition.UNDECIDED }, items.size),
        destinations = Ratio(moving.count { room(it.destRoomId) != null }, moving.size),
        roomsPacked = Ratio(origin.count { it.packed }, origin.size),
        sealed = Ratio(boxes.count { it.sealed }, boxes.size),
        loaded = Ratio(mv.count { it.stage != Stage.AT_ORIGIN }, mv.size),
        delivered = Ratio(mv.count { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }, mv.size),
        unpacked = Ratio(mv.count { it.stage == Stage.DONE }, mv.size),
        tasks = Ratio(tasks.count { it.done }, tasks.size),
    )
}

/** Things that need attention, most urgent first. Each has a route to the screen that fixes it. */
data class Issue(val text: String, val route: String, val severe: Boolean = false)

fun AppData.issues(now: Long = today()): List<Issue> {
    val out = ArrayList<Issue>()
    val mv = movables()
    val anyDelivered = mv.any { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }
    val onVan = mv.count { it.stage == Stage.LOADED }
    if (anyDelivered && onVan > 0) out += Issue("$onVan loaded but not yet delivered", "reconcile", severe = true)
    val dup = duplicateLabels()
    if (dup.isNotEmpty()) out += Issue("Label used twice: ${dup.take(4).joinToString()}", "boxes", severe = true)
    val overdue = liveTasks.count { !it.done && (dueDay(it) ?: Long.MAX_VALUE) < now }
    if (overdue > 0) out += Issue("$overdue overdue task${if (overdue == 1) "" else "s"}", "tasks", severe = true)
    val damaged = mv.count { it.damaged }
    if (damaged > 0) out += Issue("$damaged damaged in the move", "reports")
    val undecided = liveItems.count { it.disposition == Disposition.UNDECIDED }
    if (undecided > 0) out += Issue("$undecided item${if (undecided == 1) "" else "s"} not triaged", "triage")
    val moving = movingItems()
    val noDest = moving.count { room(it.destRoomId) == null }
    if (noDest > 0) out += Issue("$noDest item${if (noDest == 1) "" else "s"} with no destination room", "plan")
    val wontFit = moving.count { fitOf(it) == Fit.NO && !it.needsDisassembly }
    if (wontFit > 0) out += Issue("$wontFit item${if (wontFit == 1) "" else "s"} won't fit through the door assembled", "plan", severe = true)
    val toGo = liveItems.count { it.disposition in listOf(Disposition.SELL, Disposition.DONATE, Disposition.BIN) && !it.gone }
    if (toGo > 0) out += Issue("$toGo item${if (toGo == 1) "" else "s"} still to sell, donate or bin", "disposal")
    val boxNoDest = liveBoxes.count { room(it.destRoomId) == null }
    if (boxNoDest > 0) out += Issue("$boxNoDest box${if (boxNoDest == 1) "" else "es"} with no destination room", "boxes")
    val unsealed = liveBoxes.count { !it.sealed && it.stage == Stage.AT_ORIGIN }
    if (unsealed > 0) out += Issue("$unsealed box${if (unsealed == 1) "" else "es"} still open", "boxes")
    val blank = liveBoxes.count { b -> b.contents.isBlank() && photosOf(b.id).isEmpty() }
    if (blank > 0) out += Issue("$blank box${if (blank == 1) "" else "es"} with no contents recorded", "boxes")
    return out
}

// ---- search ----------------------------------------------------------------

data class Hit(val movable: Movable, val detail: String)

/** Search boxes and items by name, contents, label, notes and room name. */
fun AppData.search(query: String): List<Hit> {
    val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    fun matches(text: String) = words.all { text.contains(it) }
    val out = ArrayList<Hit>()
    for (b in liveBoxes) {
        val text = listOf(b.label, b.contents, b.notes, room(b.destRoomId)?.name, room(b.originRoomId)?.name)
            .joinToString(" ").lowercase()
        if (matches(text)) out += Hit(b.toMovable(), b.contents.ifBlank { b.notes })
    }
    for (i in liveItems) {
        val text = listOf(i.name, i.label, i.notes, room(i.destRoomId)?.name, room(i.originRoomId)?.name)
            .joinToString(" ").lowercase()
        if (matches(text)) out += Hit(i.toMovable(), i.notes)
    }
    return out
}

/** Where is it right now, in words. */
fun AppData.whereIs(m: Movable): String = when (m.stage) {
    Stage.AT_ORIGIN -> "${move.originName}: ${room(m.originRoomId)?.name ?: "no room"}"
    Stage.LOADED -> "On the van" + (trip(m.tripId)?.let { " (${it.name})" } ?: "")
    Stage.DELIVERED, Stage.DONE -> "${move.destName}: ${room(m.destRoomId)?.name ?: "no room"}"
}

// ---- supplies --------------------------------------------------------------

/** Rough number of boxes a room produces, from its name. */
fun boxesForRoom(name: String): Int {
    val n = name.lowercase()
    return when {
        "kitchen" in n -> 20
        "garage" in n || "loft" in n || "attic" in n || "shed" in n || "stor" in n || "basement" in n || "cellar" in n -> 15
        "living" in n || "lounge" in n || "sitting" in n || "family" in n -> 12
        "master" in n || "main bed" in n -> 12
        "bed" in n -> 10
        "office" in n || "study" in n -> 10
        "dining" in n -> 6
        "bath" in n || "toilet" in n || "wc" in n || "ensuite" in n -> 4
        "hall" in n || "landing" in n || "porch" in n -> 3
        "utility" in n || "laundry" in n -> 5
        else -> 8
    }
}

/** A starting shopping list for a self-move, sized from the rooms and the catalogue. */
fun AppData.suggestSupplies(): List<Pair<String, Int>> {
    val boxes = roomsOf(Home.ORIGIN).sumOf { boxesForRoom(it.name) }.coerceAtLeast(10)
    val big = movingItems().size
    val beds = movingItems().count { val n = it.name.lowercase(); "bed" in n || "mattress" in n }
    return listOf(
        "Small boxes (books, heavy things)" to ceil(boxes * 0.4).toInt(),
        "Medium boxes" to ceil(boxes * 0.4).toInt(),
        "Large boxes (light, bulky things)" to ceil(boxes * 0.2).toInt(),
        "Packing tape rolls" to ceil(boxes / 15.0).toInt().coerceAtLeast(2),
        "Tape dispenser" to 2,
        "Packing paper / bubble wrap packs" to ceil(boxes / 20.0).toInt().coerceAtLeast(1),
        "Marker pens" to 3,
        "Furniture blankets" to ceil(big / 2.0).toInt().coerceAtLeast(4),
        "Mattress bags" to beds.coerceAtLeast(1),
        "Stretch wrap rolls" to 2,
        "Zip bags for screws and fittings" to 20,
        "Ratchet straps" to 4,
        "Hand truck / dolly" to 1,
        "Work gloves (pairs)" to 2,
        "Toolkit (screwdrivers, hex keys, spanner)" to 1,
        "Bin bags (soft items, rubbish)" to 30,
    )
}
