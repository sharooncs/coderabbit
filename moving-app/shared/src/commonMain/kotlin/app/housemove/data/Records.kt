package app.housemove.data

/*
 * Records that belong to an item or a box and are kept apart from it, so that two phones changing them
 * apart never undo each other (version 2.1):
 *
 *  - [Hidden]: what Helpers are not sent (value, make, model, serial number, dates, asking price, what it sold for).
 *  - [Collect]: the amount to collect at a hand-over, sent only to the person handing it over.
 *  - [Damage]: one record per damage report, each cleared on its own.
 *  - [ContentsLine]: one record per line of a box's contents, removed with a tombstone.
 *
 * [Box.contents], [Box.damaged], [Item.damaged] and their notes are worked out from these after every change
 * and every merge ([withCaches]), so screens and reports that read them stay right.
 */

/** Look-ups over the records that belong to items and boxes, made once for a dataset ([AppData.index]). */
internal class RecordIndex(private val d: AppData) {
    val hidden: Map<String, Hidden> by lazy { d.hidden.filter { !it.deleted }.associateBy { it.id } }
    val collect: Map<String, Collect> by lazy { d.collect.filter { !it.deleted }.associateBy { it.id } }

    /** Live contents lines by box, in their order. */
    val lines: Map<String, List<ContentsLine>> by lazy {
        val out = HashMap<String, ArrayList<ContentsLine>>()
        for (l in d.lines) if (!l.deleted) out.getOrPut(l.boxId) { ArrayList() }.add(l)
        for (list in out.values) list.sortWith(LINE_ORDER)
        out
    }

    /** Boxes that have any contents line at all, live or removed. */
    val boxesWithLines: Set<String> by lazy { d.lines.mapTo(HashSet()) { it.boxId } }

    /** Damage reports by the thing they are about, oldest first. */
    val damage: Map<String, List<Damage>> by lazy {
        val out = HashMap<String, ArrayList<Damage>>()
        for (x in d.damage) if (!x.deleted) out.getOrPut(x.thingId) { ArrayList() }.add(x)
        for (list in out.values) list.sortWith(DAMAGE_ORDER)
        out
    }
}

private val LINE_ORDER = compareBy<ContentsLine>({ it.sort }, { it.id })
private val DAMAGE_ORDER = compareBy<Damage>({ it.at }, { it.updatedAt }, { it.id })

// ---- looking things up ---------------------------------------------------------------

/** What Helpers are not sent about [itemId], as far as this phone holds it; null when it holds none. */
fun AppData.hiddenOf(itemId: String?): Hidden? = if (itemId == null) null else index.hidden[itemId]

/** The lines of what is in a box, in order. */
fun AppData.linesOf(boxId: String): List<ContentsLine> = index.lines[boxId].orEmpty()

/** Damage reports on a box or item, oldest first: the open ones, or with [open] false every one, cleared or not. */
fun AppData.damageOf(thingId: String, open: Boolean = true): List<Damage> =
    index.damage[thingId].orEmpty().let { all -> if (open) all.filter { !it.cleared } else all }

/** Is anything reported damaged on this box or item and not cleared? */
fun AppData.isDamaged(thingId: String): Boolean = index.damage[thingId]?.any { !it.cleared } == true

/** Every open damage report, oldest first. */
fun AppData.openDamage(): List<Damage> = damage.filter { !it.deleted && !it.cleared }.sortedWith(DAMAGE_ORDER)

/**
 * The lines of a box's contents as written in its text: split at commas, semicolons and new lines only, so
 * "Kettle and toaster" written as one stays one ([contentsLines] also splits at "and", for what is said).
 * Trimmed, with no repeats.
 */
