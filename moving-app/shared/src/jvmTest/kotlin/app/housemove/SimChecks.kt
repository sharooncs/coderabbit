package app.housemove

import app.housemove.data.*
import java.io.File

/**
 * Who had which role when, worked out afresh from the grants on the people.
 * This is the test's own reading of the rule, kept apart from the app's Roles
 * class so that the two can disagree: a grant counts when whoever made it was
 * an Organiser at that moment, and a grant in nobody's name counts when it is
 * how the person first appears or when the move had no Organiser. The last
 * Organiser cannot be made anything else while others are in the move.
 */
internal class RoleBook(people: Collection<Person>) {
    private class Step(val at: Long, val role: Role, val out: Boolean) {
        val runsTheMove get() = role == Role.ORGANISER && !out
    }

    private val steps = HashMap<String, ArrayList<Step>>()

    init {
        val grants = people.flatMap { p -> p.grants.map { p.id to it } }
            .sortedWith(compareBy({ it.second.at }, { it.first }, { it.second.by }, { it.second.role.ordinal }, { it.second.removed }))
        var organisers = 0
        for ((id, g) in grants) {
            val had = steps[id]
            val allowed = if (g.by.isEmpty()) had == null || organisers == 0 else steps[g.by]?.lastOrNull()?.runsTheMove == true
            if (!allowed) continue
            val step = Step(g.at, g.role, g.removed)
            val ranIt = had?.lastOrNull()?.runsTheMove == true
            // Nobody takes the last Organiser away while others are in the move.
            if (ranIt && !step.runsTheMove && organisers == 1 && steps.any { it.key != id && !it.value.last().out }) continue
            organisers += (if (step.runsTheMove) 1 else 0) - (if (ranIt) 1 else 0)
            steps.getOrPut(id) { ArrayList() } += step
        }
    }

    /** The role someone had at [time]; null when they were not in the move then. */
    fun at(id: String, time: Long): Role? = steps[id]?.lastOrNull { it.at <= time }?.takeIf { !it.out }?.role

    /** Role and whether removed, as it stands; null for someone nobody had the right to add. */
    fun now(id: String): Pair<Role, Boolean>? = steps[id]?.lastOrNull()?.let { it.role to it.out }

    fun inMove(id: String): Boolean = now(id)?.second == false

    /** Had this person, by [time], at some point been removed or moved down a role, and then got back to where they are? */
    fun regained(id: String, time: Long): Boolean {
        val mine = steps[id]?.filter { it.at <= time } ?: return false
        fun rights(s: Step) = if (s.out) 0 else 3 - s.role.ordinal
        return rights(mine.lastOrNull() ?: return false) > 0 && mine.zipWithNext().any { (a, b) -> rights(b) < rights(a) }
    }
    fun anyOrganiser(time: Long): Boolean = steps.keys.any { at(it, time) == Role.ORGANISER }
    fun onlyEver(id: String, role: Role): Boolean = steps[id]?.all { it.role == role } == true
}

/** Whether each protected part of a record was set by someone allowed to set it, given everything in [d]. */
internal class Judge(val d: AppData) {
    val book = RoleBook(d.people)
    private val approved = d.proposals.filter { it.state == ProposalState.APPROVED && !it.deleted }

    private fun organiser(s: Stamp) = book.at(s.by, s.at) == Role.ORGANISER

    /** A part in somebody's name needs that person to have had the right; one in nobody's name needs there to have been nobody to ask. */
    private fun set(s: Stamp, allowed: () -> Boolean) = if (s.by.isEmpty()) s.at == 0L || !book.anyOrganiser(s.at) else allowed()

    /** Someone who suggested this, had it approved before [done], and did it before the decision ([standing]) changed again. */
    private fun goAhead(kind: ProposalKind, target: String, done: Stamp, standing: Stamp) = approved.any { p ->
        p.kind == kind && p.targetId == target && p.by == done.by && p.decided.at <= done.at && !(standing.at > p.decided.at && standing.at <= done.at)
    }

    private fun carriedOut(kind: ProposalKind, target: String, s: Stamp, standing: Stamp) = when (book.at(s.by, s.at)) {
        Role.ORGANISER -> true
        Role.DECIDER -> goAhead(kind, target, s, standing)
        else -> false
    }

    fun decision(i: Item) = i.decisionBy.sum == i.decisionSum() && set(i.decisionBy) { organiser(i.decisionBy) }
    fun room(i: Item) = i.roomBy.sum == i.roomSum() && set(i.roomBy) { organiser(i.roomBy) }
    fun outcome(i: Item, decided: Stamp) = i.outcomeBy.sum == i.outcomeSum() && set(i.outcomeBy) { carriedOut(ProposalKind.ITEM_DECISION, i.id, i.outcomeBy, decided) }
    fun cost(e: Expense) = e.costBy.sum == e.costSum() && set(e.costBy) { organiser(e.costBy) }
    fun paid(e: Expense, costed: Stamp) = e.paidBy.sum == e.paidSum() && set(e.paidBy) { carriedOut(ProposalKind.COST, e.id, e.paidBy, costed) }
    fun plan(t: Task) = t.planBy.sum == t.planSum() && set(t.planBy) { organiser(t.planBy) }
    fun done(t: Task, assignee: String?) =
        t.doneBy.sum == t.doneSum() && set(t.doneBy) { organiser(t.doneBy) || (t.doneBy.by == assignee && book.at(t.doneBy.by, t.doneBy.at) != null) }

    fun settings(m: MoveInfo) = m.changedBy.sum == m.settingsSum() && set(m.changedBy) { organiser(m.changedBy) }
    fun state(p: Proposal): Boolean {
        if (p.decided.sum != p.stateSum()) return false
        val settled = p.state == ProposalState.APPROVED || p.state == ProposalState.DECLINED
        return if (p.decided.by.isEmpty()) !settled || set(p.decided) { false } else organiser(p.decided)
    }
    fun regained(id: String, time: Long) = book.regained(id, time)

    fun author(p: Proposal): Boolean {
        val role = book.at(p.by, p.createdAt) ?: return false
        return role != Role.HELPER || p.kind == ProposalKind.QUESTION
    }

    fun receipt(p: Photo): Boolean {
        if (p.kind != PhotoKind.RECEIPT || p.by.isEmpty()) return true
        val cost = d.expenses.firstOrNull { it.id == p.ownerId } ?: return true
        return carriedOut(ProposalKind.COST, cost.id, Stamp(p.by, p.createdAt), cost.costBy)
    }

    /**
     * The test's own reading of who else has had a hand in [x]: its own hands, the people behind
     * photos of it, questions and suggestions about it, and (for a container or a room) what is in it.
     * What a Helper's phone never holds is left out, as the rule says. Blank stands for someone unknown.
     */
    fun others(x: Owned): Set<String> {
        val out = HashSet<String>()
        fun people(of: Owned) = (of.hands + of.createdBy).filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        out += x.hands.filter { it.isNotEmpty() }
        for (p in d.photos) if (!p.deleted && p.ownerId == x.id && p.id != x.id && p.kind !in HIDDEN_KINDS) out += people(p)
        for (p in d.proposals) if (!p.deleted && p.targetId == x.id && p.kind != ProposalKind.COST && !p.deletes) out += p.by
        if (x is Box) {
            for (i in d.items) if (!i.deleted && i.boxId == x.id) out += people(i)
            for (b in d.boxes) if (!b.deleted && b.parentId == x.id && b.id != x.id) out += people(b)
            for (e in d.entries) if (!e.deleted && e.boxId == x.id) out += e.hands.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        }
        if (x is Room) {
            for (i in d.items) if (!i.deleted && (i.originRoomId == x.id || i.destRoomId == x.id)) out += people(i)
            for (b in d.boxes) if (!b.deleted && (b.originRoomId == x.id || b.destRoomId == x.id)) out += people(b)
            for (r in d.rooms) if (!r.deleted && r.mapsTo == x.id && r.id != x.id) out += people(r)
        }
        out.remove(x.createdBy)
        return out
    }

