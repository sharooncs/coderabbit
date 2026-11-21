package app.housemove.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.Movable
import app.housemove.data.PhotoKind
import app.housemove.data.Priority
import app.housemove.data.Ratio
import app.housemove.data.Stage
import app.housemove.data.findByLabel
import app.housemove.data.labelFromQr
import app.housemove.data.movables
import app.housemove.data.roomColor
import app.housemove.data.toMovable
import app.housemove.ui.EmptyHint
import app.housemove.ui.LocalStore
import app.housemove.ui.MovableRow
import app.housemove.ui.PhotoStrip
import app.housemove.ui.ProgressRow
import app.housemove.ui.QrScanner
import app.housemove.ui.Screen
import app.housemove.ui.TextInput
import app.housemove.ui.appData
import app.housemove.ui.color
import app.housemove.ui.onColor

/** The thing that was just carried in, and what it was before, so the tap can be undone. */
private data class MdArrival(val id: String, val isBox: Boolean, val prev: Stage, val already: Boolean)

/** Unloading: scan at the door, read the room off the screen, carry it there. */
@Composable
fun UnloadScreen() {
    val store = LocalStore.current
    val data = appData()
    var scanning by rememberSaveable { mutableStateOf(true) }
    var arrival by remember { mutableStateOf<MdArrival?>(null) }
    var unknown by remember { mutableStateOf<String?>(null) }
    var tripFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var damageFor by remember { mutableStateOf<Movable?>(null) }
    val listState = rememberLazyListState()

    fun deliver(m: Movable) {
        unknown = null
        val already = mdArrived(m.stage)
        if (!already) mdSetStage(store, m, Stage.DELIVERED)
        arrival = MdArrival(m.id, m.isBox, m.stage, already)
    }

    val onScan: (String) -> Unit = { text ->
        val code = labelFromQr(text)
        val m = store.data.value.findByLabel(code)
        if (m == null) {
            arrival = null
            unknown = code
        } else deliver(m)
    }

    // Bring the result back into view if the list was scrolled down.
    LaunchedEffect(arrival, unknown) { listState.scrollToItem(0) }

    val all = data.movables()
    val arrivedCount = all.count { mdArrived(it.stage) }
    val trips = data.liveTrips
    val filter = tripFilter?.takeIf { id -> trips.any { it.id == id } }
    val onVan = all.filter { it.stage == Stage.LOADED && (filter == null || it.tripId == filter) }
        .sortedWith(compareBy<Movable>({ it.priority.ordinal }, { data.room(it.destRoomId)?.name ?: "~" }, { it.title }))
    val shown = arrival?.let { a -> if (a.isBox) data.box(a.id)?.toMovable() else data.item(a.id)?.toMovable() }

    Screen(
        "Unload",
        actions = {
            IconButton(onClick = { scanning = !scanning }) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = if (scanning) "Hide the scanner" else "Show the scanner")
            }
        },
    ) {
        if (scanning) QrScanner(onText = onScan, modifier = Modifier.fillMaxWidth().height(240.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            item(key = "result") {
                val a = arrival
                val code = unknown
                when {
                    code != null -> MdBanner(
                        MdResult(MdTone.BAD, "Unknown label $code", "This label is not on any box or item. Check the code, then carry it in and sort it out later.")
                    )
                    a != null && shown != null -> MdArrivalPanel(
                        m = shown,
                        already = a.already,
                        onDamage = { damageFor = shown },
                        onUndo = {
                            mdSetStage(store, shown, a.prev)
                            arrival = null
                        },
                    )
                    else -> Text(
                        if (scanning) "Point the camera at a label, or tap something in the list below."
                        else "Tap something in the list below when it comes off the van.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item(key = "progress") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delivered $arrivedCount of ${all.size}", style = MaterialTheme.typography.titleMedium)
                    ProgressRow("Delivered", Ratio(arrivedCount, all.size))
                }
            }
            if (trips.size > 1) item(key = "trips") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == null, onClick = { tripFilter = null }, label = { Text("All trips") })
                    trips.forEach { t ->
                        FilterChip(selected = filter == t.id, onClick = { tripFilter = t.id }, label = { Text(t.name) })
                    }
                }
            }
            item(key = "hvan") { MdGroupHeader("Still on the van", onVan.size) }
            if (onVan.isEmpty()) item(key = "empty") {
                EmptyHint(
                    if (arrivedCount > 0) "Nothing left on the van. Check Reconcile before it goes back."
                    else "Nothing is marked as loaded. You can still scan labels as things come in."
                )
            }
            items(onVan, key = { "m" + it.id }) { m ->
                MovableRow(m, onClick = { deliver(m) }, trailing = {
                    FilledTonalButton(onClick = { deliver(m) }) { Text("Here") }
                })
            }
        }
    }

    damageFor?.let { m -> MdDamageDialog(m, onClose = { damageFor = null }) }
}

