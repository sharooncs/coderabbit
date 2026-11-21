package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
        GlassTabRow(tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(data.move.originName) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(data.move.destName) })
        }
        ScrollColumn {
            Hint(
                if (home == Home.ORIGIN) "Link each room to where most of its things go."
                else "The colour is used on boxes, labels and door signs.",
            )
            rooms.forEach { r ->
                val rc = roomColor(r.color)
                GlassCard(Modifier.fillMaxWidth(), onClick = { editing = r.id }) {
                    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        if (home == Home.DEST) {
                            // The room's colour as a block, with its short code, as on the door sign.
                            Box(Modifier.width(64.dp).fillMaxHeight().background(rc.color()), contentAlignment = Alignment.Center) {
                                Text(r.code.ifBlank { suggestRoomCode(r.name) }, color = rc.onColor(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Box(Modifier.width(64.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (r.packed) Icons.Default.CheckCircle else Pic.Room, contentDescription = if (r.packed) "Packed" else null,
                                    tint = if (r.packed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            if (home == Home.ORIGIN) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "goes to", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val to = data.room(r.mapsTo)
                                    if (to != null) RoomBadge(to) else Flag("Not linked")
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Hint(listOfNotNull(rc.name, r.floor.ifBlank { null }).joinToString(" · "))
                                    if (r.doorWidthCm != null) IconFlag(Icons.Default.Straighten, "${fmtNum(r.doorWidthCm)} cm") else if (data.uses(Part.PLAN)) Flag("No door width")
                                }
                            }
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${r.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 14.dp))
                    }
                }
            }
            AddRoom(home)
        }
    }

    data.room(editing)?.let { r -> RoomDialog(r, onClose = { editing = null }) }
}

/** Common rooms by a tap, any other by saying its name; typing one is behind "Other". */
@Composable
private fun AddRoom(home: Home) {
    Section("Add a room") { RoomAdder(home) }
}

/** Common widths of a doorway in cm, offered as one-tap choices; any other is typed behind "Other". */
private val DOOR_WIDTHS = listOf(60.0, 70.0, 76.0, 80.0, 90.0)

@Composable
private fun RoomDialog(room: Room, onClose: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val right = deleteRight(room)
    var confirmDelete by remember { mutableStateOf(false) }
    val inUse = data.liveItems.count { it.originRoomId == room.id || it.destRoomId == room.id } +
        data.liveBoxes.count { it.originRoomId == room.id || it.destRoomId == room.id }
    GlassDialog(
        title = if (room.home == Home.ORIGIN) "Old-home room" else "New-home room",
        onDismiss = onClose,
        icon = Pic.Room,
        buttons = {
            // Deleting is by the Store's rule: whoever may not delete it may ask an Organiser to, at the end of the dialog.
            if (right is DeleteRight.Delete) PillButton("Delete room", onClick = { confirmDelete = true }, icon = Icons.Default.Delete, primary = false, tone = GlassTone.Red)
            PillButton("Done", onClick = onClose)
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DeleteRequests(room.id)
            TextInput(room.name, { v -> store.put(room.copy(name = v)) }, "Name", key = room.id)
            if (room.home == Home.ORIGIN) {
                Text("Most things from here go to", style = MaterialTheme.typography.labelLarge)
                RoomPicker(Home.DEST, room.mapsTo, { id -> store.put(room.copy(mapsTo = id)) })
                SwitchRow("Fully packed", room.packed, { v -> store.put(room.copy(packed = v)) })
            } else {
                // The tightest door, hallway or stair turn on the way in: a tap for the usual widths.
                Text("Narrowest opening on the way in", style = MaterialTheme.typography.labelLarge)
                var otherWidth by remember(room.id) { mutableStateOf(room.doorWidthCm != null && room.doorWidthCm !in DOOR_WIDTHS) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DOOR_WIDTHS.forEach { w ->
                        GlassChip("${fmtNum(w)} cm", selected = !otherWidth && room.doorWidthCm == w, onClick = {
                            otherWidth = false
                            store.put(room.copy(doorWidthCm = if (room.doorWidthCm == w) null else w))
                        })
                    }
                    GlassChip("Other", selected = otherWidth, onClick = { otherWidth = !otherWidth })
                }
                if (otherWidth) {
                    NumberInput(room.doorWidthCm, { v -> store.put(room.copy(doorWidthCm = v)) }, "Width in cm", Modifier.fillMaxWidth(), key = room.id + "door")
                }
                Fold(
                    "Label code and directions", Icons.Default.Edit,
                    summary = listOfNotNull(room.code.ifBlank { null }, room.floor.ifBlank { null }).joinToString(" · "), key = "code-" + room.id,
                ) {
                    TextInput(room.code, { v -> store.put(room.copy(code = v.uppercase().take(6))) }, "Short code for labels", key = room.id + "code")
                    TextInput(room.floor, { v -> store.put(room.copy(floor = v)) }, "Floor / directions (e.g. Upstairs, first left)", key = room.id + "floor", mic = true)
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ROOM_COLORS.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(c.color())
                                .border(
                                    if (room.color == i) 3.dp else 1.dp,
                                    if (room.color == i) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.35f),
                                    CircleShape,
                                )
                                .selectable(selected = room.color == i, role = androidx.compose.ui.semantics.Role.RadioButton) { store.put(room.copy(color = i)) }
                                .semantics { contentDescription = c.name },
                            contentAlignment = Alignment.Center,
                        ) {
                            // The chosen colour has a tick as well as a ring.
                            if (room.color == i) Icon(Icons.Default.Check, contentDescription = null, tint = c.onColor(), modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Hint("Selected: ${roomColor(room.color).name}. Matching coloured tape on boxes makes unloading faster.")
            }
            if (right is DeleteRight.Ask) DeleteOrAsk(room, "Delete room", "", "", onDelete = { false })
        }
    }
    if (confirmDelete) ConfirmDialog(
        title = "Delete ${room.name}?",
        text = if (inUse > 0) "$inUse item(s) or box(es) use this room. They will be left with no room." else "This room is not used by anything.",
        onConfirm = { if (store.tryDelete(room).okOrSay(context)) onClose() },
        onDismiss = { confirmDelete = false },
    )
}
