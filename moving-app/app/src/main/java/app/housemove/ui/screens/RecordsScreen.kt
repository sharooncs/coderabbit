package app.housemove.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.METER_KINDS
import app.housemove.data.PhotoKind
import app.housemove.data.Ratio
import app.housemove.data.Reading
import app.housemove.data.Store
import app.housemove.data.fmtDay
import app.housemove.data.newId
import app.housemove.data.today
import app.housemove.ui.Choice
import app.housemove.ui.ConfirmDialog
import app.housemove.ui.Fold
import app.housemove.ui.Hint
import app.housemove.ui.IconChoice
import app.housemove.ui.LocalNav
import app.housemove.ui.LocalStore
import app.housemove.ui.PhotoStrip
import app.housemove.ui.Screen
import app.housemove.ui.TextInput
import app.housemove.ui.appData
import app.housemove.ui.open

/** Change a reading using its latest saved copy, so quick edits never overwrite each other. */
private fun mdEditReading(store: Store, id: String, f: (Reading) -> Reading) {
    store.data.value.readings.firstOrNull { it.id == id && !it.deleted }?.let { store.put(f(it)) }
}

private fun mdMeterIcon(kind: String): ImageVector = when (kind) {
    "Electricity" -> Icons.Default.Bolt
    "Gas" -> Icons.Default.LocalFireDepartment
    "Water" -> Icons.Default.WaterDrop
    else -> Icons.Default.Speed
}

/** Dated photo evidence and meter readings for one of the two homes. */
@Composable
fun RecordsScreen(home: Home) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val rooms = data.roomsOf(home)
    val readings = data.liveReadings.filter { it.home == home }
    var deleting by remember { mutableStateOf<Reading?>(null) }
    val homeName = if (home == Home.ORIGIN) data.move.originName else data.move.destName
    val narrow = mdNarrow(380f) // "Electricity" needs a wider tile than most words
    val photographed = rooms.count { data.photosOf(it.id, PhotoKind.ROOM).isNotEmpty() }

    Screen(if (home == Home.ORIGIN) "Old home record" else "New home record") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MdProgressHeader(
                        Ratio(photographed, rooms.size),
                        "$photographed of ${rooms.size} rooms photographed",
                        sub = homeName,
                    )
                    Hint(
                        if (home == Home.ORIGIN) "Dated photos and readings protect your deposit and settle bills."
                        else "Do this before unloading, so marks already there are on record."
                    )
                }
            }

            // Meters are read once per home, so they fold away to one line once there is a reading.
            item(key = "meters") {
                Fold(
                    "Meter readings", Icons.Default.Speed,
                    summary = if (readings.isEmpty()) "None yet"
                    else readings.joinToString(" · ") { r -> r.kind + " " + r.value.ifBlank { "–" } },
                    open = readings.isEmpty(),
                    key = "meters:" + home.name,
                ) {
                    readings.forEach { r ->
                        IconChoice(
                            METER_KINDS.map { Choice(it, it, mdMeterIcon(it)) },
                            selected = r.kind,
                            onPick = { kind -> mdEditReading(store, r.id) { it.copy(kind = kind) } },
                            columns = if (narrow) 2 else 4,
                        )
                        PhotoStrip(r.id, PhotoKind.METER, label = "Photo of the meter")
                        TextInput(
                            r.value,
                            { v -> mdEditReading(store, r.id) { it.copy(value = v) } },
                            "${r.kind} reading",
                            key = r.id,
                            placeholder = "The number on the meter",
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Taken ${fmtDay(r.day)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { deleting = r }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete this ${r.kind} reading")
                            }
                        }
                        HorizontalDivider()
                    }
                    FilledTonalButton(
                        onClick = {
                            val kind = METER_KINDS.firstOrNull { k -> readings.none { it.kind == k } } ?: METER_KINDS.last()
                            store.put(Reading(id = newId(), home = home, kind = kind, day = today()))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add meter reading")
                    }
                }
            }

            // One strip of photos for the home as a whole, then one per room. The camera comes first in each.
            item(key = "home") { MdRecordStrip("home:" + home.name, "Whole home and outside") }
            items(rooms, key = { "room:" + it.id }) { room -> MdRecordStrip(room.id, room.name) }
            if (rooms.isEmpty()) item(key = "norooms") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("No rooms for this home yet.", Modifier.weight(1f))
                    FilledTonalButton(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                }
            }
        }
    }

    deleting?.let { r ->
        ConfirmDialog(
            title = "Delete this reading?",
            text = "The ${r.kind} reading and its photos will be removed.",
            onConfirm = {
                data.photosOf(r.id).forEach { store.delete(it) }
                store.delete(r)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** The photos of one place: its name, the camera, then what has been taken, with a tick once there is one. */
@Composable
private fun MdRecordStrip(ownerId: String, name: String) {
    val done = appData().photosOf(ownerId, PhotoKind.ROOM).isNotEmpty()
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            PhotoStrip(ownerId, PhotoKind.ROOM, Modifier.weight(1f), label = name)
            if (done) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.CheckCircle, contentDescription = "Photographed",
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