    /**
     * May [x] be deleted, as it stands: by an Organiser then, or by whoever added it while nobody else had a hand in it.
     * [alsoHands] are hands the other phone's copy of it knew of: a merge keeps those too.
     */
    fun gone(x: Owned, alsoHands: Collection<String> = emptyList()): Boolean {
        if (alsoHands.any { it.isNotEmpty() && it != x.createdBy } && x.deleted && x.deletedBy.by.isNotEmpty() && book.at(x.deletedBy.by, x.deletedBy.at).let { it != null && it != Role.ORGANISER }) return false
        return goneAsIs(x)
    }

    private fun goneAsIs(x: Owned): Boolean {
        if (!x.deleted) return true
        val s = x.deletedBy
        if (s.by.isEmpty()) return (s.sum.isEmpty() || s.sum == seal(true)) && (s.at == 0L || !book.anyOrganiser(s.at))
        if (s.sum != seal(true)) return false
        return when (book.at(s.by, s.at)) {
            Role.ORGANISER -> true
            null -> false
            else -> s.by == x.createdBy && others(x).isEmpty()
        }
    }

    /**
     * A suggestion that is deleted was withdrawn by whoever made it, and was not approved or declined; or it was made by
     * someone who could not make it, which is kept deleted rather than dropped ([leftOut]).
     */
    fun withdrawal(p: Proposal): Boolean =
        !p.deleted || !author(p) || (p.withdrawn.by == p.by && p.by.isNotEmpty() && p.state != ProposalState.APPROVED && p.state != ProposalState.DECLINED)

    /** A suggestion made by someone who could not make it is in no list: it is kept, deleted, so that news that they could can bring it back. */
    fun leftOut(p: Proposal): Boolean = author(p) || p.deleted
}

// ---- the parts of a record --------------------------------------------------------------

/** A record without its protected parts and without what merely follows from other records. */
internal fun plain(r: Any): Any = when (r) {
    // Hands are what both copies knew of together, and whether it is deleted is a part of its own ("gone").
    // Since 2.1 where something is (stage and trip) is a protected part of its own, and the damage and the contents are
    // worked out from their own records, so none of them is part of the plain record.
    is Item -> r.copy(
        disposition = Disposition.UNDECIDED, price = null, deadlineDay = null, decisionBy = Stamp(), destRoomId = null, roomBy = Stamp(),
        listed = false, gone = false, soldFor = null, outcomeBy = Stamp(),
        stage = Stage.AT_ORIGIN, tripId = null, whereBy = Stamp(), handoverTo = null, paid = false, handoverBy = Stamp(), handedOver = false, handedOverBy = Stamp(),
        damaged = false, damageNote = "",
        hands = emptyList(), deleted = false, deletedBy = Stamp(),
    )
    is Box -> r.copy(stage = Stage.AT_ORIGIN, tripId = null, whereBy = Stamp(), contents = "", damaged = false, damageNote = "", hands = emptyList(), deleted = false, deletedBy = Stamp())
    is Hidden -> Hidden(id = r.id, updatedAt = r.updatedAt)
    is Collect -> Collect(id = r.id, updatedAt = r.updatedAt)
    is Damage -> r.copy(cleared = false, clearedBy = Stamp())
    is Room -> r.copy(hands = emptyList(), deleted = false, deletedBy = Stamp())
    is Photo -> r.copy(hands = emptyList(), deleted = false, deletedBy = Stamp())
    is BoxEntry -> r.copy(hands = emptyList())
    is Task -> Task(id = r.id, title = "", updatedAt = r.updatedAt)
    is Expense -> Expense(id = r.id, updatedAt = r.updatedAt)
    is Proposal -> r.copy(state = ProposalState.WAITING, reason = "", decided = Stamp(), deleted = false, withdrawn = Stamp())
    is Person -> r.copy(role = Role.HELPER, removed = false, grants = emptyList(), deleted = false)
    is MoveInfo -> r.updatedAt
    else -> r
}

/** The protected parts of a record, each as (name, what was set and by whom). */
internal fun parts(r: Any): List<Pair<String, String>> = when (r) {
    is Item -> listOf(
        "decision" to "${r.disposition} ${r.price} ${r.deadlineDay} by ${r.decisionBy}",
        "room" to "${r.destRoomId} by ${r.roomBy}",
        "outcome" to "${r.listed} ${r.gone} ${r.soldFor} by ${r.outcomeBy}",
        "gone" to "${r.deleted} by ${r.deletedBy}",
    )
    is Box -> listOf("gone" to "${r.deleted} by ${r.deletedBy}")
    is Room -> listOf("gone" to "${r.deleted} by ${r.deletedBy}")
    is Photo -> listOf("gone" to "${r.deleted} by ${r.deletedBy}")
    is Task -> listOf(
        "plan" to "${r.title}|${r.offsetDays}|${r.dueDay}|${r.category}|${r.notes}|${r.assignedTo}|${r.deleted} by ${r.planBy}",
        "done" to "${r.done} by ${r.doneBy}",
    )
    is Expense -> listOf("cost" to "${r.title}|${r.amount}|${r.category}|${r.day}|${r.deleted} by ${r.costBy}", "paid" to "${r.planned} by ${r.paidBy}")
    is MoveInfo -> listOf("settings" to r.copy(labelsPrinted = 0, updatedAt = 0).toString())
    is Proposal -> listOf("state" to "${r.state} ${r.reason} by ${r.decided}")
    else -> emptyList()
}

private fun stampOf(r: Any, part: String): Stamp = when (r) {
    is Owned -> if (part == "gone") r.deletedBy else if (r is Item) when (part) { "decision" -> r.decisionBy; "room" -> r.roomBy; else -> r.outcomeBy } else Stamp()
    is Task -> if (part == "plan") r.planBy else r.doneBy
    is Expense -> if (part == "cost") r.costBy else r.paidBy
    is MoveInfo -> r.changedBy
    is Proposal -> r.decided
    else -> Stamp()
}

/** How a part looks when the merge found nobody with the right to have set it. */
private fun isReset(r: Any, part: String): Boolean {
    val s = stampOf(r, part)
    if (s.by.isNotEmpty() || s.at != 0L) return false
    // A deletion that did not stand is undone; one a phone made of its own things before it joined goes in as from before roles.
    if (part == "gone") return true
    return when (r) {
        is Item -> when (part) {
            "decision" -> r.disposition == Disposition.UNDECIDED && r.price == null && r.deadlineDay == null
            "room" -> true
            else -> !r.listed && !r.gone && r.soldFor == null
        }
        is Task -> part == "done" && !r.done
        is Expense -> part == "paid" && r.planned
        else -> false
    }
}

private fun when_(r: Any): Long = if (r is MoveInfo) r.updatedAt else (r as Rec).updatedAt

private val HIDDEN_KINDS = setOf(PhotoKind.RECEIPT, PhotoKind.SERIAL)

/** Is this a record a Helper's phone is not sent? */
internal fun hidden(r: Any): Boolean = r is Expense || (r is Photo && r.kind in HIDDEN_KINDS) || (r is Proposal && r.kind == ProposalKind.COST) ||
    r is Hidden || r is Collect || r is RefusedChange

/**
 * The invariants. Each failure is noted on the simulation with the records it
 * is about; nothing here throws, so that one run reports everything it found.
 */
internal class SimChecks(private val sim: Simulation) {
    companion object {
        const val SAME = "Every phone in the move holds the same data"
        const val LOST = "No accepted change was lost (the last writer wins)"
        const val MERGE = "Every single merge kept the newer copy and only what the rules allow"
        const val RULES = "No decision, approval, role change, settings change or carry-out stands that its author's role did not allow"
        const val HELPERS = "Helpers were never sent costs, cost suggestions, receipts or serial-label photos"
        const val COUNTED = "Nothing is counted twice or lost, and progress agrees on every phone"
        const val NESTING = "Containers go one level deep with no loops, and nothing is inside a deleted container"
        const val LETTERS = "No two people have the same letter"
        const val CODES = "The app never gave out the same handwritten code twice"
        const val PHOTOS = "Photo files arrived whole on every phone that should have them"
        const val TWICE = "A label used on two things is flagged on every phone"
        const val DISK = "The data on disk loads again into the same state, in every move on the phone"
        val ALL = listOf(
            Simulation.RUN, SAME, LOST, MERGE, RULES, HELPERS, COUNTED, NESTING, LETTERS, CODES, TWICE, PHOTOS, DISK, Simulation.OUTSIDERS, SimUpdates.UPDATES,
            Simulation.MOVES_APART, Simulation.CROSS, Simulation.LETTERS_APART, Simulation.SCANNED,
        )
    }

