package app.housemove.data

import kotlin.math.ceil
import kotlin.math.roundToInt

// ---- dates -----------------------------------------------------------------

fun today(): Long = localToday()

fun fmtDay(day: Long?): String = if (day == null) "No date" else dayText(day)
fun fmtDayShort(day: Long?): String = if (day == null) "–" else dayShortText(day)

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
    else -> ((v * 100).roundToInt() / 100.0).javaText()
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

/** A short code for a room called [name] that none of [others] uses: "BED", then "BED2", "BED3"… */
fun freeRoomCode(name: String, others: List<Room>): String {
    val base = suggestRoomCode(name)
    var code = base
    var n = 2
    while (others.any { it.code == code }) code = base + n++
    return code
}

/**
 * Whether renaming [room] would give it a new short code: its code is still the one made from its name (not one
 * typed in), and nothing is marked with it yet, no box from or bound for the room and no item with a label.
 */
fun AppData.roomCodeFollowsName(room: Room): Boolean {
    val base = suggestRoomCode(room.name)
    val made = room.code.isBlank() || (room.code.startsWith(base) && room.code.drop(base.length).all { it.isDigit() })
    if (!made) return false
    fun here(origin: String?, dest: String?) = origin == room.id || dest == room.id
    return liveBoxes.none { here(it.originRoomId, it.destRoomId) } &&
        liveItems.none { !it.label.isNullOrBlank() && here(it.originRoomId, it.destRoomId) }
}

/** Why [room] cannot be called [name], or null when it can: another room of the same home has that name. */
fun AppData.roomNameTaken(room: Room, name: String): String? {
    val n = name.trim()
    if (roomsOf(room.home).none { it.id != room.id && it.name.trim().equals(n, ignoreCase = true) }) return null
    val home = if (room.home == Home.ORIGIN) move.originName else move.destName
    return "There is already a room called $n in the ${home.lowercase()}."
}

/** Other names people often give a room like [name], offered as one-tap choices when renaming it. */
fun roomRenames(name: String): List<String> {
    val n = name.lowercase()
    return when {
        "bed" in n -> listOf("Guest room", "Kids' room", "Nursery", "Spare room")
        "bath" in n -> listOf("En suite", "Shower room", "Cloakroom", "WC")
        "living" in n || "lounge" in n -> listOf("Lounge", "Sitting room", "Family room", "Snug")
        "office" in n || "study" in n -> listOf("Study", "Den", "Library", "Playroom")
        "kitchen" in n -> listOf("Kitchen-diner", "Pantry")
        "dining" in n -> listOf("Breakfast room", "Conservatory")
        "hall" in n -> listOf("Landing", "Porch", "Entrance")
        "utility" in n -> listOf("Laundry", "Boot room", "Pantry")
        "loft" in n || "attic" in n -> listOf("Attic", "Eaves")
        "garage" in n -> listOf("Workshop", "Carport")
        "garden" in n || "shed" in n -> listOf("Shed", "Greenhouse", "Summer house")
        else -> listOf("Store room", "Playroom", "Study")
    }
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
    /** Must travel standing up. */
    val upright: Boolean = false,
    /** For a container, what kind it is; null for an item. */
    val kind: ContainerKind? = null,
)

/** "Box L001 · plates", "Suitcase B2", or the container's own name with its code: "Kitchen box 4 (L004)". */
fun Box.title(): String =
    (if (name.isBlank()) "${kind.noun} $label" else "$name ($label)") + if (contents.isBlank()) "" else " · " + contents.lineSequence().first().take(40)
fun Item.title(): String = name.ifBlank { "Unnamed item" }

fun Box.toMovable() = Movable(id, true, title(), label, originRoomId, destRoomId, stage, tripId, fragile, heavy, damaged, priority, travelsWithMe, thisWayUp, kind)
fun Item.toMovable() = Movable(
    id, false, title(), label, originRoomId, destRoomId, stage, tripId, fragile, twoPerson, damaged,
    if (firstNight) Priority.FIRST_NIGHT else Priority.WHENEVER, travelsWithMe, keepUpright,
)

