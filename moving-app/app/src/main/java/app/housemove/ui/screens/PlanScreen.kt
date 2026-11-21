package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import java.util.Locale

private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

/** Give every moving item a room in the new home and check it will get through the door. */
@Composable
fun PlanScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()

    val moving = data.movingItems()
    val destRooms = data.roomsOf(Home.DEST)
    val homeless = moving.filter { data.room(it.destRoomId) == null }.sortedBy { it.createdAt }
    val inches = data.move.useInches
    val vehicle = data.vehicle()

    fun edit(itemId: String, f: (Item) -> Item) {
        store.data.value.item(itemId)?.let { store.put(f(it)) }
    }

    Screen("Room plan and fit check") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (destRooms.isEmpty()) {
                item(key = "norooms") {
                    EmptyState(
                        Pic.Room, "Add the new home's rooms",
                        text = "Each room gets a colour, so things go straight to the right door.",
                        action = "Add rooms", onAction = { nav.open("rooms") },
                    )
                }
            } else if (moving.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Default.Map, "Nothing to place yet",
                        text = "Things you mark Move appear here to be given a room.",
                        action = if (data.uses(Part.TRIAGE)) "Decide what moves" else "Start the walk-through",
                        onAction = { nav.open(if (data.uses(Part.TRIAGE)) "triage" else "walk") },
                    )
                }
            }

            if (homeless.isNotEmpty()) {
                item(key = "needs") {
                    Text("Needs a room (${homeless.size})", style = MaterialTheme.typography.titleMedium)
                }
                items(homeless, key = { "h-" + it.id }) { x ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.open("item/${x.id}") },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OwnerThumb(x.id, Modifier.size(56.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(x.title(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    RoomBadge(data.room(x.originRoomId))
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (destRooms.isNotEmpty()) {
                                RoomTiles(Home.DEST, null, onPick = { rid -> edit(x.id) { it.copy(destRoomId = rid) } })
                            }
                        }
                    }
                }
            }

            if (moving.isNotEmpty()) {
                // Rooms with something going to them get a block each; the rest share one quiet line.
                val (used, unused) = destRooms.partition { r -> moving.any { it.destRoomId == r.id } }
                items(used, key = { "r-" + it.id }) { room ->
                    val here = moving.filter { it.destRoomId == room.id }.sortedBy { it.name.lowercase() }
                    RoomBlock(
                        room = room,
                        here = here,
                        data = data,
                        inches = inches,
                        onOpen = { nav.open("item/${it.id}") },
                        onTakeApart = { x -> edit(x.id) { it.copy(needsDisassembly = true) } },
                        onAddDoor = { nav.open("rooms") },
                    )
                }
                if (unused.isNotEmpty()) {
                    item(key = "unused") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nothing going here yet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                unused.forEach { RoomBadge(it) }
                            }
                        }
                    }
                }
            }

            val v = data.volume()
            if (v.totalM3 > 0.0) item(key = "load") {
                Fold(
                    "${vehicle.replaceFirstChar { it.uppercase() }} load estimate", Icons.Default.LocalShipping,
                    summary = "About ${oneDecimal(v.totalM3)} m³ · ${v.trips} trip${if (v.trips == 1) "" else "s"}",
                    key = "load",
                ) {
                    Text(
                        "About ${oneDecimal(v.totalM3)} m³ (${oneDecimal(v.totalM3 * 35.31)} ft³)",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "That is about ${v.trips} trip${if (v.trips == 1) "" else "s"} in a ${fmtNum(data.move.vanCapacityM3)} m³ $vehicle.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Furniture ${oneDecimal(v.itemsM3)} m³ + boxes ${oneDecimal(v.boxesM3)} m³, plus some slack.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (v.itemsWithoutSize > 0) {
                        IconFlag(
                            Pic.Size,
                            "${v.itemsWithoutSize} item${if (v.itemsWithoutSize == 1) "" else "s"} with no size, counted at $DEFAULT_ITEM_M3 m³ each",
                        )
                    }
                    Hint("Change the $vehicle size under More → Settings.")
                }
            }

            if (data.uses(Part.LABELS)) {
                item(key = "signs") {
                    Card(Modifier.fillMaxWidth()) {
                        MenuRow(Icons.Default.Print, "Print door signs", sub = "One coloured sign per room.") { nav.open("labels") }
                    }
                }
            }
        }
    }
}

/** One new-home room: its colour across the top, then what is going there and whether each thing fits. */
@Composable
private fun RoomBlock(
    room: Room,
    here: List<Item>,
    data: AppData,
    inches: Boolean,
    onOpen: (Item) -> Unit,
    onTakeApart: (Item) -> Unit,
    onAddDoor: () -> Unit,
) {
    val colour = roomColor(room.color)
    val door = room.doorWidthCm?.takeIf { it > 0 }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(colour.color()).padding(start = 16.dp, end = 6.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    room.name, color = colour.onColor(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${here.size} item${if (here.size == 1) "" else "s"}", color = colour.onColor(),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1,
                )
            }
            if (door != null) {
                Icon(Icons.Default.DoorFront, contentDescription = "Narrowest door", tint = colour.onColor(), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (inches) "${fmtNum(door / 2.54)} in" else "${fmtNum(door)} cm",
                    color = colour.onColor(), style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                TextButton(onClick = onAddDoor, colors = ButtonDefaults.textButtonColors(contentColor = colour.onColor())) {
                    Icon(Icons.Default.DoorFront, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add door width")
                }
            }
        }
        if (here.isNotEmpty()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                here.forEach { x ->
                    val fit = data.fitOf(x)
                    val stuck = fit == Fit.NO && !x.needsDisassembly
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(x) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OwnerThumb(x.id, Modifier.size(44.dp))
                        Text(
                            x.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        when (fit) {
                            Fit.OK -> Icon(Icons.Default.CheckCircle, contentDescription = "Fits", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                            Fit.TIGHT -> IconFlag(Icons.Default.Warning, "Tight fit")
                            Fit.NO -> if (x.needsDisassembly) IconFlag(Pic.TakeApart, "Take apart") else IconFlag(Icons.Default.Warning, "Won't fit", error = true)
                            Fit.UNKNOWN -> if (door != null) Hint("No size yet") else Unit
                        }
                    }
                    if (stuck) {
                        Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onTakeApart(x) }) {
                                Icon(Pic.TakeApart, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Mark: take apart")
                            }
                        }
                    }
                }
            }
        }
    }
}