/** Full-width panel in the room's colour: readable from a few steps away while carrying. */
@Composable
private fun MdArrivalPanel(m: Movable, already: Boolean, onDamage: () -> Unit, onUndo: () -> Unit) {
    val room = appData().room(m.destRoomId)
    val rc = room?.let { roomColor(it.color) }
    val bg = rc?.color() ?: MaterialTheme.colorScheme.inverseSurface
    val fg = rc?.onColor() ?: MaterialTheme.colorScheme.inverseOnSurface
    val name = room?.name ?: "No room set"
    Column(
        Modifier.fillMaxWidth().background(bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            name,
            color = fg,
            fontWeight = FontWeight.Bold,
            style = when {
                name.length <= 9 -> MaterialTheme.typography.displayLarge
                name.length <= 14 -> MaterialTheme.typography.displayMedium
                else -> MaterialTheme.typography.displaySmall
            },
        )
        val where = listOfNotNull(room?.floor?.takeIf { it.isNotBlank() }, rc?.let { "${it.name} door sign" }).joinToString(" · ")
        if (where.isNotBlank()) Text(where, color = fg, style = MaterialTheme.typography.titleLarge)
        Text(m.title, color = fg, style = MaterialTheme.typography.titleMedium)
        val flags = listOfNotNull(
            "FRAGILE".takeIf { m.fragile },
            "HEAVY".takeIf { m.heavy },
            "FIRST NIGHT".takeIf { m.priority == Priority.FIRST_NIGHT },
            "DAMAGE NOTED".takeIf { m.damaged },
        )
        if (flags.isNotEmpty()) {
            Text(flags.joinToString("  ·  "), color = fg, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (already) Text("Already scanned in.", color = fg, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onDamage,
                colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = bg),
                modifier = Modifier.height(52.dp),
            ) { Text("Report damage") }
            if (!already) OutlinedButton(
                onClick = onUndo,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
                border = BorderStroke(1.dp, fg),
                modifier = Modifier.height(52.dp),
            ) { Text("Undo") }
        }
    }
}

@Composable
private fun MdDamageDialog(m: Movable, onClose: () -> Unit) {
    val store = LocalStore.current
    val data = appData()
    val saved = (if (m.isBox) data.box(m.id)?.damageNote else data.item(m.id)?.damageNote) ?: ""
    val wasDamaged = (if (m.isBox) data.box(m.id)?.damaged else data.item(m.id)?.damaged) == true
    var note by remember(m.id) { mutableStateOf(saved) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Report damage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(m.title, style = MaterialTheme.typography.bodyLarge)
                TextInput(note, { note = it }, "What is damaged?", key = m.id, singleLine = false, mic = true)
                PhotoStrip(m.id, PhotoKind.DAMAGE, label = "Photos of the damage")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                mdSetDamage(store, m, true, note.trim())
                onClose()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (wasDamaged) TextButton(onClick = {
                    mdSetDamage(store, m, false, "")
                    onClose()
                }) { Text("Not damaged") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}