/**
 * Carried by hand, not on the van: it travels with the person ("With me" when loading) and was not
 * put on a trip. Such a thing is never "still on the van", however long it stays at [Stage.LOADED];
 * it is marked delivered at the new home like anything else. Something packed inside a container
 * goes the way that container goes: ask about [AppData.carrierOf] it.
 */
fun Movable.carriedByHand(): Boolean = travelsWithMe && tripId == null
fun Box.carriedByHand(): Boolean = travelsWithMe && tripId == null
fun Item.carriedByHand(): Boolean = travelsWithMe && tripId == null

/** Taken from the old home and not yet at the new one, in the van rather than by hand. */
fun Movable.onTheVan(): Boolean = stage == Stage.LOADED && !carriedByHand()

/** Where in the journey something is, in a word or two: [Stage.label], except "Carried by hand" for what is on its way with someone. */
fun AppData.statusOf(m: Movable): String {
    val c = carrierOf(m)
    return if (c.stage == Stage.LOADED && c.carriedByHand()) "Carried by hand" else m.stage.label
}

/**
 * Every item marked Move, including those packed in a container. See [looseItems] for the ones that travel on their own.
 * Once the move uses the storage unit ([usesStorage]), items marked Store travel too: to the storage unit.
 */
fun AppData.movingItems(): List<Item> {
    val storing = usesStorage()
    return liveItems.filter { it.disposition == Disposition.MOVE || (storing && it.disposition == Disposition.STORE) }
}

/**
 * What has to be loaded, unloaded and accounted for: every container that is
 * not inside another, and every moving item that is not in a container.
 * Things inside a container go wherever it goes, so they are not listed twice.
 */
fun AppData.movables(): List<Movable> = topBoxes().map { it.toMovable() } + looseItems().map { it.toMovable() }

/** Find the box or item carrying this label code. */
fun AppData.findByLabel(code: String): Movable? {
    val c = code.trim()
    liveBoxes.firstOrNull { it.label.equals(c, ignoreCase = true) }?.let { return it.toMovable() }
    liveItems.firstOrNull { it.label?.equals(c, ignoreCase = true) == true }?.let { return it.toMovable() }
    return null
}

/**
 * Find what a scanned or typed code belongs to: a label first, then an item's
 * serial number, then something scanned into a box by its own barcode.
 * The thing itself is returned even when it is inside a container; pass it to
 * [carrierOf] to get what is actually loaded and unloaded.
 */
fun AppData.findByAnyCode(code: String): Movable? {
    val c = code.trim()
    if (c.isEmpty()) return null
    findByLabel(c)?.let { return it }
    // Serial numbers are on the hidden records, which a Helper's phone does not hold.
    hidden.firstOrNull { !it.deleted && it.serial.isNotBlank() && it.serial.equals(c, ignoreCase = true) }?.let { h -> item(h.id)?.let { return it.toMovable() } }
    liveEntries.firstOrNull { it.code == c }?.let { e -> box(e.boxId)?.let { return it.toMovable() } }
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
    /** Cartons packed so far: the only containers that count as boxes to buy. */
    val cartons: Int = 0,
    /** Suitcases, bags, crates and other containers packed so far. */
    val otherContainers: Int = 0,
)

const val DEFAULT_ITEM_M3 = 0.3
const val LOAD_SLACK = 1.2

fun Item.volumeM3(): Double? {
    val w = widthCm ?: return null
    val d = depthCm ?: return null
    val h = heightCm ?: return null
    return w * d * h / 1_000_000.0
}

/** Things inside a container take no space of their own, so only [looseItems] and [topBoxes] are counted. */
fun AppData.volume(): VolumeEstimate {
    val items = looseItems()
    val unsized = items.count { it.volumeM3() == null }
    val itemsM3 = items.sumOf { it.volumeM3() ?: DEFAULT_ITEM_M3 }
    val boxesM3 = topBoxes().sumOf { it.volumeM3() }
    val total = (itemsM3 + boxesM3) * LOAD_SLACK
    val cap = move.vanCapacityM3.coerceAtLeast(1.0)
    val cartons = liveBoxes.count { it.kind == ContainerKind.CARTON }
    return VolumeEstimate(
        itemsM3, boxesM3, unsized, total, ceil(total / cap).toInt().coerceAtLeast(if (total > 0) 1 else 0),
        cartons = cartons, otherContainers = liveBoxes.size - cartons,
    )
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
    /** Delivered or unpacked at the new home, out of everything not in storage. */
    val delivered: Ratio,
    /** Unpacked, out of everything not in storage. */
    val unpacked: Ratio,
    val tasks: Ratio,
    /** At the storage unit, out of everything: in storage, not delivered. */
    val stored: Ratio = Ratio(0, 0),
)

