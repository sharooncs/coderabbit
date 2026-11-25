package app.housemove.data

/*
 * Deleting things.
 *
 * The rule: an Organiser may delete anything. Anyone else may delete an item, container, room
 * or photo they added themselves, as long as nobody else has had a hand in it since: changed it,
 * photographed it, packed something into it (or, for a room, put or planned something in it),
 * or made a suggestion or asked a question about it. Tasks and costs are part of the plan, so
 * only an Organiser deletes those. Otherwise a Decider or a Helper asks an Organiser to delete
 * it ([Store.askToDelete]); a Decider suggests deleting a cost with [Store.suggestCost].
 * Scanned things in a container, supplies, trips and meter readings are part of the work and
 * anyone may delete them, as before. A move with no Organiser is not restricted.
 *
 * The same rule is applied again when phones merge, from the data alone: a deletion stands when
 * whoever made it was an Organiser then, or was the one who added the thing and nobody else has
 * had a hand in it as far as either phone knows. So a container one phone deleted while another
 * was packing into it comes back with what was packed. Costs, cost suggestions, and receipt and
 * serial-label photos are left out of "anyone else's hand", because a Helper's phone never holds
 * them and every phone must reach the same answer.
 */

internal fun deletedSum() = seal(true)

/** The people in a list of hands, as kept: no blanks, no repeats, in order, so every phone writes the same. */
internal fun joinHands(vararg lists: Collection<String>): List<String> {
    val out = HashSet<String>()
    for (l in lists) for (h in l) if (h.isNotEmpty()) out += h
    return out.sorted()
}

/** Who has had a hand in what, worked out once for a whole dataset. */
internal class HandsOn(private val d: AppData) {
    private fun <T> index(list: List<T>, key: (T) -> String?): Map<String, List<T>> {
        val out = HashMap<String, ArrayList<T>>()
        for (x in list) key(x)?.let { out.getOrPut(it) { ArrayList() }.add(x) }
        return out
    }

    private val photosOf by lazy { index(d.photos.filter { !it.deleted && it.kind !in HIDDEN_FROM_HELPERS }) { it.ownerId } }
    private val askedAbout by lazy { index(d.proposals.filter { !it.deleted && it.kind != ProposalKind.COST && !it.deletes }) { it.targetId } }
    private val itemsIn by lazy { index(d.items.filter { !it.deleted }) { it.boxId } }
    private val boxesIn by lazy { index(d.boxes.filter { !it.deleted }) { it.parentId } }
    private val entriesIn by lazy { index(d.entries.filter { !it.deleted }) { it.boxId } }
    private val itemsInRoom by lazy {
        val out = HashMap<String, ArrayList<Item>>()
        for (i in d.items) if (!i.deleted) for (r in setOfNotNull(i.originRoomId, i.destRoomId)) out.getOrPut(r) { ArrayList() }.add(i)
        out
    }
    private val boxesInRoom by lazy {
        val out = HashMap<String, ArrayList<Box>>()
        for (b in d.boxes) if (!b.deleted) for (r in setOfNotNull(b.originRoomId, b.destRoomId)) out.getOrPut(r) { ArrayList() }.add(b)
        out
    }
    private val roomsMappedTo by lazy { index(d.rooms.filter { !it.deleted }) { it.mapsTo } }

    /**
     * Everyone other than [creator] who has had a hand in [x], in order. A blank entry stands for
     * "someone, not known who": something from before version 2.0 or from a move with nobody to ask.
     */
    fun others(x: Owned, creator: String = x.createdBy): List<String> {
        val all = HashSet<String>()
        fun add(people: Collection<String>) {
            if (people.none { it.isNotEmpty() }) all += "" else for (p in people) if (p.isNotEmpty()) all += p
        }
        fun add(o: Owned) = add(o.hands + o.createdBy)
        all += x.hands.filter { it.isNotEmpty() }
        photosOf[x.id]?.forEach { if (it.id != x.id) add(it) }
        askedAbout[x.id]?.forEach { add(listOf(it.by)) }
        when (x) {
            is Box -> {
                itemsIn[x.id]?.forEach { add(it) }
                boxesIn[x.id]?.forEach { if (it.id != x.id) add(it) }
                entriesIn[x.id]?.forEach { add(it.hands) }
            }
            is Room -> {
                itemsInRoom[x.id]?.forEach { add(it) }
                boxesInRoom[x.id]?.forEach { add(it) }
                roomsMappedTo[x.id]?.forEach { if (it.id != x.id) add(it) }
            }
        }
        all.remove(creator)
        return all.sorted()
    }
}

