package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

private const val LOOSE = "loose"
private const val PACKED = "packed"

private data class Shortcut(
    val icon: ImageVector,
    val title: String,
    val route: String,
    val status: String? = null,
    val done: Boolean = false,
    val alert: Boolean = false,
)

/** The catalogue of big things: furniture, appliances, electronics, valuables. */
@Composable
fun ItemsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()

    // Filters survive leaving and coming back to the tab. Null means "all".
    var decision by rememberSaveable { mutableStateOf<String?>(null) }
    var roomFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var packing by rememberSaveable { mutableStateOf<String?>(null) }

    val all = data.liveItems
    val originRooms = data.roomsOf(Home.ORIGIN)
    val anyWithoutRoom = all.any { data.room(it.originRoomId) == null }
    val packedIds = all.filter { data.containerOf(it) != null }.map { it.id }.toSet()
    // The loose / packed filter only appears once something has been put in a container.
    val packedFilter = packing.takeIf { packedIds.isNotEmpty() }
    val shown = all.filter { i ->
        (decision == null || i.disposition.name == decision) &&
            (packedFilter == null || (i.id in packedIds) == (packedFilter == PACKED)) &&
            when (roomFilter) {
                null -> true
                NO_ROOM -> data.room(i.originRoomId) == null
                else -> i.originRoomId == roomFilter
            }
    }.sortedWith(compareBy({ data.room(it.originRoomId)?.sort ?: Int.MAX_VALUE }, { data.room(it.originRoomId)?.name ?: "~" }, { it.createdAt }))
    val waiting = data.waitingProposals().groupBy { it.targetId }

    // On a small screen or with large text, tiles go fewer to a row so their words still fit.
    val narrow = narrowScreen()

    fun addItem() {
        val room = roomFilter?.takeIf { it != NO_ROOM } ?: device.currentRoomId?.takeIf { data.room(it) != null }
        val created = store.createItem(data.defaultItemName(room), room)
        nav.open("item/${created.id}")
    }

    Screen(
        title = "Items",
        back = false,
        tab = "items",
        actions = { TopActions() },
        // With nothing listed yet, the picture in the middle of the page carries the one button.
        fab = { if (all.isNotEmpty()) ListFab(Icons.Default.Add, "Add item") { addItem() } },
    ) {
        if (all.isEmpty()) {
            ScrollColumn {
                EmptyState(
                    Pic.Camera, "Nothing listed yet",
                    text = "Go room by room and photograph each big thing.",
                    action = "Start the walk-through", onAction = { nav.open("walk") },
                )
            }
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "shortcuts") {
                val tiles = shortcuts(data, store.can(Action.SUGGEST), store.can(Action.APPROVE))
                if (narrowScreen(300f)) {
                    // A small phone with large text: tiles side by side would break their names in two, so they
                    // are rows of one panel, which is also shorter.
                    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        tiles.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(color = Night.Hairline)
                            MenuRow(s.icon, s.title, sub = s.status, onClick = { nav.open(s.route) })
                        }
                    }
                } else TileGrid(tiles, Modifier.padding(horizontal = 16.dp), columns = tiles.size.coerceAtMost(if (narrow) 2 else 3)) { s, m ->
                    Tile(s.icon, s.title, onClick = { nav.open(s.route) }, modifier = m, status = s.status, done = s.done, alert = s.alert)
                }
            }
            stickyHeader(key = "filters") {
                Row(
                    Modifier.fillMaxWidth().groundBand()
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (originRooms.isNotEmpty()) RoomFilterChip(originRooms, roomFilter, onPick = { roomFilter = it }, withNoRoom = anyWithoutRoom)
                    GlassChip("All ${all.size}", selected = decision == null && packedFilter == null, onClick = { decision = null; packing = null })
                    if (packedIds.isNotEmpty()) {
                        GlassChip(
                            "Loose ${all.size - packedIds.size}", icon = Pic.Item, selected = packedFilter == LOOSE,
                            onClick = { packing = if (packedFilter == LOOSE) null else LOOSE },
                        )
                        GlassChip(
                            "Packed ${packedIds.size}", icon = Pic.Box, selected = packedFilter == PACKED,
                            onClick = { packing = if (packedFilter == PACKED) null else PACKED },
                        )
                    }
                    Disposition.entries.forEach { d ->
                        val n = all.count { it.disposition == d }
                        // Decisions nobody has made yet are left out, to keep the row short.
                        if (n > 0 || decision == d.name) {
                            GlassChip(
                                "${d.label} $n", icon = d.icon(), selected = decision == d.name,
                                tone = if (d == Disposition.UNDECIDED) GlassTone.Amber else GlassTone.Plain,
                                onClick = { decision = if (decision == d.name) null else d.name },
                            )
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "nomatch") {
                    EmptyState(
                        Icons.Default.SearchOff, "Nothing matches",
                        action = "Show everything", onAction = { decision = null; roomFilter = null; packing = null },
                    )
                }
            }
            items(shown.chunked(2), key = { it.first().id }) { pair ->
                Row(
                    Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { x ->
                        ItemCard(x, data, waiting[x.id].orEmpty(), Modifier.weight(1f).fillMaxHeight()) { nav.open("item/${x.id}") }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The other ways into the catalogue, each with how far along it is. Parts that are switched
 * off are left out, and so are deciding and planning for someone who may do neither.
 */
private fun shortcuts(data: AppData, maySuggest: Boolean, mayApprove: Boolean): List<Shortcut> {
    val out = ArrayList<Shortcut>()
    out += Shortcut(Pic.Camera, "Walk-through", "walk", status = "Add by photo")
    // An Organiser is told first of what others are waiting on them for.
    val asked = if (mayApprove) data.waitingProposals() else emptyList()
    if (data.uses(Part.TRIAGE) && maySuggest) {
        val undecided = data.liveItems.count { it.disposition == Disposition.UNDECIDED }
        val toApprove = asked.count { it.kind == ProposalKind.ITEM_DECISION || it.isQuestion }
        out += Shortcut(
            Icons.Default.Rule, "Triage", "triage",
            status = if (toApprove > 0) "$toApprove to approve" else if (undecided > 0) "$undecided to decide" else "All decided",
            done = undecided == 0 && toApprove == 0,
        )
    }
    if (data.uses(Part.PLAN) && maySuggest) {
        // Counted as the room plan and Home count them: something packed in a container goes where that goes.
        val moving = data.looseItems()
        val noRoom = moving.count { data.room(it.destRoomId) == null }
        val wontFit = moving.count { data.fitOf(it) == Fit.NO && !it.needsDisassembly }
        val toApprove = asked.count { it.kind == ProposalKind.ITEM_ROOM }
        out += Shortcut(
            Icons.Default.Map, "Plan rooms", "plan",
            status = when {
                wontFit > 0 -> "$wontFit won't fit"
                toApprove > 0 -> "$toApprove to approve"
                noRoom > 0 -> "$noRoom need a room"
                moving.isEmpty() -> null
                else -> "All placed"
            },
            done = moving.isNotEmpty() && noRoom == 0 && wontFit == 0 && toApprove == 0,
            alert = wontFit > 0,
        )
    }
    return out
}

/**
 * One item as a picture: its photo with how it stands marked on it, its name, where it is
 * going and anything to watch out for. Something packed carries its container's code;
 * something with a suggestion waiting carries the amber callout.
 */
@Composable
private fun ItemCard(x: Item, data: AppData, waiting: List<Proposal>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val moving = x.disposition == Disposition.MOVE
    val fit = if (moving && data.uses(Part.PLAN)) data.fitOf(x) else Fit.UNKNOWN
    val (state, tone) = data.itemState(x)
    val container = data.containerOf(x)
    Column(
        modifier
            .glass(RoundedCornerShape(22.dp), shadow = 6.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            // Something that has gone fades back, so what is still here stands out.
            ItemPhoto(x.id, Modifier.fillMaxWidth().aspectRatio(4f / 3f).alpha(if (x.gone) 0.5f else 1f), RoundedCornerShape(17.dp), iconSize = 40.dp)
            PhotoMark(
                if (tone == GlassTone.Mint) Icons.Default.CheckCircle else x.disposition.icon(), state,
                Modifier.align(Alignment.TopStart).padding(6.dp), tint = tone.onPhoto(),
            )
            if (container != null) PhotoMark(Pic.Box, container.label, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(x.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Something packed in a container goes where that goes: it only shows a room if it has one of its own.
            val showRoom = moving && (container == null || data.room(x.destRoomId) != null)
            if (showRoom || x.fragile || x.damaged) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showRoom) RoomBadge(data.room(x.destRoomId))
                    when {
                        fit == Fit.NO && x.needsDisassembly -> IconFlag(Pic.TakeApart, "Take apart")
                        fit == Fit.NO -> IconFlag(Icons.Default.Warning, "Won't fit", error = true)
                        fit == Fit.TIGHT -> IconFlag(Icons.Default.Warning, "Tight fit")
                    }
                    if (x.fragile) IconFlag(Pic.Fragile, "Fragile")
                    if (x.damaged) IconFlag(Pic.Damage, "Damaged", error = true)
                }
            }
            waiting.firstOrNull()?.let { WaitingTag(it, Modifier.fillMaxWidth(), compact = true, what = data.proposalText(it)) }
        }
    }
}