fun AppData.progress(): Progress {
    val items = liveItems
    // Something inside a container goes where the container goes and is sealed, loaded and unpacked with it.
    val moving = looseItems()
    val boxes = topBoxes()
    val mv = boxes.map { it.toMovable() } + moving.map { it.toMovable() }
    val origin = roomsOf(Home.ORIGIN)
    val tasks = liveTasks
    // Things at the storage unit are in storage, not delivered: they are left out of what is to be delivered and unpacked.
    val stored = mv.count { it.stage == Stage.STORED }
    return Progress(
        triage = Ratio(items.count { it.disposition != Disposition.UNDECIDED }, items.size),
        destinations = Ratio(moving.count { room(it.destRoomId) != null }, moving.size),
        roomsPacked = Ratio(origin.count { it.packed }, origin.size),
        sealed = Ratio(boxes.count { it.sealed }, boxes.size),
        loaded = Ratio(mv.count { it.stage != Stage.AT_ORIGIN }, mv.size),
        delivered = Ratio(mv.count { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }, mv.size - stored),
        unpacked = Ratio(mv.count { it.stage == Stage.DONE }, mv.size - stored),
        tasks = Ratio(tasks.count { it.done }, tasks.size),
        stored = Ratio(stored, mv.size),
    )
}

/** Things that need attention, most urgent first. Each has a route to the screen that fixes it. */
data class Issue(val text: String, val route: String, val severe: Boolean = false)

/** [role] is the phone's: a Helper's phone does not hold serial numbers, so it is not told none are recorded. */
fun AppData.issues(now: Long = today(), role: Role? = null): List<Issue> {
    val out = ArrayList<Issue>()
    val mv = movables()
    val anyDelivered = mv.any { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }
    // What someone carries by hand is with them, not left on the van.
    val onVan = mv.count { it.onTheVan() }
    if (uses(Part.MOVE_DAY) && anyDelivered && onVan > 0) out += Issue("$onVan loaded but not yet delivered", "reconcile", severe = true)
    val dup = duplicateLabels()
    if (dup.isNotEmpty()) out += Issue("Label used twice: ${dup.take(4).joinToString()}", "boxes", severe = true)
    val overdue = liveTasks.count { !it.done && (dueDay(it) ?: Long.MAX_VALUE) < now }
    if (uses(Part.TIMELINE) && overdue > 0) out += Issue("$overdue overdue task${if (overdue == 1) "" else "s"}", "tasks", severe = true)
    val damaged = mv.count { it.damaged }
    if (damaged > 0) out += Issue("$damaged damaged in the move", "reports")
    val undecided = liveItems.count { it.disposition == Disposition.UNDECIDED }
    if (uses(Part.TRIAGE) && undecided > 0) out += Issue("$undecided item${if (undecided == 1) "" else "s"} not triaged", "triage")
    // Items in a container, and containers inside another, go where it goes: they need no room, fit check or seal of their own.
    val moving = looseItems()
    val top = topBoxes()
    val noDest = moving.count { room(it.destRoomId) == null }
    if (uses(Part.PLAN) && noDest > 0) out += Issue("$noDest item${if (noDest == 1) "" else "s"} with no destination room", "plan")
    val wontFit = moving.count { fitOf(it) == Fit.NO && !it.needsDisassembly }
    if (uses(Part.PLAN) && wontFit > 0) out += Issue("$wontFit item${if (wontFit == 1) "" else "s"} won't fit through the door assembled", "plan", severe = true)
    // Something handed over to the buyer or the charity has gone, as far as clearing the house goes.
    val toGo = liveItems.count { it.disposition in listOf(Disposition.SELL, Disposition.DONATE, Disposition.BIN) && !it.gone && !it.handedOver }
    if (uses(Part.TRIAGE) && toGo > 0) out += Issue("$toGo item${if (toGo == 1) "" else "s"} still to sell, donate or bin", "disposal")
    val boxNoDest = top.count { room(it.destRoomId) == null }
    if (uses(Part.BOXES) && uses(Part.PLAN) && boxNoDest > 0) out += Issue("$boxNoDest box${if (boxNoDest == 1) "" else "es"} with no destination room", "boxes")
    val unsealed = top.count { !it.sealed && it.stage == Stage.AT_ORIGIN }
    if (uses(Part.BOXES) && unsealed > 0) out += Issue("$unsealed box${if (unsealed == 1) "" else "es"} still open", "boxes")
    // Serial numbers and their photos are not sent to Helpers, so only a phone that holds them can tell.
    if (role != Role.HELPER) {
        val noSerial = movingItems().count { it.isElectronic() && serialOf(it.id).isBlank() && photosOf(it.id, PhotoKind.SERIAL).isEmpty() }
        if (uses(Part.DEVICES) && noSerial > 0) out += Issue("$noSerial device${if (noSerial == 1) "" else "s"} with no serial number recorded", "electronics")
    }
    // A container counts as described when anything at all is recorded in it: text, a photo, scanned things, items or other containers.
    val boxesWithEntries = liveEntries.map { it.boxId }.toSet() + liveItems.mapNotNull { it.boxId } + liveBoxes.mapNotNull { it.parentId }
    val blank = liveBoxes.count { b -> b.contents.isBlank() && photosOf(b.id).isEmpty() && b.id !in boxesWithEntries }
    if (uses(Part.BOXES) && blank > 0) out += Issue("$blank box${if (blank == 1) "" else "es"} with no contents recorded", "boxes")
    return out
}