/** Everyone other than whoever added [x] who has had a hand in it since. A blank entry is someone not known. */
fun AppData.othersOn(x: Owned): List<String> = HandsOn(this).others(x)

/**
 * Why [person] may not delete [x], in words for the screen, or null when they may. Use it to
 * decide whether to show a delete button. [Store.tryDelete] says the same when it refuses.
 */
fun AppData.mayDelete(person: Person?, x: Rec): String? {
    if (!hasOrganiser()) return null
    val ask = "You can ask an Organiser to delete it."
    when (x) {
        is BoxEntry, is Supply, is Trip, is Reading -> return refusal(person, Action.WORK)
        is Task -> return refusal(person, Action.DELETE)?.let { if (person?.removed == true) it else "Only an Organiser can delete a task. $ask" }
        is Expense -> return refusal(person, Action.DELETE)?.let { if (person?.removed == true) it else "Only an Organiser can delete a cost." + if (person?.role == Role.DECIDER) " You can suggest deleting it." else "" }
    }
    refusal(person, Action.DELETE) ?: return null
    if (person == null || person.removed) return refusal(person, Action.DELETE)
    val owned = x as? Owned ?: return refusal(person, Action.DELETE)
    if (owned.createdBy.isEmpty() || owned.createdBy != person.id) {
        return "Only an Organiser can delete something someone else added. $ask"
    }
    val others = HandsOn(this).others(owned)
    if (others.isEmpty()) return null
    val named = others.filter { it.isNotEmpty() }.map { personName(it).ifBlank { "Someone" } }.distinct()
    val who = when {
        named.isEmpty() -> "Someone else has"
        named.size == 1 -> named[0] + " has"
        else -> named.dropLast(1).joinToString(", ") + " and " + named.last() + " have"
    }
    return "$who worked on it since you added it, so only an Organiser can delete it now. $ask"
}

/** May [person] delete [x]? See [mayDelete] for the reason when not. */
fun AppData.canDelete(person: Person?, x: Rec): Boolean = mayDelete(person, x) == null

/**
 * Does the deletion of [x] stand, by the rule above, given everything in the merged data
 * ([hands]) and who had which role when ([roles])? A record that is not deleted stands as it is.
 */
internal fun deletionStands(x: Owned, roles: Roles, hands: HandsOn): Boolean {
    if (!x.deleted) return true
    val s = x.deletedBy
    // Never stamped: data from before there were roles, as for the other protected parts. A phone marks
    // what arrives from another without a stamp ([distrustUnstamped]), so that is not taken for old data.
    if (s.sum.isEmpty()) return s.by.isEmpty()
    if (s.sum != deletedSum()) return false // the stamp was tampered with, or the deletion came without one
    // In nobody's name: from before version 2.0 (time 0), or from a time when there was nobody to ask.
    if (s.by.isEmpty()) return roles.nobodyToAsk(s.at)
    return when (roles.at(s.by, s.at)) {
        Role.ORGANISER -> true
        null -> false
        else -> s.by == x.createdBy && hands.others(x).isEmpty()
    }
}

/** A deleted record whose deletion does not stand, brought back as it was when it was deleted. */
@Suppress("UNCHECKED_CAST")
internal fun <T : Owned> T.undeleted(): T = when (this) {
    is Item -> copy(deleted = false, deletedBy = Stamp())
    is Box -> copy(deleted = false, deletedBy = Stamp())
    is Room -> copy(deleted = false, deletedBy = Stamp())
    is Photo -> copy(deleted = false, deletedBy = Stamp())
    else -> this
} as T
