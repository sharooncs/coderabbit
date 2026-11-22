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

/**
 * The newer copy. Of two copies of the same time (two phones changed the record at the same moment) a deletion wins;
 * otherwise they are told apart by what the newer copy decides ([ownPart]), never by what the merge then settles by
 * itself: the merged copy has those parts from wherever they stand, so it would compare differently with the same copy
 * at the next merge, and merging the same data again (an update file opened twice) would choose the other copy.
 *
 * A deletion of an item, container, room or photo may not stand, and is then undone ([keepDeletionsToTheRule]). So the
 * deletion that wins comes with the rest of the record from the copy that would have won without it: undone, it is the
 * same as at every other merge of the same copies.
 */
private fun <T : Rec> newer(x: T, y: T): T {
    if (x.updatedAt != y.updatedAt) return if (x.updatedAt > y.updatedAt) x else y
    if (x == y) return x
    val ox = ownPart(x)
    val oy = ownPart(y)
    if (x.deleted != y.deleted) {
        val dead = if (x.deleted) x else y
        val live = if (x.deleted) y else x
        val liveWins = if (x.deleted) oy > ox else ox > oy
        return if (liveWins) withDeletionOf(live, dead) ?: dead else dead
    }
    return when {
        ox != oy -> if (ox > oy) x else y
        else -> if (x.toString() >= y.toString()) x else y
    }
}

/** [live] deleted as [dead] is, for an item, container, room or photo; null for another record. */
@Suppress("UNCHECKED_CAST")
private fun <T : Rec> withDeletionOf(live: T, dead: T): T? = when (live) {
    is Item -> live.copy(deleted = true, deletedBy = (dead as Item).deletedBy)
    is Box -> live.copy(deleted = true, deletedBy = (dead as Box).deletedBy)
    is Room -> live.copy(deleted = true, deletedBy = (dead as Room).deletedBy)
    is Photo -> live.copy(deleted = true, deletedBy = (dead as Photo).deletedBy)
    else -> null
} as T?

/**
 * What the newer copy of a record decides at a merge, as text: the record without what the merge settles by itself,
 * which is each protected part (from the copy whose stamp stands), the hands (both copies' together), whether it is
 * deleted (by the rule of who may delete), its people's roles and phones, and what follows from other records (where a
 * thing in a container is, its damage and a container's contents). Blank for a record the merge takes whole from the
 * newer copy, and for one whose parts are all protected.
 */