    private fun broke(invariant: String, message: String, vararg ids: String?) = sim.broke(invariant, message, ids.toList())
    /** Something worth counting that is not a failure. With HM_SIM_STRICT set it is reported as one, with [detail], to look into. */
    private fun note(what: String, detail: String = "", vararg ids: String?) {
        synchronized(sim.stats) { sim.stats.notes.merge(what, 1, Int::plus) }
        if (System.getenv("HM_SIM_STRICT") != null && detail.isNotEmpty()) sim.broke("(looking closer) $what", detail, ids.toList())
    }
    private fun short(r: Any?) = r.toString().take(700)

    /** Where two copies of a record differ, field by field. */
    private fun differ(a: Any?, b: Any?): String {
        val x = a.toString().split(", ")
        val y = b.toString().split(", ")
        if (a == null || b == null || x.size != y.size) return "${short(a)}\n      against: ${short(b)}"
        return x.indices.filter { x[it] != y[it] }.joinToString("; ") { "${x[it]} against ${y[it]}" }.take(900)
    }

    // ---- at the end of a run ---------------------------------------------------------------

    fun all() {
        val c = sim.community
        if (c.isEmpty()) return broke(Simulation.RUN, "no two phones could sync at the end")
        val book = RoleBook(c[0].data.people)
        for (p in sim.phones) {
            val now = book.now(p.personId)
            val inMove = now != null && !now.second
            if (p in c && !inMove) broke(SAME, "${p.tag} still syncs with the move although its person is ${if (now == null) "not in it" else "removed"}" + sim.lately(p), p.personId)
            if (p !in c && inMove) {
                // A split move: this phone holds every phone of the group to be removed (two Organisers removed each other, and each
                // phone sided with the one it heard first), so it turns them all away and never hears the other side. Counted and reported.
                val shutOut = c.all { q -> p.data.person(q.personId)?.removed ?: true }
                if (shutOut) note("phones left outside at the end because the move had split: each side had removed the other, and they no longer sync")
                else broke(SAME, "${p.tag} is in the move as ${now!!.first.label} but could not sync with the others" + sim.lately(p, 14), p.personId)
            }
        }
        val full = c.filter { book.now(it.personId)?.first.let { r -> r != null && r != Role.HELPER } }
        val ref = full.firstOrNull() ?: c[0]
        same(c, full, ref)
        lost(c, full, ref)
        rules(ref.data, full.isNotEmpty())
        helpers(c, book)
        for (p in sim.phones) counted(p)
        agree(c)
        letters(ref.data)
        codes()
        for (p in sim.phones) twice(p)
        photos(c, full)
        outsider()
        apart()
        disk()
    }

    // ---- the other moves on the phones ----------------------------------------------------------

    /**
     * Every phone's moves are kept apart: one card per move, and no record (people included) or photo file of one move in
     * another, on that phone or across the phones, but for what a phone brought from its own move into Maple Road when it
     * joined. In each move nobody shares a letter; each phone writes with a letter of its own in each of its moves and prints
     * from label numbers of its own, apart from those of its other moves.
     *
     * A few kinds of record take their ID from the thing they belong to, not a new one: an item's [Hidden] details and its
     * amount to collect ([Collect]) have the item's ID, and a line of a box's contents ([ContentsLine]) is the box's ID and
     * its words. A thing that was brought is in both moves under the same ID, so such a record made for it later in each
     * move on its own has the same key in both. That is two records, one per move, not one record in two moves, and is
     * not counted; such records of anything that was not brought are.
     */
    private fun apart() {
        val brought = sim.phones.flatMap { it.brought }.toSet()
        val broughtFiles = sim.phones.flatMap { it.broughtFiles }.toSet()
        fun ofBrought(k: String): Boolean {
            val id = k.substringAfter(':')
            return when (k.substringBefore(':')) {
                "hidden", "collect" -> "item:$id" in brought
                "line" -> "box:${id.substringBefore('/')}" in brought
                else -> false
            }
        }
        // Every record and photo file, by the moves it is in on any phone.
        val where = HashMap<String, HashSet<String>>()
        val files = HashMap<String, HashSet<String>>()
        for (p in sim.phones) {
            val stores = p.onUi { p.stores() }
            val ids = stores.map { it.moveId }
            if (ids.toSet().size != ids.size || stores.any { a -> stores.any { b -> a !== b && a.identity.value.matches(b.identity.value) } }) {
                broke(Simulation.MOVES_APART, "${p.tag} has more than one card for the same move: ${p.moves.cards.value.map { "${it.name} ${it.moveId.take(8)}" }}")
            }
            if (stores.none { it === p.store }) broke(Simulation.MOVES_APART, "${p.tag} no longer has Maple Road in its list of moves")
            for (s in stores) {
                val move = s.identity.value.id
                for ((k, _) in s.data.value.records()) where.getOrPut(k) { HashSet() } += move
                for (f in s.photoDir.list() ?: emptyArray()) if (!f.endsWith(".part")) files.getOrPut(f) { HashSet() } += move
                if (s !== p.store) for (f in s.photoDir.listFiles() ?: emptyArray<File>()) {
                    if (f.name.endsWith(".part")) broke(PHOTOS, "${p.tag} was left with the half-received file ${f.name} in ${s.data.value.move.name}")
                    val length = sim.born[f.name]
                    if (length != null && !wholeOrSmall(f, length)) broke(PHOTOS, "${p.tag} has ${f.name} with ${f.length()} bytes instead of $length in ${s.data.value.move.name}")
                }
                if (s !== p.store) letters(s.data.value, " in ${s.data.value.move.name} on ${p.tag}")
            }
            // What this phone itself writes with and prints from, in each of its moves.
            val own = stores.map { s -> Triple(s.data.value.move.name.ifBlank { s.moveId.take(8) }, s.device.value.letter.uppercase(), s.labelRange()) }
            val selves = stores.map { it.device.value.personId }
            for ((i, a) in own.withIndex()) for (b in own.drop(i + 1)) {
                if (a.second.isNotBlank() && a.second == b.second) {
                    broke(Simulation.LETTERS_APART, "${p.tag} writes with the letter ${a.second} both in ${a.first} and in ${b.first}" + sim.lately(p), *selves.toTypedArray())
                }
                if (!a.third.isEmpty() && !b.third.isEmpty() && a.third.first <= b.third.last && b.third.first <= a.third.last) {
                    broke(Simulation.LETTERS_APART, "${p.tag} prints labels ${a.third} in ${a.first} and ${b.third} in ${b.first}" + sim.lately(p), *selves.toTypedArray())
                }
            }
        }
        fun named(ids: Collection<String>) = ids.map { sim.others[it] ?: if (it == sim.phones[0].store.moveId) "Maple Road" else it.take(8) }
        var shown = 0
        for ((k, moves) in where.toSortedMap()) {
            if (moves.size > 1 && k !in brought && !ofBrought(k) && shown++ < 4) broke(Simulation.MOVES_APART, "$k is in more than one move: ${named(moves)}", k.substringAfter(':'))
        }
        shown = 0
        for ((f, moves) in files.toSortedMap()) {
            if (moves.size > 1 && f !in broughtFiles && shown++ < 4) broke(Simulation.MOVES_APART, "the photo file $f is in more than one move: ${named(moves)}")
        }
    }

    /** Labels used on more than one live thing, worked out afresh, against what the app flags. */
    private fun twice(p: Simulation.Phone) {
        val d = p.data
        val mine = (d.boxes.filter { !it.deleted }.map { it.label } + d.items.filter { !it.deleted }.mapNotNull { it.label })
            .filter { it.isNotBlank() }.groupBy { it.uppercase() }.filter { it.value.size > 1 }.keys.sorted()
        if (d.duplicateLabels() != mine) broke(TWICE, "${p.tag} flags ${d.duplicateLabels()} as used twice, but ${mine} are")
        if (mine.isNotEmpty() && d.issues().none { it.text.startsWith("Label used twice") && it.severe }) broke(TWICE, "${p.tag} has labels used twice (${mine}) but does not say so")
    }

