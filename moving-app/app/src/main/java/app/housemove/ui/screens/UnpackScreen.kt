package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.Item
import app.housemove.data.Part
import app.housemove.data.Priority
import app.housemove.data.Ratio
import app.housemove.data.Room
import app.housemove.data.Stage
import app.housemove.data.movingItems
import app.housemove.data.title
import app.housemove.data.uses
import app.housemove.ui.ColorDot
import app.housemove.ui.EmptyState
import app.housemove.ui.IconFlag
import app.housemove.ui.LocalNav
import app.housemove.ui.LocalStore
import app.housemove.ui.OwnerThumb
import app.housemove.ui.Pic
import app.housemove.ui.RoomBadge
import app.housemove.ui.Screen
import app.housemove.ui.TopActions
import app.housemove.ui.appData
import app.housemove.ui.icon
import app.housemove.ui.open

/** After the move: what to open first, and which furniture still needs putting together. */
@Composable
fun UnpackScreen() {
    val data = appData()
    var tab by rememberSaveable { mutableStateOf(0) }
    val boxesWaiting = data.liveBoxes.count { it.stage == Stage.DELIVERED }
    val itemsWaiting = data.movingItems().count { it.stage == Stage.DELIVERED }
    // Someone who does not pack boxes with the app sees only the furniture.
    val showBoxes = data.uses(Part.BOXES) || data.liveBoxes.isNotEmpty()
    Screen("Unpack", actions = { TopActions() }) {
        if (showBoxes) TabRow(selectedTabIndex = tab) {
            MdIconTab(tab == 0, { tab = 0 }, "Boxes ($boxesWaiting)", Pic.Box)
            MdIconTab(tab == 1, { tab = 1 }, "Furniture ($itemsWaiting)", Pic.Item)
        }
        if (showBoxes && tab == 0) MdUnpackBoxes(Modifier.weight(1f)) else MdUnpackFurniture(Modifier.weight(1f))
    }
}

@Composable
private fun MdUnpackBoxes(modifier: Modifier) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    var roomFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    val arrived = data.liveBoxes.filter { mdArrived(it.stage) }
    val rooms = data.roomsOf(Home.DEST).filter { r -> arrived.any { it.destRoomId == r.id } }
    val filter = roomFilter?.takeIf { id -> rooms.any { it.id == id } }
    val shown = arrived.filter { filter == null || it.destRoomId == filter }
    val waiting = shown.filter { it.stage == Stage.DELIVERED }
        .sortedWith(compareBy<app.housemove.data.Box>({ it.priority.ordinal }, { data.room(it.destRoomId)?.name ?: "~" }, { it.label }))
    val done = shown.filter { it.stage == Stage.DONE }.sortedBy { it.label }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (arrived.isEmpty()) item(key = "empty") {
            EmptyState(Pic.Box, "No boxes here yet", text = "They appear once they are scanned in at the new home.")
        } else item(key = "head") {
            MdProgressHeader(
                Ratio(done.size, shown.size),
                "${done.size} of ${shown.size} unpacked",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                sub = "Looking for something? Search at the top finds its box.",
            ) {
                if (rooms.size > 1) Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == null, onClick = { roomFilter = null }, label = { Text("All rooms") })
                    rooms.forEach { r ->
                        FilterChip(
                            selected = filter == r.id, onClick = { roomFilter = r.id },
                            label = { Text(r.name) }, leadingIcon = { ColorDot(r.color) },
                        )
                    }
                }
            }
        }
        if (arrived.isNotEmpty() && waiting.isEmpty()) item(key = "alldone") {
            EmptyState(Icons.Default.CheckCircle, "Every box here is unpacked")
        }
        items(waiting, key = { "b" + it.id }) { box ->
            MdUnpackCard(
                ownerId = box.id,
                isBox = true,
                title = "Box ${box.label}",
                detail = box.contents,
                room = data.room(box.destRoomId),
                flags = listOfNotNull(
                    (box.priority.icon() to box.priority.label).takeIf { box.priority != Priority.WHENEVER },
                    (Pic.Fragile to "Fragile").takeIf { box.fragile },
                    (Pic.Upright to "This way up").takeIf { box.thisWayUp },
                ),
                onOpen = { nav.open("box/${box.id}") },
                action = "Unpacked",
                onAction = { store.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.DONE) } },
            )
        }
        if (done.isNotEmpty()) {
            item(key = "donehead") { MdDoneHeader("Unpacked boxes", done.size, showDone) { showDone = !showDone } }
            if (showDone) items(done, key = { "d" + it.id }) { box ->
                MdDoneRow(
                    ownerId = box.id,
                    isBox = true,
                    title = "Box ${box.label}",
                    detail = box.contents,
                    room = data.room(box.destRoomId),
                    onOpen = { nav.open("box/${box.id}") },
                    onUndo = { store.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.DELIVERED) } },
                )
            }
        }
    }
}

