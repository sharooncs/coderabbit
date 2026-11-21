package app.housemove.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.screens.agoText

/**
 * The move as six phases, each with the screens that belong to it. The same
 * order as the printed step-by-step sheet, so the app and the paper agree.
 * Home shows it as a strip; More shows every step as a tile. Search and the scanner
 * are not steps: they are the two round buttons at the top of both.
 */
data class JourneyStep(
    val title: String,
    val icon: ImageVector,
    val route: String,
    /** A few words on how far along this is, or null. */
    val status: (AppData, DeviceInfo) -> String? = { _, _ -> null },
    val done: (AppData) -> Boolean = { false },
    /** Needs attention now. */
    val alert: (AppData) -> Boolean = { false },
    /** The part of the app this belongs to; hidden when that part is switched off. */
    val part: Part? = null,
    /** A title that depends on the move, e.g. "Load the truck". */
    val titleFor: ((AppData) -> String)? = null,
    /** A title that depends on who is looking, e.g. "Approvals" for an Organiser and "My suggestions" for a Decider. */
    val titleBy: ((AppData, DeviceInfo) -> String)? = null,
    /** Whether this phone's person is shown the step at all: nobody is offered a screen they cannot act on. */
    val who: (AppData, DeviceInfo) -> Boolean = { _, _ -> true },
    /** Whether [alert] is this person's to clear. Nobody is flagged something only someone else can put right. */
    val alertTo: (AppData, DeviceInfo) -> Boolean = { _, _ -> true },
    /** Something here is waiting for an answer: marked in amber, with an hourglass. */
    val waiting: (AppData, DeviceInfo) -> Boolean = { _, _ -> false },
) {
    fun title(d: AppData): String = titleFor?.invoke(d) ?: title
    fun title(d: AppData, device: DeviceInfo): String = titleBy?.invoke(d, device) ?: title(d)
    fun shownFor(d: AppData): Boolean = part == null || d.uses(part)
    fun shownFor(d: AppData, device: DeviceInfo): Boolean = shownFor(d) && who(d, device)
    fun alerts(d: AppData, device: DeviceInfo): Boolean = alert(d) && alertTo(d, device)
}

/** How many suggestions and questions wait: for someone who may approve, all of them; for anyone else, their own. */
private fun AppData.waitingFor(device: DeviceInfo): Int =
    if (can(device, Action.APPROVE)) waitingCount() else proposalCounts(device.personId)[ProposalState.WAITING] ?: 0

/** May this phone's person decide such things, or at least suggest them? The rules are the Store's ([can]). */
private fun decidesOrSuggests(d: AppData, device: DeviceInfo) = d.can(device, Action.DECIDE) || d.can(device, Action.SUGGEST)

private fun AppData.overdue(t: Task) = !t.done && (dueDay(t) ?: Long.MAX_VALUE) < today()

data class JourneyPhase(
    val name: String,
    val short: String,
    val timing: String,
    val icon: ImageVector,
    val steps: List<JourneyStep>,
    val progress: (AppData) -> Ratio,
)

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private fun AppData.recordPhotos(home: Home): Int {
    val owners = roomsOf(home).map { it.id }.toSet() + ("home:" + home.name)
    return livePhotos.count { it.ownerId in owners }
}