    /** The phone outside the move holds nothing of it: none of its records, and none of its people. */
    private fun outsider() {
        val x = sim.outsider.data.value
        val theirs = HashSet<String>()
        for (p in sim.phones) for ((k, _) in p.data.records()) theirs += k
        val got = x.records().map { it.first }.filter { it in theirs }.toList()
        if (got.isNotEmpty()) broke(Simulation.OUTSIDERS, "the phone outside the move holds ${got.size} of the move's records, first ${got.first()}")
        if (x.move.name.isNotEmpty() && sim.phones.any { it.data.move.name == x.move.name }) broke(Simulation.OUTSIDERS, "the phone outside the move holds the move's settings")
    }

    private fun canon(d: AppData, whole: Boolean): Map<String, Any> {
        val out = java.util.TreeMap<String, Any>()
        for ((k, r) in d.records()) if (whole || !hidden(r)) out[k] = r
        out[Simulation.MOVE] = d.move
        return out
    }

    private fun same(c: List<Simulation.Phone>, full: List<Simulation.Phone>, ref: Simulation.Phone) {
        for (p in c) {
            if (p === ref) continue
            val whole = p in full && ref in full
            val a = canon(ref.data, whole)
            val b = canon(p.data, whole)
            var shown = 0
            for (k in (a.keys + b.keys).toSortedSet()) {
                if (a[k] == b[k]) continue
                if (shown++ < 3) broke(SAME, "${ref.tag} and ${p.tag} differ at $k: ${differ(a[k], b[k])}", k.substringAfter(':'))
            }
        }
    }

    private fun lost(c: List<Simulation.Phone>, full: List<Simulation.Phone>, ref: Simulation.Phone) {
        val end = HashMap<String, Any>()
        for ((k, r) in ref.data.records()) end[k] = r
        end[Simulation.MOVE] = ref.data.move
        val judge = Judge(ref.data)
        val costs = sim.versions.keys.filter { it.startsWith("expense:") }.map { it.substringAfter(':') }.toSet()
        val oldestKept = ref.data.activity.minOfOrNull { it.at } ?: 0
        for (k in c.flatMap { it.held.keys }.toSortedSet()) {
            val id = k.substringAfter(':')
            val written = sim.versions[k] ?: emptyList<Any>()
            if (written.any { hidden(it) } && full.isEmpty()) continue // only Helpers' phones are left, and they are not sent these
            val type = k.substringBefore(':')
            val stamps = c.flatMap { it.held[k] ?: emptySet() }.toSet()
            // A cost or a task is nothing but its protected parts. A version written by someone who, as it turned out, had no right to
            // (their phone had not heard that they had been removed or made a Helper) is rightly not kept, so it does not count here.
            val counted = written.filter { w -> when_(w) in stamps && when (type) { "task" -> judge.plan(w as Task); "expense" -> judge.cost(w as Expense); else -> true } }
            val newest = (if (type == "task" || type == "expense") counted.maxOfOrNull { when_(it) } else stamps.maxOrNull()) ?: -1
            val last = written.filter { when_(it) == newest }
            // The one way a rightful cost or task can still go: the only phones that had it took a later change to it in good faith,
            // from someone whose removal or demotion they had not heard of. When they hear, that change does not count, and the
            // version before it is on no phone any more. Counted and reported, not a failure of the merge.
            val overwritten = (type == "task" || type == "expense") && written.any { w -> when_(w) > newest && !(if (w is Task) judge.plan(w) else judge.cost(w as Expense)) }
            // The other way: whoever made it had been removed or demoted and then let back in or promoted again before making it, and the
            // news of the first reached a phone before the news of the second. That phone, seeing no right, dropped it; so then did the
            // author's own phone on hearing the same half of the story. Also counted and reported. Not so for a suggestion: one whose
            // author seems to have had no right is kept, out of every list, and stands again once the rest of the news arrives, so
            // a suggestion is never lost to the order in which news of roles arrives (nor to a role change by someone who had already
            // lost the right to make it, which never counts).
            val lateGoodNews = when (val w = last.firstOrNull()) {
                is Task -> judge.regained(w.planBy.by, w.planBy.at)
                is Expense -> judge.regained(w.costBy.by, w.costBy.at)
                else -> false
            }
            val rec = end[k]
            if (rec == null) {
                val mayBeGone = when (type) {
                    // Left out by the rules: nobody with the right to make it ever did.
                    "task", "expense" -> counted.isEmpty()
                    "proposal" -> written.isNotEmpty() && written.all { !judge.author(it as Proposal) }
                    "person" -> judge.book.now(id) == null
                    "photo" -> written.isNotEmpty() && written.all { it is Photo && it.kind == PhotoKind.RECEIPT && it.ownerId in costs && it.by.isNotEmpty() && judge.book.at(it.by, it.createdAt) != Role.ORGANISER }
                    "activity" -> ref.data.activity.size >= ACTIVITY_KEEP && written.all { (it as Activity).at <= oldestKept }
                    else -> false
                }
                when {
                    mayBeGone -> Unit
                    overwritten -> note("costs or tasks lost behind a later change by someone who, it turned out, had been removed or demoted by then", "$k: ${short(last.firstOrNull())}", id)
                    lateGoodNews -> note(
                        "costs or tasks lost because the news that their author was back in the move (or an Organiser again) reached the phones after the news that they were not",
                        "$k: ${short(last.firstOrNull())}", id,
                    )
                    else -> broke(LOST, "$k was written (last at $newest) and is not in the move at the end: ${short(last.firstOrNull() ?: written.lastOrNull())}", id)
                }
                continue
            }
            val stamp = when_(rec)
            if ((type == "task" || type == "expense") && stamp > newest) continue // the stamp of a version that was not kept; its parts are checked below by the rules
            if (stamp < newest && overwritten) {
                note("costs or tasks lost behind a later change by someone who, it turned out, had been removed or demoted by then")
                continue
            }
            if (stamp < newest && lateGoodNews && (type == "task" || type == "expense")) {
                note("costs or tasks lost because the news that their author was back in the move (or an Organiser again) reached the phones after the news that they were not")
                continue
            }
            if (stamp < newest) {
                broke(LOST, "$k was written at $newest, but the move ended with the copy from $stamp\n      written: ${short(last.firstOrNull())}\n      at the end: ${short(rec)}", id)
                continue
            }
            if (last.isEmpty()) {
                broke(LOST, "$k ended with a copy stamped $stamp that no phone wrote: ${short(rec)}", id)
                continue
            }
            if (last.none { plain(it) == plain(rec) }) {
                broke(LOST, "$k ended differently from what was written at $stamp\n      written: ${short(last.first())}\n      at the end: ${short(rec)}", id)
            }
            // Each protected part is one that somebody really set (or the blank the merge leaves when nobody had the right).
            for ((part, value) in parts(rec)) {
                if (rec is Proposal && (rec.kind == ProposalKind.QUESTION || rec.state !in listOf(ProposalState.APPROVED, ProposalState.DECLINED))) continue
                if (isReset(rec, part)) continue
                if (written.none { w -> parts(w).any { it.first == part && it.second == value } }) {
                    broke(LOST, "the $part of $k at the end is something nobody set: $value", id)
                }
            }
        }
        val printed = c.maxOf { it.labelsSeen }
        if (ref.data.move.labelsPrinted != printed) broke(LOST, "labels were printed up to $printed, but the move ended at ${ref.data.move.labelsPrinted}")
    }

