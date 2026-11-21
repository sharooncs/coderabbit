package app.housemove.data

/*
 * The protected parts of the records, and the bookkeeping that lets a phone
 * check them when data arrives from another phone.
 *
 * Each protected part carries a Stamp: who set it, when, and a check value
 * over what they set. When two copies of a record are merged (see Merge.kt),
 * a part is taken from the copy with the newest stamp whose author was allowed
 * to set it at that time. A copy whose values no longer match its check value
 * was changed without a new stamp and is not accepted.
 *
 *   Item     decision  disposition, price, deadlineDay        Organiser
 *            room      destRoomId                             Organiser
 *            outcome   listed, gone, soldFor                  Organiser, or a Decider with an approved suggestion
 *   Expense  cost      title, amount, category, day, deleted  Organiser (also needed to create one)
 *            paid      planned                                Organiser, or a Decider with an approved suggestion
 *   Task     plan      everything except done                 Organiser (also needed to create one)
 *            done      done                                   Organiser, or the person it is assigned to
 *   MoveInfo settings  everything except labelsPrinted        Organiser
 *   Proposal state     state, reason                          Organiser
 */

// ---- check values ------------------------------------------------------------------

/** A short check value over the values of one protected part. */
internal fun seal(vararg parts: Any?): String {
    var h = 1125899906842597L
    for (p in parts) {
        val s = p?.toString() ?: "\u0000"
        for (ch in s) h = 31 * h + ch.code
        h = 31 * h + 0x1F
    }
    return java.lang.Long.toString(h and 0xFFFFFFFFFFL, 36)
}

internal fun Item.decisionSum() = seal(disposition, price, deadlineDay)
internal fun Item.roomSum() = seal(destRoomId)
internal fun Item.outcomeSum() = seal(listed, gone, soldFor)
internal fun Expense.costSum() = seal(title, amount, category, day, deleted)
internal fun Expense.paidSum() = seal(planned)
internal fun Task.planSum() = seal(title, offsetDays, dueDay, category, notes, assignedTo, deleted)
internal fun Task.doneSum() = seal(done)
internal fun MoveInfo.settingsSum() =
    seal(name, moveDay, originName, destName, vanCapacityM3, useInches, currency, kind, off.joinToString(","), setupDone)

internal fun Proposal.stateSum() = seal(
    state, reason, kind, targetId, by, disposition, price, roomId,
    expense?.let { seal(it.title, it.amount, it.category, it.day, it.planned) },
    task?.let { seal(it.title, it.offsetDays, it.dueDay, it.category, it.notes, it.assignedTo) },
    answers,
)

/** A part nobody has set since version 2.0: stamped as it stands, with no author. */
private fun Stamp.orAsIs(sum: String): Stamp = if (this.sum.isEmpty() && by.isEmpty()) Stamp(sum = sum) else this

internal inline fun <T> List<T>.mapChanged(f: (T) -> T): List<T> {
    var out: ArrayList<T>? = null
    for (i in indices) {
        val x = this[i]
        val y = f(x)
        if (out == null && y !== x) out = ArrayList<T>(size).also { it.addAll(subList(0, i)) }
        out?.add(y)
    }
    return out ?: this
}

internal fun Item.stamped(): Item {
    if (decisionBy.sum.isNotEmpty() && roomBy.sum.isNotEmpty() && outcomeBy.sum.isNotEmpty()) return this
    return copy(decisionBy = decisionBy.orAsIs(decisionSum()), roomBy = roomBy.orAsIs(roomSum()), outcomeBy = outcomeBy.orAsIs(outcomeSum()))
}

internal fun Expense.stamped(): Expense =
    if (costBy.sum.isNotEmpty() && paidBy.sum.isNotEmpty()) this else copy(costBy = costBy.orAsIs(costSum()), paidBy = paidBy.orAsIs(paidSum()))

internal fun Task.stamped(): Task =
    if (planBy.sum.isNotEmpty() && doneBy.sum.isNotEmpty()) this else copy(planBy = planBy.orAsIs(planSum()), doneBy = doneBy.orAsIs(doneSum()))

internal fun MoveInfo.stamped(): MoveInfo = if (changedBy.sum.isNotEmpty()) this else copy(changedBy = changedBy.orAsIs(settingsSum()))

