package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

fun MoveKind.icon(): ImageVector = when (this) {
    MoveKind.SELF -> Icons.Default.LocalShipping
    MoveKind.MOVERS -> Icons.Default.Groups
    MoveKind.STORAGE -> Icons.Default.Warehouse
    MoveKind.INVENTORY -> Icons.Default.ListAlt
}

fun Part.icon(): ImageVector = when (this) {
    Part.TRIAGE -> Icons.Default.Rule
    Part.DEVICES -> Icons.Default.Tv
    Part.PLAN -> Icons.Default.Map
    Part.TIMELINE -> Icons.Default.Checklist
    Part.BUDGET -> Icons.Default.ShoppingCart
    Part.BOXES -> Icons.Default.Inventory2
    Part.LABELS -> Icons.Default.Print
    Part.MOVE_DAY -> Icons.Default.LocalShipping
    Part.RECORDS -> Icons.Default.PhotoCamera
    Part.UNPACK -> Icons.Default.Unarchive
}

/** Choose what the app is used for. Changing it resets which parts show and the place names. */
@Composable
fun KindPicker(modifier: Modifier = Modifier, large: Boolean = false) {
    val store = LocalStore.current
    val move = appData().move
    IconChoice(
        MoveKind.entries.map { Choice(it, it.label, it.icon()) },
        selected = move.kind,
        onPick = { kind ->
            store.setMove { m ->
                val (oldDefault, newDefault) = placeNamesFor(m.kind)
                val (origin, dest) = placeNamesFor(kind)
                m.copy(
                    kind = kind,
                    off = partsOffFor(kind),
                    originName = if (m.originName == oldDefault || m.originName.isBlank()) origin else m.originName,
                    destName = if (m.destName == newDefault || m.destName.isBlank()) dest else m.destName,
                )
            }
        },
        modifier = modifier,
        columns = 2,
        large = large,
    )
}

/** First-run wizard: what it is for, the move, the rooms. */
@Composable
fun SetupScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val inventoryOnly = data.move.kind == MoveKind.INVENTORY
    val titles = listOfNotNull(
        "What is it for?",
        if (inventoryOnly) "Your home" else "Your move",
        if (inventoryOnly) "Rooms" else "Rooms in ${data.move.originName.lowercase()}",
        if (inventoryOnly) null else "Rooms in ${data.move.destName.lowercase()}",
    )
    val last = titles.size - 1
    val at = step.coerceAtMost(last)

    // A second phone that joined an existing move through sync skips the wizard.
    LaunchedEffect(data.move.setupDone) {
        if (data.move.setupDone) nav.navigate("home") { popUpTo("setup") { inclusive = true } }
    }

    Screen(title = titles[at], back = false) {
        LinearProgressIndicator(progress = { (at + 1) / titles.size.toFloat() }, modifier = Modifier.fillMaxWidth())
        ScrollColumn(Modifier.weight(1f)) {
            when (at) {
                0 -> {
                    KindPicker(large = true)
                    Hint("This sets the wording and which parts of the app you see. You can change it later in Settings.")
                    Fold("Second phone for the same move?", Icons.Default.Sync, summary = "Join instead of setting up again") {
                        Text("If the move is already set up on another phone, join it so both phones share the same data.")
                        OutlinedButton(onClick = { nav.open("sync") }, modifier = Modifier.fillMaxWidth()) { Text("Join a move from another phone") }
                        SwitchRow(
                            "This is a helper's phone", device.helper, { v -> store.setDevice { it.copy(helper = v) } },
                            sub = "Shows only packing, loading, unloading and search.",
                        )
                    }
                }
                1 -> {
                    TextInput(data.move.name, { v -> store.setMove { it.copy(name = v) } }, "Name (optional)", key = "name", placeholder = "e.g. Maple Road move")
                    if (!inventoryOnly) {
                        DateButton(data.move.moveDay, { d -> store.setMove { it.copy(moveDay = d) } }, "Move day", Modifier.fillMaxWidth())
                        Hint("Not fixed yet? Leave it empty and set it later.")
                    }
                    TextInput(data.move.originName, { v -> store.setMove { it.copy(originName = v) } }, if (inventoryOnly) "What to call your home" else "Where you are moving from", key = "origin")
                    if (!inventoryOnly) {
                        TextInput(data.move.destName, { v -> store.setMove { it.copy(destName = v) } }, "Where you are moving to", key = "dest")
                    }
                    TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Who uses this phone?", key = "device")
                }
                2 -> RoomSetup(Home.ORIGIN)
                else -> RoomSetup(Home.DEST)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (at > 0) OutlinedButton(onClick = { step = at - 1 }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Back") }
            Button(
                onClick = {
                    if (at < last) step = at + 1 else {
                        finishSetup(store)
                        nav.navigate("home") { popUpTo("setup") { inclusive = true } }
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(if (at < last) "Next" else "Finish") }
        }
    }
}

/** Link old rooms to new rooms with the same name, add the checklist, mark setup done. */
private fun finishSetup(store: Store) {
    val data = store.data.value
    val dest = data.roomsOf(Home.DEST)
    for (r in data.roomsOf(Home.ORIGIN)) {
        if (r.mapsTo != null) continue
        val match = dest.firstOrNull { it.name.equals(r.name, ignoreCase = true) } ?: continue
        store.put(r.copy(mapsTo = match.id))
    }
    if (data.liveTasks.isEmpty() && data.uses(Part.TIMELINE)) seedTasks(data.move.kind).forEach { store.put(it) }
    store.setMove { it.copy(setupDone = true) }
}

@Composable
private fun RoomSetup(home: Home) {
    val store = LocalStore.current
    val rooms = appData().roomsOf(home)
    var custom by remember { mutableStateOf("") }
    Text(
        if (home == Home.ORIGIN) "Tap every room and storage space with things in it."
        else "Tap the rooms things will go into. Each gets a colour for its boxes and door sign.",
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COMMON_ROOMS.filter { name -> rooms.none { it.name.equals(name, ignoreCase = true) } }.forEach { name ->
            AssistChip(
                onClick = { store.createRoom(home, name) },
                label = { Text(name) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            colors = glassFieldColors(),
            value = custom,
            onValueChange = { custom = it },
            label = { Text("Another room") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            enabled = custom.isNotBlank(),
            onClick = { store.createRoom(home, custom); custom = "" },
        ) { Icon(Icons.Default.Add, contentDescription = "Add room") }
    }
    if (rooms.isEmpty()) {
        Hint("None yet. You can also add rooms later.")
    } else {
        Text("${rooms.size} added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rooms.forEach { r ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (home == Home.DEST) ColorDot(r.color) else Icon(Pic.Room, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(r.name, modifier = Modifier.weight(1f))
                    if (home == Home.DEST) Hint(roomColor(r.color).name)
                    IconButton(onClick = { store.delete(r) }) { Icon(Icons.Default.Close, contentDescription = "Remove ${r.name}") }
                }
            }
        }
    }
}