    /** The rules, checked on what stands at the end, with the roles people had at the time of each stamp. */
    private fun rules(d: AppData, costsToo: Boolean) {
        val j = Judge(d)
        fun who(s: Stamp) = "${s.by.take(8)} (${j.book.at(s.by, s.at)?.label ?: "not in the move"} at the time)"
        fun unsigned(s: Stamp) = s.by.isEmpty() && s.at > 0 && j.book.anyOrganiser(s.at)
        for (i in d.items) {
            if (!j.decision(i)) broke(RULES, "the decision ${i.disposition} on item ${i.id.take(8)} was set by ${who(i.decisionBy)}", i.id, i.decisionBy.by)
            if (unsigned(i.decisionBy)) broke(RULES, "the decision ${i.disposition} on item ${i.id.take(8)} carries nobody's name, though the move had an Organiser then", i.id)
            if (!j.room(i)) broke(RULES, "the room of item ${i.id.take(8)} was set by ${who(i.roomBy)}", i.id, i.roomBy.by)
            if (unsigned(i.roomBy)) broke(RULES, "the room of item ${i.id.take(8)} carries nobody's name, though the move had an Organiser then", i.id)
            if (!j.outcome(i, i.decisionBy)) broke(RULES, "item ${i.id.take(8)} was marked listed=${i.listed} gone=${i.gone} by ${who(i.outcomeBy)}", i.id, i.outcomeBy.by)
            if (unsigned(i.outcomeBy)) broke(RULES, "the outcome of item ${i.id.take(8)} carries nobody's name, though the move had an Organiser then", i.id)
            if (i.decisionBy.by.isEmpty() && i.decisionBy.at == 0L && (i.disposition != Disposition.UNDECIDED || i.price != null)) broke(RULES, "item ${i.id.take(8)} is ${i.disposition} with no decision on record", i.id)
            if (i.outcomeBy.by.isEmpty() && i.outcomeBy.at == 0L && (i.listed || i.gone || i.soldFor != null)) broke(RULES, "item ${i.id.take(8)} is listed or gone with nobody having done it", i.id)
            // Stricter than the app can be: it only keeps the latest decision, so it cannot see one that came and went in between.
            val s = i.outcomeBy
            if (s.by.isNotEmpty() && j.book.at(s.by, s.at) == Role.DECIDER) {
                val changes = (sim.versions["item:" + i.id] ?: emptyList<Any>()).map { (it as Item).decisionBy }.filter { it.by.isNotEmpty() && j.book.at(it.by, it.at) == Role.ORGANISER }.map { it.at }
                val stillStood = d.proposals.any { p ->
                    p.state == ProposalState.APPROVED && p.kind == ProposalKind.ITEM_DECISION && p.targetId == i.id && p.by == s.by && p.decided.at <= s.at && changes.none { it > p.decided.at && it <= s.at }
                }
                if (!stillStood) note("carry-outs by a Decider that stand although the decision had changed in between (the app keeps only the latest decision)")
            }
        }
        for (t in d.tasks) {
            if (!j.plan(t)) broke(RULES, "task ${t.id.take(8)} was planned by ${who(t.planBy)}", t.id, t.planBy.by)
            if (unsigned(t.planBy)) broke(RULES, "task ${t.id.take(8)} carries nobody's name, though the move had an Organiser then", t.id)
            if (!j.done(t, t.assignedTo)) broke(RULES, "task ${t.id.take(8)} (given to ${t.assignedTo?.take(8)}) was ticked by ${who(t.doneBy)}", t.id, t.doneBy.by)
            if (unsigned(t.doneBy)) broke(RULES, "the tick on task ${t.id.take(8)} carries nobody's name, though the move had an Organiser then", t.id)
        }
        if (costsToo) {
            for (e in d.expenses) {
                if (!j.cost(e)) broke(RULES, "cost ${e.id.take(8)} was set by ${who(e.costBy)}", e.id, e.costBy.by)
                if (unsigned(e.costBy)) broke(RULES, "cost ${e.id.take(8)} carries nobody's name, though the move had an Organiser then", e.id)
                if (!j.paid(e, e.costBy)) broke(RULES, "cost ${e.id.take(8)} was marked paid=${!e.planned} by ${who(e.paidBy)}", e.id, e.paidBy.by)
                if (unsigned(e.paidBy)) broke(RULES, "whether cost ${e.id.take(8)} is paid carries nobody's name, though the move had an Organiser then", e.id)
            }
            for (p in d.photos) if (!j.receipt(p)) broke(RULES, "receipt ${p.id.take(8)} was attached by ${p.by.take(8)}, who could not pay that cost", p.id, p.ownerId)
            // Since 2.1 an item's details, asking price and sale amount have stamps of their own, as does what is to be collected.
            for (h in d.hidden) for ((part, s) in listOf("details" to h.detailsBy, "asking price" to h.priceBy, "sale amount" to h.saleBy)) {
                if (unsigned(s)) broke(RULES, "the $part of item ${h.id.take(8)} carry nobody's name, though the move had an Organiser then: ${short(h)}", h.id)
            }
            for (x in d.collect) if (unsigned(x.by)) broke(RULES, "what is to be collected for item ${x.id.take(8)} carries nobody's name, though the move had an Organiser then", x.id)
        }
        for (x in d.damage) if (unsigned(x.clearedBy)) broke(RULES, "damage report ${x.id.take(8)} carries nobody's name, though the move had an Organiser then", x.id, x.thingId)
        // Deletions: by an Organiser, or by whoever added the thing while nobody else had a hand in it.
        for (x in listOf<List<Owned>>(d.items, d.boxes, d.rooms, d.photos).flatten()) {
            if (!j.gone(x)) broke(RULES, "${x.javaClass.simpleName.lowercase()} ${x.id.take(8)} (added by ${x.createdBy.take(8)}) was deleted by ${who(x.deletedBy)}, with others' hands on it: ${j.others(x).map { it.take(8) }}", x.id, x.deletedBy.by)
            if (x.deleted && unsigned(x.deletedBy)) broke(RULES, "${x.id.take(8)} was deleted in nobody's name, though the move had an Organiser then", x.id)
        }
        for (p in d.proposals) if (!j.withdrawal(p)) broke(RULES, "suggestion ${p.id.take(8)} is deleted though its author did not withdraw it, or it was ${p.state}", p.id)
        if (!j.settings(d.move)) broke(RULES, "the move settings were changed by ${who(d.move.changedBy)}", d.move.changedBy.by)
        if (unsigned(d.move.changedBy)) broke(RULES, "the move settings carry nobody's name, though the move had an Organiser then: ${short(d.move)}")
        for (p in d.proposals) {
            if (!costsToo && p.kind == ProposalKind.COST) continue
            if (!j.leftOut(p)) broke(RULES, "suggestion ${p.id.take(8)} (${p.kind}) stands, made by ${p.by.take(8)} (${j.book.at(p.by, p.createdAt)?.label ?: "not in the move"} at the time)", p.id, p.by)
            if (!j.state(p)) broke(RULES, "suggestion ${p.id.take(8)} was made ${p.state} by ${who(p.decided)}", p.id, p.decided.by)
            if (p.state in listOf(ProposalState.APPROVED, ProposalState.DECLINED) && p.decided.by.isEmpty() && j.book.anyOrganiser(p.decided.at)) {
                broke(RULES, "suggestion ${p.id.take(8)} is ${p.state} with nobody's name on it", p.id)
            }
        }
        for (p in d.people) {
            val now = j.book.now(p.id)
            if (now == null) broke(RULES, "${p.name} (${p.id.take(8)}) is in the move though nobody with the right to add them did", p.id)
            else if (now.first != p.role || now.second != p.removed) {
                broke(RULES, "${p.name} (${p.id.take(8)}) is recorded as ${p.role}${if (p.removed) ", removed" else ""}, but the grants say ${now.first}${if (now.second) ", removed" else ""}", p.id)
            }
            val known = (sim.versions["person:" + p.id] ?: emptyList<Any>()).flatMap { (it as Person).grants }.toSet()
            for (g in p.grants) if (g !in known) broke(RULES, "${p.name} (${p.id.take(8)}) carries a grant nobody made: $g", p.id)
        }
        if (d.people.none { it.role == Role.ORGANISER && !it.removed }) broke(RULES, "the move ended with no Organiser at all, which lifts every rule")
    }

