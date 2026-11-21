package app.housemove.data

// ---- who may do what -----------------------------------------------------------

/** The things the rules distinguish. [can] holds the table. */
enum class Action {
    /** Add people, change roles, remove people. */
    MANAGE_PEOPLE,

    /** Change the move settings. */
    MOVE_SETTINGS,

    /** Erase the data on a phone. */
    ERASE,

    /** Restore a backup. */
    RESTORE_BACKUP,

    /** Decide directly: keep/sell/donate/bin/store, prices, costs, room plan, create or change tasks. */
    DECIDE,

    /** Approve or decline someone's suggestion, or answer a question for good. */
    APPROVE,

    /** Put such a change forward for an Organiser to approve. */
    SUGGEST,

    /**
     * Carry out a decision: mark sold/donated/binned/gone, record the sale price, mark a cost paid,
     * attach a receipt. A Decider may only do this for something of theirs that was approved:
     * ask [mayCarryOut] / [mayPay] about the particular item or cost.
     */
    CARRY_OUT,

    /** Photograph, add items, pack, scan on and off the van, unpack, report damage, search, tick off own tasks. */
    WORK,

    /** Ask for a decision on an item. */
    ASK,

    /**
     * Delete anything, whoever added it. Anyone may delete an item, container, room or photo they
     * added themselves while nobody else has had a hand in it: ask [mayDelete] about the particular
     * thing. Otherwise a Decider or Helper asks an Organiser to delete it ([Store.askToDelete]).
     */
    DELETE,
}

/**
 * The one place that says who may do what.
 *
 * | | Organiser | Decider | Helper |
 * |---|---|---|---|
 * | MANAGE_PEOPLE, MOVE_SETTINGS, ERASE, RESTORE_BACKUP, DECIDE, APPROVE | yes | no | no |
 * | SUGGEST | yes | yes | no |
 * | CARRY_OUT | yes | once approved | no |
 * | DELETE (anything) | yes | only what they added and nobody else touched | the same |
 * | WORK, ASK | yes | yes | yes |
 *
 * A move with no Organiser at all (one phone in "helper mode", say) is not
 * restricted, so it can never get stuck with nobody able to run it.
 */
fun AppData.can(role: Role, action: Action): Boolean {
    if (!hasOrganiser()) return true
    return when (action) {
        Action.WORK, Action.ASK -> true
        Action.SUGGEST, Action.CARRY_OUT -> role != Role.HELPER
        else -> role == Role.ORGANISER
    }
}

/** Is there anyone who can run the move? */
fun AppData.hasOrganiser(): Boolean = people.any { it.role == Role.ORGANISER && !it.removed }

/** The person this phone belongs to. */
fun AppData.me(device: DeviceInfo): Person? = person(device.personId)

/**
 * This phone's role. Data with no people in it at all (from before 2.0) goes by the phone's old
 * helper switch. A phone whose person is not among the people has the least rights.
 */
fun AppData.roleOf(device: DeviceInfo): Role {
    val p = me(device) ?: return if (people.isEmpty() && !device.helper) Role.ORGANISER else Role.HELPER
    return if (p.removed) Role.HELPER else p.role
}

fun AppData.can(device: DeviceInfo, action: Action): Boolean {
    if (me(device)?.removed == true && action != Action.WORK && action != Action.ASK) return false
    return can(roleOf(device), action)
}

/** Why [person] may not do [action], in words for the screen; null when they may. */
fun AppData.refusal(person: Person?, action: Action): String? {
    val working = action == Action.WORK || action == Action.ASK
    if (person?.removed == true && !working && hasOrganiser()) return "You have been removed from this move."
    val role = person?.role ?: Role.HELPER
    if (can(role, action)) return null
    return when (action) {
        Action.MANAGE_PEOPLE -> "Only an Organiser can add people, change roles or remove people."
        Action.MOVE_SETTINGS -> "Only an Organiser can change the move settings."
        Action.ERASE -> "Only an Organiser can erase the move."
        Action.RESTORE_BACKUP -> "Only an Organiser can restore a backup."
        Action.DECIDE -> if (role == Role.DECIDER) "You can suggest this, and an Organiser decides." else "Only an Organiser can decide this."
        Action.APPROVE -> "Only an Organiser can approve or decline a suggestion."
        Action.SUGGEST -> "Helpers cannot suggest this. You can ask for a decision instead."
        Action.CARRY_OUT -> "Only an Organiser, or a Decider whose suggestion was approved, can do this."
        Action.DELETE -> "Only an Organiser can delete what someone else added or worked on. You can ask an Organiser to delete it."
        Action.WORK, Action.ASK -> "You cannot do this."
    }
}

