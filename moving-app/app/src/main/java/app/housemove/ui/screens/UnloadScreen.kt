package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.ContainerKind
import app.housemove.data.Movable
import app.housemove.data.PhotoKind
import app.housemove.data.Priority
import app.housemove.data.Ratio
import app.housemove.data.Stage
import app.housemove.data.labelFromQr
import app.housemove.data.movables
import app.housemove.data.roomColor
import app.housemove.data.toMovable
import app.housemove.data.vehicle
import app.housemove.data.*
import app.housemove.ui.*

/**
 * The thing that was just carried in, and what it was before, so the tap can be undone.
 * [inside] says the label scanned was on something packed in it ("Lamp is inside L012").
 */
private data class MdArrival(val id: String, val isBox: Boolean, val prev: Stage, val already: Boolean, val inside: String?)

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
    var reporting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun deliver(m: Movable, inside: String? = null) {
        unknown = null
        reporting = false
        val already = mdArrived(m.stage)
        if (!already) mdSetStage(store, m, Stage.DELIVERED)
        arrival = MdArrival(m.id, m.isBox, m.stage, already, inside)
    }

    // Any code will do: the label of an item inside a container brings in the container, and says so.
    val onScan: (String) -> Unit = { text ->
        val found = mdFind(store.data.value, text)
        if (found == null) {
            arrival = null
            unknown = labelFromQr(text)
        } else deliver(found.carrier, found.inside)
    }

    // Each thing once: a container stands for everything packed inside it.
    val all = data.movables()
    val carried = remember(data) { data.carriedCounts() }
    val arrivedCount = all.count { mdArrived(it.stage) }
    val trips = data.liveTrips
    val filter = tripFilter?.takeIf { id -> trips.any { it.id == id } }
    val order = compareBy<Movable>({ it.priority.ordinal }, { data.room(it.destRoomId)?.name ?: "~" }, { it.title })
    // What travels with the person is not on the van: it is listed on its own, to be ticked off as it comes in.
    val stillOn = all.count { it.stage == Stage.LOADED && !it.byHand() }
    val onVan = all.filter { it.stage == Stage.LOADED && !it.byHand() && (filter == null || it.tripId == filter) }.sortedWith(order)
    val withMe = if (filter != null) emptyList() else all.filter { it.stage == Stage.LOADED && it.byHand() }.sortedWith(order)
    val shown = arrival?.let { a -> if (a.isBox) data.box(a.id)?.toMovable() else data.item(a.id)?.toMovable() }

    Screen("Unload") {
        if (all.isEmpty()) {
            EmptyState(
                Icons.Default.Download, "Nothing to unload yet",
                text = "Boxes you pack and items marked Move appear here.",
            )
        } else {
            // Only the scan button stays put, as on Load: the count, the result and the list scroll under it.
            MdScanAction(scanning, if (narrowScreen()) "Scan labels" else "Scan labels at the door") { scanning = !scanning }
            if (scanning) MdScanner(onScan)

            // Bring the result back into view if the list was scrolled down.
            LaunchedEffect(arrival, unknown) { listState.scrollToItem(0) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp)) {
                item(key = "result") {
                    val a = arrival
                    val code = unknown
                    when {
                        code != null -> MdBanner(
                            MdResult(MdTone.BAD, "Unknown label $code", "Carry it in and sort it out later."),
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        a != null && shown != null && reporting -> MdDamagePanel(shown, onClose = { reporting = false })
                        a != null && shown != null -> MdArrivalPanel(
                            m = shown,
                            arrival = a,
                            carried = carried[shown.id] ?: 0,
                            onDamage = { reporting = true },
                            onUndo = {
                                mdSetStage(store, shown, a.prev)
                                arrival = null
                            },
                        )
                    }
                }
                item(key = "head") {
                    ProgressCard(
                        Ratio(arrivedCount, all.size),
                        "$arrivedCount of ${all.size} delivered",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        sub = if (stillOn > 0) "$stillOn still on the $vehicle" else null,
                    ) {
                        if (trips.size > 1) Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            GlassChip("All trips", selected = filter == null, onClick = { tripFilter = null })
                            trips.forEach { t -> GlassChip(t.name, selected = filter == t.id, onClick = { tripFilter = t.id }) }
                        }
                    }
                }
                if (onVan.isNotEmpty() || filter != null) item(key = "hvan") { ListHeading("Still on the $vehicle", MdHeadingPadding, count = onVan.size) }
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
                    MdThingRow(m, carried = carried[m.id] ?: 0, onClick = { deliver(m) }) {
                        RowButton("Here", onClick = { deliver(m) })
                    }
                }
                if (withMe.isNotEmpty()) {
                    item(key = "hwithme") { ListHeading("Travelling with you", MdHeadingPadding, count = withMe.size) }
                    items(withMe, key = { "w" + it.id }) { m ->
                        MdThingRow(m, carried = carried[m.id] ?: 0, onClick = { deliver(m) }) {
                            RowButton("Here", onClick = { deliver(m) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The hero of the screen: a glass panel with the room it goes to shown as that room's
 * door sign, in the room's own colour and large enough to read from a few steps away
 * while carrying.
 */
@Composable
private fun MdArrivalPanel(m: Movable, arrival: MdArrival, carried: Int, onDamage: () -> Unit, onUndo: () -> Unit) {
    val room = appData().room(m.destRoomId)
    val rc = room?.let { roomColor(it.color) }
    val name = room?.name ?: "No room set"
    val already = arrival.already
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), if (already) GlassTone.Amber else GlassTone.Mint, sheen = true)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MdMark(if (already) MdTone.WARN else MdTone.GOOD, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        already && room != null -> "Already scanned in. It goes to"
                        already -> "Already scanned in"
                        room != null -> "Carry it to"
                        else -> "Carried in"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                arrival.inside?.let {
                    Text(if (already) it else "$it: delivered ${mdShort(m)}", style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
                }
            }
        }
        // The door sign. With no room there is no colour to show, so it is plain glass.
        val sign = RoundedCornerShape(20.dp)
        val fg = rc?.onColor() ?: Night.Text
        Column(
            Modifier.fillMaxWidth()
                .then(
                    if (rc != null) Modifier.clip(sign).background(rc.color()).border(2.dp, Color.White.copy(alpha = 0.75f), sign)
                    else Modifier.glass(sign, shadow = 0.dp)
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
            if (where.isNotBlank()) Text(where, color = fg, style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThingThumb(m.id, m.picture(), size = 60.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InsideChip(carried)
            }
        }
        // The same warnings, in the same words, as on every list; larger here, to be read while carrying.
        val flags = m.flags() + listOfNotNull((Pic.Damage to "Damage noted").takeIf { m.damaged })
        if (flags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                flags.forEach { (icon, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = Night.AmberPale, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = Night.AmberPale, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowButton("Report damage", onDamage, forward = false, icon = Pic.Damage)
            if (!already) RowButton("Undo", onUndo, forward = false, icon = Icons.Default.Undo)
        }
    }
}

/**
 * Reporting damage, in the place of the arrival panel: the photo comes first, as one
 * large camera target. What happened is optional, and entered as on the box's or item's
 * own page: the usual words to tap, or said, or typed behind the pencil.
 */
@Composable
private fun MdDamagePanel(m: Movable, onClose: () -> Unit) {
    val store = LocalStore.current
    val data = appData()
    val saved = (if (m.isBox) data.box(m.id)?.damageNote else data.item(m.id)?.damageNote) ?: ""
    var note by remember(m.id) { mutableStateOf(saved) }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), GlassTone.Red, sheen = true).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MdMark(MdTone.BAD, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Damage", style = MaterialTheme.typography.titleMedium)
                Text(m.title, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PhotoHero(m.id, PhotoKind.DAMAGE, label = "Damage", height = 160.dp, emptyText = "Photograph it")
        DamageText(note, { note = it }, key = m.id + ":arrival-damage")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowButton("Save", onClick = { mdSetDamage(store, m, true, note.trim()); onClose() }, icon = Icons.Default.Check, look = RowButtonLook.Primary)
            if (m.damaged) RowButton("Not damaged", onClick = { mdSetDamage(store, m, false, ""); onClose() }, forward = false)
            RowButton("Cancel", onClose, forward = false)
        }
    }
}
