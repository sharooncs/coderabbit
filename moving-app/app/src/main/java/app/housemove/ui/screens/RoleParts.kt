package app.housemove.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.housemove.data.*
import app.housemove.ui.shared
import app.housemove.ui.withArticle

/*
 * What Home says about this phone's own role (design 2.1, section 2): a banner when the role
 * changes ("You are now a Decider"), and for someone just promoted, that costs and decisions
 * arrive at the next sync with an Organiser or a Decider, until this phone holds them.
 */

private const val ROLE_PREFS = "housemove-roles"

/**
 * Which role change this phone's person has read about, as the time of the grant they last
 * tapped "Got it" on, per person. Kept on the phone only: it is about what this person has seen.
 */
internal class SeenRoles(private val context: Context, private val personId: String) {
    private val prefs get() = context.getSharedPreferences(ROLE_PREFS, Context.MODE_PRIVATE)
    var seenAt by mutableLongStateOf(if (personId.isEmpty()) 0L else prefs.getLong(personId, 0L))
        private set

    fun mark(at: Long) {
        seenAt = maxOf(seenAt, at)
        if (personId.isNotEmpty()) prefs.edit().putLong(personId, seenAt).apply()
    }
}

@Composable
internal fun rememberSeenRoles(personId: String): SeenRoles {
    val context = LocalContext.current.applicationContext
    return remember(context, personId) { SeenRoles(context, personId) }
}

/** What the role banner says: [title] in large type, [joined] when this is the first role the person was given. */
internal class RoleNews(val role: Role, val title: String, val at: Long, val joined: Boolean)

/**
 * The latest role this phone's person was given by someone else, when they have not read about it yet
 * ([seenAt]); null otherwise, and always on a move with one person or for someone removed (People says that).
 * The first role someone is given is how they joined: "You joined as a Decider". A later one is a change:
 * "You are now a Decider".
 */
internal fun AppData.roleNews(device: DeviceInfo, seenAt: Long): RoleNews? {
    if (!shared()) return null
    val me = me(device) ?: return null
    if (me.removed) return null
    val grants = me.grants.sortedBy { it.at }
    val last = grants.lastOrNull() ?: return null
    // A role the person gave themselves (founding the move) is no news to them.
    if (last.removed || last.by.isEmpty() || last.by == me.id || last.at <= seenAt) return null
    val earlier = grants.dropLast(1).lastOrNull { !it.removed }
    val role = me.role
    return if (earlier == null) RoleNews(role, "You joined as ${role.withArticle()}", last.at, joined = true)
    else if (earlier.role == role) null
    else RoleNews(role, "You are now ${role.withArticle()}", last.at, joined = false)
}

/**
 * Has this phone's person been made an Organiser or a Decider after being a Helper, while this phone still
 * holds none of what Helpers are not sent? Then their costs and decisions are on the other phones and arrive
 * at the next sync with an Organiser or a Decider.
 *
 * There is no flag for this in the data; it is worked out from what the phone holds:
 *  1. the person's role history ([Person.grants]) has them as a Helper before their present role, and
 *  2. the phone holds no costs, cost suggestions, hidden item records (values, prices, serial numbers),
 *     receipt or serial-label photos, refused changes or other people's hand-over amounts ([holdsHidden]), and
 *  3. something it does hold shows that such details exist on the other phones: an item decided Sell (it
 *     carries a price), a device (it carries a serial number), or a cost named in the activity log.
 * So it goes away by itself once the sync has brought them, and never shows on a move with nothing to bring.
 */
internal fun AppData.awaitsHidden(device: DeviceInfo): Boolean {
    if (!shared() || !hasOrganiser()) return false
    val me = me(device) ?: return false
    if (me.removed || me.role == Role.HELPER) return false
    val grants = me.grants.filter { !it.removed }.sortedBy { it.at }
    if (grants.size < 2 || grants.dropLast(1).none { it.role == Role.HELPER }) return false
    if (holdsHidden(me.id)) return false
    return liveItems.any { it.disposition == Disposition.SELL || it.isElectronic() } ||
        activity.any { !it.deleted && it.text.contains("cost") }
}