/**
 * Bring data saved by an older version up to this one. Nothing is changed or
 * lost: every protected part that has never been stamped is stamped as it
 * stands, with no author, which is how "from before there were roles" is told
 * apart from "changed by someone who should not have".
 */
fun migrate(d: AppData): AppData = d.copy(
    version = maxOf(d.version, SCHEMA),
    move = d.move.stamped(),
    items = d.items.mapChanged { it.stamped() },
    expenses = d.expenses.mapChanged { it.stamped() },
    tasks = d.tasks.mapChanged { it.stamped() },
    people = settlePeople(d.people),
)

private fun Stamp.distrusted(): Stamp = if (sum.isEmpty()) copy(sum = "?") else this

/**
 * Data arriving from another phone on this version always has its stamps.
 * Parts that arrive without one are marked so the merge does not mistake them
 * for old data and accept them.
 */
internal fun distrustUnstamped(d: AppData): AppData = d.copy(
    move = d.move.let { m -> if (m.changedBy.sum.isEmpty()) m.copy(changedBy = m.changedBy.distrusted()) else m },
    items = d.items.mapChanged { i ->
        if (i.decisionBy.sum.isNotEmpty() && i.roomBy.sum.isNotEmpty() && i.outcomeBy.sum.isNotEmpty()) i
        else i.copy(decisionBy = i.decisionBy.distrusted(), roomBy = i.roomBy.distrusted(), outcomeBy = i.outcomeBy.distrusted())
    },
    expenses = d.expenses.mapChanged { e ->
        if (e.costBy.sum.isNotEmpty() && e.paidBy.sum.isNotEmpty()) e else e.copy(costBy = e.costBy.distrusted(), paidBy = e.paidBy.distrusted())
    },
    tasks = d.tasks.mapChanged { t ->
        if (t.planBy.sum.isNotEmpty() && t.doneBy.sum.isNotEmpty()) t else t.copy(planBy = t.planBy.distrusted(), doneBy = t.doneBy.distrusted())
    },
)

// ---- approved suggestions and the go-ahead they give -------------------------------

/** Approved suggestions, by what they are about. */
internal class Approvals(proposals: List<Proposal>) {
    private val byTarget = HashMap<String, ArrayList<Proposal>>()

    init {
        for (p in proposals) if (p.state == ProposalState.APPROVED && !p.deleted) byTarget.getOrPut(p.targetId) { ArrayList() }.add(p)
    }

    fun of(kind: ProposalKind, targetId: String): List<Proposal> = byTarget[targetId]?.filter { it.kind == kind } ?: emptyList()

    /** The approved suggestion, if any, that put [reg] on the target: the one whose approval is still what stands. */
    fun standing(kind: ProposalKind, targetId: String, reg: Stamp): Proposal? =
        byTarget[targetId]?.firstOrNull { it.kind == kind && it.decided.by == reg.by && it.decided.at == reg.at }

    /**
     * Did the person who carried something out at [done] have an approved suggestion of their own
     * for it then? [reg] is the target's decision as it stands: a decision changed after the
     * approval and before [done] had already ended the go-ahead.
     */
    fun coveredThen(kind: ProposalKind, targetId: String, done: Stamp, reg: Stamp): Boolean =
        byTarget[targetId]?.any { p ->
            p.kind == kind && p.by == done.by && p.decided.at <= done.at && !(reg.at > p.decided.at && reg.at <= done.at)
        } == true
}

/**
 * May this person carry out the decision on [item] now (mark it listed, sold,
 * donated, binned or gone, record what it sold for)? An Organiser always may.
 * A Decider may while a suggestion of theirs for this item is approved and the
 * decision has not been changed since. Nothing is stored for this: changing
 * the decision is what ends the go-ahead.
 */
fun AppData.mayCarryOut(person: Person?, item: Item): Boolean {
    if (!hasOrganiser()) return true
    if (person == null || person.removed) return false
    return when (person.role) {
        Role.ORGANISER -> true
        Role.DECIDER -> Approvals(proposals).standing(ProposalKind.ITEM_DECISION, item.id, item.decisionBy)?.by == person.id
        Role.HELPER -> false
    }
}

