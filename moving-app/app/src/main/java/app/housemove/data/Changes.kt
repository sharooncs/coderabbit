package app.housemove.data

/** What a Store call that the rules can refuse did: [Ok] with the saved record, or [Refused] with a reason to show. */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Refused(override val reason: String) : Outcome<Nothing>()

    val ok: Boolean get() = this is Ok

    /** The saved record, or null when the call was refused. */
    fun valueOrNull(): T? = (this as? Ok)?.value

    /** Why the call was refused, or null when it went through. */
    open val reason: String? get() = null
}

/** Whoever is making a change on this phone, and what the rules let them do. */
internal class Actor(val data: AppData, val me: Person?) {
    /** A move with no Organiser is not restricted. */
    val open: Boolean = !data.hasOrganiser()
    val id: String = me?.id ?: ""

    fun refusal(action: Action): String? = data.refusal(me, action)
    fun may(action: Action): Boolean = refusal(action) == null

    /** May this person delete [x] as it is stored now? See [mayDelete]. */
    fun mayDelete(x: Rec): Boolean = data.mayDelete(me, x) == null

    /** The name that goes on what this person sets. Blank while there is no Organiser, as for data from before roles. */
    val signature: String get() = if (open) "" else id

    fun stamp(t: Long, sum: String) = Stamp(signature, t, sum)
}

// ---- what each record may be saved as --------------------------------------------------

/**
 * [x] as the rules allow this person to save it over [old]. The protected
 * parts they may not set stay as they were; everything else is taken from [x].
 * Stamps always come from the stored record, never from the caller's copy.
 */
internal fun Actor.guard(old: Item?, x: Item, t: Long, events: Events? = null): Item {
    val was = old?.stamped()
    var r = x.copy(
        decisionBy = was?.decisionBy ?: Stamp(), roomBy = was?.roomBy ?: Stamp(), outcomeBy = was?.outcomeBy ?: Stamp(),
        createdBy = old?.createdBy ?: signature, hands = old?.hands ?: emptyList(), deletedBy = old?.deletedBy ?: Stamp(),
    )
    // Whether it may be deleted at all is asked before this (Draft.saveItems leaves it alone when not).
    r = when {
        r.deleted && old?.deleted != true -> r.copy(deletedBy = stamp(t, deletedSum()))
        r.deleted -> r
        else -> r.copy(deletedBy = Stamp())
    }

    val wasDisposition = was?.disposition ?: Disposition.UNDECIDED
    if (r.disposition != wasDisposition || r.price != was?.price || r.deadlineDay != was?.deadlineDay) {
        if (may(Action.DECIDE)) {
            r = r.copy(decisionBy = stamp(t, r.decisionSum()))
            if (r.disposition != wasDisposition) events?.add(decisionVerb(r.disposition), r.title(), r.id)
        } else r = r.copy(disposition = wasDisposition, price = was?.price, deadlineDay = was?.deadlineDay)
    }

    if (r.destRoomId != was?.destRoomId) {
        r = when {
            may(Action.DECIDE) -> r.copy(roomBy = stamp(t, r.roomSum()))
            // The room its old-home room maps to is nobody's decision: anyone adding an item gets it.
            was?.destRoomId == null && r.destRoomId == data.room(r.originRoomId)?.mapsTo -> r.copy(roomBy = Stamp(sum = r.roomSum()))
            else -> r.copy(destRoomId = was?.destRoomId)
        }
    }

    if (r.listed != (was?.listed ?: false) || r.gone != (was?.gone ?: false) || r.soldFor != was?.soldFor) {
        if (data.mayCarryOut(me, r)) {
            r = r.copy(outcomeBy = stamp(t, r.outcomeSum()))
            if (r.gone && was?.gone != true) events?.add(goneVerb(r.disposition), r.title(), r.id)
        } else r = r.copy(listed = was?.listed ?: false, gone = was?.gone ?: false, soldFor = was?.soldFor)
    }
    if (r.damaged && was?.damaged != true) events?.add("reported damage to", r.title(), r.id)
    return r.stamped()
}

