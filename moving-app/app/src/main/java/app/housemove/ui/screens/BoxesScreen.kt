package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** The states a box goes through, as the list shows and filters them. */
private enum class BoxFilter { ALL, OPEN, SEALED, ON_VAN, DELIVERED, UNPACKED }

private fun Box.state(): BoxFilter = when {
    stage == Stage.LOADED -> BoxFilter.ON_VAN
    stage == Stage.DELIVERED -> BoxFilter.DELIVERED
    stage == Stage.DONE -> BoxFilter.UNPACKED
    sealed -> BoxFilter.SEALED
    else -> BoxFilter.OPEN
}

private fun BoxFilter.label(vehicle: String): String = when (this) {
    BoxFilter.ALL -> "All"
    BoxFilter.OPEN -> "Open"
    BoxFilter.SEALED -> "Sealed"
    BoxFilter.ON_VAN -> stageText(Stage.LOADED, isBox = true, vehicle)
    BoxFilter.DELIVERED -> stageText(Stage.DELIVERED, isBox = true, vehicle)
    BoxFilter.UNPACKED -> stageText(Stage.DONE, isBox = true, vehicle)
}

private fun BoxFilter.icon(): ImageVector = when (this) {
    BoxFilter.ALL -> Pic.Box
    BoxFilter.OPEN -> Icons.Default.LockOpen
    BoxFilter.SEALED -> Icons.Default.Lock
    BoxFilter.ON_VAN -> Stage.LOADED.icon()
    BoxFilter.DELIVERED -> Stage.DELIVERED.icon()
    BoxFilter.UNPACKED -> Stage.DONE.icon()
}

/** L2 before L10, and printed labels grouped apart from each phone's handwritten ones. */
private val labelOrder = compareBy<Box>(
    { it.label.takeWhile { c -> !c.isDigit() }.uppercase() },
    { it.label.filter { c -> c.isDigit() }.take(9).toIntOrNull() ?: 0 },
    { it.label },
)

