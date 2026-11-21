package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.housemove.data.Movable
import app.housemove.data.Priority
import app.housemove.data.Ratio
import app.housemove.data.Stage
import app.housemove.data.Store
import app.housemove.data.Trip
import app.housemove.data.newId
import app.housemove.data.vehicle
import app.housemove.ui.BigAction
import app.housemove.ui.IconFlag
import app.housemove.ui.LocalNav
import app.housemove.ui.OwnerThumb
import app.housemove.ui.Pic
import app.housemove.ui.Ring
import app.housemove.ui.RoomBadge
import app.housemove.ui.appData
import app.housemove.ui.icon
import app.housemove.ui.openMovable
import app.housemove.ui.stageText
import app.housemove.ui.*

// Shared by the move-day screens (load, unload, reconcile, sweep, unpack).

/** Colour of a result banner: green = done, amber = nothing to do, red = problem. */
internal enum class MdTone(val bg: Color, val fg: Color, val icon: ImageVector) {
    GOOD(Night.Mint, Night.OnMint, Icons.Default.CheckCircle),
    WARN(Night.Amber, Night.OnAmber, Icons.Default.Info),
    BAD(Night.RedSolid, Color.White, Icons.Default.ErrorOutline),
}

internal data class MdResult(val tone: MdTone, val title: String, val detail: String = "")

/** True once something has reached the new home. */
internal fun mdArrived(stage: Stage) = stage == Stage.DELIVERED || stage == Stage.DONE

/** Move one box or item to [stage]. The trip is kept unless [tripId] is given. */
internal fun mdSetStage(store: Store, m: Movable, stage: Stage, tripId: String? = m.tripId) {
    if (m.isBox) store.updateBoxes(listOf(m.id)) { it.copy(stage = stage, tripId = tripId) }
    else store.updateItems(listOf(m.id)) { it.copy(stage = stage, tripId = tripId) }
}

/** Move many boxes and items to [stage] on [tripId] in as few writes as possible. */
internal fun mdSetStageAll(store: Store, list: List<Movable>, stage: Stage, tripId: String?) {
    val boxes = list.filter { it.isBox }.map { it.id }
    val items = list.filter { !it.isBox }.map { it.id }
    if (boxes.isNotEmpty()) store.updateBoxes(boxes) { it.copy(stage = stage, tripId = tripId) }
    if (items.isNotEmpty()) store.updateItems(items) { it.copy(stage = stage, tripId = tripId) }
}

internal fun mdSetDamage(store: Store, m: Movable, damaged: Boolean, note: String) {
    if (m.isBox) store.updateBoxes(listOf(m.id)) { it.copy(damaged = damaged, damageNote = note) }
    else store.updateItems(listOf(m.id)) { it.copy(damaged = damaged, damageNote = note) }
}

/**
 * The trip things are being loaded onto: this phone's current trip if it still
 * exists, otherwise the first trip, otherwise a new "Trip 1".
 */
internal fun mdEnsureTrip(store: Store): String {
    val data = store.data.value
    data.trip(store.device.value.currentTripId)?.let { return it.id }
    val trip = data.liveTrips.firstOrNull() ?: Trip(id = newId(), name = "Trip 1", sort = 1).also { store.put(it) }
    store.setDevice { it.copy(currentTripId = trip.id) }
    return trip.id
}

/** "1 thing", "3 things". */
internal fun mdCount(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

/** Large coloured strip that says what just happened. Readable at arm's length. */
@Composable
internal fun MdBanner(result: MdResult, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(result.tone.bg).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(result.tone.icon, contentDescription = null, tint = result.tone.fg, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(result.title, color = result.tone.fg, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (result.detail.isNotBlank()) {
                Text(result.detail, color = result.tone.fg, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Grey heading above a group of rows, with an optional button on the right. */
@Composable
internal fun MdGroupHeader(title: String, count: Int, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/**
 * The top of a move-day screen: how far along it is, as a ring with the count in
 * the middle, and one line saying what is counted. [below] holds chips that belong
 * with the count (trips, rooms).
 */
@Composable
internal fun MdProgressHeader(
    ratio: Ratio,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val done = ratio.total > 0 && ratio.done >= ratio.total
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Ring(ratio, size = 64.dp, stroke = 7.dp, color = if (done) scheme.tertiary else scheme.primary) {
            if (done) Icon(Icons.Default.Check, contentDescription = "Done", tint = scheme.tertiary, modifier = Modifier.size(30.dp))
            else Text("${ratio.done}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            below?.invoke()
        }
    }
}

/** The scan button of Load and Unload: the main action while the camera is closed, quieter once it is open. */
@Composable
internal fun MdScanAction(scanning: Boolean, label: String, onToggle: () -> Unit) {
    BigAction(
        Icons.Default.QrCodeScanner, if (scanning) "Hide the scanner" else label, onToggle,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), primary = !scanning,
    )
}

/** A tab with a picture beside its name; the picture is left out where it would squeeze the name. */
@Composable
internal fun MdIconTab(selected: Boolean, onClick: () -> Unit, label: String, icon: ImageVector) {
    if (mdNarrow()) Tab(selected = selected, onClick = onClick, text = { Text(label, maxLines = 1) })
    else LeadingIconTab(
        selected = selected, onClick = onClick,
        text = { Text(label, maxLines = 1) }, icon = { Icon(icon, contentDescription = null) },
    )
}

/**
 * A small phone, or large text on a medium one: rows of four become rows of two, and tabs
 * drop their pictures. [limit] is the screen width in dp, at normal text size, below which that happens.
 */
@Composable
internal fun mdNarrow(limit: Float = 340f): Boolean = LocalConfiguration.current.let { it.screenWidthDp / it.fontScale < limit }

/** Height of the live scanner: about a fifth of the screen, so the result and the list keep most of it. */
@Composable
internal fun mdScannerHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.22f).dp.coerceIn(130.dp, 200.dp)

/**
 * One box or item on a move-day list: its photo, the room it goes to in that
 * room's colour, and a picture for each warning. Tapping it opens the box or
 * item unless [onClick] is given. [open] marks a box that is not sealed yet,
 * [showStage] adds where it is now, [note] is shown underneath.
 */
@Composable
internal fun MdThingRow(
    m: Movable,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    showStage: Boolean = false,
    note: String = "",
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val data = appData()
    val nav = LocalNav.current
    Row(
        modifier.fillMaxWidth().clickable { if (onClick != null) onClick() else nav.openMovable(m) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerThumb(m.id, Modifier.size(52.dp), isBox = m.isBox)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoomBadge(data.room(m.destRoomId))
                if (showStage) IconFlag(m.stage.icon(), stageText(m.stage, m.isBox, data.vehicle()))
                if (m.priority == Priority.FIRST_NIGHT) IconFlag(Pic.FirstNight, "First night")
                if (m.fragile) IconFlag(Pic.Fragile, "Fragile")
                if (m.upright) IconFlag(Pic.Upright, "Upright")
                if (m.heavy) IconFlag(if (m.isBox) Pic.Heavy else Pic.TwoPeople, if (m.isBox) "Heavy" else "2 people")
                if (open) IconFlag(Icons.Default.LockOpen, "Open")
                if (m.damaged) IconFlag(Pic.Damage, "Damaged", error = true)
            }
            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
