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

/** Like [mergeList], but [f] sees both phones' copies of each record and decides what is kept (null: nothing). */
private inline fun <T : Rec> mergeEach(a: List<T>, b: List<T>, f: (x: T?, y: T?, newer: T) -> T?): List<T> {
    if (a.isEmpty() && b.isEmpty()) return a
    val mine = HashMap<String, T>(a.size * 2)
    val theirs = HashMap<String, T>(b.size * 2)
    for (x in a) mine[x.id] = x
    for (y in b) theirs[y.id] = y
    val out = ArrayList<T>(a.size + b.size)
    for (w in mergeList(a, b)) f(mine[w.id], theirs[w.id], w)?.let { out += it }
    return out
}

/**
 * One protected part of a record (see Rules.kt): where its stamp is, the check
 * value over its values, and whether the person on a stamp was allowed to set it.
 */
private class Guarded<T>(val roles: Roles, val stamp: (T) -> Stamp, val sum: (T) -> String, val allowed: (T, Stamp) -> Boolean) {
    fun good(r: T?): Boolean {
        if (r == null) return false
        val s = stamp(r)
        if (s.sum.isEmpty()) return s.by.isEmpty() // never stamped: data from before there were roles
        if (s.sum != sum(r)) return false // the values were changed without a new stamp
        // Set in nobody's name: that counts from a time when the move had no Organiser to ask, and not otherwise.
        return if (s.by.isEmpty()) roles.nobodyToAsk(s.at) else allowed(r, s)
    }

    /**
     * The copy whose values for this part stand: the one with the newest stamp that was allowed.
     * Null when neither copy was set by someone with the right to. [newer] is the copy that wins
     * for the rest of the record; it also settles parts nobody has stamped with a name.
     */
    fun pick(x: T?, y: T?, newer: T): T? {
        val gx = good(x)
        val gy = good(y)
        if (!gx || !gy) return if (gx) x else if (gy) y else null
        val sx = stamp(x!!)
        val sy = stamp(y!!)
        return when {
            sx.at != sy.at -> if (sx.at > sy.at) x else y
            sx.sum.isEmpty() != sy.sum.isEmpty() -> if (sx.sum.isEmpty()) y else x
            sx == sy || (sx.by.isEmpty() && sy.by.isEmpty()) -> newer
            sx.by != sy.by -> if (sx.by > sy.by) x else y
            else -> if (sx.sum >= sy.sum) x else y
        }
    }
}

private fun mergeMove(a: MoveInfo, b: MoveInfo, roles: Roles): MoveInfo {
    val newer = when {
        a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
        else -> if (a.toString() >= b.toString()) a else b
    }
    val settings = Guarded<MoveInfo>(roles, { it.changedBy }, { it.settingsSum() }) { _, s -> roles.organiserAt(s.by, s.at) }
    val from = settings.pick(a, b, newer) ?: newer
    val printed = maxOf(a.labelsPrinted, b.labelsPrinted)
    return if (from.updatedAt == newer.updatedAt && from.labelsPrinted == printed) from else from.copy(updatedAt = newer.updatedAt, labelsPrinted = printed)
}

private val CLASS = mapOf(
    ProposalState.WAITING to 0, ProposalState.OVERTAKEN to 1, ProposalState.NOT_CHOSEN to 1,
    ProposalState.APPROVED to 2, ProposalState.DECLINED to 2,
)

/**
 * Suggestions. One made by someone who could not make it is left out. Its
 * state is taken from the copy an Organiser set; an approval or decline beats
 * a state that merely followed from other changes, and a state nobody had the
 * right to set falls back to waiting.
 */
