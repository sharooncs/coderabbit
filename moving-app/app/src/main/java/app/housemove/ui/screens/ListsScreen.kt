package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** Three short lists of things that need handling differently from the main load. */
@Composable
fun ListsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val vehicle = data.vehicle()

    val travel = data.liveBoxes.filter { it.travelsWithMe }.map { it.toMovable() } +
        data.liveItems.filter { it.travelsWithMe }.map { it.toMovable() }
    val firstNight = data.liveBoxes.filter { it.priority == Priority.FIRST_NIGHT }.map { it.toMovable() } +
        data.liveItems.filter { it.firstNight }.map { it.toMovable() }
    val hazardous = data.liveBoxes.filter { it.hazardous }.map { it.toMovable() } +
        data.liveItems.filter { it.hazardous }.map { it.toMovable() }
    val nothing = travel.isEmpty() && firstNight.isEmpty() && hazardous.isEmpty()

    fun changeBox(id: String, f: (Box) -> Box) {
        store.data.value.box(id)?.let { store.put(f(it)) }
    }

    fun changeItem(id: String, f: (Item) -> Item) {
        store.data.value.item(id)?.let { store.put(f(it)) }
    }

    Screen("Special lists") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (nothing) {
                item(key = "empty") {
                    val boxes = data.uses(Part.BOXES)
                    EmptyState(
                        Icons.Default.Star, "Nothing on these lists yet",
                        text = "Open a box or item and switch on \"Travels with me\", \"First night\" or \"Hazardous\".",
                        action = if (boxes) "Go to the boxes" else "Go to the items",
                        onAction = { nav.goTab(if (boxes) "boxes" else "items") },
                    )
                }
            }
            listSection(
                key = "travel",
                title = "Travels with me",
                icon = Pic.WithMe,
                hint = "Stays with you: documents, valuables, medication, chargers, keys.",
                things = travel,
                quiet = nothing,
            ) { m ->
                if (m.isBox) changeBox(m.id) { it.copy(travelsWithMe = false) }
                else changeItem(m.id) { it.copy(travelsWithMe = false) }
            }
            listSection(
                key = "night",
                title = "First night",
                icon = Pic.FirstNight,
                hint = "Loaded last, off first: bedding, kettle, toiletries, tools.",
                things = firstNight,
                quiet = nothing,
            ) { m ->
                if (m.isBox) changeBox(m.id) { it.copy(priority = Priority.DAY_1) }
                else changeItem(m.id) { it.copy(firstNight = false) }
            }
            listSection(
                key = "hazard",
                title = "Hazardous",
                icon = Pic.Hazard,
                hint = "Not in the $vehicle: gas, paint, fuel, aerosols, bleach, plants.",
                things = hazardous,
                quiet = nothing,
                warning = true,
            ) { m ->
                if (m.isBox) changeBox(m.id) { it.copy(hazardous = false) }
                else changeItem(m.id) { it.copy(hazardous = false) }
            }
        }
    }
}

/** One list: its picture, what belongs on it, and the things on it with their photos. */
private fun LazyListScope.listSection(
    key: String,
    title: String,
    icon: ImageVector,
    hint: String,
    things: List<Movable>,
    quiet: Boolean,
    warning: Boolean = false,
    onRemove: (Movable) -> Unit,
) {
    item(key = "head-$key") {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (warning) scheme.errorContainer else scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = if (warning) scheme.error else scheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (things.isEmpty()) title else "$title (${things.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Hint(hint)
            }
        }
    }
    if (things.isEmpty() && !quiet) {
        item(key = "empty-$key") {
            Text(
                "Nothing here yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 74.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
    items(things, key = { "$key-${it.id}" }) { m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OwnerThumb(m.id, Modifier.padding(start = 16.dp).size(48.dp), isBox = m.isBox)
            MovableRow(m, Modifier.weight(1f), trailing = {
                IconButton(onClick = { onRemove(m) }) {
                    Icon(Icons.Default.Close, contentDescription = "Take ${m.title} off the $title list")
                }
            })
        }
    }
    item(key = "rule-$key") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
}

/** What to pack when, and how far along each old-home room is. */
@Composable
fun PackOrderScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val rooms = data.roomsOf(Home.ORIGIN)
    val boxes = data.liveBoxes

    Screen("Packing order") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rooms.isNotEmpty()) {
                item(key = "progress") {
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        tone = GlassTone.Mint,
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Ring(Ratio(rooms.count { it.packed }, rooms.size), size = 72.dp, stroke = 8.dp) {
                                Text(
                                    "${rooms.count { it.packed }}", style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("of ${rooms.size} room${if (rooms.size == 1) "" else "s"} fully packed", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${boxes.count { it.sealed }} of ${boxes.size} box${if (boxes.size == 1) "" else "es"} sealed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "no-rooms") {
                    EmptyState(
                        Pic.Room, "No rooms yet",
                        text = "Add the rooms of your old home to track packing room by room.",
                        action = "Add rooms", onAction = { nav.open("rooms") },
                    )
                }
            }
            item(key = "order") {
                Fold("Suggested order", Icons.Default.FormatListNumbered, summary = "Least used first, everyday things last") {
                    PACK_ORDER.forEachIndexed { i, (what, timing) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(28.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(what, style = MaterialTheme.typography.bodyLarge)
                                Hint(timing)
                            }
                        }
                    }
                }
            }
            items(rooms, key = { it.id }) { room ->
                val fromHere = boxes.filter { it.originRoomId == room.id }
                RoomPackRow(
                    room, data.room(room.mapsTo), Ratio(fromHere.count { it.sealed }, fromHere.size),
                    onPacked = { v -> store.data.value.room(room.id)?.let { store.put(it.copy(packed = v)) } },
                    onPack = {
                        store.setDevice { it.copy(currentRoomId = room.id) }
                        nav.open("pack")
                    },
                )
            }
        }
    }
}

/** One old-home room: a ring of its boxes sealed, where they go, and the switch that says it is finished. */
@Composable
private fun RoomPackRow(room: Room, to: Room?, sealed: Ratio, onPacked: (Boolean) -> Unit, onPack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(enabled = !room.packed, onClick = onPack)
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A finished room shows a full green ring, whatever its boxes say.
                Ring(if (room.packed) Ratio(1, 1) else sealed, size = 48.dp, stroke = 5.dp, color = if (room.packed) scheme.tertiary else scheme.primary) {
                    if (room.packed) Icon(Icons.Default.Check, contentDescription = null, tint = scheme.tertiary)
                    else Text("${sealed.done}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Hint(if (sealed.total == 0) "No boxes yet" else "${sealed.done} of ${sealed.total} sealed")
                        if (to != null) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "goes to",
                                tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp),
                            )
                            RoomBadge(to)
                        }
                    }
                    if (!room.packed) {
                        Text("Pack a box from here", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    }
                }
            }
            Column(
                Modifier.toggleable(value = room.packed, role = Role.Switch, onValueChange = onPacked)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Switch(checked = room.packed, onCheckedChange = null)
                Text("Fully packed", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