/** May this person mark [expense] paid or attach its receipt now? The same rule as [mayCarryOut], for costs. */
fun AppData.mayPay(person: Person?, expense: Expense): Boolean {
    if (!hasOrganiser()) return true
    if (person == null || person.removed) return false
    return when (person.role) {
        Role.ORGANISER -> true
        Role.DECIDER -> Approvals(proposals).standing(ProposalKind.COST, expense.id, expense.costBy)?.by == person.id
        Role.HELPER -> false
    }
}

/** May this person tick [task] off? An Organiser may tick any; anyone may tick one assigned to them. */
fun AppData.mayTick(person: Person?, task: Task): Boolean {
    if (!hasOrganiser()) return true
    if (person == null || person.removed) return false
    return person.role == Role.ORGANISER || task.assignedTo == person.id
}

// ---- suggestions: lists for the screens ----------------------------------------------

/** Suggestions and questions still waiting for an Organiser, oldest first. */
fun AppData.waitingProposals(): List<Proposal> =
    liveProposals.filter { it.state == ProposalState.WAITING }.sortedWith(compareBy({ it.createdAt }, { it.id }))

fun AppData.waitingCount(): Int = liveProposals.count { it.state == ProposalState.WAITING }

/** Everything [personId] has suggested or asked, newest first, whatever became of it. */
fun AppData.proposalsBy(personId: String?): List<Proposal> =
    if (personId.isNullOrEmpty()) emptyList()
    else liveProposals.filter { it.by == personId }.sortedWith(compareByDescending<Proposal> { it.createdAt }.thenBy { it.id })

/** How many of [personId]'s suggestions are in each state. States with none are left out. */
fun AppData.proposalCounts(personId: String?): Map<ProposalState, Int> = proposalsBy(personId).groupingBy { it.state }.eachCount()

/** Suggestions and questions about one item, cost or task, newest first. */
fun AppData.proposalsFor(targetId: String): List<Proposal> =
    liveProposals.filter { it.targetId == targetId }.sortedWith(compareByDescending<Proposal> { it.createdAt }.thenBy { it.id })

/** What a suggestion asks for, in a few words, e.g. "Sell Sofa for 50". */
fun AppData.describe(p: Proposal): String {
    val item = items.firstOrNull { it.id == p.targetId }?.title() ?: "an item"
    return when (p.kind) {
        ProposalKind.ITEM_DECISION ->
            (p.disposition ?: Disposition.UNDECIDED).label + " " + item + (p.price?.let { " for " + (move.currency + " " + fmtNum(it)).trim() } ?: "")
        ProposalKind.ITEM_ROOM -> item + " to " + (room(p.roomId)?.name ?: "no room")
        ProposalKind.COST -> p.expense?.let { (it.title.ifBlank { it.category.ifBlank { "Cost" } }) + " " + (move.currency + " " + fmtNum(it.amount)).trim() } ?: "A cost"
        ProposalKind.TASK -> p.task?.title?.ifBlank { null } ?: "A task"
        ProposalKind.QUESTION -> "What to do with $item?"
    }
}

// ---- keeping the data consistent after any change -------------------------------------

/**
 * Close suggestions that events have passed by. A waiting suggestion is
 * NOT_CHOSEN when another suggestion for the same thing was approved after it
 * was made, and OVERTAKEN when an Organiser changed that thing directly.
 * This is worked out from the data alone, so every phone reaches the same
 * answer whatever order the changes arrived in.
 */