private fun mergeProposals(a: List<Proposal>, b: List<Proposal>, roles: Roles): List<Proposal> {
    fun good(p: Proposal?): Boolean {
        if (p == null || p.decided.sum != p.stateSum()) return false // the state or what was proposed changed without a stamp
        // An approval or refusal in nobody's name only counts from a time when there was no Organiser to give it.
        if (p.decided.by.isEmpty()) return CLASS.getValue(p.state) < 2 || roles.nobodyToAsk(p.decided.at)
        return roles.organiserAt(p.decided.by, p.decided.at)
    }
    /** Withdrawn by the person who made it, with the stamp to show it. */
    fun withdrawnRightly(p: Proposal?): Boolean = p != null && p.deleted && p.withdrawn.by.isNotEmpty() && p.withdrawn.by == p.by && p.withdrawn.sum == p.withdrawnSum()
    return mergeEach(a, b) { x, y, newer ->
        val role = roles.at(newer.by, newer.createdAt) ?: return@mergeEach null
        if (role == Role.HELPER && newer.kind != ProposalKind.QUESTION) return@mergeEach null
        val gx = good(x)
        val gy = good(y)
        val from = when {
            gx && gy -> {
                val cx = CLASS.getValue(x!!.state)
                val cy = CLASS.getValue(y!!.state)
                when {
                    cx != cy -> if (cx > cy) x else y
                    x.decided.at != y.decided.at -> if (x.decided.at > y.decided.at) x else y
                    x.decided == y.decided -> newer
                    x.decided.by != y.decided.by -> if (x.decided.by > y.decided.by) x else y
                    else -> if (x.decided.sum >= y.decided.sum) x else y
                }
            }
            gx -> x!!
            gy -> y!!
            else -> newer.copy(state = ProposalState.WAITING, reason = "").let { it.copy(decided = Stamp(sum = it.stateSum())) }
        }
        // A suggestion is only ever deleted by its author withdrawing it while it waited. An approval or a
        // decline made meanwhile on another phone stands instead, whichever came later: it may already have
        // changed what the suggestion was about.
        val withdrawal = if (withdrawnRightly(x)) x else if (withdrawnRightly(y)) y else null
        val gone = withdrawal != null && CLASS.getValue(from.state) < 2
        val withdrawn = withdrawal?.withdrawn ?: from.withdrawn
        if (from.updatedAt == newer.updatedAt && from.deleted == gone && from.withdrawn == withdrawn) from
        else from.copy(updatedAt = newer.updatedAt, deleted = gone, withdrawn = withdrawn)
    }
}

/** Two phones' copies of the same records: each keeps every hand either copy knows of. */
private inline fun <T : Rec> keepHands(a: List<T>, b: List<T>, merged: List<T>, hands: (T) -> List<String>, with: (T, List<String>) -> T): List<T> {
    if (a.isEmpty() || b.isEmpty()) return merged
    val mine = HashMap<String, T>(a.size * 2)
    for (x in a) mine[x.id] = x
    val theirs = HashMap<String, T>(b.size * 2)
    for (y in b) theirs[y.id] = y
    return merged.mapChanged { r ->
        val x = mine[r.id]
        val y = theirs[r.id]
        if (x == null || y == null) return@mapChanged r
        val all = joinHands(hands(x), hands(y))
        if (all == hands(r)) r else with(r, all)
    }
}

/**
 * Deletions of items, containers, rooms and photos that the rule does not allow (see Deleting.kt)
 * are undone: the record comes back as it was when it was deleted. Bringing one thing back can
 * show that another was not its deleter's to delete (an item comes back into a room), so this
 * goes round until nothing more comes back. It is worked out from the merged data alone, so
 * every phone that holds the same reaches the same.
 */
internal fun keepDeletionsToTheRule(d: AppData, roles: Roles): AppData {
    fun doubtful(x: Owned): Boolean {
        if (!x.deleted) return false
        val s = x.deletedBy
        if (s.sum.isEmpty() && s.by.isEmpty()) return false // from before there were roles
        return !(s.sum == deletedSum() && (s.by.isEmpty() && s.at == 0L || roles.organiserAt(s.by, s.at)))
    }
    if (d.items.none(::doubtful) && d.boxes.none(::doubtful) && d.rooms.none(::doubtful) && d.photos.none(::doubtful)) return d
    var cur = d
    while (true) {
        val hands = HandsOn(cur)
        var back = false
        fun <T : Owned> List<T>.checked(): List<T> = mapChanged { x ->
            if (doubtful(x) && !deletionStands(x, roles, hands)) x.undeleted().also { back = true } else x
        }
        val next = cur.copy(items = cur.items.checked(), boxes = cur.boxes.checked(), rooms = cur.rooms.checked(), photos = cur.photos.checked())
        if (!back) return cur
        cur = next
    }
}

