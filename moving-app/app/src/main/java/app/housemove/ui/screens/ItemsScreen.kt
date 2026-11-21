package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

private const val NO_ROOM = "none"

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
    val undecided = all.count { it.disposition == Disposition.UNDECIDED }

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
                onClick = { addItem() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add item") },
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.open("walk") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Walk-through", maxLines = 1)
                        }
                        OutlinedButton(onClick = { nav.open("plan") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.MeetingRoom, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Plan rooms", maxLines = 1)
                        }
                    }
                    if (undecided > 0) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { nav.open("triage") }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$undecided not triaged yet",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text("Triage", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            if (all.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Section("Nothing catalogued yet") {
                            Text(
                                "Start with the walk-through: go room by room and take one photo of each big thing. " +
                                    "Every photo becomes an item you can name and decide on later.",
                            )
                            Hint(
                                "Only furniture, appliances, electronics, valuables and awkward things belong here. " +
                                    "Small things are recorded by box when you pack.",
                            )
                        }
                    }
                }
            } else {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = decision == null, onClick = { decision = null }, label = { Text("All (${all.size})") })
                            Disposition.entries.forEach { d ->
                                val n = all.count { it.disposition == d }
                                FilterChip(
                                    selected = decision == d.name,
                                    onClick = { decision = if (decision == d.name) null else d.name },
                                    label = { Text("${d.label} ($n)") },
                                )
                            }
                        }
                        if (originRooms.isNotEmpty()) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(selected = roomFilter == null, onClick = { roomFilter = null }, label = { Text("All rooms") })
                                originRooms.forEach { r ->
                                    FilterChip(
                                        selected = roomFilter == r.id,
                                        onClick = { roomFilter = if (roomFilter == r.id) null else r.id },
                                        label = { Text(r.name) },
                                    )
                                }
                                if (anyWithoutRoom) {
                                    FilterChip(
                                        selected = roomFilter == NO_ROOM,
                                        onClick = { roomFilter = if (roomFilter == NO_ROOM) null else NO_ROOM },
                                        label = { Text("No room") },
                                    )
                                }
                            }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "nomatch") { EmptyHint("No items match these filters.") }
                }
                items(shown, key = { it.id }) { x ->
                    ItemRow(x, data) { nav.open("item/${x.id}") }
                    HorizontalDivider(Modifier.padding(start = 84.dp))
                }
            }
        }
    }
}

@Composable
private fun ItemRow(x: Item, data: AppData, onClick: () -> Unit) {
    val origin = data.room(x.originRoomId)
    val dest = data.room(x.destRoomId)
    val moving = x.disposition == Disposition.MOVE
    val fit = if (moving) data.fitOf(x) else Fit.UNKNOWN
    val status = when (x.disposition) {
        Disposition.MOVE -> "Move · " + stageText(x.stage, isBox = false)
        Disposition.SELL -> "Sell · " + if (x.gone) "sold" else if (x.listed) "listed" else "not listed yet"
        Disposition.DONATE -> "Donate · " + if (x.gone) "gone" else "still here"
        Disposition.BIN -> "Bin · " + if (x.gone) "gone" else "still here"
        Disposition.STORE -> "Store"
        Disposition.UNDECIDED -> "Not decided yet"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerThumb(x.id, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(x.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoomBadge(origin, Modifier.weight(1f, fill = false))
                if (moving) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "goes to",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    RoomBadge(dest, Modifier.weight(1f, fill = false))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    fit == Fit.NO && x.needsDisassembly -> Flag("Take apart")
                    fit == Fit.NO -> Flag("Won't fit", error = true)
                    fit == Fit.TIGHT -> Flag("Tight fit")
                }
                if (x.fragile) Flag("Fragile")
                if (x.damaged) Flag("Damaged", error = true)
            }
        }
    }
}