fun AppData.personName(id: String?): String = person(id)?.name?.ifBlank { null } ?: if (id.isNullOrEmpty()) "" else "Someone"

// ---- roles over time -------------------------------------------------------------

/**
 * Everyone's role at any moment, worked out from the [Grant]s on the people.
 *
 * A grant counts when it was made by someone who was an Organiser at that
 * time. An open grant ([Grant.by] blank) counts when it is how the person
 * first appears (a phone starting its own move) or when the move had no
 * Organiser at that time, so there was nobody to ask. Anything else, such as
 * a Decider's phone writing itself a promotion, is ignored. So is a grant that
 * would take the last Organiser away while other people are in the move.
 * Because past roles are kept, a decision made by an Organiser stays good
 * after they are demoted or removed.
 */
class Roles(people: Collection<Person>) {
    private class Step(val at: Long, val role: Role, val removed: Boolean, val by: String) {
        val organiser get() = role == Role.ORGANISER && !removed
    }

    private val steps = HashMap<String, ArrayList<Step>>()

    /** Each moment the number of Organisers changed, with the number from then on. */
    private val organisersFrom = ArrayList<Pair<Long, Int>>()

    init {
        val all = ArrayList<Pair<String, Grant>>()
        for (p in people) for (g in p.grants) all += p.id to g
        all.sortWith(compareBy({ it.second.at }, { it.first }, { it.second.by }, { it.second.role.ordinal }, { it.second.removed }))
        var organisers = 0
        for ((id, g) in all) {
            val mine = steps[id]
            val ok = if (g.by.isEmpty()) mine == null || organisers == 0 else steps[g.by]?.lastOrNull()?.organiser == true
            if (!ok) continue
            val step = Step(g.at, g.role, g.removed, g.by)
            val wasOrganiser = mine?.lastOrNull()?.organiser == true
            // The last Organiser stays one while there is anyone else in the move. Each phone refuses that by itself;
            // this is for two Organisers who step down (or remove themselves) on their own phones at the same time.
            if (wasOrganiser && !step.organiser && organisers == 1 && steps.any { (other, s) -> other != id && !s.last().removed }) continue
            val before = organisers
            organisers += (if (step.organiser) 1 else 0) - (if (wasOrganiser) 1 else 0)
            if (organisers != before) organisersFrom += g.at to organisers
            steps.getOrPut(id) { ArrayList() }.add(step)
        }
    }

    /**
     * Was there nobody to ask at [time]: no Organiser in the move then? Something set in
     * nobody's name counts only if so. Time 0 is data from before there were roles.
     */
    fun nobodyToAsk(time: Long): Boolean = time == 0L || (organisersFrom.lastOrNull { it.first <= time }?.second ?: 0) == 0

    private fun stepAt(id: String, time: Long): Step? = steps[id]?.lastOrNull { it.at <= time }

    /** The person's role at [time], or null when they were not in the move then (unknown, not yet added, or removed). */
    fun at(id: String, time: Long): Role? = stepAt(id, time)?.takeIf { !it.removed }?.role

    fun organiserAt(id: String, time: Long): Boolean = at(id, time) == Role.ORGANISER

    fun memberAt(id: String, time: Long): Boolean = at(id, time) != null

    /** Role and removed flag as they stand now, or null for someone no Organiser ever added. */
    fun now(id: String): Pair<Role, Boolean>? = steps[id]?.lastOrNull()?.let { it.role to it.removed }

    /** Has an Organiser other than the person themself ever given them a role? */
    fun vouchedFor(id: String): Boolean = steps[id]?.any { it.by.isNotEmpty() && it.by != id } == true
}