private fun countOf(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
fun BoxesScreen() {
    val nav = LocalNav.current
    val data = appData()
    var filter by rememberSaveable { mutableStateOf(BoxFilter.ALL) }
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val vehicle = data.vehicle()
    val boxes = data.liveBoxes
    val destRooms = data.roomsOf(Home.DEST)
    val room = destRooms.firstOrNull { it.id == roomId }
    val duplicates = data.duplicateLabels()
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val shown = boxes.filter { b ->
        (filter == BoxFilter.ALL || b.state() == filter) && (room == null || b.destRoomId == room.id) &&
            words.all { w -> b.label.lowercase().contains(w) || b.contents.lowercase().contains(w) }
    }.sortedWith(labelOrder)

    Screen(
        "Boxes",
        back = false,
        tab = "boxes",
        actions = { TopActions() },
        fab = {
            // With no boxes yet, the picture in the middle of the page carries the one button.
            if (boxes.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { nav.open("pack") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Pack a box") },
                )
            }
        },
    ) {
        if (boxes.isEmpty()) {
            ScrollColumn {
                EmptyState(
                    Pic.Box, "No boxes yet",
                    text = "Label a box, photograph what's inside and seal it. Search finds it later.",
                    action = "Pack the first box", onAction = { nav.open("pack") },
                )
                if (data.uses(Part.LABELS)) {
                    TextButton(onClick = { nav.open("labels") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Print labels first")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "header") {
                    Column(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (duplicates.isNotEmpty()) {
                            Card(
                                Modifier.padding(horizontal = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Label used twice: ${duplicates.joinToString()}. Open one and change its label.",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Filter by label or contents") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = if (query.isBlank()) null else {
                                { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear filter") } }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )

                        // One row: the room, then each state with how many boxes are in it.
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (destRooms.isNotEmpty()) RoomFilter(destRooms, room, onPick = { roomId = it })
                            BoxFilter.entries.forEach { f ->
                                val n = if (f == BoxFilter.ALL) boxes.size else boxes.count { it.state() == f }
                                if (f == BoxFilter.ALL || n > 0 || f == filter) {
                                    FilterChip(
                                        selected = f == filter,
                                        onClick = { filter = f },
                                        label = { Text("${f.label(vehicle)} $n") },
                                        // The same pictures mark each box in the list below.
                                        leadingIcon = if (f == BoxFilter.ALL) null else {
                                            { Icon(f.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (shown.isEmpty()) item(key = "none") {
                    EmptyState(
                        Icons.Default.SearchOff, "No boxes match",
                        action = "Show all boxes", onAction = { filter = BoxFilter.ALL; roomId = null; query = "" },
                    )
                }
                items(shown, key = { it.id }) { b ->
                    BoxRow(b, data.room(b.destRoomId), vehicle) { nav.open("box/${b.id}") }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The room filter as one chip that opens a list of rooms. */
@Composable
private fun RoomFilter(rooms: List<Room>, selected: Room?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(selected?.name ?: "All rooms", maxLines = 1) },
            leadingIcon = if (selected == null) null else {
                { ColorDot(selected.color) }
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose a room", modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All rooms") },
                leadingIcon = { Icon(Pic.Room, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { onPick(null); open = false },
            )
            rooms.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.name) },
                    leadingIcon = { ColorDot(r.color) },
                    onClick = { onPick(r.id); open = false },
                )
            }
        }
    }
}

/** One box: its photo, its code, its room in colour, what's inside, and where it has got to. */
@Composable
private fun BoxRow(box: Box, dest: Room?, vehicle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state = box.state()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerThumb(box.id, Modifier.size(56.dp), isBox = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    box.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RoomBadge(dest)
            }
            val first = box.contents.lineSequence().firstOrNull { it.isNotBlank() }
            Text(
                first ?: "No contents written down",
                style = MaterialTheme.typography.bodyMedium,
                color = if (first == null) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (box.priority != Priority.WHENEVER || box.fragile || box.heavy || box.thisWayUp || box.travelsWithMe || box.hazardous || box.damaged) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (box.priority != Priority.WHENEVER) IconFlag(box.priority.icon(), box.priority.label)
                    if (box.fragile) IconFlag(Pic.Fragile, "Fragile")
                    if (box.heavy) IconFlag(Pic.Heavy, "Heavy")
                    if (box.thisWayUp) IconFlag(Pic.Upright, "This way up")
                    if (box.travelsWithMe) IconFlag(Pic.WithMe, "With me")
                    if (box.hazardous) IconFlag(Pic.Hazard, "Hazardous", error = true)
                    if (box.damaged) IconFlag(Pic.Damage, "Damaged", error = true)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            state.icon(),
            contentDescription = state.label(vehicle),
            tint = when (state) {
                BoxFilter.OPEN -> scheme.onSurfaceVariant
                BoxFilter.UNPACKED -> scheme.tertiary
                else -> scheme.primary
            },
        )
    }
}

/** The packing loop: pick the room once, then label, describe and seal box after box. */
@Composable
fun PackScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val device = deviceInfo()
    val vehicle = data.vehicle()
    val room = data.room(device.currentRoomId)?.takeIf { it.home == Home.ORIGIN }
    val hasRooms = data.roomsOf(Home.ORIGIN).isNotEmpty()
    var scanning by remember { mutableStateOf(false) }
    val fromRoom = data.liveBoxes.filter { it.originRoomId == room?.id }.sortedByDescending { it.createdAt }

    fun startBox(code: String) {
        val c = code.trim()
        if (c.isBlank()) return
        if (c.startsWith("HMSYNC", ignoreCase = true)) {
            toast(context, "That is a code for linking phones, not a box label.")
            return
        }
        val existing = store.data.value.findByLabel(c)
        if (existing != null) {
            toast(context, "$c is already in use. Opening it.")
            nav.openMovable(existing)
        } else {
            val box = store.createBox(c, room?.id)
            nav.open("box/${box.id}")
        }
    }

    Screen("Pack a box") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "room") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Heading("Packing in", Modifier.weight(1f))
                        val to = data.room(room?.mapsTo)
                        if (to != null) {
                            Hint("Boxes go to")
                            RoomBadge(to)
                        }
                    }
                    RoomTiles(
                        Home.ORIGIN,
                        room?.id,
                        onPick = { id -> store.setDevice { it.copy(currentRoomId = id) } },
                        allowNone = false,
                    )
                    if (!hasRooms) {
                        TextButton(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                    } else if (room == null) {
                        Hint("Pick a room, so new boxes know where they are going.")
                    }
                }
            }
            item(key = "buttons") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Without printed labels in use, the handwritten code is the main way in.
                    val printed = data.uses(Part.LABELS)
                    val scan = @Composable {
                        BigAction(Icons.Default.QrCodeScanner, "Scan a printed label", { scanning = true }, primary = printed)
                    }
                    val write = @Composable {
                        BigAction(Icons.Default.Edit, "No sticker? Use a handwritten code", { startBox(store.nextHandCode()) }, primary = !printed)
                    }
                    if (printed) { scan(); write() } else { write(); scan() }
                    Hint("Handwritten codes from this phone start with ${device.letter.ifBlank { "A" }}.")
                }
            }
            if (room != null) item(key = "packed") {
                RoomPackedCard(
                    room, Ratio(fromRoom.count { it.sealed }, fromRoom.size),
                    onChange = { v -> store.data.value.room(room.id)?.let { store.put(it.copy(packed = v)) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (fromRoom.isNotEmpty()) {
                item(key = "list-title") {
                    Heading(
                        if (room != null) "Boxes from ${room.name}" else "Boxes with no room yet",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    )
                }
                items(fromRoom, key = { it.id }) { b ->
                    BoxRow(b, data.room(b.destRoomId), vehicle) { nav.open("box/${b.id}") }
                }
            }
            item(key = "tips") {
                Fold(
                    "Packing tips", Icons.Default.Lightbulb,
                    summary = "Small boxes for heavy things, and two more",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Heavy things (books, tins, tools) go in small boxes; light, bulky things in large ones.")
                    Text("Photograph the open box before you seal it. The photo is your list of what's inside.")
                    Text("Label the top and one side, so the code shows when boxes are stacked.")
                }
            }
        }
    }

    if (scanning) {
        ScanDialog(
            title = "Scan the label on the box",
            onCode = { code ->
                scanning = false
                startBox(code)
            },
            onClose = { scanning = false },
        )
    }
}

/** How far the room being packed has got, and the switch that says it is finished. */
@Composable
private fun RoomPackedCard(room: Room, sealed: Ratio, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = room.packed, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ring(sealed, size = 44.dp, stroke = 5.dp, color = if (room.packed) scheme.tertiary else scheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("This room is fully packed", style = MaterialTheme.typography.bodyLarge)
                Hint("${sealed.done} sealed of ${countOf(sealed.total, "box", "boxes")} from ${room.name}")
            }
            Switch(checked = room.packed, onCheckedChange = null)
        }
    }
}