private fun mergeItems(a: List<Item>, b: List<Item>, roles: Roles, approvals: Approvals, rooms: List<Room>): List<Item> {
    val organiser = { _: Item, s: Stamp -> roles.organiserAt(s.by, s.at) }
    val decision = Guarded<Item>(roles, { it.decisionBy }, { it.decisionSum() }, organiser)
    val room = Guarded<Item>(roles, { it.roomBy }, { it.roomSum() }, organiser)
    var decided = Stamp()
    val outcome = Guarded<Item>(roles, { it.outcomeBy }, { it.outcomeSum() }) { item, s ->
        when (roles.at(s.by, s.at)) {
            Role.ORGANISER -> true
            Role.DECIDER -> approvals.coveredThen(ProposalKind.ITEM_DECISION, item.id, s, decided)
            else -> false
        }
    }
    return mergeEach(a, b) { x, y, newer ->
        var r = newer
        val d = decision.pick(x, y, newer)
        if (d == null) r = r.copy(disposition = Disposition.UNDECIDED, price = null, deadlineDay = null).let { it.copy(decisionBy = Stamp(sum = it.decisionSum())) }
        else if (d !== newer) r = r.copy(disposition = d.disposition, price = d.price, deadlineDay = d.deadlineDay, decisionBy = d.decisionBy)
        val p = room.pick(x, y, newer)
        if (p == null) r = r.copy(destRoomId = rooms.firstOrNull { it.id == r.originRoomId && !it.deleted }?.mapsTo).let { it.copy(roomBy = Stamp(sum = it.roomSum())) }
        else if (p !== newer) r = r.copy(destRoomId = p.destRoomId, roomBy = p.roomBy)
        decided = r.decisionBy
        val o = outcome.pick(x, y, newer)
        if (o == null) r = r.copy(listed = false, gone = false, soldFor = null).let { it.copy(outcomeBy = Stamp(sum = it.outcomeSum())) }
        else if (o !== newer) r = r.copy(listed = o.listed, gone = o.gone, soldFor = o.soldFor, outcomeBy = o.outcomeBy)
        r
    }
}

/** Costs. One created by someone who may not create costs is left out altogether. */
private fun mergeExpenses(a: List<Expense>, b: List<Expense>, roles: Roles, approvals: Approvals): List<Expense> {
    val cost = Guarded<Expense>(roles, { it.costBy }, { it.costSum() }) { _, s -> roles.organiserAt(s.by, s.at) }
    var costed = Stamp()
    val paid = Guarded<Expense>(roles, { it.paidBy }, { it.paidSum() }) { e, s ->
        when (roles.at(s.by, s.at)) {
            Role.ORGANISER -> true
            Role.DECIDER -> approvals.coveredThen(ProposalKind.COST, e.id, s, costed)
            else -> false
        }
    }
    return mergeEach(a, b) { x, y, newer ->
        val c = cost.pick(x, y, newer) ?: return@mergeEach null
        var r = if (c === newer) newer else newer.copy(title = c.title, amount = c.amount, category = c.category, day = c.day, deleted = c.deleted, costBy = c.costBy)
        costed = r.costBy
        val p = paid.pick(x, y, newer)
        if (p == null) r = r.copy(planned = true).let { it.copy(paidBy = Stamp(sum = it.paidSum())) }
        else if (p !== newer) r = r.copy(planned = p.planned, paidBy = p.paidBy)
        r
    }
}

