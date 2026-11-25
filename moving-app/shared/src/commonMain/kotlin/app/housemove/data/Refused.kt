package app.housemove.data

/*
 * Changes refused at a merge, kept for the Organisers.
 *
 * When phones merge, a protected part set by someone whose role did not allow it is not accepted (see
 * Merge.kt). Each such change becomes a [RefusedChange]: who tried what, on which thing, and when. It is
 * worked out from the data alone, with an id made from those four, so every phone that turns the same change
 * away makes the same record, and one an Organiser has dismissed stays dismissed. It holds no amounts or cost
 * details, and Helpers are not sent it ([sentTo]).
 *
 * Only changes that carry a name and a check value that holds are recorded: a stamp that does not hold, or a
 * part set in nobody's name, says nothing about who tried it.
 */

/**
 * Gathers the refused changes found during one merge. A record holds only what is the same on every phone that turns
 * the change away: what was refused, and who tried it when. The role they had then is worked out when it is shown
 * ([describe]): a phone that has not yet heard of a removal would otherwise record another role than one that has, and
 * the record, made with the same id everywhere, would never settle.
 */
internal class Refusals(@Suppress("UNUSED_PARAMETER") roles: Roles) {
    private val found = LinkedHashMap<String, RefusedChange>()

    fun add(what: String, targetId: String, s: Stamp, tried: String) {
        if (s.by.isEmpty()) return
        val id = "refused/" + seal(what, targetId, s.by, s.at)
        if (id in found) return
        found[id] = RefusedChange(id, s.by, s.at, what, targetId, tried, updatedAt = s.at)
    }

    fun isEmpty() = found.isEmpty()

    /** What was found, merged into [known] (a record dismissed since stays dismissed: its time is later). */
    fun into(known: List<RefusedChange>): List<RefusedChange> = if (found.isEmpty()) known else mergeList(known, found.values.toList())
}

/** Changes refused at a merge that no Organiser has dismissed yet, newest first. For Organisers' Home. */
fun AppData.refusedChanges(): List<RefusedChange> =
    refused.filter { !it.deleted }.sortedWith(compareByDescending<RefusedChange> { it.at }.thenBy { it.id })

/**
 * A refused change in words: "Sam's phone tried to mark Sofa sold; Sam is a Helper." The thing is named as it
 * is called now; a cost is never named or priced.
 */
fun AppData.describe(r: RefusedChange): String {
    val who = personName(r.by).ifBlank { "Someone" }
    val name = if (r.targetId.isEmpty() || expenses.any { it.id == r.targetId }) "it" else nameOf(r.targetId).let { if (it == "something") "something" else it }
    // A thing marked gone is called sold, donated or binned by what is decided for it now (see [outcomeVerb]).
    val tried = (if (r.tried == GONE) item(r.targetId)?.let { goneVerb(it.disposition) } ?: GONE else r.tried).replace("{}", name)
    val now = person(r.by)
    // Their role when they tried it, as this phone knows the move's people now.
    val role = roles().at(r.by, r.at)
    val why = when {
        role == null -> if (now == null) "$who is not in the move" else "$who was not in the move then"
        now != null && !now.removed && now.role == role -> "$who is ${if (role == Role.ORGANISER) "an" else "a"} ${role.label}"
        else -> "$who was ${if (role == Role.ORGANISER) "an" else "a"} ${role.label} then"
    }
    return "$who's phone tried to $tried; $why."
}

/** What a decision is called after "decide to", for a refused change. */
internal fun decideVerb(d: Disposition): String = when (d) {
    Disposition.UNDECIDED -> "reopen the decision on {}"
    Disposition.MOVE -> "decide to move {}"
    Disposition.SELL -> "decide to sell {}"
    Disposition.DONATE -> "decide to donate {}"
    Disposition.BIN -> "decide to bin {}"
    Disposition.STORE -> "decide to store {}"
}

private const val GONE = "mark {} gone"

/**
 * What carrying a decision out is called, for a refused change: from the part that was refused alone (listed and
 * gone), which is the same in every copy that carries it. What is decided for the thing is not part of it, and two
 * phones may hold different decisions then; were it in the words, the same refused change would read differently
 * on each and the record, made with the same id everywhere, would not settle. [describe] names it from the decision.
 */
fun outcomeVerb(i: Item): String = when {
    i.gone -> GONE
    i.listed -> "mark {} listed for sale"
    else -> "change whether {} has gone"
}

private fun goneVerb(d: Disposition): String = when (d) {
    Disposition.SELL -> "mark {} sold"
    Disposition.DONATE -> "mark {} donated"
    Disposition.BIN -> "mark {} binned"
    else -> GONE
}
