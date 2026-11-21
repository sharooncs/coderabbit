package app.housemove.data

// ---- containers of any kind, and what is inside what ---------------------------------

/** Space a container takes on the van: a carton by its size, other kinds by their typical volume scaled by size. */
fun Box.volumeM3(): Double = if (kind == ContainerKind.CARTON) size.m3 else kind.m3 * size.m3 / BoxSize.M.m3

/**
 * The container [b] is inside, if any. Nesting is one level deep: a parent that
 * is itself inside something, or deleted, or [b] itself, does not count, so two
 * phones that nest things differently at the same time cannot make a loop.
 */
fun AppData.parentOf(b: Box): Box? {
    val p = box(b.parentId) ?: return null
    return if (p.id == b.id || box(p.parentId) != null) null else p
}

/** The container an item is packed in, if any. */
fun AppData.containerOf(i: Item): Box? = box(i.boxId)

/** The thing that is physically carried: the outermost container. */
fun AppData.carrierOf(b: Box): Box = parentOf(b) ?: b
fun AppData.carrierOf(i: Item): Box? = containerOf(i)?.let { carrierOf(it) }

/** For something inside a container, that container; otherwise the thing itself. Use it when a scan should load or unload. */
fun AppData.carrierOf(m: Movable): Movable =
    (if (m.isBox) box(m.id)?.let { carrierOf(it) } else item(m.id)?.let { carrierOf(it) })?.toMovable() ?: m

/** Containers directly inside this one. */
fun AppData.childrenOf(boxId: String): List<Box> = liveBoxes.filter { it.parentId == boxId && parentOf(it) != null }.sortedBy { it.label }

/** Catalogued items packed in this container. */
fun AppData.itemsIn(boxId: String): List<Item> = liveItems.filter { it.boxId == boxId }.sortedBy { it.title().lowercase() }

/** Containers that are not inside another: the ones to load, unload and count. */
fun AppData.topBoxes(): List<Box> {
    val boxes = liveBoxes
    if (boxes.none { it.parentId != null }) return boxes
    val ids = boxes.associateBy { it.id }
    return boxes.filter { b ->
        val p = b.parentId?.let { ids[it] }
        p == null || p.id == b.id || (p.parentId != null && ids[p.parentId] != null)
    }
}

/** Items marked Move that are not packed in a container: the ones to load and unload on their own. */
fun AppData.looseItems(): List<Item> {
    val moving = movingItems()
    if (moving.none { it.boxId != null }) return moving
    val ids = liveBoxes.map { it.id }.toSet()
    return moving.filter { it.boxId == null || it.boxId !in ids }
}

/** Why [childId] cannot go inside [parentId], in words for the screen; null when it can. */
fun AppData.whyNotNest(childId: String, parentId: String): String? {
    val child = box(childId) ?: return "That container is no longer here."
    val parent = box(parentId) ?: return "That container is no longer here."
    return when {
        child.id == parent.id -> "A container cannot go inside itself."
        parentOf(parent) != null -> "${parent.title()} is already inside ${parentOf(parent)!!.title()}. Containers only go one inside another."
        liveBoxes.any { it.parentId == child.id && parentOf(it) != null } -> "${child.title()} has containers inside it, so it cannot go inside another."
        else -> null
    }
}

// ---- suggestions, so nothing has to be typed -----------------------------------------

private fun words(text: String): List<String> = text.lowercase().split(Regex("[^a-z0-9-]+")).filter { it.isNotBlank() }

private fun List<String>.has(word: String): Boolean = any { it == word || it == word + "s" || it == word + "es" }

private val CATEGORY_WORDS: Map<String, List<String>> = mapOf(
    "Kitchen" to listOf("plate", "glass", "mug", "cup", "pan", "pot", "cutlery", "kettle", "toaster", "bowl", "knife", "knives", "utensil", "tupperware", "baking", "crockery", "saucepan", "tray"),
    "Clothes" to listOf("clothes", "clothing", "shirt", "jumper", "coat", "jacket", "shoe", "sock", "trouser", "jeans", "dress", "underwear", "t-shirt", "suit", "boot", "hat", "scarf"),
    "Books" to listOf("book", "novel", "magazine", "comic", "cookbook", "textbook", "paperback"),
    "Documents" to listOf("document", "paper", "paperwork", "passport", "certificate", "file", "folder", "letter", "contract", "bill", "statement"),
    "Toys" to listOf("toy", "lego", "game", "puzzle", "doll", "teddy", "teddies", "jigsaw"),
    "Bathroom" to listOf("towel", "toiletries", "toiletry", "shampoo", "soap", "medicine", "toothbrush", "make-up", "makeup", "razor"),
    "Tools" to listOf("tool", "drill", "screwdriver", "hammer", "spanner", "paint", "screw", "nail", "saw", "ladder", "hose"),
    "Electronics" to listOf("cable", "charger", "laptop", "console", "router", "camera", "headphone", "speaker", "keyboard", "monitor", "lead", "remote"),
    "Decor" to listOf("picture", "frame", "vase", "ornament", "candle", "lamp", "mirror", "cushion", "rug", "plant", "photo", "clock"),
    "Bedding" to listOf("duvet", "pillow", "sheet", "blanket", "bedding", "quilt", "pillowcase"),
    "Food" to listOf("food", "tin", "pasta", "rice", "cereal", "tea", "coffee", "spice", "snack", "jar", "sauce"),
)

