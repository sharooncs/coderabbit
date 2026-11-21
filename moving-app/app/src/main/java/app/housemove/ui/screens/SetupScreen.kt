package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** First-run wizard: the move, the old home's rooms, the new home's rooms. */
@Composable
fun SetupScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()
    var step by remember { mutableIntStateOf(0) }

    // A second phone that joined an existing move through sync skips the wizard.
    LaunchedEffect(data.move.setupDone) {
        if (data.move.setupDone) nav.navigate("home") { popUpTo("setup") { inclusive = true } }
    }

    Screen(title = listOf("Your move", "Rooms in the old home", "Rooms in the new home")[step], back = false) {
        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
        ScrollColumn(Modifier.weight(1f)) {
            when (step) {
                0 -> {
                    Section("Second phone?") {
                        Text("If someone has already set this move up on another phone, don't set it up again. Join it instead and both phones will share the same data.")
                        OutlinedButton(onClick = { nav.open("sync") }) { Text("Join a move from another phone") }
                    }
                    Section("The move") {
                        TextInput(data.move.name, { v -> store.setMove { it.copy(name = v) } }, "Name for this move (optional)", key = "name")
                        DateButton(data.move.moveDay, { d -> store.setMove { it.copy(moveDay = d) } }, "Move day", Modifier.fillMaxWidth())
                        Hint("Not fixed yet? Leave it empty and set it later in Settings. The task timeline counts back from this date.")
                        TextInput(data.move.originName, { v -> store.setMove { it.copy(originName = v) } }, "What to call the old home", key = "origin")
                        TextInput(data.move.destName, { v -> store.setMove { it.copy(destName = v) } }, "What to call the new home", key = "dest")
                    }
                    Section("This phone") {
                        TextInput(device.name, { v -> store.setDevice { it.copy(name = v) } }, "Who uses this phone?", key = "device")
                        Hint("Shown as \"packed by\" on boxes and when phones sync.")
                    }
                }
                1 -> RoomSetup(Home.ORIGIN, "Add every room and storage space you are moving things out of.")
                else -> RoomSetup(Home.DEST, "Add the rooms things will go into. Each gets a colour for its boxes and door sign.")
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick = {
                    if (step < 2) step++ else {
                        finishSetup(store)
                        nav.navigate("home") { popUpTo("setup") { inclusive = true } }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (step < 2) "Next" else "Finish") }
        }
    }
}

/** Link old rooms to new rooms with the same name, add the task timeline, mark setup done. */
private fun finishSetup(store: Store) {
    val data = store.data.value
    val dest = data.roomsOf(Home.DEST)
    for (r in data.roomsOf(Home.ORIGIN)) {
        if (r.mapsTo != null) continue
        val match = dest.firstOrNull { it.name.equals(r.name, ignoreCase = true) } ?: continue
        store.put(r.copy(mapsTo = match.id))
    }
    if (data.liveTasks.isEmpty()) seedTasks().forEach { store.put(it) }
    store.setMove { it.copy(setupDone = true) }
}

@Composable
private fun RoomSetup(home: Home, intro: String) {
    val store = LocalStore.current
    val rooms = appData().roomsOf(home)
    var custom by remember { mutableStateOf("") }
    Text(intro)
    Section("Tap to add") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COMMON_ROOMS.filter { name -> rooms.none { it.name.equals(name, ignoreCase = true) } }.forEach { name ->
                AssistChip(onClick = { store.createRoom(home, name) }, label = { Text(name) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
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
    }
    Section(if (rooms.isEmpty()) "No rooms yet" else "${rooms.size} room${if (rooms.size == 1) "" else "s"}") {
        if (rooms.isEmpty()) Hint("You can also add and change rooms later under More → Rooms.")
        rooms.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (home == Home.DEST) {
                    ColorDot(r.color)
                    Spacer(Modifier.width(10.dp))
                }
                Text(r.name, modifier = Modifier.weight(1f))
                if (home == Home.DEST) Hint(roomColor(r.color).name)
                IconButton(onClick = { store.delete(r) }) { Icon(Icons.Default.Close, contentDescription = "Remove ${r.name}") }
            }
        }
    }
}