fun AppData.roles(): Roles = Roles(people)

/** People with their [Person.role] and [Person.removed] set from their grants. People nobody had the right to add are left out. */
internal fun settlePeople(people: List<Person>): List<Person> {
    if (people.isEmpty()) return people
    val roles = Roles(people)
    return people.mapNotNull { p ->
        val (role, removed) = roles.now(p.id) ?: return@mapNotNull null
        if (p.role == role && p.removed == removed && !p.deleted) p else p.copy(role = role, removed = removed, deleted = false)
    }
}

private val GRANT_ORDER = compareBy<Grant>({ it.at }, { it.by }, { it.role.ordinal }, { it.removed })

/** Two phones' copies of the people: newest details win, and every grant either phone knows is kept. */
internal fun mergePeople(a: List<Person>, b: List<Person>): List<Person> {
    if (a.isEmpty() && b.isEmpty()) return a
    val other = HashMap<String, Person>()
    for (p in a) other[p.id] = p
    for (p in b) other.merge(p.id, p) { x, y -> x.copy(grants = (x.grants + y.grants)) }
    val merged = mergeList(a, b).map { p ->
        val grants = other.getValue(p.id).grants.distinct().sortedWith(GRANT_ORDER)
        if (grants == p.grants) p else p.copy(grants = grants)
    }
    return settlePeople(merged)
}

// ---- letters for handwritten codes -----------------------------------------------

private fun letterAt(n: Int): String = if (n < 26) ('A' + n).toString() else ('A' + (n / 26 - 1) % 26).toString() + ('A' + n % 26)

/** The next letter nobody in the move uses: A to Z, then AA, AB and so on. */
fun AppData.freeLetter(except: String? = null): String {
    val used = people.filter { it.id != except }.map { it.letter.uppercase() }.toSet()
    var n = 0
    while (letterAt(n) in used) n++
    return letterAt(n)
}

/**
 * The letter for someone [by] is adding. While [by] is the only Organiser, nobody else can be
 * adding people at the same moment, so the next free letter is theirs to give. Once there are
 * several Organisers, two of them could each give out the same "next free" letter on their own
 * phones before those have synced, and two people would then write the same codes on their
 * boxes. So each Organiser hands out letters that begin with their own: Asha (A) gives AA, AB, …
 * and Omar (B) gives BA, BB, ….
 */
internal fun AppData.letterFor(by: Person?): String {
    val mine = by?.letter?.trim()?.uppercase().orEmpty()
    val alone = people.none { it.id != by?.id && it.role == Role.ORGANISER && !it.removed }
    if (by == null || mine.isEmpty() || alone) return freeLetter()
    val used = people.map { it.letter.uppercase() }.toSet()
    var n = 0
    while (mine + letterAt(n) in used) n++
    return mine + letterAt(n)
}

/**
 * No two people share a letter, yet two phones can each have given one out, or had one chosen,
 * before they met: two people picking the same letter at the same moment, or a phone let in as it
 * is that happens to use a letter already taken. When their phones learn of each other, whoever
 * has been in the move longest keeps the letter. Returns the letter [me] takes instead when [me]
 * is not that person, or null when nothing has to change: their own with one more letter after it.
 */
internal fun AppData.letterInstead(me: Person): String? {
    val mine = me.letter.trim().uppercase()
    if (mine.isEmpty()) return null
    fun since(p: Person) = p.grants.minOfOrNull { it.at } ?: Long.MAX_VALUE
    val first = people.filter { it.letter.trim().equals(mine, ignoreCase = true) }.minWithOrNull(compareBy({ since(it) }, { it.id })) ?: return null
    if (first.id == me.id) return null
    val used = people.map { it.letter.uppercase() }.toSet()
    // Where to start looking differs from person to person, so that two who must both change do not pick the same again.
    var n = Math.floorMod(me.id.hashCode(), 26)
    repeat(26) {
        if (mine + letterAt(n) !in used) return mine + letterAt(n)
        n = (n + 1) % 26
    }
    return freeLetter()
}