internal fun closeProposals(d: AppData): AppData {
    if (d.proposals.none { it.state == ProposalState.WAITING && !it.deleted }) return d
    val approvals = Approvals(d.proposals)
    val items = HashMap<String, Item>()
    val expenses = HashMap<String, Expense>()
    val tasks = HashMap<String, Task>()
    val targets = d.proposals.filter { it.state == ProposalState.WAITING && !it.deleted }.map { it.targetId }.toSet()
    for (i in d.items) if (i.id in targets) items[i.id] = i
    for (e in d.expenses) if (e.id in targets) expenses[e.id] = e
    for (t in d.tasks) if (t.id in targets) tasks[t.id] = t
    val proposals = d.proposals.mapChanged { p ->
        if (p.state != ProposalState.WAITING || p.deleted) return@mapChanged p
        val reg: Stamp = when (p.kind) {
            ProposalKind.ITEM_DECISION, ProposalKind.QUESTION -> items[p.targetId]?.let { if (it.deleted) Stamp(at = it.updatedAt) else it.decisionBy }
            ProposalKind.ITEM_ROOM -> items[p.targetId]?.let { if (it.deleted) Stamp(at = it.updatedAt) else it.roomBy }
            ProposalKind.COST -> expenses[p.targetId]?.costBy
            ProposalKind.TASK -> tasks[p.targetId]?.planBy
        } ?: return@mapChanged p
        if (reg.at <= p.createdAt) return@mapChanged p
        val kind = if (p.kind == ProposalKind.QUESTION) ProposalKind.ITEM_DECISION else p.kind
        val chosen = approvals.standing(kind, p.targetId, reg)?.takeIf { it.id != p.id }
        val state = when {
            p.kind == ProposalKind.QUESTION -> if (chosen?.answers == p.id) ProposalState.APPROVED else ProposalState.OVERTAKEN
            chosen != null -> ProposalState.NOT_CHOSEN
            else -> ProposalState.OVERTAKEN
        }
        p.copy(state = state).let { it.copy(decided = Stamp(reg.by, reg.at, it.stateSum())) }
    }
    return if (proposals === d.proposals) d else d.copy(proposals = proposals)
}

/**
 * Things inside a container are wherever the container is. Sets the stage and
 * trip of every item in a container, and of every container inside another,
 * to those of the outermost one. [stamp] is the time to mark the changed
 * records with; null leaves their time alone (used after a merge, where both
 * phones work this out for themselves).
 */
internal fun mirrorContainers(d: AppData, stamp: Long? = null): AppData {
    if (d.items.none { it.boxId != null } && d.boxes.none { it.parentId != null }) return d
    val live = HashMap<String, Box>()
    for (b in d.boxes) if (!b.deleted) live[b.id] = b
    fun top(b: Box): Box {
        val p = b.parentId?.let { live[it] } ?: return b
        return if (p.id == b.id || (p.parentId != null && live[p.parentId] != null)) b else p
    }
    val boxes = d.boxes.mapChanged { b ->
        if (b.deleted || b.parentId == null) return@mapChanged b
        val t = top(b)
        if (t === b || (b.stage == t.stage && b.tripId == t.tripId)) b
        else b.copy(stage = t.stage, tripId = t.tripId, updatedAt = stamp ?: b.updatedAt)
    }
    val items = d.items.mapChanged { i ->
        if (i.deleted || i.boxId == null) return@mapChanged i
        val t = live[i.boxId]?.let { top(it) } ?: return@mapChanged i
        if (i.stage == t.stage && i.tripId == t.tripId) i else i.copy(stage = t.stage, tripId = t.tripId, updatedAt = stamp ?: i.updatedAt)
    }
    return if (boxes === d.boxes && items === d.items) d else d.copy(boxes = boxes, items = items)
}

/** How many lines of the activity log are kept. Older ones are dropped on every phone alike. */
const val ACTIVITY_KEEP = 300

private val ACTIVITY_ORDER = compareByDescending<Activity> { it.at }.thenBy { it.id }

internal fun pruneActivity(list: List<Activity>): List<Activity> =
    if (list.size <= ACTIVITY_KEEP) list else list.sortedWith(ACTIVITY_ORDER).take(ACTIVITY_KEEP)

/** The latest [n] things anyone did, newest first. */
fun AppData.latestActivity(n: Int = 20): List<Activity> = activity.filter { !it.deleted }.sortedWith(ACTIVITY_ORDER).take(n)

/** "Sam sealed L012", for a line of the log. */
fun AppData.line(a: Activity): String = (personName(a.by).ifBlank { "Someone" } + " " + a.text).trim()

/** Everything that follows from the records themselves, applied after a merge or a batch of changes. */
internal fun settle(d: AppData): AppData = mirrorContainers(closeProposals(d)).let { s ->
    val log = pruneActivity(s.activity)
    if (log === s.activity) s else s.copy(activity = log)
}