private fun ownPart(r: Rec): String = when (r) {
    is Item -> r.copy(
        disposition = Disposition.UNDECIDED, deadlineDay = null, decisionBy = Stamp(), destRoomId = null, roomBy = Stamp(),
        listed = false, gone = false, outcomeBy = Stamp(), handoverTo = null, paid = false, handoverBy = Stamp(), handedOver = false, handedOverBy = Stamp(),
        stage = Stage.AT_ORIGIN, tripId = null, whereBy = Stamp(), damaged = false, damageNote = "", hands = emptyList(), deleted = false, deletedBy = Stamp(),
    ).toString()
    is Box -> r.copy(
        stage = Stage.AT_ORIGIN, tripId = null, whereBy = Stamp(), contents = "", damaged = false, damageNote = "",
        hands = emptyList(), deleted = false, deletedBy = Stamp(),
    ).toString()
    is Room -> r.copy(hands = emptyList(), deleted = false, deletedBy = Stamp()).toString()
    is Photo -> r.copy(hands = emptyList(), deleted = false, deletedBy = Stamp()).toString()
    is BoxEntry -> r.copy(hands = emptyList()).toString()
    is Damage -> r.copy(cleared = false, clearedBy = Stamp()).toString()
    is Person -> r.copy(role = Role.HELPER, removed = false, grants = emptyList(), phones = emptyList(), deleted = false).toString()
    else -> ""
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
 * A change that carries a name and a check value that holds, but whose author was not
 * allowed to make it, is noted in [refusals] as [what], with [tried] saying what it was.
 */
private class Guarded<T : Rec>(
    val roles: Roles, val stamp: (T) -> Stamp, val sum: (T) -> String, val allowed: (T, Stamp) -> Boolean,
    val refusals: Refusals? = null, val what: String = "", val tried: (T) -> String = { "change {}" },
) {
    fun good(r: T?): Boolean {
        if (r == null) return false
        val s = stamp(r)
        if (s.sum.isEmpty()) return s.by.isEmpty() // never stamped: data from before there were roles
        if (s.sum != sum(r)) return false // the values were changed without a new stamp
        // Set in nobody's name: that counts from a time when the move had no Organiser to ask, and not otherwise.
        if (s.by.isEmpty()) return roles.nobodyToAsk(s.at)
        if (allowed(r, s)) return true
        refusals?.add(what, r.id, s, tried(r))
        return false
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

private fun mergeMove(a: MoveInfo, b: MoveInfo, roles: Roles, refusals: Refusals): MoveInfo {
    val newer = when {
        a.updatedAt != b.updatedAt -> if (a.updatedAt > b.updatedAt) a else b
        else -> if (a.toString() >= b.toString()) a else b
    }
    // MoveInfo is not a record with an id; the check is made here, as Guarded makes it for records.
    fun good(m: MoveInfo): Boolean {
        val s = m.changedBy
        if (s.sum.isEmpty()) return s.by.isEmpty()
        if (s.sum != m.settingsSum()) return false
        if (s.by.isEmpty()) return roles.nobodyToAsk(s.at)
        return roles.organiserAt(s.by, s.at).also { if (!it) refusals.add("settings", "move", s, "change the move settings") }
    }
    val ga = good(a)
    val gb = good(b)
    val from = when {
        ga && gb -> when {
            a.changedBy.at != b.changedBy.at -> if (a.changedBy.at > b.changedBy.at) a else b
            a.changedBy.sum.isEmpty() != b.changedBy.sum.isEmpty() -> if (a.changedBy.sum.isEmpty()) b else a
            a.changedBy == b.changedBy || (a.changedBy.by.isEmpty() && b.changedBy.by.isEmpty()) -> newer
            a.changedBy.by != b.changedBy.by -> if (a.changedBy.by > b.changedBy.by) a else b
            else -> if (a.changedBy.sum >= b.changedBy.sum) a else b
        }
        ga -> a
        gb -> b
        else -> newer
    }
    val printed = maxOf(a.labelsPrinted, b.labelsPrinted)
    return if (from.updatedAt == newer.updatedAt && from.labelsPrinted == printed) from else from.copy(updatedAt = newer.updatedAt, labelsPrinted = printed)
}

private val CLASS = mapOf(
    ProposalState.WAITING to 0, ProposalState.OVERTAKEN to 1, ProposalState.NOT_CHOSEN to 1,
    ProposalState.APPROVED to 2, ProposalState.DECLINED to 2,
)

/**
 * Suggestions. One made by someone who could not make it is left out of every
 * list: it is kept deleted, in nobody's name, not dropped. Whether they could
 * can look otherwise once the phone hears more (that whoever changed their role
 * had already lost the right to), and the suggestion then stands again, worked
 * out afresh at every merge. Its state is taken from the copy an Organiser set;
 * an approval or decline beats a state that merely followed from other changes,
 * and a state nobody had the right to set falls back to waiting.
 */
private fun mergeProposals(a: List<Proposal>, b: List<Proposal>, roles: Roles, refusals: Refusals): List<Proposal> {
    fun good(p: Proposal?): Boolean {
        if (p == null || p.decided.sum != p.stateSum()) return false // the state or what was proposed changed without a stamp
        // An approval or refusal in nobody's name only counts from a time when there was no Organiser to give it.
        if (p.decided.by.isEmpty()) return CLASS.getValue(p.state) < 2 || roles.nobodyToAsk(p.decided.at)
        return roles.organiserAt(p.decided.by, p.decided.at).also {
            if (!it) refusals.add("approval", p.id, p.decided, if (p.state == ProposalState.DECLINED) "decline a suggestion" else "approve a suggestion")
        }
    }
    /** Withdrawn by the person who made it, with the stamp to show it. */
    fun withdrawnRightly(p: Proposal?): Boolean = p != null && p.deleted && p.withdrawn.by.isNotEmpty() && p.withdrawn.by == p.by && p.withdrawn.sum == p.withdrawnSum()
    return mergeEach(a, b) { x, y, newer ->
        val role = roles.at(newer.by, newer.createdAt)
        val rightful = role != null && (role != Role.HELPER || newer.kind == ProposalKind.QUESTION)
        if (!rightful) {
            val tried = when (newer.kind) {
                ProposalKind.COST -> "suggest a cost"
                ProposalKind.TASK -> "suggest a task"
                ProposalKind.ITEM_ROOM -> "suggest a room for {}"
                else -> "make a suggestion about {}"
            }
            // About the thing it names, so the Organisers see which; a cost is never named.
            refusals.add("suggestion", if (newer.kind == ProposalKind.COST) "" else newer.targetId, Stamp(newer.by, newer.createdAt), tried)
        }
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
        // A suggestion is only ever deleted by its author withdrawing it while it waited, or here, while its author
        // could not make it as far as this merge knows. An approval or a decline made meanwhile on another phone stands
        // instead of a withdrawal, whichever came later: it may already have changed what the suggestion was about.
        val withdrawal = if (withdrawnRightly(x)) x else if (withdrawnRightly(y)) y else null
        val gone = !rightful || (withdrawal != null && CLASS.getValue(from.state) < 2)
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
internal fun keepDeletionsToTheRule(d: AppData, roles: Roles): AppData = keepDeletionsToTheRule(d, roles, null)

private fun keepDeletionsToTheRule(d: AppData, roles: Roles, refusals: Refusals?): AppData {
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
            if (doubtful(x) && !deletionStands(x, roles, hands)) {
                if (x.deletedBy.sum == deletedSum() && !(x is Photo && x.kind in HIDDEN_FROM_HELPERS)) refusals?.add("delete", x.id, x.deletedBy, "delete {}")
                x.undeleted().also { back = true }
            } else x
        }
        val next = cur.copy(items = cur.items.checked(), boxes = cur.boxes.checked(), rooms = cur.rooms.checked(), photos = cur.photos.checked())
        if (!back) return cur
        cur = next
    }
}

private fun mergeItems(a: List<Item>, b: List<Item>, roles: Roles, approvals: Approvals, rooms: List<Room>, refusals: Refusals): List<Item> {
    val organiser = { _: Item, s: Stamp -> roles.organiserAt(s.by, s.at) }
    val decision = Guarded<Item>(roles, { it.decisionBy }, { it.decisionSum() }, organiser, refusals, "decision") { decideVerb(it.disposition) }
    val room = Guarded<Item>(roles, { it.roomBy }, { it.roomSum() }, organiser, refusals, "room") { "choose the room {} goes to" }
    var decided = Stamp()
    var disposition = Disposition.UNDECIDED
    fun carriedOut(item: Item, s: Stamp) = when (roles.at(s.by, s.at)) {
        Role.ORGANISER -> true
        Role.DECIDER -> approvals.coveredThen(ProposalKind.ITEM_DECISION, item.id, s, decided)
        else -> false
    }
    val outcome = Guarded<Item>(roles, { it.outcomeBy }, { it.outcomeSum() }, ::carriedOut, refusals, "outcome") { outcomeVerb(it) }
    val handover = Guarded<Item>(roles, { it.handoverBy }, { it.handoverSum() }, ::carriedOut, refusals, "handover") { "arrange the hand-over of {}" }
    val handed = Guarded<Item>(roles, { it.handedOverBy }, { it.handedOverSum() }, { _, s ->
        roles.organiserAt(s.by, s.at) || (roles.memberAt(s.by, s.at) && (disposition == Disposition.SELL || disposition == Disposition.DONATE))
    }, refusals, "handed") { if (it.handedOver) "mark {} handed over" else "mark {} not handed over" }
    val where = Guarded<Item>(roles, { it.whereBy }, { it.whereSum() }, { _, s -> roles.memberAt(s.by, s.at) }, refusals, "where") { "move {}" }
    return mergeEach(a, b) { x, y, newer ->
        var r = newer
        val d = decision.pick(x, y, newer)
        if (d == null) r = r.copy(disposition = Disposition.UNDECIDED, deadlineDay = null).let { it.copy(decisionBy = Stamp(sum = it.decisionSum())) }
        else if (d !== newer) r = r.copy(disposition = d.disposition, deadlineDay = d.deadlineDay, decisionBy = d.decisionBy)
        val p = room.pick(x, y, newer)
        if (p == null) r = r.copy(destRoomId = rooms.firstOrNull { it.id == r.originRoomId && !it.deleted }?.mapsTo).let { it.copy(roomBy = Stamp(sum = it.roomSum())) }
        else if (p !== newer) r = r.copy(destRoomId = p.destRoomId, roomBy = p.roomBy)
        decided = r.decisionBy
        disposition = r.disposition
        val o = outcome.pick(x, y, newer)
        if (o == null) r = r.copy(listed = false, gone = false).let { it.copy(outcomeBy = Stamp(sum = it.outcomeSum())) }
        else if (o !== newer) r = r.copy(listed = o.listed, gone = o.gone, outcomeBy = o.outcomeBy)
        val h = handover.pick(x, y, newer)
        if (h == null) r = r.copy(handoverTo = null, paid = false).let { it.copy(handoverBy = Stamp(sum = it.handoverSum())) }
        else if (h !== newer) r = r.copy(handoverTo = h.handoverTo, paid = h.paid, handoverBy = h.handoverBy)
        val g = handed.pick(x, y, newer)
        if (g == null) r = r.copy(handedOver = false).let { it.copy(handedOverBy = Stamp(sum = it.handedOverSum())) }
        else if (g !== newer) r = r.copy(handedOver = g.handedOver, handedOverBy = g.handedOverBy)
        // Where it is: the newest move anyone in the move made, on or back. With no such stamp the newer copy says.
        val w = where.pick(x, y, newer)
        if (w != null && w !== newer) r = r.copy(stage = w.stage, tripId = w.tripId, whereBy = w.whereBy)
        r
    }
}

/** Containers: where each is has its own stamp, as for items; the rest is the newer copy's. */
private fun mergeBoxes(a: List<Box>, b: List<Box>, roles: Roles, refusals: Refusals): List<Box> {
    val where = Guarded<Box>(roles, { it.whereBy }, { it.whereSum() }, { _, s -> roles.memberAt(s.by, s.at) }, refusals, "where") { "move {}" }
    return mergeEach(a, b) { x, y, newer ->
        val w = where.pick(x, y, newer)
        if (w == null || w === newer) newer else newer.copy(stage = w.stage, tripId = w.tripId, whereBy = w.whereBy)
    }
}

/**
 * What Helpers are not sent. Never a Helper's to write: details are an Organiser's or a Decider's, the asking
 * price goes with the decision (an Organiser's), and what it sold for with carrying the sale out.
 */
private fun mergeHidden(a: List<Hidden>, b: List<Hidden>, roles: Roles, approvals: Approvals, items: Map<String, Item>, refusals: Refusals): List<Hidden> {
    val details = Guarded<Hidden>(roles, { it.detailsBy }, { it.detailsSum() }, { _, s ->
        roles.at(s.by, s.at).let { it == Role.ORGANISER || it == Role.DECIDER }
    }, refusals, "details") { "change the make, model, serial number or value of {}" }
    val price = Guarded<Hidden>(roles, { it.priceBy }, { it.priceSum() }, { _, s -> roles.organiserAt(s.by, s.at) }, refusals, "price") { "set the asking price of {}" }
    var decided = Stamp()
    val sale = Guarded<Hidden>(roles, { it.saleBy }, { it.saleSum() }, { h, s ->
        when (roles.at(s.by, s.at)) {
            Role.ORGANISER -> true
            Role.DECIDER -> approvals.coveredThen(ProposalKind.ITEM_DECISION, h.id, s, decided)
            else -> false
        }
    }, refusals, "sale") { "record what {} sold for" }
    return mergeEach(a, b) { x, y, newer ->
        var r = newer
        val d = details.pick(x, y, newer)
        if (d == null) r = r.copy(value = null, make = "", model = "", serial = "", boughtDay = null, warrantyDay = null).let { it.copy(detailsBy = Stamp(sum = it.detailsSum())) }
        else if (d !== newer) r = r.copy(value = d.value, make = d.make, model = d.model, serial = d.serial, boughtDay = d.boughtDay, warrantyDay = d.warrantyDay, detailsBy = d.detailsBy)
        val p = price.pick(x, y, newer)
        if (p == null) r = r.copy(price = null).let { it.copy(priceBy = Stamp(sum = it.priceSum())) }
        else if (p !== newer) r = r.copy(price = p.price, priceBy = p.priceBy)
        decided = items[r.id]?.decisionBy ?: Stamp()
        val s = sale.pick(x, y, newer)
        if (s == null) r = r.copy(soldFor = null).let { it.copy(saleBy = Stamp(sum = it.saleSum())) }
        else if (s !== newer) r = r.copy(soldFor = s.soldFor, saleBy = s.saleBy)
        r
    }
}

/** Amounts to collect at a hand-over: whoever may arrange the hand-over (as for carrying the sale out). */
private fun mergeCollect(a: List<Collect>, b: List<Collect>, roles: Roles, approvals: Approvals, items: Map<String, Item>, refusals: Refusals): List<Collect> {
    var decided = Stamp()
    val by = Guarded<Collect>(roles, { it.by }, { it.collectSum() }, { c, s ->
        when (roles.at(s.by, s.at)) {
            Role.ORGANISER -> true
            Role.DECIDER -> approvals.coveredThen(ProposalKind.ITEM_DECISION, c.id, s, decided)
            else -> false
        }
    }, refusals, "collect") { "arrange the hand-over of {}" }
    return mergeEach(a, b) { x, y, newer ->
        decided = items[newer.id]?.decisionBy ?: Stamp()
        val c = by.pick(x, y, newer)
        when {
            c == null -> newer.copy(to = "", amount = null).let { it.copy(by = Stamp(sum = it.collectSum())) }
            c !== newer -> newer.copy(to = c.to, amount = c.amount, by = c.by)
            else -> newer
        }
    }
}

/**
 * Damage reports. Anyone in the move reports damage, and each report is its own record, so two phones
 * reporting apart keep both. Clearing one is an Organiser's, with a stamp; until then the report carries its
 * reporter's own stamp, which no one else's "not cleared" can stand in for.
 */
private fun mergeDamage(a: List<Damage>, b: List<Damage>, roles: Roles, refusals: Refusals): List<Damage> {
    val cleared = Guarded<Damage>(roles, { it.clearedBy }, { it.clearedSum() }, { x, s ->
        roles.organiserAt(s.by, s.at) || (!x.cleared && s.by == x.by && s.at == x.at)
    }, refusals, "cleared") { if (it.cleared) "clear a damage report on {}" else "reopen a damage report on {}" }
    return mergeEach(a, b) { x, y, newer ->
        val c = cleared.pick(x, y, newer)
        when {
            c == null -> newer.copy(cleared = false).let { it.copy(clearedBy = Stamp(it.by, it.at, it.clearedSum())) }
            c !== newer -> newer.copy(cleared = c.cleared, clearedBy = c.clearedBy)
            else -> newer
        }
    }
}

/**
 * Contents lines: the newer copy of each line, so a removed line (its tombstone is newer) never comes back
 * from an older copy, and the same words in a box are one line however many phones added them. Lines made
 * from a box's 2.0 text at the upgrade are kept only from the newest such text of that box, as 2.0 itself
 * would have kept only the newer box; lines added since are always kept.
 */
private fun mergeLines(a: List<ContentsLine>, b: List<ContentsLine>): List<ContentsLine> {
    val merged = mergeList(a, b)
    if (merged.none { it.batch != 0L }) return merged
    val newest = HashMap<String, Long>()
    for (l in merged) if (l.batch != 0L) newest.merge(l.boxId, l.batch, ::maxOf)
    return merged.filter { it.batch == 0L || it.batch == newest[it.boxId] }
}

/** Costs. One created by someone who may not create costs is left out altogether. */
private fun mergeExpenses(a: List<Expense>, b: List<Expense>, roles: Roles, approvals: Approvals, refusals: Refusals): List<Expense> {
    val cost = Guarded<Expense>(roles, { it.costBy }, { it.costSum() }, { _, s -> roles.organiserAt(s.by, s.at) }, refusals, "cost") { "change a cost" }
    var costed = Stamp()
    val paid = Guarded<Expense>(roles, { it.paidBy }, { it.paidSum() }, { e, s ->
        when (roles.at(s.by, s.at)) {
            Role.ORGANISER -> true
            Role.DECIDER -> approvals.coveredThen(ProposalKind.COST, e.id, s, costed)
            else -> false
        }
    }, refusals, "paid") { if (it.planned) "mark a cost not paid" else "mark a cost paid" }
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
private fun mergeTasks(a: List<Task>, b: List<Task>, roles: Roles, refusals: Refusals): List<Task> {
    val plan = Guarded<Task>(roles, { it.planBy }, { it.planSum() }, { _, s -> roles.organiserAt(s.by, s.at) }, refusals, "task") { "change the task {}" }
    var assignee: String? = null
    val done = Guarded<Task>(roles, { it.doneBy }, { it.doneSum() }, { _, s ->
        roles.organiserAt(s.by, s.at) || (s.by == assignee && roles.memberAt(s.by, s.at))
    }, refusals, "done") { if (it.done) "tick off the task {}" else "untick the task {}" }
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
private fun mergePhotos(a: List<Photo>, b: List<Photo>, roles: Roles, approvals: Approvals, expenses: List<Expense>, refusals: Refusals): List<Photo> {
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
        }.also { if (!it) refusals.add("receipt", "", Stamp(p.by, p.createdAt), "attach a receipt to a cost") }
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
 * Each change turned away is kept for the Organisers ([refusedChanges]).
 * Data still in 2.0's shape is brought up to 2.1 first ([upgradeRecordsTo21]).
 */
fun merge(a0: AppData, b0: AppData): AppData {
    val a = upgradeRecordsTo21(a0)
    val b = upgradeRecordsTo21(b0)
    val people = mergePeople(a.people, b.people)
    val roles = Roles(people)
    val refusals = Refusals(roles)
    val rooms = mergeList(a.rooms, b.rooms)
    val proposals = mergeProposals(a.proposals, b.proposals, roles, refusals)
    val approvals = Approvals(proposals)
    val expenses = mergeExpenses(a.expenses, b.expenses, roles, approvals, refusals)
    val items = keepHands(a.items, b.items, mergeItems(a.items, b.items, roles, approvals, rooms, refusals), { it.hands }) { r, h -> r.copy(hands = h) }
    val byId = items.associateBy { it.id }
    val merged = AppData(
        version = maxOf(a.version, b.version),
        move = mergeMove(a.move, b.move, roles, refusals),
        rooms = keepHands(a.rooms, b.rooms, rooms, { it.hands }) { r, h -> r.copy(hands = h) },
        items = items,
        boxes = keepHands(a.boxes, b.boxes, mergeBoxes(a.boxes, b.boxes, roles, refusals), { it.hands }) { r, h -> r.copy(hands = h) },
        photos = keepHands(a.photos, b.photos, mergePhotos(a.photos, b.photos, roles, approvals, expenses, refusals), { it.hands }) { r, h -> r.copy(hands = h) },
        tasks = mergeTasks(a.tasks, b.tasks, roles, refusals),
        expenses = expenses,
        supplies = mergeList(a.supplies, b.supplies),
        trips = mergeList(a.trips, b.trips),
        readings = mergeList(a.readings, b.readings),
        entries = keepHands(a.entries, b.entries, mergeList(a.entries, b.entries), { it.hands }) { r, h -> r.copy(hands = h) },
        people = people,
        proposals = proposals,
        activity = mergeList(a.activity, b.activity),
        hidden = mergeHidden(a.hidden, b.hidden, roles, approvals, byId, refusals),
        collect = mergeCollect(a.collect, b.collect, roles, approvals, byId, refusals),
        damage = mergeDamage(a.damage, b.damage, roles, refusals),
        lines = mergeLines(a.lines, b.lines),
    )
    val kept = keepDeletionsToTheRule(merged, roles, refusals)
    val settled = settle(kept)
    noteWhereItFollows(kept, settled, roles, refusals)
    return settled.copy(refused = refusals.into(mergeList(a.refused, b.refused)))
}

/**
 * Things in a container take its place and its stamp ([mirrorContainers]), so a merge can give a thing a stamp neither
 * copy of it had: it went into a container, or one it is in came back from a deletion that did not stand. When its
 * author had no right to that move, the next merge turns it away on the thing as well as on the container, as it does
 * whenever such a copy comes in. It is noted here, by the merge that gave the stamp, so that merging the same again
 * notes nothing new.
 */
private fun noteWhereItFollows(before: AppData, after: AppData, roles: Roles, refusals: Refusals) {
    fun check(id: String, s: Stamp, was: Stamp?, sum: String) {
        if (s != was && s.by.isNotEmpty() && s.sum == sum && !roles.memberAt(s.by, s.at)) refusals.add("where", id, s, "move {}")
    }
    if (after.items !== before.items) {
        val was = before.items.associateBy { it.id }
        for (i in after.items) if (!i.deleted && i.boxId != null) check(i.id, i.whereBy, was[i.id]?.whereBy, i.whereSum())
    }
    if (after.boxes !== before.boxes) {
        val was = before.boxes.associateBy { it.id }
        for (b in after.boxes) if (!b.deleted && b.parentId != null) check(b.id, b.whereBy, was[b.id]?.whereBy, b.whereSum())
    }
}