@Composable
private fun MdUnpackFurniture(modifier: Modifier) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    var showDone by rememberSaveable { mutableStateOf(false) }

    val arrived = data.movingItems().filter { mdArrived(it.stage) }
    val waiting = arrived.filter { it.stage == Stage.DELIVERED }
        .sortedWith(compareBy<Item>({ !it.firstNight }, { data.room(it.destRoomId)?.name ?: "~" }, { it.title() }))
    val done = arrived.filter { it.stage == Stage.DONE }.sortedBy { it.title() }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (arrived.isEmpty()) item(key = "empty") {
            EmptyState(Pic.Item, "No furniture here yet", text = "It appears once it is scanned in at the new home.")
        } else item(key = "head") {
            MdProgressHeader(
                Ratio(done.size, arrived.size),
                "${done.size} of ${arrived.size} in place",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                sub = "Beds first. Tap a thing for its take-apart photos.",
            )
        }
        if (arrived.isNotEmpty() && waiting.isEmpty()) item(key = "alldone") {
            EmptyState(Icons.Default.CheckCircle, "Everything is in place")
        }
        items(waiting, key = { "i" + it.id }) { item ->
            val needsBuilding = item.needsDisassembly && !item.reassembled
            MdUnpackCard(
                ownerId = item.id,
                isBox = false,
                title = item.title(),
                detail = if (needsBuilding) "Needs putting back together" else "",
                detailIsWarning = needsBuilding,
                room = data.room(item.destRoomId),
                flags = listOfNotNull(
                    (Pic.FirstNight to "First night").takeIf { item.firstNight },
                    (Pic.Fragile to "Fragile").takeIf { item.fragile },
                    (Pic.TwoPeople to "2 people").takeIf { item.twoPerson },
                ),
                onOpen = { nav.open("item/${item.id}") },
                action = "In place",
                onAction = { store.updateItems(listOf(item.id)) { it.copy(stage = Stage.DONE) } },
                extra = if (!item.needsDisassembly) null else {
                    {
                        // Side by side where there is room, one under the other on a small phone.
                        FlowRow(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                Modifier.toggleable(item.reassembled, role = Role.Checkbox) { v ->
                                    store.updateItems(listOf(item.id)) { it.copy(reassembled = v) }
                                }.heightIn(min = 48.dp).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = item.reassembled, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                                Text("Put back together", style = MaterialTheme.typography.bodyLarge)
                            }
                            if (needsBuilding) OutlinedButton(
                                onClick = { nav.open("item/${item.id}") },
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Pic.TakeApart, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Photos and fittings", maxLines = 1)
                            }
                        }
                    }
                },
            )
        }
        if (done.isNotEmpty()) {
            item(key = "donehead") { MdDoneHeader("In place", done.size, showDone) { showDone = !showDone } }
            if (showDone) items(done, key = { "d" + it.id }) { item ->
                MdDoneRow(
                    ownerId = item.id,
                    isBox = false,
                    title = item.title(),
                    detail = "",
                    room = data.room(item.destRoomId),
                    onOpen = { nav.open("item/${item.id}") },
                    onUndo = { store.updateItems(listOf(item.id)) { it.copy(stage = Stage.DELIVERED) } },
                )
            }
        }
    }
}

/**
 * One thing waiting to be unpacked, led by its photo: what it is, what is inside,
 * the room it belongs in, and one large button to say it is done.
 */
@Composable
private fun MdUnpackCard(
    ownerId: String,
    isBox: Boolean,
    title: String,
    detail: String,
    room: Room?,
    flags: List<Pair<ImageVector, String>>,
    onOpen: () -> Unit,
    action: String,
    onAction: () -> Unit,
    detailIsWarning: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp)) {
            OwnerThumb(ownerId, Modifier.size(104.dp), isBox = isBox)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (detailIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoomBadge(room)
                    flags.forEach { (icon, label) -> IconFlag(icon, label) }
                }
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(action, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                }
            }
        }
        extra?.invoke()
    }
}

/** A finished one: small, with a way back in case it was ticked by mistake. */
@Composable
private fun MdDoneRow(
    ownerId: String,
    isBox: Boolean,
    title: String,
    detail: String,
    room: Room?,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerThumb(ownerId, Modifier.size(52.dp), isBox = isBox)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) {
                Text(
                    detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            RoomBadge(room)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
    }
}

/** Tappable heading that shows or hides the finished ones. */
@Composable
private fun MdDoneHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (open) "Hide" else "Show",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
