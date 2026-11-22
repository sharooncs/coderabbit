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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.Item
import app.housemove.data.Part
import app.housemove.data.Priority
import app.housemove.data.Ratio
import app.housemove.data.Room
import app.housemove.data.Stage
import app.housemove.data.childrenOf
import app.housemove.data.itemsIn
import app.housemove.data.looseItems
import app.housemove.data.title
import app.housemove.data.topBoxes
import app.housemove.data.uses
import app.housemove.data.*
import app.housemove.ui.*

/** After the move: what to open first, and which furniture still needs putting together. */
@Composable
fun UnpackScreen() {
    val data = appData()
    var tab by rememberSaveable { mutableStateOf(0) }
    // A container inside another, and an item packed in a container, come out with it: they are not counted on their own.
    val boxesWaiting = data.topBoxes().count { it.stage == Stage.DELIVERED }
    val itemsWaiting = data.looseItems().count { it.stage == Stage.DELIVERED }
    // Someone who does not pack boxes with the app sees only the furniture.
    val showBoxes = data.uses(Part.BOXES) || data.liveBoxes.isNotEmpty()
    Screen("Unpack", actions = { TopActions() }) {
        StorageNote(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        if (showBoxes) GlassTabRow(tab) {
            MdIconTab(tab == 0, { tab = 0 }, "Boxes ($boxesWaiting)", Pic.Box)
            MdIconTab(tab == 1, { tab = 1 }, "Furniture ($itemsWaiting)", Pic.Item)
        }
        if (showBoxes && tab == 0) MdUnpackBoxes(Modifier.weight(1f)) else MdUnpackFurniture(Modifier.weight(1f))
    }
}

/** Something that should come out of a container: an item packed in it, or a container inside it. */
private class MdInside(val id: String, val name: String, val icon: ImageVector, val route: String)

@Composable
private fun MdUnpackBoxes(modifier: Modifier) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    var roomFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    val arrived = data.topBoxes().filter { mdArrived(it.stage) }
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
            ProgressCard(
                Ratio(done.size, shown.size),
                "${done.size} of ${shown.size} unpacked",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            ) {
                // The same room filter as on the Boxes list: one chip that opens the rooms.
                if (rooms.size > 1) RoomFilterChip(rooms, filter, onPick = { roomFilter = it })
            }
        }
        if (arrived.isNotEmpty() && waiting.isEmpty()) item(key = "alldone") {
            EmptyState(Icons.Default.CheckCircle, "Every box here is unpacked")
        }
        items(waiting, key = { "b" + it.id }) { box ->
            // What should come out of it: the items packed in it and the containers inside it, then what those hold.
            val inner = data.childrenOf(box.id)
            val inside = data.itemsIn(box.id).map { MdInside(it.id, it.title(), Pic.Item, "item/${it.id}") } +
                inner.map { MdInside(it.id, it.nameAndCode(), it.kind.picture(), "box/${it.id}") } +
                inner.flatMap { c -> data.itemsIn(c.id).map { MdInside(it.id, it.title(), Pic.Item, "item/${it.id}") } }
            val scanned = data.entriesOf(box.id).map { it.title.ifBlank { it.code } }
            MdUnpackCard(
                ownerId = box.id,
                icon = box.kind.picture(),
                title = box.nameAndCode(),
                detail = listOf(
                    box.contents,
                    scanned.take(3).joinToString(", ") + if (scanned.size > 3) " and ${scanned.size - 3} more" else "",
                ).filter { it.isNotBlank() }.joinToString(" · "),
                inside = inside,
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
                    icon = box.kind.picture(),
                    title = box.nameAndCode(),
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

    // Items packed in a container come out with it, on the other tab.
    val arrived = data.looseItems().filter { mdArrived(it.stage) }
    val waiting = arrived.filter { it.stage == Stage.DELIVERED }
        .sortedWith(compareBy<Item>({ !it.firstNight }, { data.room(it.destRoomId)?.name ?: "~" }, { it.title() }))
    val done = arrived.filter { it.stage == Stage.DONE }.sortedBy { it.title() }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (arrived.isEmpty()) item(key = "empty") {
            EmptyState(Pic.Item, "No furniture here yet", text = "It appears once it is scanned in at the new home.")
        } else item(key = "head") {
            ProgressCard(
                Ratio(done.size, arrived.size),
                "${done.size} of ${arrived.size} in place",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            )
        }
        if (arrived.isNotEmpty() && waiting.isEmpty()) item(key = "alldone") {
            EmptyState(Icons.Default.CheckCircle, "Everything is in place")
        }
        items(waiting, key = { "i" + it.id }) { item ->
            val needsBuilding = item.needsDisassembly && !item.reassembled
            MdUnpackCard(
                ownerId = item.id,
                icon = Pic.Item,
                title = item.title(),
                detail = if (needsBuilding) "Needs putting back together" else "",
                detailIsWarning = needsBuilding,
                room = data.room(item.destRoomId),
                flags = listOfNotNull(
                    (Pic.FirstNight to "First night").takeIf { item.firstNight },
                    (Pic.Fragile to "Fragile").takeIf { item.fragile },
                    (Pic.TwoPeople to "Two people").takeIf { item.twoPerson },
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
                            if (needsBuilding) RowButton(
                                "Photos and fittings", onClick = { nav.open("item/${item.id}") }, forward = false, icon = Pic.TakeApart,
                            )
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
                    icon = Pic.Item,
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
 * the room it belongs in, and one large button to say it is done. For a container,
 * [inside] is what should come out of it, each with its photo.
 */
@Composable
private fun MdUnpackCard(
    ownerId: String,
    icon: ImageVector,
    title: String,
    detail: String,
    room: Room?,
    flags: List<Pair<ImageVector, String>>,
    onOpen: () -> Unit,
    action: String,
    onAction: () -> Unit,
    detailIsWarning: Boolean = false,
    inside: List<MdInside> = emptyList(),
    extra: (@Composable () -> Unit)? = null,
) {
    val nav = LocalNav.current
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), sheen = true) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp)) {
            ThingThumb(ownerId, icon, size = 104.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    flags.forEach { (picture, label) -> IconFlag(picture, label) }
                }
                RowButton(action, onClick = onAction, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), icon = Icons.Default.Check)
            }
        }
        if (inside.isNotEmpty()) {
            Text(
                countOf(inside.size, "thing") + " to come out",
                style = MaterialTheme.typography.labelLarge, color = Night.TextSoft,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                inside.forEach { thing ->
                    Column(
                        Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Open") { nav.open(thing.route) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ThingThumb(thing.id, thing.icon, size = 68.dp)
                        Text(
                            thing.name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
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
    icon: ImageVector,
    title: String,
    detail: String,
    room: Room?,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).glass(RoundedCornerShape(22.dp), shadow = 4.dp)
            .clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThingThumb(ownerId, icon, size = 52.dp)
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
        RowButton("Undo", onClick = onUndo, forward = false)
    }
}

/** Tappable heading that shows or hides the finished ones. */
@Composable
private fun MdDoneHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 52.dp).padding(horizontal = 22.dp),
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

/**
 * What is at the storage unit, where it would otherwise be missed: in storage, not delivered, so not waiting
 * to be unpacked. "Bring back" starts a trip from the storage unit to the new home and opens Load on it.
 */
@Composable
internal fun StorageNote(modifier: Modifier = Modifier) {
    val data = appData()
    val store = LocalStore.current
    val nav = LocalNav.current
    val stored = data.inStorage()
    if (stored.isEmpty()) return
    GlassPill(modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
        GlassOrb(Stage.STORED.icon(), null, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(countOf(stored.size, "thing") + " in storage", style = MaterialTheme.typography.titleSmall)
            Text("Not delivered yet", style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
        }
        if (data.uses(Part.MOVE_DAY) && data.move.kind != MoveKind.STORAGE) {
            RowButton("Bring back", onClick = { mdStorageTrip(store, fromStorage = true); nav.open("load") }, forward = true)
        }
    }
}
