package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/**
 * Rapid catalogue: stand in a room and keep taking photos.
 * Each photo becomes an item in that room, to be named now or later.
 */
@Composable
fun WalkScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()

    val rooms = data.roomsOf(Home.ORIGIN)
    val roomId = device.currentRoomId?.takeIf { id -> rooms.any { it.id == id } }
    val room = data.room(roomId)
    var camera by remember { mutableStateOf(false) }

    // Newest first, so the thing just photographed is at the top ready to be named.
    val inRoom = data.liveItems
        .filter { if (roomId == null) data.room(it.originRoomId) == null else it.originRoomId == roomId }
        .sortedByDescending { it.createdAt }
    val canShoot = roomId != null || rooms.isEmpty()

    Screen("Walk-through") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "room") {
                Section("Which room are you in?") {
                    if (rooms.isEmpty()) {
                        Hint("You have not added the rooms of your old home yet. You can still take photos now and sort them into rooms later.")
                        OutlinedButton(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                    } else {
                        RoomPicker(
                            Home.ORIGIN, roomId,
                            onPick = { picked -> store.setDevice { it.copy(currentRoomId = picked) } },
                            allowNone = false,
                        )
                        if (roomId == null) Hint("Pick the room first, so everything you photograph is filed under it.")
                    }
                }
            }
            item(key = "shoot") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { camera = true },
                        enabled = canShoot,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Start shooting", style = MaterialTheme.typography.titleMedium)
                    }
                    Hint("One photo per big thing. Keep tapping the shutter as you go round the room, then press Done.")
                    Hint("Only furniture, appliances, electronics, valuables and awkward things. Small stuff is recorded by box when you pack.")
                }
            }
            item(key = "count") {
                Text(
                    if (room != null) "In ${room.name} (${inRoom.size})" else "Not in a room yet (${inRoom.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (inRoom.isEmpty()) {
                item(key = "none") { Hint("Nothing here yet. Photos you take will appear in this list, ready to be named.") }
            }
            items(inRoom, key = { it.id }) { x ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OwnerThumb(x.id, Modifier.size(56.dp))
                    Spacer(Modifier.width(10.dp))
                    TextInput(
                        value = x.name,
                        onChange = { v -> store.data.value.item(x.id)?.let { store.put(it.copy(name = v)) } },
                        label = "What is it?",
                        modifier = Modifier.weight(1f),
                        key = x.id,
                        mic = true,
                    )
                    IconButton(onClick = { nav.open("item/${x.id}") }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Open item details")
                    }
                }
            }
            if (inRoom.isNotEmpty()) {
                item(key = "next") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("Names can wait. When every room is done, go through the items and decide what moves, sells, goes to charity or gets binned.")
                        OutlinedButton(onClick = { nav.open("triage") }, modifier = Modifier.fillMaxWidth()) { Text("Decide what to keep (Triage)") }
                    }
                }
            }
        }
    }

    if (camera && canShoot) {
        CameraDialog(
            hint = (room?.name?.let { "$it: " } ?: "") + "one photo per big thing",
            newFile = { store.newPhotoFile() },
            onPhoto = { file ->
                val created = store.createItem("", roomId)
                store.addPhoto(created.id, PhotoKind.GENERAL, file)
            },
            onClose = { camera = false },
        )
    }
}