private fun categoryForRoom(roomName: String?): String? {
    val n = roomName?.lowercase() ?: return null
    return when {
        "kitchen" in n || "dining" in n || "pantry" in n -> "Kitchen"
        "bath" in n || "toilet" in n || "wc" in n || "ensuite" in n || "en-suite" in n -> "Bathroom"
        "office" in n || "study" in n -> "Documents"
        "garage" in n || "shed" in n || "workshop" in n || "garden" in n -> "Tools"
        "nursery" in n || "playroom" in n || "kid" in n || "child" in n -> "Toys"
        "living" in n || "lounge" in n || "sitting" in n || "family" in n -> "Decor"
        "bed" in n -> "Clothes"
        else -> null
    }
}

/**
 * The category a container most likely belongs to, from what has been written
 * or scanned into it, and failing that from the room it is packed in.
 * Always one of [BOX_CATEGORIES]; "Other" when there is nothing to go on.
 */
fun suggestCategory(roomName: String?, contents: String = "", entries: List<BoxEntry> = emptyList()): String {
    val w = words(contents + " " + entries.joinToString(" ") { it.title })
    val scores = HashMap<String, Int>()
    for ((category, list) in CATEGORY_WORDS) {
        val n = list.count { w.has(it) }
        if (n > 0) scores[category] = n
    }
    val books = entries.count { isIsbn(it.code) }
    if (books > 0) scores["Books"] = (scores["Books"] ?: 0) + books
    val best = scores.values.maxOrNull()
    if (best != null) return BOX_CATEGORIES.first { scores[it] == best }
    return categoryForRoom(roomName) ?: "Other"
}

fun AppData.suggestCategory(box: Box): String = suggestCategory(room(box.originRoomId)?.name, box.contents, entriesOf(box.id))

data class ContainerSuggestion(val kind: ContainerKind, val size: BoxSize)

/**
 * A kind and size of container that suits what is going in it: heavy things in
 * small cartons, light bulky things in large ones, hanging clothes in a
 * wardrobe box, soft things in bags.
 */
fun suggestContainer(roomName: String?, contents: String = "", category: String = suggestCategory(roomName, contents)): ContainerSuggestion {
    val w = words(contents)
    return when {
        listOf("hanging", "suit", "dress", "coat", "gown").any { w.has(it) } -> ContainerSuggestion(ContainerKind.WARDROBE, BoxSize.L)
        listOf("duvet", "pillow", "cushion", "bedding", "quilt", "teddy", "teddies").any { w.has(it) } -> ContainerSuggestion(ContainerKind.BAG, BoxSize.L)
        else -> when (category) {
            "Books", "Documents", "Food", "Bathroom" -> ContainerSuggestion(ContainerKind.CARTON, BoxSize.S)
            "Tools" -> ContainerSuggestion(ContainerKind.CRATE, BoxSize.M)
            "Bedding" -> ContainerSuggestion(ContainerKind.BAG, BoxSize.L)
            "Clothes", "Toys" -> ContainerSuggestion(ContainerKind.CARTON, BoxSize.L)
            else -> ContainerSuggestion(ContainerKind.CARTON, BoxSize.M)
        }
    }
}

private val ROOM_FAMILIES = listOf(
    listOf("living", "lounge", "sitting", "family", "front room"),
    listOf("office", "study"),
    listOf("bath", "shower", "ensuite", "en-suite", "toilet", "wc"),
    listOf("kitchen", "diner"),
    listOf("utility", "laundry"),
    listOf("loft", "attic"),
    listOf("garage", "shed", "workshop", "garden"),
    listOf("hall", "landing", "porch"),
    listOf("dining"),
    listOf("bed"),
)

/** 0 when the names have nothing in common, more the closer they are. */
private fun roomLikeness(a: String, b: String): Int {
    val x = a.lowercase().trim()
    val y = b.lowercase().trim()
    if (x == y) return 100
    val wx = words(x)
    val wy = words(y)
    if (wx.isNotEmpty() && wx.toSet() == wy.toSet()) return 90
    val fx = ROOM_FAMILIES.indexOfFirst { f -> f.any { it in x } }
    val fy = ROOM_FAMILIES.indexOfFirst { f -> f.any { it in y } }
    if (fx < 0 || fx != fy) return 0
    // Same kind of room: "Bedroom 2" is closer to "Bedroom 2 (back)" than to "Bedroom 3".
    val shared = wx.count { it in wy }
    return 50 + 10 * shared - (wx.size + wy.size - 2 * shared)
}