// ---- search ----------------------------------------------------------------

data class Hit(val movable: Movable, val detail: String)

/**
 * Search boxes and items by name, contents, label, notes and room name, and items by make, model and serial
 * number where this phone holds them. For a Helper ([role]) what is marked "travels with me" is left out.
 */
fun AppData.search(query: String, role: Role? = null): List<Hit> {
    val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    fun matches(text: String) = words.all { text.contains(it) }
    val out = ArrayList<Hit>()
    val helper = role == Role.HELPER
    fun hiddenFromHelper(m: Movable) = helper && (m.travelsWithMe || carrierOf(m).travelsWithMe)
    for (b in liveBoxes) {
        if (hiddenFromHelper(b.toMovable())) continue
        val text = listOf(b.label, b.name, b.contents, b.notes, b.category, b.kind.label, room(b.destRoomId)?.name, room(b.originRoomId)?.name)
            .joinToString(" ").lowercase()
        if (!matches(text)) continue
        val inside = parentOf(b)?.let { "Inside ${it.title()}" }
        out += Hit(b.toMovable(), listOfNotNull(inside, b.contents.ifBlank { b.notes }.ifBlank { null }).joinToString(" · "))
    }
    for (i in liveItems) {
        if (hiddenFromHelper(i.toMovable())) continue
        val h = hiddenOf(i.id)
        val text = listOf(i.name, i.label, i.notes, h?.make, h?.model, h?.serial, i.accessories, room(i.destRoomId)?.name, room(i.originRoomId)?.name)
            .joinToString(" ").lowercase()
        if (!matches(text)) continue
        // An item packed in a container is still found by its own name; the detail says where it is packed.
        val inside = containerOf(i)?.let { "In ${it.title()}" }
        val detail = listOfNotNull(h?.make, h?.model, h?.serial).filter { it.isNotBlank() }.joinToString(" ").ifBlank { i.notes }
        out += Hit(i.toMovable(), listOfNotNull(inside, detail.ifBlank { null }).joinToString(" · "))
    }
    // Books and products scanned into a box: the hit is the box, with what matched as the detail.
    val seen = out.filter { it.movable.isBox }.map { it.movable.id }.toMutableSet()
    for ((boxId, list) in liveEntries.groupBy { it.boxId }) {
        if (boxId in seen) continue
        val matched = list.filter { e -> matches(listOf(e.title, e.author, e.code).joinToString(" ").lowercase()) }
        if (matched.isEmpty()) continue
        val b = box(boxId) ?: continue
        if (hiddenFromHelper(b.toMovable())) continue
        seen += boxId
        out += Hit(b.toMovable(), matched.take(3).joinToString("; ") { it.title.ifBlank { it.code } } + if (matched.size > 3) " and ${matched.size - 3} more" else "")
    }
    return out
}