/** A cost as this person may save it, or null when they may not create one. */
internal fun Actor.guard(old: Expense?, x: Expense, t: Long): Expense? {
    if (old == null) {
        if (!may(Action.DECIDE)) return null
        return x.copy(costBy = stamp(t, x.costSum()), paidBy = stamp(t, x.paidSum()))
    }
    val was = old.stamped()
    var r = x.copy(costBy = was.costBy, paidBy = was.paidBy)
    if (r.costSum() != was.costSum()) {
        r = if (may(Action.DECIDE)) r.copy(costBy = stamp(t, r.costSum()))
        else r.copy(title = was.title, amount = was.amount, category = was.category, day = was.day, deleted = was.deleted)
    }
    if (r.planned != was.planned) {
        r = if (data.mayPay(me, r)) r.copy(paidBy = stamp(t, r.paidSum())) else r.copy(planned = was.planned)
    }
    return r
}

/** A task as this person may save it, or null when they may not create one. */
internal fun Actor.guard(old: Task?, x: Task, t: Long): Task? {
    if (old == null) {
        if (!may(Action.DECIDE)) return null
        return x.copy(planBy = stamp(t, x.planSum()), doneBy = if (x.done) stamp(t, x.doneSum()) else Stamp(sum = x.doneSum()))
    }
    val was = old.stamped()
    var r = x.copy(planBy = was.planBy, doneBy = was.doneBy)
    if (r.planSum() != was.planSum()) {
        r = if (may(Action.DECIDE)) r.copy(planBy = stamp(t, r.planSum()))
        else r.copy(
            title = was.title, offsetDays = was.offsetDays, dueDay = was.dueDay, category = was.category, notes = was.notes,
            assignedTo = was.assignedTo, deleted = was.deleted,
        )
    }
    if (r.done != was.done) {
        r = if (data.mayTick(me, r)) r.copy(doneBy = stamp(t, r.doneSum())) else r.copy(done = was.done)
    }
    return r
}

/** The move settings as this person may save them. Anyone may move [MoveInfo.labelsPrinted] on. */
internal fun Actor.guard(old: MoveInfo, x: MoveInfo, t: Long): MoveInfo {
    val was = old.stamped()
    val r = x.copy(changedBy = was.changedBy)
    return when {
        r.settingsSum() == was.settingsSum() -> r
        may(Action.MOVE_SETTINGS) -> r.copy(changedBy = stamp(t, r.settingsSum()))
        else -> was.copy(labelsPrinted = r.labelsPrinted)
    }
}

/**
 * Would saving [x] over [old] delete something this person may not delete? Then nothing is
 * saved: the save was asked for in order to delete it, and its other values may be stale.
 */
internal fun Actor.refusesDeleting(old: Rec?, x: Rec): Boolean = x.deleted && old != null && !old.deleted && !mayDelete(old)

/** [hands] with this person added, when [changed]; a save that changes nothing leaves no hand on a thing. */
internal fun Actor.handsAfter(hands: List<String>, changed: Boolean): List<String> =
    if (changed && signature.isNotEmpty() && signature !in hands) joinHands(hands, listOf(signature)) else hands

/** The deletion stamp a record saved as [deleted] gets, over what it was ([old]). */
internal fun Actor.deletionStamp(old: Owned?, deleted: Boolean, t: Long): Stamp = when {
    !deleted -> Stamp()
    old?.deleted == true -> old.deletedBy
    else -> stamp(t, deletedSum())
}

/** A room as this person saves it: who added it and whose hands have been on it come from the stored copy. */
internal fun Actor.guard(old: Room?, x: Room, t: Long): Room {
    val r = x.copy(createdBy = old?.createdBy ?: signature, hands = old?.hands ?: emptyList(), deletedBy = deletionStamp(old, x.deleted, t), updatedAt = t)
    return r.copy(hands = handsAfter(r.hands, old == null || r.copy(updatedAt = old.updatedAt) != old))
}

/** A photo as this person saves it. Who took it is never changed once it is recorded. */
internal fun Actor.guard(old: Photo?, x: Photo, t: Long): Photo {
    val r = x.copy(
        by = old?.by ?: x.by, hands = old?.hands ?: emptyList(), deletedBy = deletionStamp(old, x.deleted, t), updatedAt = t,
        createdAt = old?.createdAt?.takeIf { it != 0L } ?: x.createdAt.takeIf { it != 0L } ?: t,
    )
    return r.copy(hands = handsAfter(r.hands, old == null || r.copy(updatedAt = old.updatedAt) != old))
}