fun storedLines(text: String): List<String> {
    val seen = HashSet<String>()
    return text.split(Regex("[,;\\n\\r]+")).map { it.trim().trimEnd('.').trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
}

/** The id of the line [text] of box [boxId]: the same words in the same box are always the same line. */
internal fun lineId(boxId: String, text: String): String = boxId + "/" + seal(text.trim().lowercase())

/** The id of the damage that 2.0 recorded on a thing, made into a report when the data is brought up to 2.1. */
internal fun oldDamageId(thingId: String): String = "$thingId/damage"

// ---- the fields worked out from these records ------------------------------------------

/**
 * [Box.contents], and [Box.damaged], [Item.damaged] with their notes, as their records say. A thing with no
 * records of that kind at all keeps what it says (2.0 data not yet brought up to date, which [migrate] does).
 * Only the fields are set; the record's time is left alone, as every phone works this out for itself.
 */
internal fun withCaches(d: AppData): AppData {
    if (d.lines.isEmpty() && d.damage.isEmpty()) return d
    val idx = d.index
    fun damageNow(id: String, damaged: Boolean, note: String): Pair<Boolean, String>? {
        val all = idx.damage[id] ?: return null
        val open = all.filter { !it.cleared }
        val now = open.isNotEmpty() to open.map { it.note.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
        return if (now.first == damaged && now.second == note) null else now
    }
    val boxes = d.boxes.mapChanged { b ->
        val text = if (b.id in idx.boxesWithLines) idx.lines[b.id].orEmpty().joinToString(", ") { it.text } else b.contents
        val dmg = damageNow(b.id, b.damaged, b.damageNote)
        if (text == b.contents && dmg == null) b
        else b.copy(contents = text, damaged = dmg?.first ?: b.damaged, damageNote = dmg?.second ?: b.damageNote)
    }
    val items = if (d.damage.isEmpty()) d.items else d.items.mapChanged { i ->
        val dmg = damageNow(i.id, i.damaged, i.damageNote) ?: return@mapChanged i
        i.copy(damaged = dmg.first, damageNote = dmg.second)
    }
    return if (boxes === d.boxes && items === d.items) d else d.copy(boxes = boxes, items = items)
}

// ---- changes, as a person makes them ([Draft]) ---------------------------------------------

/** Is this person an Organiser now (or is the move one with nobody to ask)? */
internal val Actor.isOrganiser: Boolean get() = open || (me != null && !me.removed && me.role == Role.ORGANISER)

/**
 * Make the lines of box [boxId] those of [text] ([storedLines]): lines no longer there are removed
 * (a tombstone, so an older copy never brings them back), new ones are added, and a line removed earlier
 * and written again comes back. Returns whether anything changed.
 */
internal fun Draft.setLines(boxId: String, text: String): Boolean {
    val want = LinkedHashMap<String, String>()
    for (w in storedLines(text)) want.getOrPut(lineId(boxId, w)) { w }
    val have = HashSet<String>()
    var changed = false
    val kept = d.lines.mapChanged { l ->
        if (l.boxId != boxId) return@mapChanged l
        have += l.id
        val w = want[l.id]
        when {
            w == null && !l.deleted -> l.copy(deleted = true, updatedAt = t)
            w != null && l.deleted -> l.copy(deleted = false, text = w, sort = t * 1000, by = actor.id, updatedAt = t)
            w != null && l.text != w -> l.copy(text = w, updatedAt = t)
            else -> l
        }.also { if (it !== l) changed = true }
    }
    var n = 0
    val added = want.filter { it.key !in have }.map { (id, w) -> ContentsLine(id, boxId, w, sort = t * 1000 + n++, by = actor.id, updatedAt = t) }
    if (!changed && added.isEmpty()) return false
    d = d.copy(lines = kept + added)
    return true
}

/** A new damage report on [thingId] in this person's name. */
internal fun Draft.addDamage(thingId: String, note: String): Damage {
    val x = Damage(id = newId(), thingId = thingId, note = note.trim(), by = actor.id, at = t, updatedAt = t)
        .let { it.copy(clearedBy = Stamp(actor.signature, t, it.clearedSum())) }
    d = d.copy(damage = d.damage + x)
    return x
}

/** Clear the open reports [ids] (or reopen them, with [cleared] false) in this person's name. The caller checks they may. */
internal fun Draft.setCleared(ids: Set<String>, cleared: Boolean = true): List<Damage> {
    val out = ArrayList<Damage>()
    d = d.copy(
        damage = d.damage.mapChanged { x ->
            if (x.id !in ids || x.cleared == cleared) x
            else x.copy(cleared = cleared, updatedAt = t).let { it.copy(clearedBy = actor.stamp(t, it.clearedSum())) }.also { out += it }
        },
    )
    return out
}

/** Change the note of a report. Part of the work: anyone may. */
internal fun Draft.setDamageNote(id: String, note: String): Damage? {
    var saved: Damage? = null
    d = d.copy(damage = d.damage.mapChanged { x -> if (x.id != id || x.note == note.trim()) x else x.copy(note = note.trim(), updatedAt = t).also { saved = it } })
    return saved
}

/**
 * What saving [now] over [old] says about the damage on a thing, for screens that set the damaged switch and
 * the note on the record itself ([Item.damaged], [Box.damaged]): switching it on reports damage, switching it
 * off clears the open reports (Organisers only; for anyone else nothing changes), and a new note while there
 * is one open report is that report's note.
 */
internal fun Draft.damageFromFields(thingId: String, oldDamaged: Boolean, oldNote: String, damaged: Boolean, note: String): Boolean {
    val open = d.index.damage[thingId].orEmpty().filter { !it.cleared }
    return when {
        damaged && !oldDamaged -> {
            addDamage(thingId, note)
            true
        }
        !damaged && oldDamaged -> if (!actor.isOrganiser) false else setCleared(open.map { it.id }.toSet()).isNotEmpty()
        damaged && note.trim() != oldNote.trim() && open.size == 1 -> setDamageNote(open[0].id, note) != null
        damaged && open.isEmpty() -> {
            addDamage(thingId, note)
            true
        }
        else -> false
    }
}
