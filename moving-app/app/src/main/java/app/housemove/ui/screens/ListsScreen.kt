package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** Three short lists of things that need handling differently from the main load. */
@Composable
fun ListsScreen() {
    val store = LocalStore.current
    val data = appData()

    val travel = data.liveBoxes.filter { it.travelsWithMe }.map { it.toMovable() } +
        data.liveItems.filter { it.travelsWithMe }.map { it.toMovable() }
    val firstNight = data.liveBoxes.filter { it.priority == Priority.FIRST_NIGHT }.map { it.toMovable() } +
        data.liveItems.filter { it.firstNight }.map { it.toMovable() }
    val hazardous = data.liveBoxes.filter { it.hazardous }.map { it.toMovable() } +
        data.liveItems.filter { it.hazardous }.map { it.toMovable() }

    fun changeBox(id: String, f: (Box) -> Box) {
        store.data.value.box(id)?.let { store.put(f(it)) }
    }

    fun changeItem(id: String, f: (Item) -> Item) {
        store.data.value.item(id)?.let { store.put(f(it)) }
    }

    Screen("Special lists") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "intro") {
                Hint(
                    "To put something on a list, open the box or item and switch on \"Travels with me\", " +
                        "\"Hazardous\", or choose \"First night\". Tap ✕ here to take it off again.",
                    Modifier.padding(16.dp),
                )
            }
            listSection(
                key = "travel",
                title = "Travels with me",
                hint = "Stays with you, not in the van: documents, jewellery, medication, laptops, chargers, keys.",
                things = travel,
            ) { m ->
                if (m.isBox) changeBox(m.id) { it.copy(travelsWithMe = false) }
                else changeItem(m.id) { it.copy(travelsWithMe = false) }
            }
            listSection(
                key = "night",
                title = "First night",
                hint = "Loaded last so it comes off first: bedding, kettle and mugs, toiletries, toilet roll, basic tools, phone chargers.",
                things = firstNight,
            ) { m ->
                if (m.isBox) changeBox(m.id) { it.copy(priority = Priority.DAY_1) }
                else changeItem(m.id) { it.copy(firstNight = false) }
            }
            listSection(
                key = "hazard",
                title = "Hazardous / not in the van",
                hint = "Move separately or use up before the day: gas bottles, paint, fuel, aerosols, bleach, plants.",
                things = hazardous,
            ) { m ->
                if (m.isBox) changeBox(m.id) { it.copy(hazardous = false) }
                else changeItem(m.id) { it.copy(hazardous = false) }
            }
        }
    }
}

private fun LazyListScope.listSection(
    key: String,
    title: String,
    hint: String,
    things: List<Movable>,
    onRemove: (Movable) -> Unit,
) {
    item(key = "head-$key") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                if (things.isEmpty()) title else "$title (${things.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            Hint(hint)
        }
    }
    if (things.isEmpty()) {
        item(key = "empty-$key") {
            Text(
                "Nothing on this list yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    items(things, key = { "$key-${it.id}" }) { m ->
        MovableRow(m, trailing = {
            IconButton(onClick = { onRemove(m) }) {
                Icon(Icons.Default.Close, contentDescription = "Take ${m.title} off the $title list")
            }
        })
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
            item(key = "progress") {
                Section("How far along") {
                    ProgressRow("Rooms fully packed", Ratio(rooms.count { it.packed }, rooms.size))
                    ProgressRow("Boxes sealed", Ratio(boxes.count { it.sealed }, boxes.size))
                }
            }
            item(key = "order") {
                Section("Suggested order") {
                    Hint("Start with what you use least and finish with what you need every day.")
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
            if (rooms.isEmpty()) {
                item(key = "no-rooms") {
                    Section("Rooms") {
                        Text("Add the rooms of your old home to track packing room by room.")
                        TextButton(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                    }
                }
            }
            items(rooms, key = { it.id }) { room ->
                val fromHere = boxes.filter { it.originRoomId == room.id }
                Section(room.name) {
                    Text(
                        if (fromHere.isEmpty()) "No boxes packed from here yet"
                        else "${fromHere.count { it.sealed }} sealed of ${fromHere.size} box${if (fromHere.size == 1) "" else "es"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val to = data.room(room.mapsTo)
                    if (to != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Hint("Goes to")
                            RoomBadge(to)
                        }
                    }
                    SwitchRow(
                        "Fully packed",
                        room.packed,
                        onChange = { v -> store.data.value.room(room.id)?.let { store.put(it.copy(packed = v)) } },
                    )
                    if (!room.packed) {
                        TextButton(onClick = {
                            store.setDevice { it.copy(currentRoomId = room.id) }
                            nav.open("pack")
                        }) { Text("Pack a box from here") }
                    }
                }
            }
        }
    }
}
