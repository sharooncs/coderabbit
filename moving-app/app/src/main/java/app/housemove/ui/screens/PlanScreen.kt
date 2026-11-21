package app.housemove.ui.screens

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

    fun edit(itemId: String, f: (Item) -> Item) {
        store.data.value.item(itemId)?.let { store.put(f(it)) }
    }

    Screen("Room plan and fit check") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (moving.isEmpty()) {
                item(key = "empty") {
                    Section("Nothing to place yet") {
                        Text("Items you mark as \"Move\" appear here so you can give each one a room in the new home.")
                        Button(onClick = { nav.open("triage") }) { Text("Decide what moves") }
                    }
                }
            }

            if (destRooms.isEmpty()) {
                item(key = "norooms") {
                    Section("Add the new home's rooms") {
                        Text("List the rooms of the new home first. Each gets a colour, so boxes and furniture can be sent straight to the right door.")
                        Button(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                    }
                }
            }

            if (homeless.isNotEmpty()) {
                item(key = "needs") {
                    Text("Needs a room (${homeless.size})", style = MaterialTheme.typography.titleMedium)
                }
                items(homeless, key = { "h-" + it.id }) { x ->
                    Section {
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.open("item/${x.id}") },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OwnerThumb(x.id, Modifier.size(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(x.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Hint("From " + (data.room(x.originRoomId)?.name ?: "no room"))
                            }
                        }
                        RoomPicker(Home.DEST, null, onPick = { rid -> edit(x.id) { it.copy(destRoomId = rid) } })
                    }
                }
            }

            items(destRooms, key = { "r-" + it.id }) { room ->
                val here = moving.filter { it.destRoomId == room.id }.sortedBy { it.name.lowercase() }
                val door = room.doorWidthCm
                Section(
                    title = "${room.name} (${here.size})",
                    action = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(room.color)
                            Spacer(Modifier.width(6.dp))
                            Text(roomColor(room.color).name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                ) {
                    if (door != null && door > 0) {
                        Hint("Narrowest door on the way in: " + if (inches) "${fmtNum(door / 2.54)} in" else "${fmtNum(door)} cm")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Hint("No door width yet, so the fit can't be checked.", Modifier.weight(1f))
                            TextButton(onClick = { nav.open("rooms") }) { Text("Add it") }
                        }
                    }
                    if (here.isEmpty()) Hint("Nothing is going to this room yet.")
                    here.forEach { x ->
                        val fit = data.fitOf(x)
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.open("item/${x.id}") }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(x.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                when (fit) {
                                    Fit.OK -> Text("Fits", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                                    Fit.TIGHT -> Flag("Tight fit")
                                    Fit.NO -> if (x.needsDisassembly) Flag("Take apart") else Flag("Won't fit", error = true)
                                    Fit.UNKNOWN -> if (door != null && door > 0) Hint("Add its size") else Unit
                                }
                            }
                            if (fit == Fit.NO && !x.needsDisassembly) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Hint("Too big for the door in one piece.", Modifier.weight(1f))
                                    TextButton(onClick = { edit(x.id) { it.copy(needsDisassembly = true) } }) { Text("Mark: take apart") }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "load") {
                val v = data.volume()
                Section("Van load estimate") {
                    if (v.totalM3 <= 0.0) {
                        Hint("Once items are marked to move and boxes are packed, the size of the load is estimated here.")
                    } else {
                        Text(
                            "About ${oneDecimal(v.totalM3)} m³ (${oneDecimal(v.totalM3 * 35.31)} ft³)",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "That is about ${v.trips} trip${if (v.trips == 1) "" else "s"} in a ${fmtNum(data.move.vanCapacityM3)} m³ van.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Hint("Furniture ${oneDecimal(v.itemsM3)} m³ + boxes ${oneDecimal(v.boxesM3)} m³, plus some slack for awkward shapes.")
                        if (v.itemsWithoutSize > 0) {
                            Hint(
                                "${v.itemsWithoutSize} item${if (v.itemsWithoutSize == 1) " has" else "s have"} no size yet and " +
                                    "${if (v.itemsWithoutSize == 1) "is" else "are"} counted at $DEFAULT_ITEM_M3 m³ each. Add sizes for a better estimate.",
                            )
                        }
                        Hint("Change the van size under More → Settings.")
                    }
                }
            }

            item(key = "signs") {
                Card(Modifier.fillMaxWidth()) {
                    MenuRow(
                        Icons.Default.Print,
                        "Print door signs",
                        sub = "One coloured sign per room, so helpers know where things go.",
                    ) { nav.open("labels") }
                }
            }
        }
    }
}
