package app.housemove.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import app.housemove.data.vehicle
import app.housemove.ui.EmptyState
import app.housemove.ui.LocalNav
import app.housemove.ui.LocalStore
import app.housemove.ui.OwnerThumb
import app.housemove.ui.PhotoStrip
import app.housemove.ui.Pic
import app.housemove.ui.QrScanner
import app.housemove.ui.Screen
import app.housemove.ui.TextInput
import app.housemove.ui.appData
import app.housemove.ui.color
import app.housemove.ui.onColor
import app.housemove.ui.open

/** The thing that was just carried in, and what it was before, so the tap can be undone. */
private data class MdArrival(val id: String, val isBox: Boolean, val prev: Stage, val already: Boolean)

/** Unloading: scan at the door, read the room off the screen, carry it there. */
@Composable
fun UnloadScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val vehicle = data.vehicle()
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

    val all = data.movables()
    val arrivedCount = all.count { mdArrived(it.stage) }
    val trips = data.liveTrips
    val filter = tripFilter?.takeIf { id -> trips.any { it.id == id } }
    val stillOn = all.count { it.stage == Stage.LOADED }
    val onVan = all.filter { it.stage == Stage.LOADED && (filter == null || it.tripId == filter) }
        .sortedWith(compareBy<Movable>({ it.priority.ordinal }, { data.room(it.destRoomId)?.name ?: "~" }, { it.title }))
    val shown = arrival?.let { a -> if (a.isBox) data.box(a.id)?.toMovable() else data.item(a.id)?.toMovable() }

    Screen("Unload") {
        if (all.isEmpty()) {
            EmptyState(
                Icons.Default.Download, "Nothing to unload yet",
                text = "Boxes you pack and items marked Move appear here.",
            )
        } else {
            MdProgressHeader(
                Ratio(arrivedCount, all.size),
                "$arrivedCount of ${all.size} delivered",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                sub = if (stillOn > 0) "$stillOn still on the $vehicle" else null,
            ) {
                if (trips.size > 1) Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == null, onClick = { tripFilter = null }, label = { Text("All trips") })
                    trips.forEach { t ->
                        FilterChip(selected = filter == t.id, onClick = { tripFilter = t.id }, label = { Text(t.name) })
                    }
                }
            }
            MdScanAction(scanning, "Scan labels at the door") { scanning = !scanning }
            if (scanning) QrScanner(onText = onScan, modifier = Modifier.fillMaxWidth().height(mdScannerHeight()))

            // Bring the result back into view if the list was scrolled down.
            LaunchedEffect(arrival, unknown) { listState.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                item(key = "result") {
                    val a = arrival
                    val code = unknown
                    when {
                        code != null -> MdBanner(
                            MdResult(MdTone.BAD, "Unknown label $code", "Check the code. Carry it in and sort it out later.")
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
                        onVan.isNotEmpty() -> Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (scanning) "Scan a label, or tap Here as each thing comes off."
                                else "Tap Here as each thing comes off the $vehicle.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                if (onVan.isNotEmpty() || filter != null) item(key = "hvan") { MdGroupHeader("Still on the $vehicle", onVan.size) }
                if (onVan.isEmpty()) item(key = "empty") {
                    when {
                        // Only the trip being looked at is empty.
                        stillOn > 0 -> EmptyState(Icons.Default.CheckCircle, "Nothing left from this trip")
                        arrivedCount > 0 -> EmptyState(
                            Icons.Default.CheckCircle, "Nothing left on the $vehicle",
                            text = "Check Reconcile before it goes back.",
                            action = "Reconcile", onAction = { nav.open("reconcile") },
                        )
                        else -> EmptyState(
                            Icons.Default.LocalShipping, "Nothing is marked as loaded",
                            text = "You can still scan labels as things come in.",
                        )
                    }
                }
                items(onVan, key = { "m" + it.id }) { m ->
                    MdThingRow(m, onClick = { deliver(m) }) {
                        FilledTonalButton(onClick = { deliver(m) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Here") }
                    }
                }
            }
        }
    }

    damageFor?.let { m -> MdDamageDialog(m, onClose = { damageFor = null }) }
}

/**
 * The hero of the screen: a full-width panel in the room's colour, with the room
 * name large enough to read from a few steps away while carrying.
 */
@Composable
private fun MdArrivalPanel(m: Movable, already: Boolean, onDamage: () -> Unit, onUndo: () -> Unit) {
    val room = appData().room(m.destRoomId)
    val rc = room?.let { roomColor(it.color) }
    val bg = rc?.color() ?: MaterialTheme.colorScheme.inverseSurface
    val fg = rc?.onColor() ?: MaterialTheme.colorScheme.inverseOnSurface
    val name = room?.name ?: "No room set"
    Column(
        Modifier.fillMaxWidth().background(bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Pic.Where, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    already && room != null -> "Already scanned in. It goes to"
                    already -> "Already scanned in"
                    room != null -> "Carry it to"
                    else -> "Carried in"
                },
                color = fg, style = MaterialTheme.typography.titleMedium,
            )
        }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OwnerThumb(m.id, Modifier.size(64.dp), isBox = m.isBox)
            Spacer(Modifier.width(12.dp))
            Text(m.title, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val flags = listOfNotNull<Pair<ImageVector, String>>(
            (Pic.Fragile to "Fragile").takeIf { m.fragile },
            (Pic.Upright to "Keep upright").takeIf { m.upright },
            (if (m.isBox) Pic.Heavy to "Heavy" else Pic.TwoPeople to "2 people").takeIf { m.heavy },
            (Pic.FirstNight to "First night").takeIf { m.priority == Priority.FIRST_NIGHT },
            (Pic.Damage to "Damage noted").takeIf { m.damaged },
        )
        if (flags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                flags.forEach { (icon, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onDamage,
                colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = bg),
                modifier = Modifier.heightIn(min = 52.dp),
            ) {
                Icon(Pic.Damage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Report damage")
            }
            if (!already) OutlinedButton(
                onClick = onUndo,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
                border = BorderStroke(1.dp, fg),
                modifier = Modifier.heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Undo")
            }
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