/** Tasks. One created by someone who may not create tasks is left out altogether. */
private fun mergeTasks(a: List<Task>, b: List<Task>, roles: Roles): List<Task> {
    val plan = Guarded<Task>(roles, { it.planBy }, { it.planSum() }) { _, s -> roles.organiserAt(s.by, s.at) }
    var assignee: String? = null
    val done = Guarded<Task>(roles, { it.doneBy }, { it.doneSum() }) { _, s ->
        roles.organiserAt(s.by, s.at) || (s.by == assignee && roles.memberAt(s.by, s.at))
    }
    return mergeEach(a, b) { x, y, newer ->
        val p = plan.pick(x, y, newer) ?: return@mergeEach null
        var r = if (p === newer) newer else newer.copy(
            title = p.title, offsetDays = p.offsetDays, dueDay = p.dueDay, category = p.category, notes = p.notes,
            assignedTo = p.assignedTo, deleted = p.deleted, planBy = p.planBy,
        )
        assignee = r.assignedTo
        val t = done.pick(x, y, newer)
        if (t == null) r = r.copy(done = false).let { it.copy(doneBy = Stamp(sum = it.doneSum())) }
        else if (t !== newer) r = r.copy(done = t.done, doneBy = t.doneBy)
        r
    }
}

/** Photos. A receipt attached to a cost by someone who could not carry that cost out is left out. */
private fun mergePhotos(a: List<Photo>, b: List<Photo>, roles: Roles, approvals: Approvals, expenses: List<Expense>): List<Photo> {
    val merged = mergeList(a, b)
    if (merged.none { it.kind == PhotoKind.RECEIPT && it.by.isNotEmpty() }) return merged
    val costs = expenses.associateBy { it.id }
    return merged.filter { p ->
        if (p.kind != PhotoKind.RECEIPT || p.by.isEmpty()) return@filter true
        val cost = costs[p.ownerId] ?: return@filter true
        when (roles.at(p.by, p.createdAt)) {
            Role.ORGANISER -> true
            Role.DECIDER -> approvals.coveredThen(ProposalKind.COST, cost.id, Stamp(p.by, p.createdAt), cost.costBy)
            else -> false
        }
    }
}

/**
 * Merge two phones' datasets (or a phone's and a backup's).
 *
 * Most of a record is last-writer-wins. The protected parts listed in Rules.kt
 * are taken from the newest copy whose author was allowed to set them, so a
 * change made by someone whose role does not allow it is not accepted while
 * the rest of that record still is. Both copies are checked the same way,
 * whichever phone they came from, so every phone ends up with the same result.
 */
fun merge(a: AppData, b: AppData): AppData {
    val people = mergePeople(a.people, b.people)
    val roles = Roles(people)
    val rooms = mergeList(a.rooms, b.rooms)
    val proposals = mergeProposals(a.proposals, b.proposals, roles)
    val approvals = Approvals(proposals)
    val expenses = mergeExpenses(a.expenses, b.expenses, roles, approvals)
    val merged = AppData(
        version = maxOf(a.version, b.version),
        move = mergeMove(a.move, b.move, roles),
        rooms = keepHands(a.rooms, b.rooms, rooms, { it.hands }) { r, h -> r.copy(hands = h) },
        items = keepHands(a.items, b.items, mergeItems(a.items, b.items, roles, approvals, rooms), { it.hands }) { r, h -> r.copy(hands = h) },
        boxes = keepHands(a.boxes, b.boxes, mergeList(a.boxes, b.boxes), { it.hands }) { r, h -> r.copy(hands = h) },
        photos = keepHands(a.photos, b.photos, mergePhotos(a.photos, b.photos, roles, approvals, expenses), { it.hands }) { r, h -> r.copy(hands = h) },
        tasks = mergeTasks(a.tasks, b.tasks, roles),
        expenses = expenses,
        supplies = mergeList(a.supplies, b.supplies),
        trips = mergeList(a.trips, b.trips),
        readings = mergeList(a.readings, b.readings),
        entries = keepHands(a.entries, b.entries, mergeList(a.entries, b.entries), { it.hands }) { r, h -> r.copy(hands = h) },
        people = people,
        proposals = proposals,
        activity = mergeList(a.activity, b.activity),
    )
    return settle(keepDeletionsToTheRule(merged, roles))
}
