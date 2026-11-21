package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

private const val NO_ROOM = "none"

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

    val all = data.liveItems
    val originRooms = data.roomsOf(Home.ORIGIN)
    val anyWithoutRoom = all.any { data.room(it.originRoomId) == null }
    val shown = all.filter { i ->
        (decision == null || i.disposition.name == decision) &&
            when (roomFilter) {
                null -> true
                NO_ROOM -> data.room(i.originRoomId) == null
                else -> i.originRoomId == roomFilter
            }
    }.sortedWith(compareBy({ data.room(it.originRoomId)?.sort ?: Int.MAX_VALUE }, { data.room(it.originRoomId)?.name ?: "~" }, { it.createdAt }))

    // On a small screen or with large text, tiles go fewer to a row so their words still fit.
    val config = LocalConfiguration.current
    val narrow = config.screenWidthDp / config.fontScale < 340

    fun addItem() {
        val room = roomFilter?.takeIf { it != NO_ROOM } ?: device.currentRoomId?.takeIf { data.room(it) != null }
        val created = store.createItem("", room)
        nav.open("item/${created.id}")
    }

    Screen(
        title = "Items",
        back = false,
        tab = "items",
        actions = { TopActions() },
        fab = {
            ExtendedFloatingActionButton(
                containerColor = Night.Mint, contentColor = Night.OnMint,
                onClick = { addItem() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add item") },
            )
        },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "shortcuts") {
                val tiles = shortcuts(data)
                TileGrid(tiles, Modifier.padding(horizontal = 16.dp), columns = tiles.size.coerceAtMost(if (narrow) 2 else 3)) { s, m ->
                    Tile(s.icon, s.title, onClick = { nav.open(s.route) }, modifier = m, status = s.status, done = s.done, alert = s.alert)
                }
            }
            stickyHeader(key = "filters") {
                Row(
                    Modifier.fillMaxWidth().groundBand()
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (originRooms.isNotEmpty()) {
                        RoomFilterChip(
                            rooms = originRooms,
                            withNoRoom = anyWithoutRoom,
                            selected = roomFilter,
                            onPick = { roomFilter = it },
                        )
                    }
                    FilterChip(selected = decision == null, onClick = { decision = null }, label = { Text("All ${all.size}") })
                    Disposition.entries.forEach { d ->
                        val n = all.count { it.disposition == d }
                        // Decisions nobody has made yet are left out, to keep the row short.
                        if (n > 0 || decision == d.name) {
                            FilterChip(
                                selected = decision == d.name,
                                onClick = { decision = if (decision == d.name) null else d.name },
                                label = { Text("${d.label} $n") },
                                leadingIcon = { Icon(d.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "nomatch") {
                    EmptyState(
                        Icons.Default.SearchOff, "Nothing matches",
                        action = "Show everything", onAction = { decision = null; roomFilter = null },
                    )
                }
            }
            items(shown.chunked(2), key = { it.first().id }) { pair ->
                Row(
                    Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { x -> ItemCard(x, data, Modifier.weight(1f).fillMaxHeight()) { nav.open("item/${x.id}") } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The other ways into the catalogue, each with how far along it is. Parts that are switched off are left out. */
private fun shortcuts(data: AppData): List<Shortcut> {
    val out = ArrayList<Shortcut>()
    out += Shortcut(Pic.Camera, "Walk-through", "walk", status = "Add by photo")
    if (data.uses(Part.TRIAGE)) {
        val undecided = data.liveItems.count { it.disposition == Disposition.UNDECIDED }
        out += Shortcut(
            Icons.Default.Rule, "Triage", "triage",
            status = if (undecided > 0) "$undecided to decide" else "All decided", done = undecided == 0,
        )
    }
    if (data.uses(Part.PLAN)) {
        val moving = data.movingItems()
        val noRoom = moving.count { data.room(it.destRoomId) == null }
        val wontFit = moving.count { data.fitOf(it) == Fit.NO && !it.needsDisassembly }
        out += Shortcut(
            Icons.Default.Map, "Plan rooms", "plan",
            status = when {
                wontFit > 0 -> "$wontFit won't fit"
                noRoom > 0 -> "$noRoom need a room"
                moving.isEmpty() -> null
                else -> "All placed"
            },
            done = moving.isNotEmpty() && noRoom == 0 && wontFit == 0,
            alert = wontFit > 0,
        )
    }
    return out
}

/** One chip that opens the list of old-home rooms, so rooms do not need a filter row of their own. */
@Composable
private fun RoomFilterChip(rooms: List<Room>, withNoRoom: Boolean, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (selected) {
        null -> "All rooms"
        NO_ROOM -> "No room"
        else -> rooms.firstOrNull { it.id == selected }?.name ?: "All rooms"
    }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = { Icon(Pic.Room, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose a room", modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All rooms") }, onClick = { onPick(null); open = false })
            rooms.forEach { r ->
                DropdownMenuItem(text = { Text(r.name) }, onClick = { onPick(r.id); open = false })
            }
            if (withNoRoom) DropdownMenuItem(text = { Text("No room") }, onClick = { onPick(NO_ROOM); open = false })
        }
    }
}

/** One item as a picture: photo, name, the decision, where it is going, and anything to watch out for. */
@Composable
private fun ItemCard(x: Item, data: AppData, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val moving = x.disposition == Disposition.MOVE
    val fit = if (moving && data.uses(Part.PLAN)) data.fitOf(x) else Fit.UNKNOWN
    val state = when (x.disposition) {
        Disposition.MOVE -> if (x.stage == Stage.AT_ORIGIN) "Move" else "Move · " + stageText(x.stage, isBox = false, data.vehicle())
        Disposition.SELL -> if (x.gone) "Sold" else if (x.listed) "Sell · listed" else "Sell · not listed"
        Disposition.DONATE -> if (x.gone) "Donate · gone" else "Donate"
        Disposition.BIN -> if (x.gone) "Bin · gone" else "Bin"
        Disposition.STORE -> "Store"
        Disposition.UNDECIDED -> "Not decided"
    }
    Column(
        modifier
            .glass(RoundedCornerShape(18.dp), shadow = 5.dp)
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val photo = data.photosOf(x.id).firstOrNull()
        val frame = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
        if (photo != null) PhotoThumb(photo, frame) else {
            Box(frame.clip(RoundedCornerShape(8.dp)).background(scheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Pic.Item, contentDescription = "No photo yet", tint = scheme.outline, modifier = Modifier.size(44.dp))
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(x.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (x.disposition == Disposition.UNDECIDED) {
                IconFlag(x.disposition.icon(), state)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(x.disposition.icon(), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(state, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val flags = moving || x.fragile || x.damaged
            if (flags) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (moving) RoomBadge(data.room(x.destRoomId))
                    when {
                        fit == Fit.NO && x.needsDisassembly -> IconFlag(Pic.TakeApart, "Take apart")
                        fit == Fit.NO -> IconFlag(Icons.Default.Warning, "Won't fit", error = true)
                        fit == Fit.TIGHT -> IconFlag(Icons.Default.Warning, "Tight fit")
                    }
                    if (x.fragile) IconFlag(Pic.Fragile, "Fragile")
                    if (x.damaged) IconFlag(Pic.Damage, "Damaged", error = true)
                }
            }
        }
    }
}
