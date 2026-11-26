package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import app.housemove.data.File

/**
 * Set in tests to stand in for the camera of the walk-through: it is handed the file a
 * picture should be saved to and writes one. Null means: open the real camera.
 */
val LocalWalkCamera = staticCompositionLocalOf<((File) -> Unit)?> { null }

/**
 * One photo taken in the walk-through becomes one item of [roomId]. The photo is enough:
 * the item gets a name such as "Kitchen item 3" and can be named properly later, or never.
 */
internal fun Store.addByPhoto(roomId: String?, photo: File): Item {
    val created = createItem(data.value.defaultItemName(roomId), roomId)
    addPhoto(created.id, PhotoKind.GENERAL, photo)
    return created
}

/**
 * Rapid catalogue: stand in a room and keep taking photos. Each photo becomes an item
 * in that room, and that is all that is needed. Naming is one tap on a chip, or a word
 * spoken, whenever there is time for it.
 */
@Composable
fun WalkScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()
    val testCamera = LocalWalkCamera.current

    val rooms = data.roomsOf(Home.ORIGIN)
    val roomId = device.currentRoomId?.takeIf { id -> rooms.any { it.id == id } }
    val room = data.room(roomId)
    var camera by remember { mutableStateOf(false) }

    // Newest first, so the thing just photographed is at the top ready to be named.
    val inRoom = data.liveItems
        .filter { if (roomId == null) data.room(it.originRoomId) == null else it.originRoomId == roomId }
        .sortedByDescending { it.createdAt }
    val canShoot = roomId != null || rooms.isEmpty()
    val unnamed = inRoom.count { it.hasPlaceholderName() }

    Screen("Walk-through") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "room") {
                if (rooms.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("No rooms yet. Photos can be sorted into rooms later.", Modifier.weight(1f))
                        RowButton("Add rooms", onClick = { nav.open("rooms") }, forward = false)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (canShoot) "Which room are you in?" else "Pick the room you are in",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        RoomPicker(
                            Home.ORIGIN, roomId,
                            onPick = { picked -> store.setDevice { it.copy(currentRoomId = picked) } },
                            allowNone = false,
                        )
                    }
                }
            }
            item(key = "shoot") {
                BigAction(
                    Pic.Camera, "Start shooting",
                    onClick = {
                        if (testCamera != null) {
                            val file = store.newPhotoFile()
                            testCamera(file)
                            store.addByPhoto(roomId, file)
                        } else camera = true
                    },
                    enabled = canShoot,
                )
            }
            if (inRoom.isEmpty()) {
                item(key = "none") {
                    EmptyState(Pic.Item, "Nothing here yet", text = "One photo per big thing. That is all it takes.")
                }
            } else {
                item(key = "count") {
                    ListHeading(
                        if (room != null) "In ${room.name}" else "Not in a room yet", icon = Pic.Room, count = inRoom.size,
                        trailing = if (unnamed > 0) "$unnamed to name" else null,
                    )
                }
            }
            items(inRoom, key = { it.id }) { x ->
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), shadow = 6.dp).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ItemPhoto(
                        x.id,
                        Modifier.size(84.dp).clickable(onClickLabel = "Open item details") { nav.open("item/${x.id}") },
                        RoundedCornerShape(16.dp),
                    )
                    ItemNamer(x, Modifier.weight(1f))
                }
            }
            if (inRoom.isNotEmpty() && data.uses(Part.TRIAGE) && store.can(Action.SUGGEST)) {
                item(key = "next") {
                    BigAction(Icons.Default.Rule, "Decide what to keep", onClick = { nav.open("triage") }, primary = false)
                }
            }
        }
    }

    if (camera && canShoot) {
        PhotoCamera(
            hint = (room?.name?.let { "$it: " } ?: "") + "one photo per big thing",
            onPhoto = { file -> store.addByPhoto(roomId, file) },
            onClose = { camera = false },
        )
    }
}