/** Something scanned into a container, as this person saves it: whoever puts it in a container has a hand in it. */
internal fun Actor.guard(old: BoxEntry?, x: BoxEntry, t: Long): BoxEntry {
    val r = x.copy(hands = old?.hands ?: emptyList(), updatedAt = t, createdAt = old?.createdAt?.takeIf { it != 0L } ?: x.createdAt.takeIf { it != 0L } ?: t)
    return r.copy(hands = handsAfter(r.hands, old == null || r.copy(updatedAt = old.updatedAt) != old))
}

// ---- words for the activity log ---------------------------------------------------------

internal fun decisionVerb(d: Disposition) = when (d) {
    Disposition.UNDECIDED -> "reopened the decision on"
    Disposition.MOVE -> "decided to move"
    Disposition.SELL -> "decided to sell"
    Disposition.DONATE -> "decided to donate"
    Disposition.BIN -> "decided to bin"
    Disposition.STORE -> "decided to store"
}

private fun goneVerb(d: Disposition) = when (d) {
    Disposition.SELL -> "sold"
    Disposition.DONATE -> "donated"
    Disposition.BIN -> "binned"
    else -> "marked as gone:"
}

/** Short name of a container for the log: "L012", "suitcase B2". */
internal fun Box.logName(): String = if (kind == ContainerKind.CARTON) label else kind.noun.lowercase() + " " + label

/** Things that happened in one change, gathered so that forty boxes loaded at once make one line, not forty. */
internal class Events {
    private class Line(val verb: String, var name: String, var ref: String?, var count: Int)

    private val lines = LinkedHashMap<String, Line>()

    fun add(verb: String, name: String, ref: String?) {
        val l = lines[verb]
        if (l == null) lines[verb] = Line(verb, name, ref, 1) else l.count++
    }

    fun say(text: String, ref: String? = null) {
        lines["\u0000" + lines.size] = Line(text, "", ref, 0)
    }

    fun texts(): List<Pair<String, String?>> = lines.values.map { l ->
        when (l.count) {
            0 -> l.verb to l.ref
            1 -> "${l.verb} ${l.name}" to l.ref
            else -> "${l.verb} ${l.count} things" to null
        }
    }
}

// ---- one change to the data ---------------------------------------------------------------

/**
 * A change being made to the data by one person at one moment. The pieces
 * below keep to the rules and to the container logic; [finish] then brings
 * everything that follows from the change into line and writes the log.
 */
internal class Draft(var d: AppData, val actor: Actor, val t: Long) {
    val events = Events()

    /** Save new versions of items. Stage changes asked of an item inside a container go to the container. */
    fun saveItems(desired: List<Item>) {
        if (desired.isEmpty()) return
        val wanted = desired.associateBy { it.id }
        val known = HashSet<String>()
        val moves = LinkedHashMap<String, Pair<Stage, String?>>()
        fun one(old: Item?, x: Item): Item {
            if (actor.refusesDeleting(old, x)) return old!!
            var r = actor.guard(old, x, t, events).copy(updatedAt = t, createdAt = if (x.createdAt == 0L) old?.createdAt?.takeIf { it != 0L } ?: t else x.createdAt)
            val carrier = if (old != null && r.boxId == old.boxId) d.carrierOf(old) else null
            val moved = old != null && (r.stage != old.stage || r.tripId != old.tripId)
            fun handed(r: Item) = r.copy(hands = actor.handsAfter(r.hands, old == null || r.copy(updatedAt = old.updatedAt, hands = old.hands) != old.stamped()))
            if (carrier != null && moved) {
                if (r.stage == Stage.DONE) r = r.copy(boxId = null) // taken out and put in its place
                else {
                    moves[carrier.id] = r.stage to r.tripId
                    return handed(r.copy(stage = old!!.stage, tripId = old.tripId))
                }
            }
            if (old != null && r.stage.ordinal > old.stage.ordinal) events.add(stageVerb(r.stage), r.title(), r.id)
            if (r.deleted && old != null && !old.deleted) events.add("deleted", r.title(), r.id)
            return handed(r)
        }
        val items = d.items.map { old -> wanted[old.id]?.let { known += old.id; one(old, it) } ?: old } +
            desired.filter { it.id !in known }.distinctBy { it.id }.map { one(null, it) }
        d = d.copy(items = items)
        if (moves.isNotEmpty()) saveBoxes(moves.mapNotNull { (id, m) -> d.box(id)?.copy(stage = m.first, tripId = m.second) })
    }

