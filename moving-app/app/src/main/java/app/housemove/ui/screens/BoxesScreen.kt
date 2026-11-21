package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

private enum class BoxFilter(val label: String) {
    ALL("All"), OPEN("Open"), SEALED("Sealed"), ON_VAN("On the van"), DELIVERED("Delivered"), UNPACKED("Unpacked")
}

private fun Box.matches(f: BoxFilter): Boolean = when (f) {
    BoxFilter.ALL -> true
    BoxFilter.OPEN -> stage == Stage.AT_ORIGIN && !sealed
    BoxFilter.SEALED -> stage == Stage.AT_ORIGIN && sealed
    BoxFilter.ON_VAN -> stage == Stage.LOADED
    BoxFilter.DELIVERED -> stage == Stage.DELIVERED
    BoxFilter.UNPACKED -> stage == Stage.DONE
}

private fun boxStatus(box: Box): String = when {
    box.stage != Stage.AT_ORIGIN -> stageText(box.stage, true)
    box.sealed -> "Sealed"
    else -> "Open"
}

/** L2 before L10, and printed labels grouped apart from each phone's handwritten ones. */
private val labelOrder = compareBy<Box>(
    { it.label.takeWhile { c -> !c.isDigit() }.uppercase() },
    { it.label.filter { c -> c.isDigit() }.take(9).toIntOrNull() ?: 0 },
    { it.label },
)

private fun countOf(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

@Composable
fun BoxesScreen() {
    val nav = LocalNav.current
    val data = appData()
    var filter by rememberSaveable { mutableStateOf(BoxFilter.ALL) }
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val boxes = data.liveBoxes
    val destRooms = data.roomsOf(Home.DEST)
    val duplicates = data.duplicateLabels()
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val shown = boxes.filter { b ->
        b.matches(filter) && (roomId == null || b.destRoomId == roomId) &&
            words.all { w -> b.label.lowercase().contains(w) || b.contents.lowercase().contains(w) }
    }.sortedWith(labelOrder)

    Screen(
        "Boxes",
        back = false,
        tab = "boxes",
        actions = { TopActions() },
        fab = {
            ExtendedFloatingActionButton(
                onClick = { nav.open("pack") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Pack a box") },
            )
        },
    ) {
        if (boxes.isEmpty()) {
            ScrollColumn {
                Section("No boxes yet") {
                    Text("Small things are not listed one by one. You record them a box at a time, while the box is open in front of you:")
                    Text("1. Stick a printed label on the box, or write a short code on it.")
                    Text("2. Tap \"Pack a box\" and scan the label.")
                    Text("3. Choose the room it goes to, take a photo of what's inside and say a few words about it.")
                    Text("4. Seal it. Later, searching for \"kettle\" tells you which box it is in.")
                    Button(onClick = { nav.open("pack") }) { Text("Pack the first box") }
                    TextButton(onClick = { nav.open("labels") }) { Text("Print labels first") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "header") {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val parts = listOfNotNull(
                            countOf(boxes.size, "box", "boxes"),
                            boxes.count { it.matches(BoxFilter.OPEN) }.takeIf { it > 0 }?.let { "$it open" },
                            boxes.count { it.matches(BoxFilter.SEALED) }.takeIf { it > 0 }?.let { "$it sealed" },
                            boxes.count { it.matches(BoxFilter.ON_VAN) }.takeIf { it > 0 }?.let { "$it on the van" },
                            boxes.count { it.matches(BoxFilter.DELIVERED) }.takeIf { it > 0 }?.let { "$it delivered" },
                            boxes.count { it.matches(BoxFilter.UNPACKED) }.takeIf { it > 0 }?.let { "$it unpacked" },
                        )
                        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)

                        if (duplicates.isNotEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "The same label is on more than one thing: ${duplicates.joinToString()}. " +
                                            "Open one of each pair and choose \"Change label\".",
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
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoxFilter.entries.forEach { f ->
                                FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label) })
                            }
                        }
                        if (destRooms.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = roomId == null, onClick = { roomId = null }, label = { Text("All rooms") })
                                destRooms.forEach { r ->
                                    FilterChip(
                                        selected = r.id == roomId,
                                        onClick = { roomId = if (r.id == roomId) null else r.id },
                                        label = { Text(r.name) },
                                        leadingIcon = { ColorDot(r.color) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (shown.isEmpty()) item(key = "none") { EmptyHint("No boxes match this filter.") }
                items(shown, key = { it.id }) { b ->
                    BoxRow(b, data.room(b.destRoomId)) { nav.open("box/${b.id}") }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BoxRow(box: Box, dest: Room?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            box.label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val first = box.contents.lineSequence().firstOrNull { it.isNotBlank() }
            Text(
                first ?: "No contents written down",
                style = MaterialTheme.typography.bodyLarge,
                color = if (first == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RoomBadge(dest)
                if (box.priority != Priority.WHENEVER) Flag(box.priority.label)
                if (box.fragile) Flag("Fragile")
                if (box.heavy) Flag("Heavy")
                if (box.damaged) Flag("Damaged", error = true)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(boxStatus(box), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Section("Packing in", Modifier.padding(horizontal = 16.dp)) {
                    RoomPicker(
                        Home.ORIGIN,
                        room?.id,
                        onPick = { id -> store.setDevice { it.copy(currentRoomId = id) } },
                        allowNone = false,
                    )
                    if (!hasRooms) {
                        TextButton(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                    } else if (room == null) {
                        Hint("Pick the room you are packing, so each new box already knows where it is going.")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Hint("New boxes from here go to")
                            RoomBadge(data.room(room.mapsTo))
                        }
                    }
                }
            }
            item(key = "buttons") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Scan a printed label", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(onClick = { startBox(store.nextHandCode()) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("No sticker? Use a handwritten code")
                    }
                    Hint("Handwritten codes from this phone start with ${device.letter.ifBlank { "A" }}. You write the code on the box with a marker.")
                }
            }
            if (room != null) item(key = "packed") {
                Section(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SwitchRow(
                        "This room is fully packed",
                        room.packed,
                        onChange = { v -> store.data.value.room(room.id)?.let { store.put(it.copy(packed = v)) } },
                        sub = "${fromRoom.count { it.sealed }} sealed of ${countOf(fromRoom.size, "box", "boxes")} from ${room.name}",
                    )
                }
            }
            item(key = "list-title") {
                Text(
                    if (room != null) "Boxes packed from ${room.name}" else "Boxes with no room yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
            }
            if (fromRoom.isEmpty()) item(key = "list-empty") {
                Hint("None yet. Scan a label to start the first one.", Modifier.padding(horizontal = 16.dp))
            }
            items(fromRoom, key = { it.id }) { b ->
                BoxRow(b, data.room(b.destRoomId)) { nav.open("box/${b.id}") }
            }
            item(key = "tips") {
                Section("Tips", Modifier.padding(horizontal = 16.dp)) {
                    Text("• Heavy things (books, tins, tools) go in small boxes; light, bulky things in large ones.")
                    Text("• Photograph the open box before you seal it. The photo is your list of what's inside.")
                    Text("• Label the top and one side, so the code shows when boxes are stacked.")
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
