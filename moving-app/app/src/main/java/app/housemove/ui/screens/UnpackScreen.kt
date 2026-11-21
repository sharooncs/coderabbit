package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.Item
import app.housemove.data.Priority
import app.housemove.data.Ratio
import app.housemove.data.Room
import app.housemove.data.Stage
import app.housemove.data.movingItems
import app.housemove.data.title
import app.housemove.ui.EmptyHint
import app.housemove.ui.Flag
import app.housemove.ui.Hint
import app.housemove.ui.LocalNav
import app.housemove.ui.LocalStore
import app.housemove.ui.OwnerThumb
import app.housemove.ui.ProgressRow
import app.housemove.ui.RoomBadge
import app.housemove.ui.Screen
import app.housemove.ui.TopActions
import app.housemove.ui.appData
import app.housemove.ui.open

/** After the move: what to open first, and which furniture still needs putting together. */
@Composable
fun UnpackScreen() {
    val data = appData()
    var tab by rememberSaveable { mutableStateOf(0) }
    val boxesWaiting = data.liveBoxes.count { it.stage == Stage.DELIVERED }
    val itemsWaiting = data.movingItems().count { it.stage == Stage.DELIVERED }
    Screen("Unpack", actions = { TopActions() }) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Boxes ($boxesWaiting)") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Furniture ($itemsWaiting)") })
        }
        if (tab == 0) MdUnpackBoxes(Modifier.weight(1f)) else MdUnpackFurniture(Modifier.weight(1f))
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

    LazyColumn(modifier.fillMaxWidth()) {
        item(key = "head") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${done.size} of ${shown.size} unpacked", style = MaterialTheme.typography.titleMedium)
                ProgressRow("Unpacked", Ratio(done.size, shown.size))
                Hint("Looking for something? Use the search button at the top to find which box it is in.")
            }
        }
        if (rooms.size > 1) item(key = "rooms") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { roomFilter = null }, label = { Text("All rooms") })
                rooms.forEach { r ->
                    FilterChip(selected = filter == r.id, onClick = { roomFilter = r.id }, label = { Text(r.name) })
                }
            }
        }
        if (arrived.isEmpty()) item(key = "empty") {
            EmptyHint("Boxes appear here once they are scanned in at the new home. First-night boxes are listed first.")
        } else if (waiting.isEmpty()) item(key = "alldone") {
            EmptyHint("Every box here is unpacked.")
        }
        items(waiting, key = { "b" + it.id }) { box ->
            MdUnpackRow(
                ownerId = box.id,
                isBox = true,
                title = "Box ${box.label}",
                detail = box.contents,
                room = data.room(box.destRoomId),
                flags = listOfNotNull(
                    box.priority.label.takeIf { box.priority != Priority.WHENEVER },
                    "Fragile".takeIf { box.fragile },
                    "This way up".takeIf { box.thisWayUp },
                ),
                onOpen = { nav.open("box/${box.id}") },
            ) {
                Button(
                    onClick = { store.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.DONE) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Unpacked") }
            }
            HorizontalDivider()
        }
        if (done.isNotEmpty()) {
            item(key = "donehead") { MdDoneHeader("Unpacked boxes", done.size, showDone) { showDone = !showDone } }
            if (showDone) items(done, key = { "d" + it.id }) { box ->
                MdUnpackRow(
                    ownerId = box.id,
                    isBox = true,
                    title = "Box ${box.label}",
                    detail = box.contents,
                    room = data.room(box.destRoomId),
                    flags = emptyList(),
                    onOpen = { nav.open("box/${box.id}") },
                ) {
                    TextButton(onClick = { store.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.DELIVERED) } }) { Text("Undo") }
                }
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

    LazyColumn(modifier.fillMaxWidth()) {
        item(key = "head") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${done.size} of ${arrived.size} in place", style = MaterialTheme.typography.titleMedium)
                ProgressRow("In place", Ratio(done.size, arrived.size))
                Hint("Beds first. Open an item to see the photos you took when taking it apart.")
            }
        }
        if (arrived.isEmpty()) item(key = "empty") {
            EmptyHint("Furniture and other items appear here once they are scanned in at the new home.")
        } else if (waiting.isEmpty()) item(key = "alldone") {
            EmptyHint("Everything is in place.")
        }
        items(waiting, key = { "i" + it.id }) { item ->
            val needsBuilding = item.needsDisassembly && !item.reassembled
            Column {
                MdUnpackRow(
                    ownerId = item.id,
                    isBox = false,
                    title = item.title(),
                    detail = if (needsBuilding) "Needs putting back together" else "",
                    detailIsWarning = needsBuilding,
                    room = data.room(item.destRoomId),
                    flags = listOfNotNull(
                        "First night".takeIf { item.firstNight },
                        "Fragile".takeIf { item.fragile },
                        "2 people".takeIf { item.twoPerson },
                    ),
                    onOpen = { nav.open("item/${item.id}") },
                ) {
                    Button(
                        onClick = { store.updateItems(listOf(item.id)) { it.copy(stage = Stage.DONE) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("In place") }
                }
                if (item.needsDisassembly) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).clickable {
                                store.updateItems(listOf(item.id)) { it.copy(reassembled = !it.reassembled) }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = item.reassembled,
                                onCheckedChange = { v -> store.updateItems(listOf(item.id)) { it.copy(reassembled = v) } },
                            )
                            Text("Put back together", style = MaterialTheme.typography.bodyLarge)
                        }
                        if (needsBuilding) OutlinedButton(onClick = { nav.open("item/${item.id}") }) { Text("Photos and fittings") }
                    }
                }
                HorizontalDivider()
            }
        }
        if (done.isNotEmpty()) {
            item(key = "donehead") { MdDoneHeader("In place", done.size, showDone) { showDone = !showDone } }
            if (showDone) items(done, key = { "d" + it.id }) { item ->
                MdUnpackRow(
                    ownerId = item.id,
                    isBox = false,
                    title = item.title(),
                    detail = "",
                    room = data.room(item.destRoomId),
                    flags = emptyList(),
                    onOpen = { nav.open("item/${item.id}") },
                ) {
                    TextButton(onClick = { store.updateItems(listOf(item.id)) { it.copy(stage = Stage.DELIVERED) } }) { Text("Undo") }
                }
            }
        }
    }
}

/** Thumbnail, name, what is inside, where it goes, and one big action on the right. */
@Composable
private fun MdUnpackRow(
    ownerId: String,
    isBox: Boolean,
    title: String,
    detail: String,
    room: Room?,
    flags: List<String>,
    onOpen: () -> Unit,
    detailIsWarning: Boolean = false,
    action: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerThumb(ownerId, Modifier.size(56.dp), isBox = isBox)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoomBadge(room, Modifier.weight(1f, fill = false))
                flags.take(2).forEach { Flag(it) }
            }
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

/** Tappable heading that shows or hides the finished ones. */
@Composable
private fun MdDoneHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
