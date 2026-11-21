package app.housemove.data

/**
 * Last-writer-wins merge. For every record id the copy with the newer
 * [Rec.updatedAt] is kept. The tie-break is deterministic, so two phones that
 * exchange data always end up with identical datasets regardless of who merges.
 */
fun <T : Rec> mergeList(a: List<T>, b: List<T>): List<T> {
    val out = LinkedHashMap<String, T>()
    for (x in a) out[x.id] = x
    for (y in b) {
        val x = out[y.id]
        out[y.id] = if (x == null) y else newer(x, y)
    }
    return out.values.toList()
}

private fun <T : Rec> newer(x: T, y: T): T = when {
    x.updatedAt != y.updatedAt -> if (x.updatedAt > y.updatedAt) x else y
    x.deleted != y.deleted -> if (x.deleted) x else y
    else -> if (x.toString() >= y.toString()) x else y
}

fun merge(a: AppData, b: AppData): AppData = AppData(
    version = maxOf(a.version, b.version),
    move = when {
        a.move.updatedAt != b.move.updatedAt -> if (a.move.updatedAt > b.move.updatedAt) a.move else b.move
        else -> if (a.move.toString() >= b.move.toString()) a.move else b.move
    },
    rooms = mergeList(a.rooms, b.rooms),
    items = mergeList(a.items, b.items),
    boxes = mergeList(a.boxes, b.boxes),
    photos = mergeList(a.photos, b.photos),
    tasks = mergeList(a.tasks, b.tasks),
    expenses = mergeList(a.expenses, b.expenses),
    supplies = mergeList(a.supplies, b.supplies),
    trips = mergeList(a.trips, b.trips),
    readings = mergeList(a.readings, b.readings),
    entries = mergeList(a.entries, b.entries),
)