val JOURNEY: List<JourneyPhase> = listOf(
    JourneyPhase(
        "Set up", "Set up", "Day one", Icons.Default.Flag,
        listOf(
            JourneyStep(
                "Rooms", Icons.Default.MeetingRoom, "rooms",
                status = { d, _ -> "${d.roomsOf(Home.ORIGIN).size} old · ${d.roomsOf(Home.DEST).size} new" },
                done = { d -> d.roomsOf(Home.ORIGIN).isNotEmpty() && d.roomsOf(Home.DEST).isNotEmpty() },
            ),
            JourneyStep(
                "People", Icons.Default.Group, "people",
                status = { d, _ ->
                    val toJoin = d.livePeople.count { it.joinedAt == 0L }
                    when {
                        !d.shared() -> "Just you"
                        toJoin > 0 -> "$toJoin still to join"
                        else -> "${d.livePeople.size} people"
                    }
                },
                done = { d -> d.shared() && d.livePeople.all { it.joinedAt != 0L } },
            ),
            JourneyStep(
                "Sync and backup", Icons.Default.Sync, "sync",
                status = { _, dev -> if (dev.lastSyncAt == 0L) "Not synced yet" else "Synced ${agoText(dev.lastSyncAt)}" },
            ),
            JourneyStep(
                "Settings", Icons.Default.Settings, "settings",
                status = { d, _ -> if (d.move.kind == MoveKind.INVENTORY) null else d.move.moveDay?.let { "Move day ${fmtDayShort(it)}" } ?: "No move day yet" },
                done = { d -> d.move.moveDay != null },
                alert = { d -> d.move.moveDay == null && d.move.kind != MoveKind.INVENTORY },
                alertTo = { d, dev -> d.can(dev, Action.MOVE_SETTINGS) },
            ),
        ),
        progress = { d ->
            Ratio(
                listOf(d.move.setupDone, d.roomsOf(Home.ORIGIN).isNotEmpty(), d.roomsOf(Home.DEST).isNotEmpty(), d.move.moveDay != null).count { it }, 4,
            )
        },
    ),
    JourneyPhase(
        "List and decide", "List", "6–4 weeks before", Icons.Default.PhotoCamera,
        listOf(
            JourneyStep(
                "Walk-through", Icons.Default.PhotoCamera, "walk",
                status = { d, _ -> plural(d.liveItems.size, "item") },
                done = { d -> d.liveItems.isNotEmpty() },
            ),
            JourneyStep(
                "Triage", Icons.Default.Rule, "triage", part = Part.TRIAGE, who = ::decidesOrSuggests,
                status = { d, _ ->
                    val n = d.liveItems.count { it.disposition == Disposition.UNDECIDED }
                    if (n > 0) "$n to decide" else if (d.liveItems.isEmpty()) null else "All decided"
                },
                done = { d -> d.liveItems.isNotEmpty() && d.liveItems.none { it.disposition == Disposition.UNDECIDED } },
            ),
            // Only on a move with more than one person: alone there is nobody to suggest anything.
            JourneyStep(
                "Approvals", Icons.Default.TaskAlt, "approvals",
                who = { d, _ -> d.shared() || d.liveProposals.isNotEmpty() },
                titleBy = { d, dev ->
                    when {
                        d.can(dev, Action.APPROVE) -> "Approvals"
                        d.roleOf(dev) == app.housemove.data.Role.HELPER -> "My questions"
                        else -> "My suggestions"
                    }
                },
                status = { d, dev -> d.waitingFor(dev).let { n -> if (n == 0) "Nothing waiting" else "$n waiting" } },
                waiting = { d, dev -> d.waitingFor(dev) > 0 },
            ),
            JourneyStep(
                "Devices", Icons.Default.Tv, "electronics", part = Part.DEVICES,
                status = { d, _ ->
                    val devices = d.liveItems.filter { it.isElectronic() }
                    val missing = devices.count { it.serial.isBlank() && d.photosOf(it.id, PhotoKind.SERIAL).isEmpty() }
                    if (devices.isEmpty()) null else if (missing > 0) plural(missing, "serial") + " missing" else "All recorded"
                },
                done = { d ->
                    val devices = d.liveItems.filter { it.isElectronic() }
                    devices.isNotEmpty() && devices.none { it.serial.isBlank() && d.photosOf(it.id, PhotoKind.SERIAL).isEmpty() }
                },
            ),
            JourneyStep(
                "Plan rooms", Icons.Default.Map, "plan", part = Part.PLAN, who = ::decidesOrSuggests,
                status = { d, _ ->
                    val moving = d.movingItems()
                    val noRoom = moving.count { d.room(it.destRoomId) == null }
                    val wontFit = moving.count { d.fitOf(it) == Fit.NO && !it.needsDisassembly }
                    when {
                        wontFit > 0 -> "$wontFit won't fit"
                        noRoom > 0 -> "$noRoom need a room"
                        moving.isEmpty() -> null
                        else -> "All placed"
                    }
                },
                done = { d -> d.movingItems().let { m -> m.isNotEmpty() && m.all { d.room(it.destRoomId) != null } } },
                alert = { d -> d.movingItems().any { d.fitOf(it) == Fit.NO && !it.needsDisassembly } },
            ),
            JourneyStep(
                "Sell, donate, bin", Icons.Default.Sell, "disposal", part = Part.TRIAGE,
                who = { d, dev -> decidesOrSuggests(d, dev) || d.can(dev, Action.CARRY_OUT) },
                status = { d, _ ->
                    val going = d.liveItems.filter { it.disposition in listOf(Disposition.SELL, Disposition.DONATE, Disposition.BIN) }
                    val left = going.count { !it.gone }
                    if (going.isEmpty()) null else if (left > 0) "$left still to go" else "All gone"
                },
                done = { d ->
                    val going = d.liveItems.filter { it.disposition in listOf(Disposition.SELL, Disposition.DONATE, Disposition.BIN) }
                    going.isNotEmpty() && going.all { it.gone }
                },
            ),
        ),
        progress = { d -> d.progress().let { Ratio(it.triage.done + it.destinations.done, it.triage.total + it.destinations.total) } },
    ),
    JourneyPhase(
        "Get ready", "Ready", "4–3 weeks before", Icons.Default.Checklist,
        listOf(
            JourneyStep(
                "Timeline", Icons.Default.Checklist, "tasks", part = Part.TIMELINE,
                status = { d, _ ->
                    val overdue = d.liveTasks.count { d.overdue(it) }
                    val p = d.progress().tasks
                    if (p.total == 0) "No tasks yet" else if (overdue > 0) "$overdue overdue" else "${p.done} of ${p.total} done"
                },
                done = { d -> d.progress().tasks.let { it.total > 0 && it.done == it.total } },
                alert = { d -> d.liveTasks.any { d.overdue(it) } },
                alertTo = { d, dev -> d.liveTasks.any { d.overdue(it) && d.mayTick(d.me(dev), it) } },
            ),
            JourneyStep(
                "Supplies and costs", Icons.Default.ShoppingCart, "budget", part = Part.BUDGET,
                titleBy = { d, dev -> if (decidesOrSuggests(d, dev)) "Supplies and costs" else "Supplies" },
                status = { d, _ ->
                    val toBuy = d.liveSupplies.sumOf { (it.need - it.have).coerceAtLeast(0) }
                    if (d.liveSupplies.isEmpty()) "No list yet" else if (toBuy > 0) "$toBuy to buy" else "All bought"
                },
                done = { d -> d.liveSupplies.isNotEmpty() && d.liveSupplies.all { it.have >= it.need } },
            ),
            JourneyStep(
                "Labels and signs", Icons.Default.Print, "labels", part = Part.LABELS,
                status = { d, _ -> if (d.move.labelsPrinted > 0) "Printed to L${d.move.labelsPrinted.toString().padStart(3, '0')}" else "Not printed yet" },
                done = { d -> d.move.labelsPrinted > 0 },
            ),
            JourneyStep(
                "Packing order", Icons.Default.FormatListNumbered, "packorder", part = Part.BOXES,
                status = { d, _ -> d.progress().roomsPacked.let { "${it.done} of ${it.total} packed" } },
                done = { d -> d.progress().roomsPacked.let { it.total > 0 && it.done == it.total } },
            ),
        ),
        progress = { d ->
            val early = d.liveTasks.filter { (it.offsetDays ?: 0) <= -14 }
            if (early.isEmpty()) d.progress().tasks else Ratio(early.count { it.done }, early.size)
        },
    ),
    JourneyPhase(
        "Pack", "Pack", "From 3 weeks before", Icons.Default.Inventory2,
        listOf(
            JourneyStep(
                "Rooms overview", Icons.Default.Roofing, "overview", part = Part.BOXES,
                status = { d, _ -> d.progress().roomsPacked.let { if (it.total == 0) "No rooms yet" else "${it.done} of ${it.total} packed" } },
                done = { d -> d.progress().roomsPacked.let { it.total > 0 && it.done == it.total } },
            ),
            JourneyStep(
                "Pack a box", Icons.Default.Inventory2, "pack", part = Part.BOXES,
                status = { d, _ -> d.progress().sealed.let { if (it.total == 0) "No boxes yet" else "${it.done} of ${it.total} sealed" } },
                done = { d -> d.progress().sealed.let { it.total > 0 && it.done == it.total } },
            ),
            JourneyStep(
                "Special lists", Icons.Default.Star, "lists", part = Part.BOXES,
                status = { d, _ ->
                    val n = d.movables().count { it.travelsWithMe || it.priority == Priority.FIRST_NIGHT }
                    if (n == 0) "Nothing marked" else plural(n, "thing") + " marked"
                },
            ),
        ),
        progress = { d -> d.progress().let { Ratio(it.sealed.done + it.roomsPacked.done, it.sealed.total + it.roomsPacked.total) } },
    ),
    JourneyPhase(
        "Move day", "Move", "On the day", Icons.Default.LocalShipping,
        listOf(
            JourneyStep(
                "Old home record", Icons.Default.PhotoCamera, "records/ORIGIN", part = Part.RECORDS,
                status = { d, _ -> plural(d.recordPhotos(Home.ORIGIN), "photo") },
                done = { d -> d.recordPhotos(Home.ORIGIN) > 0 },
            ),
            JourneyStep(
                "Load the van", Icons.Default.LocalShipping, "load", part = Part.MOVE_DAY,
                titleFor = { d -> "Load the ${d.vehicle()}" },
                status = { d, _ -> d.progress().loaded.let { "${it.done} of ${it.total} on" } },
                done = { d -> d.progress().loaded.let { it.total > 0 && it.done == it.total } },
            ),
            JourneyStep(
                "Final sweep", Icons.Default.CleaningServices, "sweep", part = Part.MOVE_DAY,
                status = { d, _ -> d.roomsOf(Home.ORIGIN).let { r -> "${r.count { it.swept }} of ${r.size} rooms" } },
                done = { d -> d.roomsOf(Home.ORIGIN).let { r -> r.isNotEmpty() && r.all { it.swept } } },
            ),
            JourneyStep(
                "New home record", Icons.Default.Home, "records/DEST", part = Part.RECORDS,
                status = { d, _ -> plural(d.recordPhotos(Home.DEST), "photo") },
                done = { d -> d.recordPhotos(Home.DEST) > 0 },
            ),
            JourneyStep(
                "Unload", Icons.Default.Download, "unload", part = Part.MOVE_DAY,
                status = { d, _ -> d.progress().delivered.let { "${it.done} of ${it.total} in" } },
                done = { d -> d.progress().delivered.let { it.total > 0 && it.done == it.total } },
            ),
            JourneyStep(
                "Reconcile", Icons.Default.FactCheck, "reconcile", part = Part.MOVE_DAY,
                status = { d, _ ->
                    val onVan = d.movables().count { it.stage == Stage.LOADED }
                    if (onVan > 0) "$onVan still on the ${d.vehicle()}" else "Nothing on the ${d.vehicle()}"
                },
                alert = { d ->
                    val mv = d.movables()
                    mv.any { it.stage == Stage.LOADED } && mv.any { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }
                },
            ),
        ),
        progress = { d -> d.progress().let { Ratio(it.loaded.done + it.delivered.done, it.loaded.total + it.delivered.total) } },
    ),
    JourneyPhase(
        "Settle in", "Settle", "After the move", Icons.Default.Weekend,
        listOf(
            JourneyStep(
                "Unpack", Icons.Default.Unarchive, "unpack", part = Part.UNPACK,
                status = { d, _ -> d.progress().unpacked.let { "${it.done} of ${it.total} done" } },
                done = { d -> d.progress().unpacked.let { it.total > 0 && it.done == it.total } },
            ),
            JourneyStep(
                "Reports", Icons.Default.Description, "reports",
                status = { d, _ -> d.movables().count { it.damaged }.let { if (it > 0) "$it damaged" else null } },
            ),
        ),
        progress = { d -> d.progress().unpacked },
    ),
)

