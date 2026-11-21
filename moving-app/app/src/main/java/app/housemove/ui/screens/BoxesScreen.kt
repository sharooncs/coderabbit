package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** One line of the list: a container, and either what is inside it or what it is inside. */
private class ListRow(val box: Box, val inside: Box? = null, val children: List<Box> = emptyList())

/**
 * Every container, led by its photo. Containers inside another are shown under the one
 * they are in and are not counted again: "All" and the states count what is carried,
 * and "Inside another" says how many more are packed inside those.
 */
@Composable
fun BoxesScreen() {
    val nav = LocalNav.current
    val data = appData()
    // One of the PackState names, "INSIDE" for containers inside another, or null for all.
    var state by rememberSaveable { mutableStateOf<String?>(null) }
    var roomId by rememberSaveable { mutableStateOf<String?>(null) }
    var kindName by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }

    val vehicle = data.vehicle()
    val boxes = data.liveBoxes
    val top = data.topBoxes().sortedWith(CODE_ORDER)
    val topIds = top.map { it.id }.toSet()
    val nested = boxes.filter { it.id !in topIds }.sortedWith(CODE_ORDER)
    val destRooms = data.roomsOf(Home.DEST)
    val room = destRooms.firstOrNull { it.id == roomId }
    val kind = ContainerKind.entries.firstOrNull { it.name == kindName }
    val duplicates = data.duplicateLabels()
    val categories = boxes.associate { it.id to data.categoryOf(it) }

    fun own(b: Box) = (kind == null || b.kind == kind) && (category == null || categories[b.id] == category)
    val rows: List<ListRow> = if (state == "INSIDE") {
        nested.filter { own(it) }.mapNotNull { b -> data.parentOf(b)?.takeIf { room == null || it.destRoomId == room.id }?.let { ListRow(b, inside = it) } }
    } else {
        top.filter { (state == null || it.packState().name == state) && (room == null || it.destRoomId == room.id) }.flatMap { t ->
            val kids = data.childrenOf(t.id).filter { own(it) }
            if (own(t)) listOf(ListRow(t, children = kids)) else kids.map { ListRow(it, inside = t) }
        }
    }

    Screen(
        "Boxes",
        back = false,
        tab = "boxes",
        actions = { TopActions() },
        // With no boxes yet, the picture in the middle of the page carries the one button.
        fab = { if (boxes.isNotEmpty()) ListFab(Icons.Default.Add, "Pack a box") { nav.open("pack") } },
    ) {
        if (boxes.isEmpty()) {
            ScrollColumn {
                EmptyState(
                    Pic.Box, "No boxes yet",
                    text = "Scan a label, photograph what's inside, seal it. Suitcases, bags and crates count too.",
                    action = "Pack the first box", onAction = { nav.open("pack") },
                )
                if (data.uses(Part.LABELS)) {
                    GlassChip("Print labels first", Modifier.align(Alignment.CenterHorizontally), icon = Icons.Default.Print, onClick = { nav.open("labels") })
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    Column {
                        if (duplicates.isNotEmpty()) {
                            GlassCard(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), tone = GlassTone.Red) {
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
                        // One line of filters. The room, then where things have got to, each state with how many
                        // are in it; what kind of container and what is in it are two chips that open a list,
                        // and only appear once there is more than one to choose from.
                        ChipRow {
                            if (destRooms.isNotEmpty()) RoomFilterChip(destRooms, room?.id, onPick = { roomId = it })
                            GlassChip("All ${top.size}", selected = state == null, onClick = { state = null })
                            PackState.entries.forEach { s ->
                                val n = top.count { it.packState() == s }
                                if (n > 0 || state == s.name) {
                                    // The same pictures mark each row of the list below.
                                    GlassChip("${s.label(vehicle)} $n", icon = s.picture(), selected = state == s.name, onClick = { state = if (state == s.name) null else s.name })
                                }
                            }
                            // Extra to the total, not part of it: what is packed inside those.
                            if (nested.isNotEmpty() || state == "INSIDE") {
                                GlassChip(
                                    "Inside another ${nested.size}", icon = Icons.Default.SubdirectoryArrowRight,
                                    selected = state == "INSIDE", onClick = { state = if (state == "INSIDE") null else "INSIDE" },
                                )
                            }
                            val kinds = ContainerKind.entries.filter { k -> boxes.any { it.kind == k } }
                            if (kinds.size > 1 || kind != null) {
                                MenuChip(
                                    kind?.groupLabel() ?: "Kind", selected = kind != null, description = "Choose a kind of container",
                                    leading = { Icon(kind?.picture() ?: Pic.Box, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                ) { close ->
                                    DropdownMenuItem(text = { Text("All kinds") }, onClick = { kindName = null; close() })
                                    kinds.forEach { k ->
                                        DropdownMenuItem(
                                            text = { Text("${k.groupLabel()} ${boxes.count { it.kind == k }}") },
                                            leadingIcon = { Icon(k.picture(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = { kindName = k.name; close() },
                                        )
                                    }
                                }
                            }
                            val used = BOX_CATEGORIES.filter { c -> categories.values.any { it == c } }
                            if (used.size > 1 || category != null) {
                                MenuChip(
                                    category ?: "Contents", selected = category != null, description = "Choose what is inside",
                                    leading = { Icon(category?.let { categoryPicture(it) } ?: Icons.Default.Category, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                ) { close ->
                                    DropdownMenuItem(text = { Text("All contents") }, onClick = { category = null; close() })
                                    used.forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text("$c ${categories.values.count { it == c }}") },
                                            leadingIcon = { Icon(categoryPicture(c), contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = { category = c; close() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (rows.isEmpty()) item(key = "none") {
                    EmptyState(
                        Icons.Default.SearchOff, "Nothing matches",
                        action = "Show everything", onAction = { state = null; roomId = null; kindName = null; category = null },
                    )
                }
                items(rows, key = { it.box.id }) { r ->
                    ContainerRow(
                        r.box, data, Modifier.padding(horizontal = 16.dp), inside = r.inside, children = r.children,
                        onOpen = { nav.open("box/${it.id}") },
                    )
                }
            }
        }
    }
}

/** One line of filter chips that scrolls sideways. */
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The packing loop: pick the room once, then scan a label (or take the next handwritten
 * code) and the container is there, filled in like the last one from that room.
 */
@Composable
fun PackScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val device = deviceInfo()
    val rooms = data.roomsOf(Home.ORIGIN)
    val room = data.room(device.currentRoomId)?.takeIf { it.home == Home.ORIGIN }
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
            val box = store.createContainer(c, room?.id)
            nav.open("box/${box.id}")
        }
    }

    Screen("Pack a box") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "room") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ContainerHeading("Packing in", Modifier.weight(1f))
                        val to = room?.let { data.room(data.packDefaults(it.id).destRoomId) }
                        if (to != null) {
                            Hint("Goes to")
                            RoomBadge(to)
                        }
                    }
                    if (rooms.isEmpty()) {
                        RowButton("Add rooms", onClick = { nav.open("rooms") }, forward = false)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rooms.forEach { r ->
                                GlassChip(r.name, selected = r.id == room?.id, onClick = { store.setDevice { it.copy(currentRoomId = r.id) } })
                            }
                        }
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
                }
            }
            if (room != null) item(key = "packed") {
                // Counted like everywhere else: something inside another container is sealed with that one.
                val carried = fromRoom.filter { data.parentOf(it) == null }
                RoomPackedCard(
                    room, Ratio(carried.count { it.sealed }, carried.size),
                    onChange = { v -> store.data.value.room(room.id)?.let { store.put(it.copy(packed = v)) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (fromRoom.isNotEmpty()) {
                if (room == null) item(key = "list-title") {
                    ContainerHeading("Packed with no room yet", Modifier.padding(horizontal = 16.dp))
                }
                items(fromRoom, key = { it.id }) { b ->
                    ContainerRow(b, data, Modifier.padding(horizontal = 16.dp), inside = data.parentOf(b), onOpen = { nav.open("box/${it.id}") })
                }
            }
        }
    }

    if (scanning) {
        ScanDialog(
            title = "Scan the label",
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
    GlassCard(modifier.fillMaxWidth(), tone = if (room.packed) GlassTone.Mint else GlassTone.Plain) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = room.packed, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ring(sealed, size = 44.dp, stroke = 5.dp, color = if (room.packed) scheme.tertiary else scheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${room.name} is fully packed", style = MaterialTheme.typography.bodyLarge)
                Hint(if (sealed.total == 0) "Nothing packed from here yet" else "${sealed.done} of ${sealed.total} sealed")
            }
            Switch(checked = room.packed, onCheckedChange = null)
        }
    }
}