    private fun helpers(c: List<Simulation.Phone>, book: RoleBook) {
        val kinds = HashMap<String, Photo>()
        for (p in sim.phones) for (ph in p.data.photos) kinds.putIfAbsent(ph.file, ph)
        for (p in c) {
            if (p.sawMore || !book.onlyEver(p.personId, Role.HELPER)) continue
            val d = p.data
            for (e in d.expenses) broke(HELPERS, "${p.tag}, never anything but a Helper, holds cost ${e.id.take(8)}: ${short(e)}", e.id)
            for (x in d.proposals) if (x.kind == ProposalKind.COST) broke(HELPERS, "${p.tag}, never anything but a Helper, holds cost suggestion ${x.id.take(8)}", x.id)
            for (x in d.hidden) broke(HELPERS, "${p.tag}, never anything but a Helper, holds the hidden details of ${x.id.take(8)}", x.id)
            for (x in d.refused) broke(HELPERS, "${p.tag}, never anything but a Helper, holds the refused change ${x.id.take(8)}", x.id)
            for (x in d.collect) if (x.to != p.personId) broke(HELPERS, "${p.tag}, never anything but a Helper, holds what someone else is to collect for ${x.id.take(8)}", x.id)
            for (x in d.photos) if (x.kind in HIDDEN_KINDS && x.by !in p.selves) broke(HELPERS, "${p.tag}, never anything but a Helper, holds the ${x.kind} photo ${x.id.take(8)} taken by ${x.by.take(8)}", x.id)
            for (f in p.store.photoDir.listFiles() ?: emptyArray<File>()) {
                val ph = kinds[f.name] ?: continue
                if (ph.kind in HIDDEN_KINDS && ph.by !in p.selves) broke(HELPERS, "${p.tag}, never anything but a Helper, has the file of the ${ph.kind} photo ${ph.id.take(8)}", ph.id)
            }
        }
    }

    /** Works out afresh what is inside what and what has to be loaded, and holds every view the app offers against it. */
    private fun counted(p: Simulation.Phone) {
        val d = p.data
        val live = d.boxes.filter { !it.deleted }.associateBy { it.id }
        fun inside(b: Box): Box? {
            val parent = b.parentId?.let { live[it] } ?: return null
            if (parent.id == b.id) return null
            if (parent.parentId != null && live[parent.parentId] != null) return null // its parent is itself inside something: one level only
            return parent
        }
        val tops = live.values.filter { inside(it) == null }
        val carried = live.values.filter { inside(it) != null }
        if (d.boxes.groupBy { it.id }.any { it.value.size > 1 } || d.items.groupBy { it.id }.any { it.value.size > 1 }) broke(COUNTED, "${p.tag} holds the same record twice")
        if (d.topBoxes().map { it.id }.sorted() != tops.map { it.id }.sorted()) broke(COUNTED, "${p.tag}: the containers to load are not the ones that are inside nothing")
        for (b in live.values) {
            val parent = inside(b)
            if (d.parentOf(b)?.id != parent?.id) broke(NESTING, "${p.tag}: container ${b.id.take(8)} is said to be inside ${d.parentOf(b)?.id?.take(8)}, not ${parent?.id?.take(8)}", b.id)
            if (d.carrierOf(b).id != (parent ?: b).id) broke(NESTING, "${p.tag}: container ${b.id.take(8)} is carried by the wrong thing", b.id)
            if (parent != null) {
                if (inside(parent) != null || parent.deleted) broke(NESTING, "${p.tag}: container ${b.id.take(8)} is two levels deep or inside a deleted one", b.id, parent.id)
                if (b.stage != parent.stage || b.tripId != parent.tripId) broke(COUNTED, "${p.tag}: container ${b.id.take(8)} (${b.stage}) is inside ${parent.id.take(8)} (${parent.stage}) but not where it is", b.id, parent.id)
                if (b.id !in d.childrenOf(parent.id).map { it.id }) broke(NESTING, "${p.tag}: container ${b.id.take(8)} is not listed inside ${parent.id.take(8)}", b.id, parent.id)
            } else if (b.parentId != null && p === sim.phones[0]) note("containers whose saved parent no longer counts (deleted, or itself put inside another), so they are loaded on their own")
        }
        for (b in tops) if (d.childrenOf(b.id).any { inside(it)?.id != b.id }) broke(NESTING, "${p.tag}: ${b.id.take(8)} lists something inside it that is not", b.id)
        val items = d.items.filter { !it.deleted }
        // Things marked Store travel too once the move uses the storage unit.
        val storing = d.usesStorage()
        val moving = items.filter { it.disposition == Disposition.MOVE || (storing && it.disposition == Disposition.STORE) }
        val loose = moving.filter { it.boxId == null || live[it.boxId] == null }
        if (d.looseItems().map { it.id }.sorted() != loose.map { it.id }.sorted()) broke(COUNTED, "${p.tag}: the loose items are not the moving items that are in no container")
        for (i in items) {
            val box = i.boxId?.let { live[it] }
            if (d.containerOf(i)?.id != box?.id) broke(NESTING, "${p.tag}: item ${i.id.take(8)} is said to be in ${d.containerOf(i)?.id?.take(8)}, not ${box?.id?.take(8)}", i.id)
            if (i.boxId != null && box == null && p === sim.phones[0]) note("items whose saved container has been deleted, so they count as loose")
            if (box != null) {
                val carrier = inside(box) ?: box
                if (d.carrierOf(i)?.id != carrier.id) broke(NESTING, "${p.tag}: item ${i.id.take(8)} is carried by the wrong thing", i.id)
                if (i.stage != carrier.stage || i.tripId != carrier.tripId) broke(COUNTED, "${p.tag}: item ${i.id.take(8)} (${i.stage}) is in ${carrier.id.take(8)} (${carrier.stage}) but not where it is", i.id, carrier.id)
                if (i.id !in d.itemsIn(box.id).map { it.id }) broke(NESTING, "${p.tag}: item ${i.id.take(8)} is not listed in its container", i.id, box.id)
            }
        }
        // Every live container and every moving item is in exactly one place: to be loaded itself, or inside something that is.
        val movable = d.movables()
        val expected = tops.map { it.id } + loose.map { it.id }
        if (movable.map { it.id }.sorted() != expected.sorted() || movable.map { it.id }.toSet().size != movable.size) broke(COUNTED, "${p.tag}: the things to load are not each container and loose item exactly once")
        if (tops.size + carried.size != live.size || loose.size + moving.count { it.boxId != null && live[it.boxId] != null } != moving.size) broke(COUNTED, "${p.tag}: something is neither loose nor carried")
        val pr = d.progress()
        val mv = tops.map { it.stage } + loose.map { it.stage }
        val mine = listOf(
            pr.triage.done to items.count { it.disposition != Disposition.UNDECIDED }, pr.triage.total to items.size,
            pr.sealed.done to tops.count { it.sealed }, pr.sealed.total to tops.size,
            pr.loaded.done to mv.count { it != Stage.AT_ORIGIN }, pr.loaded.total to mv.size,
            pr.delivered.done to mv.count { it == Stage.DELIVERED || it == Stage.DONE }, pr.delivered.total to mv.count { it != Stage.STORED },
            pr.unpacked.done to mv.count { it == Stage.DONE }, pr.unpacked.total to mv.count { it != Stage.STORED },
            pr.stored.done to mv.count { it == Stage.STORED }, pr.stored.total to mv.size,
            pr.destinations.total to loose.size,
            pr.tasks.done to d.tasks.count { !it.deleted && it.done }, pr.tasks.total to d.tasks.count { !it.deleted },
            d.volume().cartons + d.volume().otherContainers to live.size,
        )
        if (mine.any { it.first != it.second }) broke(COUNTED, "${p.tag}: the progress counts are not what a fresh count gives: $pr against $mine")
    }

    private fun agree(c: List<Simulation.Phone>) {
        val counts = c.map { it.data.progress() to it.data.movables().size }
        if (counts.toSet().size > 1) broke(COUNTED, "the phones do not agree on progress: " + c.indices.joinToString { "${c[it].tag} ${counts[it]}" })
    }

    private fun letters(d: AppData, where: String = "") {
        for ((letter, who) in d.people.filter { it.letter.isNotBlank() }.groupBy { it.letter.uppercase() }) {
            if (who.size > 1) broke(LETTERS, "the letter $letter belongs to ${who.joinToString { "${it.name} (${it.id.take(8)})" }}$where", *who.map { it.id }.toTypedArray())
        }
    }

    /** No code is given out twice in one move, and no phone gives out the same code twice, in whichever of its moves. */
    private fun codes() {
        val all = sim.phones.flatMap { p -> p.handCodes.map { it.uppercase() to p.tag } }
        for ((code, where) in all.groupBy({ it.first }, { it.second })) {
            if (where.size > 1) broke(CODES, "the code ${code.substringAfter('/')} was given out ${where.size} times in ${sim.others[code.substringBefore('/').lowercase()] ?: "one move"}: on ${where.joinToString()}")
        }
        for (p in sim.phones) for ((code, n) in p.handCodes.groupingBy { it.substringAfter('/').uppercase() }.eachCount()) {
            if (n > 1) broke(Simulation.LETTERS_APART, "${p.tag} gave out the code $code $n times across its moves")
        }
    }