/**
 * Is the screen at [route] offered to this phone's person? A screen that is a step of the
 * journey is offered when one of its steps is ([JourneyStep.who]); any other screen always is.
 * Home uses it so that nothing it points at leads somewhere the Everything list leaves out.
 */
fun offeredTo(route: String, data: AppData, device: DeviceInfo): Boolean {
    val steps = JOURNEY.flatMap { it.steps }.filter { it.route == route }
    return steps.isEmpty() || steps.any { it.who(data, device) }
}

/** The phases that have at least one step in this move, with their position in [JOURNEY]. */
fun shownPhases(data: AppData): List<IndexedValue<JourneyPhase>> =
    JOURNEY.withIndex().filter { (_, phase) -> phase.steps.any { it.shownFor(data) } }

/** Which phase the move is in now (an index into [JOURNEY]), always one that is shown. */
fun currentPhase(data: AppData, now: Long = today()): Int {
    val shown = shownPhases(data).map { it.index }
    val natural = naturalPhase(data, now)
    return shown.lastOrNull { it <= natural } ?: shown.firstOrNull() ?: 0
}

private fun naturalPhase(data: AppData, now: Long): Int {
    val mv = data.movables()
    val p = data.progress()
    val moveDay = data.move.moveDay
    return when {
        !data.move.setupDone || data.roomsOf(Home.ORIGIN).isEmpty() -> 0
        data.move.kind != MoveKind.INVENTORY && data.roomsOf(Home.DEST).isEmpty() -> 0
        mv.isNotEmpty() && mv.all { it.stage == Stage.DELIVERED || it.stage == Stage.DONE } -> 5
        mv.any { it.stage != Stage.AT_ORIGIN } -> 4
        moveDay != null && moveDay <= now && mv.isNotEmpty() -> 4
        data.liveBoxes.isNotEmpty() -> 3
        data.liveItems.isEmpty() || p.triage.done < p.triage.total || p.destinations.done < p.destinations.total -> 1
        moveDay != null && moveDay - now <= 21 -> 3
        else -> 2
    }
}