    /** Save new versions of containers. Deleting one lets go of what was in it. */
    fun saveBoxes(desired: List<Box>) {
        if (desired.isEmpty()) return
        val wanted = desired.associateBy { it.id }
        val known = HashSet<String>()
        val moves = LinkedHashMap<String, Pair<Stage, String?>>()
        val deleted = HashSet<String>()
        fun one(old: Box?, x: Box): Box {
            if (actor.refusesDeleting(old, x)) return old!!
            var r = x.copy(
                updatedAt = t, createdAt = if (x.createdAt == 0L) old?.createdAt?.takeIf { it != 0L } ?: t else x.createdAt,
                createdBy = old?.createdBy ?: actor.signature, hands = old?.hands ?: emptyList(), deletedBy = actor.deletionStamp(old, x.deleted, t),
            )
            fun handed(r: Box) = r.copy(hands = actor.handsAfter(r.hands, old == null || r.copy(updatedAt = old.updatedAt, hands = old.hands) != old))
            if (r.deleted) {
                if (old != null && !old.deleted) {
                    deleted += r.id
                    events.add("deleted", r.logName(), r.id)
                }
                return handed(r)
            }
            // One level of nesting only: a new parent that would break that is not taken.
            if (r.parentId != old?.parentId && r.parentId != null && d.whyNotNest(r.id, r.parentId!!) != null) r = r.copy(parentId = old?.parentId)
            val parent = if (old != null && r.parentId == old.parentId) d.parentOf(old) else null
            val moved = old != null && (r.stage != old.stage || r.tripId != old.tripId)
            if (parent != null && moved) {
                if (r.stage == Stage.DONE) r = r.copy(parentId = null) // unpacked on its own
                else {
                    moves[parent.id] = r.stage to r.tripId
                    r = r.copy(stage = old!!.stage, tripId = old.tripId)
                }
            } else if (old != null && r.stage.ordinal > old.stage.ordinal) events.add(stageVerb(r.stage), r.logName(), r.id)
            if (old != null && r.sealed != old.sealed) events.add(if (r.sealed) "sealed" else "opened", r.logName(), r.id)
            if (r.damaged && old?.damaged != true) events.add("reported damage to", r.logName(), r.id)
            return handed(r)
        }
        val boxes = d.boxes.map { old -> wanted[old.id]?.let { known += old.id; one(old, it) } ?: old } +
            desired.filter { it.id !in known }.distinctBy { it.id }.map { one(null, it) }
        d = d.copy(boxes = boxes)
        if (deleted.isNotEmpty()) {
            d = d.copy(
                boxes = d.boxes.mapChanged { if (it.parentId in deleted && !it.deleted) it.copy(parentId = null, updatedAt = t) else it },
                items = d.items.mapChanged { if (it.boxId in deleted && !it.deleted) it.copy(boxId = null, updatedAt = t) else it },
                entries = d.entries.mapChanged { if (it.boxId in deleted && !it.deleted) it.copy(deleted = true, updatedAt = t) else it },
            )
        }
        // A stage change asked of a container that is inside another goes to the outer one.
        if (moves.isNotEmpty()) saveBoxes(moves.mapNotNull { (id, m) -> d.box(id)?.copy(stage = m.first, tripId = m.second) })
    }

    fun finish(): AppData {
        var out = closeProposals(mirrorContainers(d, t))
        val texts = events.texts()
        if (texts.isNotEmpty()) {
            out = out.copy(
                activity = pruneActivity(out.activity + texts.map { (text, ref) -> Activity(id = newId(), by = actor.id, at = t, text = text, refId = ref, updatedAt = t) }),
            )
        }
        return out
    }
}

private fun stageVerb(s: Stage) = when (s) {
    Stage.AT_ORIGIN -> "brought back"
    Stage.LOADED -> "loaded"
    Stage.DELIVERED -> "delivered"
    Stage.DONE -> "unpacked"
}
