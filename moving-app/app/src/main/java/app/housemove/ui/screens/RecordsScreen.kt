package app.housemove.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.METER_KINDS
import app.housemove.data.PhotoKind
import app.housemove.data.Reading
import app.housemove.data.Store
import app.housemove.data.fmtDay
import app.housemove.data.newId
import app.housemove.data.today
import app.housemove.ui.ChipPicker
import app.housemove.ui.ConfirmDialog
import app.housemove.ui.Hint
import app.housemove.ui.LocalStore
import app.housemove.ui.PhotoStrip
import app.housemove.ui.Screen
import app.housemove.ui.ScrollColumn
import app.housemove.ui.Section
import app.housemove.ui.TextInput
import app.housemove.ui.appData

/** Change a reading using its latest saved copy, so quick edits never overwrite each other. */
private fun mdEditReading(store: Store, id: String, f: (Reading) -> Reading) {
    store.data.value.readings.firstOrNull { it.id == id && !it.deleted }?.let { store.put(f(it)) }
}

/** Dated photo evidence and meter readings for one of the two homes. */
@Composable
fun RecordsScreen(home: Home) {
    val store = LocalStore.current
    val data = appData()
    val rooms = data.roomsOf(home)
    val readings = data.liveReadings.filter { it.home == home }
    var deleting by remember { mutableStateOf<Reading?>(null) }
    val homeName = if (home == Home.ORIGIN) data.move.originName else data.move.destName

    Screen(if (home == Home.ORIGIN) "Old home record" else "New home record") {
        ScrollColumn {
            Text(homeName, style = MaterialTheme.typography.titleLarge)
            Hint(
                if (home == Home.ORIGIN)
                    "Photos of every room as you leave it, plus the final meter readings. They protect your deposit and settle arguments about bills."
                else
                    "Do this before unloading. Photograph every room and any marks or damage already there, and read the meters before anything is carried in."
            )

            Section("Whole home") {
                PhotoStrip("home:" + home.name, PhotoKind.ROOM, label = "Whole home, outside, existing damage")
            }

            Section("Meter readings") {
                if (readings.isEmpty()) Hint("Photograph each meter and type the number shown.")
                readings.forEach { r ->
                    ChipPicker(METER_KINDS, r.kind, { it }, { kind -> mdEditReading(store, r.id) { it.copy(kind = kind) } })
                    TextInput(
                        r.value,
                        { v -> mdEditReading(store, r.id) { it.copy(value = v) } },
                        "${r.kind} reading",
                        key = r.id,
                        placeholder = "The number on the meter",
                    )
                    PhotoStrip(r.id, PhotoKind.METER, label = "Photo of the meter")
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
                FilledTonalButton(onClick = {
                    val kind = METER_KINDS.firstOrNull { k -> readings.none { it.kind == k } } ?: METER_KINDS.last()
                    store.put(Reading(id = newId(), home = home, kind = kind, day = today()))
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add meter reading")
                }
            }

            if (rooms.isEmpty()) Hint("No rooms for this home yet. Add them under More → Rooms to record each room separately.")
            rooms.forEach { room ->
                Section(room.name) {
                    PhotoStrip(room.id, PhotoKind.ROOM, label = "Condition")
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