/**
 * Where is it right now, in words. Something inside a container is where the
 * container is: "In Suitcase B2 · On the van (Trip 1)". Once the container has
 * been unpacked its contents are in their own rooms again.
 */
fun AppData.whereIs(m: Movable): String {
    val carrier = carrierOf(m)
    if (carrier.id != m.id && carrier.stage != Stage.DONE) return "In ${carrier.title} · ${placeText(carrier)}"
    return placeText(m)
}

private fun AppData.placeText(m: Movable): String = when (m.stage) {
    Stage.AT_ORIGIN -> "${move.originName}: ${room(m.originRoomId)?.name ?: "no room"}"
    Stage.LOADED -> if (m.carriedByHand()) "Carried by hand, on its way to ${move.destName}" else "On the ${vehicle()}" + (trip(m.tripId)?.let { " (${it.name})" } ?: "")
    // The storage unit is one place with no rooms.
    Stage.STORED -> "At the storage unit"
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

/**
 * A starting shopping list for a self-move, sized from the rooms and the catalogue.
 * Only cartons count as boxes to buy: suitcases, bags and crates are already owned.
 * If more cartons of a size have been packed than the estimate, that many are needed.
 */
fun AppData.suggestSupplies(): List<Pair<String, Int>> {
    val cartons = liveBoxes.filter { it.kind == ContainerKind.CARTON }
    fun packed(vararg sizes: BoxSize) = cartons.count { it.size in sizes }
    val boxes = maxOf(roomsOf(Home.ORIGIN).sumOf { boxesForRoom(it.name) }.coerceAtLeast(10), cartons.size)
    val big = looseItems().size
    val beds = movingItems().count { val n = it.name.lowercase(); "bed" in n || "mattress" in n }
    return listOf(
        "Small boxes (books, heavy things)" to maxOf(ceil(boxes * 0.4).toInt(), packed(BoxSize.S)),
        "Medium boxes" to maxOf(ceil(boxes * 0.4).toInt(), packed(BoxSize.M)),
        "Large boxes (light, bulky things)" to maxOf(ceil(boxes * 0.2).toInt(), packed(BoxSize.L, BoxSize.XL)),
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

// ---- barcodes already on things ---------------------------------------------

/** True for a book barcode: ISBN-13 (978/979 with a valid check digit) or ISBN-10. */
fun isIsbn(code: String): Boolean {
    val c = code.trim().replace("-", "").replace(" ", "")
    if (c.length == 13 && c.all { it.isDigit() } && (c.startsWith("978") || c.startsWith("979"))) {
        val sum = c.take(12).mapIndexed { i, ch -> (ch - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10 == c[12] - '0'
    }
    if (c.length == 10 && c.take(9).all { it.isDigit() } && (c[9].isDigit() || c[9] == 'X' || c[9] == 'x')) {
        val sum = c.take(9).mapIndexed { i, ch -> (ch - '0') * (10 - i) }.sum() + if (c[9].isDigit()) c[9] - '0' else 10
        return sum % 11 == 0
    }
    return false
}

/** A retail barcode number (EAN / UPC / ISBN) rather than a title someone typed. */
fun looksLikeBarcodeNumber(text: String): Boolean {
    val c = text.trim().replace("-", "").replace(" ", "")
    return c.length in 8..14 && c.all { it.isDigit() } || isIsbn(c)
}

// ---- electronics and appliances ----------------------------------------------

private val ELECTRONIC_WORDS = listOf(
    "tv", "television", "monitor", "projector", "computer", "pc", "imac", "macbook", "laptop", "tablet", "ipad", "printer",
    "scanner", "router", "console", "playstation", "xbox", "nintendo", "speaker", "speakers", "soundbar", "amplifier", "hi-fi",
    "hifi", "stereo", "turntable", "camera", "drone", "fridge", "fridge-freezer", "freezer", "refrigerator", "washing machine",
    "washer", "dryer", "dishwasher", "microwave", "oven", "cooker", "vacuum", "hoover", "dehumidifier", "air conditioner",
    "coffee machine", "drill", "mower", "lawnmower", "treadmill", "e-bike", "ebike", "nas",
)

private val NOT_THE_DEVICE = setOf(
    "stand", "stands", "unit", "cabinet", "table", "desk", "bench", "bracket", "brackets", "mount", "shelf", "shelves", "rack",
    "cover", "case", "bag", "trolley", "chair", "cables", "leads",
)

/** Does the name sound like something with a plug and a serial number? */
fun looksElectronic(name: String): Boolean {
    val words = name.lowercase().split(Regex("[^a-z0-9-]+")).filter { it.isNotBlank() }
    // "TV stand", "computer desk", "speaker brackets": furniture and fittings named after a device.
    if (words.any { it in NOT_THE_DEVICE }) return false
    val joined = " " + words.joinToString(" ") + " "
    return ELECTRONIC_WORDS.any { w -> if (' ' in w) w in joined else w in words }
}

fun Item.isElectronic(): Boolean = electronic ?: looksElectronic(name)

fun Item.makeModel(): String = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")

/** Moving advice specific to the kind of device, from its name. */
fun handlingTips(name: String): List<String> {
    val n = " " + name.lowercase() + " "
    fun has(vararg w: String) = w.any { it in n }
    val tips = ArrayList<String>()
    if (has(" tv", "television", "monitor", "screen")) {
        tips += "Carry and transport it upright, never flat: the panel can crack under its own weight."
        tips += "Use the original box if you kept it. Otherwise cover the screen with cardboard, then a blanket."
        tips += "Take the stand or wall bracket off and bag its screws with the remote."
    }
    if (has("fridge", "freezer", "refrigerator")) {
        tips += "Empty and defrost it 24–48 hours before, then dry it so it does not leak or smell."
        tips += "Keep it upright in the van. At the new home let it stand a few hours before switching on."
    }
    if (has("washing", "washer", "dishwasher")) {
        tips += "Drain the hoses and tape them and the door shut."
        if (has("washing", "washer")) tips += "Fit the transit bolts so the drum cannot swing."
    }
    if (has("computer", " pc ", "laptop", "imac", "mac ", "console", "playstation", "xbox", "nintendo", " nas ", "tablet")) {
        tips += "Back it up before it is unplugged."
        tips += "Pack it in its original box if you have it; otherwise pad every side."
    }
    if (has("printer")) tips += "Take out ink or toner cartridges and tape the lid and trays shut."
    if (has("turntable")) tips += "Secure the tone arm and remove the platter."
    if (has("microwave")) tips += "Take the glass plate out and wrap it separately."
    return tips
}

// ---- one app, different uses -----------------------------------------------------

/** Is this part of the app switched on for this move? */
fun AppData.uses(part: Part): Boolean = part.name !in move.off

/** The word for what carries the load: movers bring a truck, a self-move hires a van. */
fun AppData.vehicle(): String = if (move.kind == MoveKind.MOVERS) "truck" else "van"

/** Parts that make no sense for a kind of use, switched off when that kind is chosen. */
fun partsOffFor(kind: MoveKind): List<String> = when (kind) {
    MoveKind.SELF, MoveKind.MOVERS -> emptyList()
    MoveKind.STORAGE -> listOf(Part.RECORDS, Part.UNPACK).map { it.name }
    MoveKind.INVENTORY -> listOf(Part.PLAN, Part.TIMELINE, Part.BUDGET, Part.BOXES, Part.LABELS, Part.MOVE_DAY, Part.RECORDS, Part.UNPACK).map { it.name }
}

/** Names for the two places, suited to the kind of use. */
fun placeNamesFor(kind: MoveKind): Pair<String, String> = when (kind) {
    MoveKind.STORAGE -> "Home" to "Storage unit"
    MoveKind.INVENTORY -> "Home" to "New home"
    else -> "Old home" to "New home"
}