/**
 * The new-home room something from [originRoomId] most likely goes to: the
 * room it is mapped to, otherwise the new-home room with the same or the most
 * similar name ("Lounge" to "Living room", "Bedroom 2" to "Bedroom 2").
 */
fun AppData.suggestDestRoom(originRoomId: String?): Room? {
    val origin = room(originRoomId) ?: return null
    room(origin.mapsTo)?.let { return it }
    return roomsOf(Home.DEST).map { it to roomLikeness(origin.name, it.name) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
}

/** A name for a new container when none is typed: "Kitchen box 4", "Suitcase 2". */
fun AppData.defaultBoxName(kind: ContainerKind = ContainerKind.CARTON, originRoomId: String? = null): String {
    val room = room(originRoomId)?.name?.trim()?.ifBlank { null }
    val stem = if (kind == ContainerKind.CARTON && room != null) "$room box" else kind.noun
    val boxes = liveBoxes
    var n = boxes.count { it.kind == kind && (kind != ContainerKind.CARTON || it.originRoomId == originRoomId) } + 1
    val taken = boxes.map { it.name.lowercase() }.toSet()
    while ("$stem $n".lowercase() in taken) n++
    return "$stem $n"
}

/** A name for a new item when none is typed: "Kitchen item 3". */
fun AppData.defaultItemName(originRoomId: String? = null): String {
    val room = room(originRoomId)?.name?.trim()?.ifBlank { null }
    val stem = if (room != null) "$room item" else "Item"
    val items = liveItems
    var n = items.count { it.originRoomId == originRoomId } + 1
    val taken = items.map { it.name.lowercase() }.toSet()
    while ("$stem $n".lowercase() in taken) n++
    return "$stem $n"
}

/** What to pre-fill for the next container packed in a room. */
data class PackDefaults(
    val originRoomId: String?,
    val destRoomId: String?,
    val category: String,
    val kind: ContainerKind,
    val size: BoxSize,
    /** True when these were copied from the last container packed there, false when they are suggestions. */
    val fromLast: Boolean,
)

/**
 * "Same as last time": the room, destination, category, kind and size of the
 * last container created in [originRoomId], to pre-fill the next one. For the
 * first container of a room they are suggested from the room instead.
 */
fun AppData.packDefaults(originRoomId: String?): PackDefaults {
    val last = liveBoxes.filter { it.originRoomId == originRoomId }.maxWithOrNull(compareBy({ it.createdAt }, { it.updatedAt }, { it.id }))
    if (last != null) {
        return PackDefaults(originRoomId, last.destRoomId ?: suggestDestRoom(originRoomId)?.id, last.category.ifBlank { suggestCategory(last) }, last.kind, last.size, true)
    }
    val name = room(originRoomId)?.name
    val category = suggestCategory(name)
    val c = suggestContainer(name, category = category)
    return PackDefaults(originRoomId, suggestDestRoom(originRoomId)?.id, category, c.kind, c.size, false)
}

/**
 * One-tap contents for a container: the common things for its [category]
 * (or for the room, when it has no category yet), without those already in
 * [contents].
 */
fun contentsChips(category: String, roomName: String? = null, contents: String = ""): List<String> {
    val key = category.ifBlank { suggestCategory(roomName, contents) }
    val have = contentsLines(contents).map { it.lowercase() }.toSet()
    return (COMMON_CONTENTS[key] ?: COMMON_CONTENTS.getValue("Other")).filter { it.lowercase() !in have }
}

fun AppData.contentsChips(box: Box): List<String> = contentsChips(box.category, room(box.originRoomId)?.name, box.contents)

/**
 * Turn spoken, scanned or typed text into separate contents lines: split at
 * commas, semicolons, new lines and the word "and"; trimmed; no repeats.
 * "Plates, bowls and the big pans" gives [Plates, bowls, the big pans].
 */
fun contentsLines(text: String): List<String> {
    val seen = HashSet<String>()
    return text.split(Regex("[,;\\n\\r]+|\\s+(?:and|&|plus)\\s+", RegexOption.IGNORE_CASE))
        .map { it.trim().removePrefix("and ").trim().trimEnd('.').trim() }
        .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
}

/** [contents] with [more] added, as one comma-separated line with no repeats. */
fun addContents(contents: String, vararg more: String): String = contentsLines(contents + "\n" + more.joinToString("\n")).joinToString(", ")

/** [contents] without [line]. */
fun removeContents(contents: String, line: String): String =
    contentsLines(contents).filter { !it.equals(line.trim(), ignoreCase = true) }.joinToString(", ")
