package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

@Composable
fun RoomsScreen() {
    val store = LocalStore.current
    val data = appData()
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    val home = if (tab == 0) Home.ORIGIN else Home.DEST
    val rooms = data.roomsOf(home)

    Screen("Rooms") {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(data.move.originName) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(data.move.destName) })
        }
        ScrollColumn {
            Hint(
                if (home == Home.ORIGIN) "Rooms you are moving out of. Link each to the new room most of its things go to, so boxes get that destination automatically."
                else "Rooms things go into. The colour is used on labels and door signs. Enter the narrowest opening on the way in to check furniture fits.",
            )
            rooms.forEach { r ->
                Card(Modifier.fillMaxWidth().clickable { editing = r.id }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (home == Home.DEST) {
                            ColorDot(r.color)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Hint(
                                if (home == Home.ORIGIN) {
                                    "Goes to: " + (data.room(r.mapsTo)?.name ?: "not linked") + if (r.packed) " · packed" else ""
                                } else {
                                    listOfNotNull(
                                        r.code.ifBlank { null },
                                        roomColor(r.color).name,
                                        r.floor.ifBlank { null },
                                        r.doorWidthCm?.let { "opening ${fmtNum(it)} cm" } ?: "no opening width",
                                    ).joinToString(" · ")
                                },
                            )
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${r.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            AddRoom(home)
        }
    }

    data.room(editing)?.let { r -> RoomDialog(r, onClose = { editing = null }) }
}

@Composable
private fun AddRoom(home: Home) {
    val store = LocalStore.current
    val rooms = appData().roomsOf(home)
    var name by remember { mutableStateOf("") }
    Section("Add a room") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COMMON_ROOMS.filter { n -> rooms.none { it.name.equals(n, ignoreCase = true) } }.forEach { n ->
                AssistChip(onClick = { store.createRoom(home, n) }, label = { Text(n) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Room name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(enabled = name.isNotBlank(), onClick = { store.createRoom(home, name); name = "" }) {
                Icon(Icons.Default.Add, contentDescription = "Add room")
            }
        }
    }
}

@Composable
private fun RoomDialog(room: Room, onClose: () -> Unit) {
    val store = LocalStore.current
    val data = appData()
    var confirmDelete by remember { mutableStateOf(false) }
    val inUse = data.liveItems.count { it.originRoomId == room.id || it.destRoomId == room.id } +
        data.liveBoxes.count { it.originRoomId == room.id || it.destRoomId == room.id }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (room.home == Home.ORIGIN) "Old-home room" else "New-home room") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(room.name, { v -> store.put(room.copy(name = v)) }, "Name", key = room.id)
                if (room.home == Home.ORIGIN) {
                    Text("Most things from here go to", style = MaterialTheme.typography.labelLarge)
                    RoomPicker(Home.DEST, room.mapsTo, { id -> store.put(room.copy(mapsTo = id)) })
                    SwitchRow("Fully packed", room.packed, { v -> store.put(room.copy(packed = v)) })
                } else {
                    TextInput(room.code, { v -> store.put(room.copy(code = v.uppercase().take(6))) }, "Short code for labels", key = room.id + "code")
                    TextInput(room.floor, { v -> store.put(room.copy(floor = v)) }, "Floor / directions (e.g. Upstairs, first left)", key = room.id + "floor")
                    NumberInput(
                        room.doorWidthCm, { v -> store.put(room.copy(doorWidthCm = v)) },
                        "Narrowest opening on the way in (cm)", Modifier.fillMaxWidth(), key = room.id + "door",
                    )
                    Hint("Measure the tightest door, hallway or stair turn between the front door and this room.")
                    Text("Colour", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ROOM_COLORS.forEachIndexed { i, c ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(c.color())
                                    .border(
                                        if (room.color == i) 3.dp else 0.dp,
                                        if (room.color == i) MaterialTheme.colorScheme.onSurface else c.color(),
                                        CircleShape,
                                    )
                                    .clickable { store.put(room.copy(color = i)) },
                            )
                        }
                    }
                    Hint("Selected: ${roomColor(room.color).name}. Matching coloured tape on boxes makes unloading faster.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { confirmDelete = true }) { Text("Delete room") } },
    )
    if (confirmDelete) ConfirmDialog(
        title = "Delete ${room.name}?",
        text = if (inUse > 0) "$inUse item(s) or box(es) use this room. They will be left with no room." else "This room is not used by anything.",
        onConfirm = { store.delete(room); onClose() },
        onDismiss = { confirmDelete = false },
    )
}
