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
}

/**
 * The one place that says who may do what.
 *
 * | | Organiser | Decider | Helper |
 * |---|---|---|---|
 * | MANAGE_PEOPLE, MOVE_SETTINGS, ERASE, RESTORE_BACKUP, DECIDE, APPROVE | yes | no | no |
 * | SUGGEST | yes | yes | no |
 * | CARRY_OUT | yes | once approved | no |
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
 * a Decider's phone writing itself a promotion, is ignored. Because past
 * roles are kept, a decision made by an Organiser stays good after they are
 * demoted or removed.
 */
class Roles(people: Collection<Person>) {
    private class Step(val at: Long, val role: Role, val removed: Boolean, val by: String) {
        val organiser get() = role == Role.ORGANISER && !removed
    }

    private val steps = HashMap<String, ArrayList<Step>>()

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
            organisers += (if (step.organiser) 1 else 0) - (if (mine?.lastOrNull()?.organiser == true) 1 else 0)
            steps.getOrPut(id) { ArrayList() }.add(step)
        }
    }

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