/** The six phases in a row: a ring of progress round each picture, the current one marked. */
@Composable
fun JourneyStrip(data: AppData, selected: Int, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        shownPhases(data).forEach { (i, phase) ->
            val ratio = phase.progress(data)
            val finished = ratio.total > 0 && ratio.done >= ratio.total
            val isSelected = i == selected
            Column(
                Modifier.weight(1f)
                    .then(if (isSelected) Modifier.glass(RoundedCornerShape(18.dp), shadow = 3.dp) else Modifier.clip(RoundedCornerShape(18.dp)))
                    .clickable(role = Role.Tab, onClickLabel = phase.name) { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Ring(ratio, size = 44.dp, stroke = 4.dp, color = if (finished) scheme.tertiary else scheme.primary) {
                    Icon(
                        if (finished) Icons.Default.Check else phase.icon,
                        contentDescription = null,
                        tint = when {
                            finished -> scheme.tertiary
                            i == current || isSelected -> scheme.primary
                            else -> scheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    phase.short,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == current || isSelected) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // A dot marks where the move is now.
                Box(
                    Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i == current) scheme.primary else Color.Transparent)
                )
            }
        }
    }
}

/** The steps of one phase as tiles. */
@Composable
fun PhaseTiles(phase: JourneyPhase, data: AppData, device: DeviceInfo, modifier: Modifier = Modifier, columns: Int = 2, skipRoutes: Set<String> = emptySet()) {
    val nav = LocalNav.current
    TileGrid(phase.steps.filter { it.route !in skipRoutes && it.shownFor(data, device) }, modifier, columns) { step, m ->
        Tile(
            step.icon, step.title(data, device), onClick = { nav.open(step.route) }, modifier = m,
            status = step.status(data, device), done = step.done(data), alert = step.alerts(data, device), waiting = step.waiting(data, device),
        )
    }
}
