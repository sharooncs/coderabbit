package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "room", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Which room are you in?", style = MaterialTheme.typography.titleMedium)
                    if (rooms.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Hint("No rooms yet. Photos can be sorted into rooms later.", Modifier.weight(1f))
                            OutlinedButton(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                        }
                    } else {
                        RoomTiles(
                            Home.ORIGIN, roomId,
                            onPick = { picked -> store.setDevice { it.copy(currentRoomId = picked) } },
                            allowNone = false,
                        )
                    }
                }
            }
            item(key = "shoot", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BigAction(Pic.Camera, "Start shooting", onClick = { camera = true }, enabled = canShoot)
                    Hint(
                        if (canShoot) "One photo per big thing. Small things are recorded by box."
                        else "Pick the room first, so the photos are filed under it."
                    )
                }
            }
            item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (room != null) "In ${room.name} (${inRoom.size})" else "Not in a room yet (${inRoom.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (inRoom.isEmpty()) {
                item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Pic.Item, "Nothing here yet", text = "Each photo you take appears here, ready to be named.")
                }
            }
            items(inRoom, key = { it.id }) { x ->
                val scheme = MaterialTheme.colorScheme
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surface)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        OwnerThumb(
                            x.id,
                            Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                                .clickable(onClickLabel = "Open item details") { nav.open("item/${x.id}") },
                        )
                        FilledTonalIconButton(
                            onClick = { nav.open("item/${x.id}") },
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp),
                        ) { Icon(Icons.Default.ChevronRight, contentDescription = "Open item details") }
                    }
                    TextInput(
                        value = x.name,
                        onChange = { v -> store.data.value.item(x.id)?.let { store.put(it.copy(name = v)) } },
                        label = "What is it?",
                        key = x.id,
                        mic = true,
                    )
                }
            }
            if (inRoom.isNotEmpty() && data.uses(Part.TRIAGE)) {
                item(key = "next", span = { GridItemSpan(maxLineSpan) }) {
                    BigAction(Icons.Default.Rule, "Decide what to keep", onClick = { nav.open("triage") }, primary = false)
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