    /** Is [f] a photo file of [length] bytes as it was taken, or the small copy of it an update carries ([SimUpdates.isSmallCopyOf])? */
    private fun wholeOrSmall(f: File, length: Long) = f.length() == length || SimUpdates.isSmallCopyOf(f, length)

    /**
     * Every file is the photo as it was taken or the small copy an update brought, and every phone in the move holds each
     * photo it should have whole once any phone in the move does. A sync passes a photo on only whole: a small copy stays on
     * the phone that opened the update, until a phone that has the photo whole syncs with it and replaces it. So a photo
     * whose whole file only ever reached phones that are no longer in the move rightly stays a small copy where an update
     * put one, and nowhere at all on the other phones.
     */
    private fun photos(c: List<Simulation.Phone>, full: List<Simulation.Phone>) {
        for (p in sim.phones) for (f in p.store.photoDir.listFiles() ?: emptyArray<File>()) {
            if (f.name.endsWith(".part")) broke(PHOTOS, "${p.tag} was left with the half-received file ${f.name}")
            val length = sim.born[f.name]
            if (length != null && !wholeOrSmall(f, length)) broke(PHOTOS, "${p.tag} has ${f.name} with ${f.length()} bytes instead of $length")
        }
        for (p in c) for (ph in p.data.livePhotos) {
            if (ph.kind in HIDDEN_KINDS && p !in full) continue
            val length = sim.born[ph.file] ?: continue
            fun whole(q: Simulation.Phone) = File(q.store.photoDir, ph.file).let { it.isFile && it.length() == length }
            if (whole(p)) continue
            val holders = c.filter { whole(it) }
            if (holders.isEmpty()) continue
            val small = File(p.store.photoDir, ph.file).isFile
            broke(
                PHOTOS,
                "${p.tag} has the photo ${ph.id.take(8)} on record but " + (if (small) "only the small copy an update brought" else "not its file") +
                    ", which ${holders.joinToString { it.tag }} ${if (holders.size == 1) "has" else "have"} whole",
                ph.id,
            )
        }
    }

    /** Every phone starts again: its list of moves is the same, and each move loads into the state it had, with the same settings. */
    private fun disk() {
        for (p in sim.phones) {
            val had = p.onUi { p.stores().map { Triple(it.moveId, it.data.value, it.device.value) } }
            // A save that failed would be said on the phone (Store.saveProblem); none may have.
            p.onUi { p.stores().forEach { s -> s.saveProblem.value?.let { broke(DISK, "${p.tag} could not save ${sim.others[s.moveId] ?: "Maple Road"}: $it") } } }
            val cards = p.moves.cards.value.map { it.moveId }.sorted()
            p.onUi { p.stores().forEach { it.close() } }
            val again = p.onUi { p.openMoves() }
            if (again.cards.value.map { it.moveId }.sorted() != cards) broke(DISK, "${p.tag} started again with other moves than it had: ${again.cards.value.map { it.name }}")
            for ((id, before, device) in had) {
                val s = p.onUi { again.store(id) }
                if (s == null) {
                    broke(DISK, "${p.tag} started again without the move ${sim.others[id] ?: "Maple Road"}")
                    continue
                }
                val where = if (id == p.store.moveId) "" else " in ${sim.others[id] ?: id.take(8)}"
                if (s.data.value != before) {
                    val a = canon(before, true)
                    val b = canon(s.data.value, true)
                    val k = (a.keys + b.keys).firstOrNull { a[it] != b[it] }
                    broke(DISK, "${p.tag} loaded something else from disk than it had$where, first at $k\n      had: ${short(a[k])}\n      loaded: ${short(b[k])}", k?.substringAfter(':'))
                }
                if (s.device.value != device) broke(DISK, "${p.tag} loaded other phone settings than it had$where: $device against ${s.device.value}")
                if (s.loadWarning != null) broke(DISK, "${p.tag}$where: ${s.loadWarning}")
            }
            p.onUi { again.cards.value.forEach { again.store(it.moveId)?.close() } }
        }
    }

    // ---- after each sync ---------------------------------------------------------------------

    fun exchange(x: Simulation.Exchange, wire: Wire, complete: Boolean) {
        // After a sync that went through with nothing else going on, the two phones hold the same (but for what a Helper is not sent).
        if (complete && x.quiet) {
            val d = x.host.data
            val whole = listOf(x.host, x.joiner).all { p -> d.person(p.personId)?.let { !it.removed && it.role != Role.HELPER } == true }
            val a = canon(d, whole)
            val b = canon(x.joiner.data, whole)
            if (a != b) {
                val k = (a.keys + b.keys).first { a[it] != b[it] }
                note("syncs after which the two phones did not yet hold the same, and needed another", "${x.host.tag} hosted ${x.joiner.tag}, first at $k: host against joiner: ${differ(a[k], b[k])}", k.substringAfter(':'))
            }
        }
        val sent = wire.data(3)
        val back = wire.data(4)
        val hostBefore = x.hostBefore
        val hostAfter = x.hostAfter
        if (sent != null && hostBefore != null && hostAfter != null) {
            merged(x.host, x.hostWas, hostBefore, sent, hostAfter)
            // What the joiner sends depends on who it takes the host to be; what comes back, on who the host now knows the joiner to be.
            val hostSelf = x.hostAtStart!!.person(x.host.personId)
            val joinerView = x.joinerSent ?: x.joinerAtStart!!
            val hostRole = hostSelf?.let { me -> RoleBook(joinerView.people.filter { it.id != me.id } + me.copy(grants = (me.grants + (joinerView.person(me.id)?.grants ?: emptyList())).distinct())).now(me.id) }
            if (hostRole == null || hostRole.second || hostRole.first == Role.HELPER) notSent(x.joiner, x.host, sent, wire.files(7), joinerView)
            if (back != null) {
                val joinerRole = RoleBook(hostAfter.people).now(x.joiner.personId)
                if (joinerRole == null || joinerRole.second || joinerRole.first == Role.HELPER) notSent(x.host, x.joiner, back, wire.files(8), hostAfter)
                val joinerBefore = x.joinerBefore
                val joinerAfter = x.joinerAfter
                if (joinerBefore != null && joinerAfter != null) merged(x.joiner, x.joinerWas, joinerBefore, back, joinerAfter)
            }
        }
    }

    private fun notSent(from: Simulation.Phone, to: Simulation.Phone, data: AppData, files: List<String>, senders: AppData) {
        for (e in data.expenses) broke(HELPERS, "${from.tag} sent cost ${e.id.take(8)} to ${to.tag}, whom it knew to be a Helper", e.id, to.personId)
        for (p in data.proposals) if (p.kind == ProposalKind.COST) broke(HELPERS, "${from.tag} sent cost suggestion ${p.id.take(8)} to ${to.tag}, whom it knew to be a Helper", p.id, to.personId)
        for (p in data.photos) if (p.kind in HIDDEN_KINDS) broke(HELPERS, "${from.tag} sent the ${p.kind} photo ${p.id.take(8)} to ${to.tag}, whom it knew to be a Helper", p.id, to.personId)
        val secret = senders.photos.filter { it.kind in HIDDEN_KINDS }.map { it.file }.toSet()
        for (f in files) if (f in secret) broke(HELPERS, "${from.tag} sent the file of a receipt or serial-label photo to ${to.tag}, whom it knew to be a Helper", to.personId)
    }