/** Letters used by more than one person, which would make their handwritten codes clash. */
fun AppData.duplicateLetters(): List<String> =
    livePeople.map { it.letter.uppercase() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted()

// ---- joining and syncing ---------------------------------------------------------

/** How this phone regards another phone that wants to sync. */
enum class Admission {
    /** Already in this move. */
    MEMBER,

    /** Not in this move, and this phone may let them in. */
    NEWCOMER,

    /** Was in this move and has been removed. */
    REMOVED,

    /** Not in this move, and this phone may not add people. */
    STRANGER,
}

/**
 * Decide whether to sync with the phone of [peer] (their own Person record, as
 * their phone sent it). Someone this phone has not heard of yet still counts
 * as a member when one of this move's Organisers added them.
 */
fun AppData.admission(device: DeviceInfo, peer: Person?): Admission {
    val mayAdd = can(device, Action.MANAGE_PEOPLE)
    if (peer == null) return if (mayAdd) Admission.NEWCOMER else Admission.STRANGER
    val combined = Roles(mergePeopleRaw(people, peer))
    val known = people.any { it.id == peer.id }
    val now = combined.now(peer.id)
    return when {
        now != null && now.second && (known || combined.vouchedFor(peer.id)) -> Admission.REMOVED
        known && now != null -> Admission.MEMBER
        now != null && combined.vouchedFor(peer.id) -> Admission.MEMBER
        mayAdd -> Admission.NEWCOMER
        else -> Admission.STRANGER
    }
}

private fun mergePeopleRaw(mine: List<Person>, peer: Person): List<Person> {
    val mineCopy = mine.firstOrNull { it.id == peer.id } ?: return mine + peer
    return mine.map { if (it.id == peer.id) it.copy(grants = (mineCopy.grants + peer.grants).distinct()) else it }
}

/** [peer]'s role once their own record is taken into account, or null when they have none this move accepts. */
fun AppData.roleOfPeer(peer: Person?): Role? {
    if (peer == null) return null
    val (role, removed) = Roles(mergePeopleRaw(people, peer)).now(peer.id) ?: return null
    return if (removed) null else role
}

/** Photo kinds a Helper's phone is not sent. */
val HIDDEN_FROM_HELPERS = setOf(PhotoKind.RECEIPT, PhotoKind.SERIAL)

/**
 * What a Helper's phone keeps of a merge. The other phone leaves out what a Helper is not sent,
 * but it goes by what it knows, and it may take this phone's person for more than a Helper (it
 * has heard of a promotion that does not count, say). So a phone whose person was a Helper before
 * the merge and is one after it takes in none of it: its costs, cost suggestions, and receipt and
 * serial-label photos stay exactly as they were ([was]).
 */
internal fun AppData.withHelpersShareOf(was: AppData): AppData {
    val photosWere = was.photos.filter { it.kind in HIDDEN_FROM_HELPERS }.associateBy { it.id }
    val suggestionsWere = was.proposals.filter { it.kind == ProposalKind.COST }.associateBy { it.id }
    val photoIds = photos.map { it.id }.toSet()
    val suggestionIds = proposals.map { it.id }.toSet()
    return copy(
        expenses = was.expenses,
        photos = photos.mapNotNull { if (it.kind in HIDDEN_FROM_HELPERS) photosWere[it.id] else it } + photosWere.values.filter { it.id !in photoIds },
        proposals = proposals.mapNotNull { if (it.kind == ProposalKind.COST) suggestionsWere[it.id] else it } + suggestionsWere.values.filter { it.id !in suggestionIds },
    )
}

/**
 * The part of the data that is sent to a phone whose person has [role].
 * A Helper's phone is not sent costs, cost suggestions, or receipt and
 * serial-number photos. Whole records are left out, never single fields, and
 * leaving a record out is not a deletion: nothing changes on the other phones
 * when the Helper syncs back.
 */
fun AppData.visibleTo(role: Role?): AppData =
    if (role != Role.HELPER) this
    else copy(
        expenses = emptyList(),
        photos = photos.filter { it.kind !in HIDDEN_FROM_HELPERS },
        proposals = proposals.filter { it.kind != ProposalKind.COST },
    )