    /** One merge: [before] on the phone (whose person was [was] then), [got] from the other, [after] as the phone was left. */
    private fun merged(p: Simulation.Phone, was: String, before: AppData, got: AppData, after: AppData) {
        val j = Judge(after)
        val mine = HashMap<String, Rec>()
        val theirs = HashMap<String, Rec>()
        val out = HashMap<String, Rec>()
        for ((k, r) in before.records()) mine[k] = r
        for ((k, r) in got.records()) theirs[k] = r
        for ((k, r) in after.records()) out[k] = r
        fun bad(what: String, k: String) = broke(MERGE, "a merge on ${p.tag} $what $k\n      had: ${short(mine[k])}\n      got: ${short(theirs[k])}\n      kept: ${short(out[k])}", k.substringAfter(':'))
        // A phone whose person is a Helper before and after takes in nothing of what Helpers are not sent, whatever the other phone thought.
        fun helperOn(d: AppData, me: String) = d.people.any { it.role == Role.ORGANISER && !it.removed } && (d.person(me)?.let { it.removed || it.role == Role.HELPER } ?: true)
        val asHelper = helperOn(before, was) && helperOn(after.copy(people = after.people.filter { it.id != p.personId || it.id == was }), was)
        if (asHelper) {
            fun share(d: AppData) = listOf(
                d.expenses.toSet(), d.photos.filter { it.kind in HIDDEN_KINDS }.toSet(), d.proposals.filter { it.kind == ProposalKind.COST }.toSet(),
                d.hidden.toSet(), d.refused.toSet(),
            )
            if (share(before) != share(after)) broke(HELPERS, "${p.tag}, a Helper's phone, took in costs, cost suggestions or receipt or serial-label photos in a merge", p.personId)
        }

        /** The part of the result must be the newest one that was allowed, of the two copies that went in. */
        fun part(k: String, name: String, a: Any?, b: Any?, result: Any, ok: (Any) -> Boolean) {
            val good = listOfNotNull(a, b).filter(ok)
            val kept = parts(result).first { it.first == name }.second
            if (good.isEmpty()) {
                if (name == "settings") return // nobody had the right on either copy: the newer one stands as it is
                if (!isReset(result, name)) bad("kept a $name nobody had the right to set on", k)
                return
            }
            val newest = good.maxOf { stampOf(it, name).at }
            if (good.none { stampOf(it, name).at == newest && parts(it).first { x -> x.first == name }.second == kept }) bad("did not keep the newest allowed $name of", k)
        }

        for (k in (mine.keys + theirs.keys).toSortedSet()) {
            val a = mine[k]
            val b = theirs[k]
            val r = out[k]
            val type = k.substringBefore(':')
            if (type == "activity") continue
            if (asHelper && listOfNotNull(a, b, r).any { hidden(it) }) continue
            val newest = maxOf(a?.updatedAt ?: -1, b?.updatedAt ?: -1)
            // The phone's own person may have given way on a letter someone else had first: a change of its own, made after the merge.
            if (r is Person && r.id == p.personId && r.updatedAt > newest && (a as Person?)?.letter != r.letter) continue
            if (r == null) {
                val mayGo = when (type) {
                    "task" -> listOfNotNull(a, b).none { j.plan(it as Task) }
                    "expense" -> listOfNotNull(a, b).none { j.cost(it as Expense) }
                    "proposal" -> !j.author((a ?: b) as Proposal)
                    "person" -> j.book.now(k.substringAfter(':')) == null
                    "photo" -> listOfNotNull(a, b).all { it is Photo && it.kind == PhotoKind.RECEIPT && it.by.isNotEmpty() }
                    else -> false
                }
                if (!mayGo) bad("dropped", k)
                continue
            }
            // A phone this one let in as it is (its person counts in nobody's name here) gets a Helper's grant in this phone's
            // person's name as the sync begins (Store.letIn): a change of this phone's own, made before the data went across.
            if (r is Person && r.updatedAt > newest) {
                val known = listOfNotNull(a as Person?, b as Person?).flatMap { it.grants }.toSet()
                val extra = r.grants.filter { it !in known }
                if (extra.isNotEmpty() && extra.all { it.by == p.personId && it.role == Role.HELPER && !it.removed && it.at == r.updatedAt }) continue
            }
            if (r.updatedAt != newest) {
                bad("left the wrong version (${r.updatedAt}, not $newest) of", k)
                continue
            }
            if (listOfNotNull(a, b).none { it.updatedAt == newest && plain(it) == plain(r) }) bad("changed", k)
            // Every hand either copy knew of is kept, and none is made up.
            val handsIn = listOfNotNull(a, b).flatMap { if (it is Owned) it.hands else if (it is BoxEntry) it.hands else emptyList() }.toSet()
            val handsOut = if (r is Owned) r.hands else if (r is BoxEntry) r.hands else emptyList()
            if (handsOut.toSet() != handsIn || handsOut != handsOut.distinct().sorted()) bad("lost or invented a hand on", k)
            if (r is Owned) {
                // A deletion is kept only when it was allowed, and undone only when it was not.
                fun about(x: Owned) = "deleted by ${x.deletedBy} (${j.book.at(x.deletedBy.by, x.deletedBy.at)} then), added by ${x.createdBy.take(8)}, hands ${x.hands.map { it.take(8) }}, others ${j.others(x).map { it.take(8) }}"
                if (r.deleted && !j.gone(r)) broke(MERGE, "a merge on ${p.tag} kept a deletion its author had no right to make of $k: ${about(r)}", k.substringAfter(':'))
                val dropped = listOfNotNull(a as Owned?, b as Owned?).firstOrNull { it.updatedAt == newest && it.deleted }
                if (!r.deleted && dropped != null && j.gone(dropped, r.hands)) broke(MERGE, "a merge on ${p.tag} undid a deletion its author had the right to make of $k: ${about(dropped)}; kept hands ${r.hands.map { it.take(8) }}", k.substringAfter(':'))
            }
            if (r is Proposal) {
                if (!j.withdrawal(r)) bad("deleted a suggestion that was not withdrawn by its author, or was approved or declined, at", k)
                if (!j.leftOut(r)) bad("kept in the lists a suggestion its author could not make,", k)
                val withdrawn = listOfNotNull(a as Proposal?, b as Proposal?).any { it.deleted && it.withdrawn.by == it.by && it.by.isNotEmpty() }
                if (withdrawn && !r.deleted && r.state != ProposalState.APPROVED && r.state != ProposalState.DECLINED) bad("lost the withdrawal of", k)
            }
            when (r) {
                is Item -> {
                    part(k, "decision", a, b, r) { j.decision(it as Item) }
                    part(k, "room", a, b, r) { j.room(it as Item) }
                    part(k, "outcome", a, b, r) { j.outcome(it as Item, r.decisionBy) }
                }
                is Task -> {
                    part(k, "plan", a, b, r) { j.plan(it as Task) }
                    part(k, "done", a, b, r) { j.done(it as Task, r.assignedTo) }
                }
                is Expense -> {
                    part(k, "cost", a, b, r) { j.cost(it as Expense) }
                    part(k, "paid", a, b, r) { j.paid(it as Expense, r.costBy) }
                }
                is Person -> {
                    val want = ((a as Person?)?.grants.orEmpty() + (b as Person?)?.grants.orEmpty()).toSet()
                    // The same Helper's grant from letting a phone in (Store.letIn) on a copy the merge then stamped over with the
                    // other phone's newer one: that copy is not among those that went in, but the grant is this phone's own.
                    val letIn = (r.grants.toSet() - want).all { it.by == p.personId && it.role == Role.HELPER && !it.removed && it.at <= r.updatedAt }
                    if (!r.grants.containsAll(want) || !letIn) bad("lost or invented a grant on", k)
                    val now = j.book.now(r.id)
                    if (now == null || now.first != r.role || now.second != r.removed) bad("gave the wrong role to", k)
                }
                is Proposal -> {
                    val good = listOfNotNull(a as Proposal?, b as Proposal?).filter { j.state(it) }
                    val final = good.filter { it.state == ProposalState.APPROVED || it.state == ProposalState.DECLINED }
                    if (final.isNotEmpty()) {
                        val at = final.maxOf { it.decided.at }
                        if (final.none { it.decided.at == at && it.state == r.state && it.decided == r.decided }) bad("did not keep the latest approval or refusal of", k)
                    } else if ((r.state == ProposalState.APPROVED || r.state == ProposalState.DECLINED) && r.kind != ProposalKind.QUESTION) bad("approved or declined by itself", k)
                }
            }
        }
        part(Simulation.MOVE, "settings", before.move, got.move, after.move) { j.settings(it as MoveInfo) }
        if (after.move.labelsPrinted != maxOf(before.move.labelsPrinted, got.move.labelsPrinted) || after.move.updatedAt != maxOf(before.move.updatedAt, got.move.updatedAt)) {
            broke(MERGE, "a merge on ${p.tag} lost the newer move record or the count of printed labels")
        }
    }
}
