package app.housemove.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.Category
import app.housemove.ui.icons.filled.Check
import app.housemove.ui.icons.filled.Checkroom
import app.housemove.ui.icons.filled.Close
import app.housemove.ui.icons.filled.LockOpen
import app.housemove.ui.icons.filled.Luggage
import app.housemove.ui.icons.filled.PriorityHigh
import app.housemove.ui.icons.filled.QrCodeScanner
import app.housemove.ui.icons.filled.ShoppingBag
import app.housemove.ui.icons.filled.Undo
import app.housemove.ui.icons.filled.Warehouse
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.housemove.data.AppData
import app.housemove.data.ContainerKind
import app.housemove.data.Movable
import app.housemove.data.Priority
import app.housemove.data.Ratio
import app.housemove.data.Stage
import app.housemove.data.Store
import app.housemove.data.Trip
import app.housemove.data.carrierOf
import app.housemove.data.findByAnyCode
import app.housemove.data.labelCodeOf
import app.housemove.data.newId
import app.housemove.data.parentOf
import app.housemove.data.vehicle
import app.housemove.data.*
import app.housemove.ui.*

// Shared by the move-day screens (load, unload, reconcile, sweep, unpack).

/** What a banner says. Each has its own mark as well as its colour: a tick, an exclamation mark, a cross. */
internal enum class MdTone(val glass: GlassTone, val icon: ImageVector, val spoken: String) {
    GOOD(GlassTone.Mint, Icons.Default.Check, "Done"),
    WARN(GlassTone.Amber, Icons.Default.PriorityHigh, "Note"),
    BAD(GlassTone.Red, Icons.Default.Close, "Problem"),
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

/**
 * A trip to the storage unit, or one bringing things back from it ([fromStorage]): this phone's current
 * trip when it is that kind, otherwise the latest of that kind, otherwise a new one. It becomes this
 * phone's current trip, so Load and Unload start on it.
 */
internal fun mdStorageTrip(store: Store, fromStorage: Boolean): String? {
    val data = store.data.value
    fun fits(t: Trip) = if (fromStorage) t.from == Place.STORAGE else t.to == Place.STORAGE
    val trip = data.trip(store.device.value.currentTripId)?.takeIf { fits(it) }
        ?: data.liveTrips.lastOrNull { fits(it) }
        ?: (if (fromStorage) store.addTrip(Place.STORAGE, Place.NEW_HOME) else store.addTrip(Place.OLD_HOME, Place.STORAGE)).valueOrNull()
        ?: return null
    store.setDevice { it.copy(currentTripId = trip.id) }
    return trip.id
}

/** Does this move have a storage unit in it: a trip to or from it, something in it, or something decided Store? */
internal fun AppData.offersStorage(): Boolean =
    move.kind != MoveKind.STORAGE && move.kind != MoveKind.INVENTORY &&
        (usesStorage() || liveItems.any { it.disposition == Disposition.STORE })

/** A trip's name with where it goes, for a chip: "To storage", "Bring back from storage", "Trip 2". */
internal fun Trip.chipIcon(): ImageVector? = when {
    to == Place.STORAGE -> Icons.Default.Warehouse
    from == Place.STORAGE -> Icons.Default.Undo
    else -> null
}

// ---- what is inside what ----------------------------------------------------------------

/** A few words for a thing in the middle of a sentence: "L012", "suitcase B2", "Floor lamp". */
internal fun mdShort(m: Movable): String = when {
    !m.isBox || m.label.isNullOrBlank() -> m.title
    m.kind == null || m.kind == ContainerKind.CARTON -> m.label!!
    else -> m.kind!!.noun.lowercase() + " " + m.label
}

/**
 * What a scanned code stands for at the van: [thing] is what the label is on, [carrier] is
 * what is actually carried, which is the container when the thing is packed inside one.
 */
internal class MdScanned(val thing: Movable, val carrier: Movable, private val named: String?) {
    /** "Lamp is inside L012", or null when the thing scanned is itself what is carried. */
    val inside: String?
        get() = if (named == null && thing.id == carrier.id) null
        else ((named ?: mdShort(thing)) + " is inside " + mdShort(carrier)).replaceFirstChar { it.uppercase() }
}

/** Look up a scanned or typed code: a label, a serial number, or the barcode of something scanned into a box. */
internal fun mdFind(data: AppData, text: String): MdScanned? {
    val code = labelCodeOf(text)
    val thing = data.findByAnyCode(code) ?: return null
    // Found through the barcode of a book or product inside a box: name that, the box is what moves.
    val entry = if (!thing.isBox || thing.label.equals(code, ignoreCase = true)) null
    else data.liveEntries.firstOrNull { it.code == code && it.boxId == thing.id }
    return MdScanned(thing, data.carrierOf(thing), entry?.let { it.title.ifBlank { "Barcode $code" } })
}

// ---- banners and headings -----------------------------------------------------------------

private fun DrawScope.mdRedOrb() {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val hot = Offset(c.x - r * 0.4f, c.y - r * 0.56f)
    drawCircle(
        Brush.radialGradient(0f to Night.Red.copy(alpha = 0.5f), 0.6f to Night.RedDeep.copy(alpha = 0.3f), 1f to Color.Transparent, center = c, radius = r * 1.5f),
        r * 1.5f, c,
    )
    drawCircle(Brush.radialGradient(0f to Color(0xFFFFE9E5), 0.3f to Night.Red, 1f to Night.RedDeep, center = hot, radius = r * 2.1f), r, c)
    val w = 1.dp.toPx()
    drawCircle(
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.85f), 0.5f to Color.Transparent, startY = c.y - r, endY = c.y + r),
        r - w / 2f, c, style = Stroke(w),
    )
}

/** The mark that says how something went: a glowing ball with a tick, an exclamation mark or a cross. */
@Composable
internal fun MdMark(tone: MdTone, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).drawBehind {
            when (tone) {
                MdTone.GOOD -> drawOrb(OrbKind.Primary, ring = false)
                MdTone.WARN -> drawOrb(OrbKind.Amber, ring = false)
                MdTone.BAD -> mdRedOrb()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            tone.icon, contentDescription = tone.spoken, modifier = Modifier.size(size * 0.5f),
            tint = when (tone) {
                MdTone.GOOD -> Night.OnMint
                MdTone.WARN -> Night.OnAmber
                MdTone.BAD -> Color(0xFF4A0D08)
            },
        )
    }
}

/** A glass banner that says what just happened, or how things stand. Readable at arm's length. */
@Composable
internal fun MdBanner(result: MdResult, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp), result.tone.glass, sheen = true)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MdMark(result.tone, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (result.detail.isNotBlank()) {
                Text(result.detail, color = Night.TextSoft, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Where a heading over a group of rows sits on a move-day list: in line with the rows' text. */
internal val MdHeadingPadding = Modifier.padding(start = 22.dp, end = 16.dp, top = 6.dp)

// ---- actions --------------------------------------------------------------------------------

/**
 * The one thing to do on a move-day screen, made to be found at a glance outdoors:
 * a glowing mint pill, taller and with larger words than anything else on the screen.
 */
@Composable
internal fun MdMainAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null) {
    Row(
        modifier.fillMaxWidth().mintPill().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 76.dp).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Night.OnMint, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Night.OnMint)
            if (!sub.isNullOrBlank()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = Night.OnMint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The scan button of Load and Unload: the main action while the camera is closed, quieter once it is open. */
@Composable
internal fun MdScanAction(scanning: Boolean, label: String, onToggle: () -> Unit) {
    // Scanning needs the camera: where HouseMove cannot use it yet (the iPhone, until step 3), things are ticked off by tapping.
    if (!LocalPlatform.current.camera && LocalMdScans.current == null) return
    if (scanning) BigAction(Icons.Default.QrCodeScanner, "Hide the scanner", onToggle, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), primary = false)
    else MdMainAction(Icons.Default.QrCodeScanner, label, onToggle, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

/** A tab with a picture beside its name; the picture is left out where it would squeeze the name. */
@Composable
internal fun MdIconTab(selected: Boolean, onClick: () -> Unit, label: String, icon: ImageVector) {
    if (narrowScreen()) Tab(selected = selected, onClick = onClick, text = { Text(label, maxLines = 1) })
    else LeadingIconTab(
        selected = selected, onClick = onClick,
        text = { Text(label, maxLines = 1) }, icon = { Icon(icon, contentDescription = null) },
    )
}

/** Height of the live scanner: about a fifth of the screen, so the result and the list keep most of it. */
@Composable
internal fun mdScannerHeight(): Dp = (screenDp().screenHeightDp * 0.22f).dp.coerceIn(130.dp, 200.dp)

/** Something that hands over codes as they are scanned. The app uses the camera; tests provide a fake. */
fun interface MdScans {
    /** Called while a scanner is on screen with the place to send codes to, and with null when it leaves. */
    fun listen(onText: ((String) -> Unit)?)
}

/** Set in tests to stand in for the camera on Load and Unload. Null means: use the camera. */
val LocalMdScans = staticCompositionLocalOf<MdScans?> { null }

/** The live scanner of Load and Unload: a rounded window with the rim of a glass panel. */
@Composable
internal fun MdScanner(onText: (String) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val frame = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(mdScannerHeight()).clip(shape).border(1.dp, Night.GlassRim, shape)
    val fake = LocalMdScans.current
    if (fake == null) QrScanner(onText = onText, modifier = frame)
    else {
        val callback by rememberUpdatedState(onText)
        DisposableEffect(fake) {
            fake.listen { callback(it) }
            onDispose { fake.listen(null) }
        }
        Box(frame)
    }
}

// ---- rows -------------------------------------------------------------------------------------

/**
 * One box or item on a move-day list, as a glass row led by its photo: what kind of
 * thing it is, the room it goes to in that room's colour, how many things it carries
 * ([carried], from [carriedCounts]) and a picture for each warning. Tapping it opens the
 * box or item unless [onClick] is given. [open] marks a box that is not sealed yet,
 * [showStage] adds where it is now, [note] is shown underneath.
 */
@Composable
internal fun MdThingRow(
    m: Movable,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    showStage: Boolean = false,
    note: String = "",
    carried: Int = 0,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val data = appData()
    val nav = LocalNav.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .glass(RoundedCornerShape(22.dp), shadow = 4.dp)
            .clickable { if (onClick != null) onClick() else nav.openMovable(m) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The picture says what kind of thing it is: a container's kind is marked on its photo.
        ThingThumb(m.id, m.picture())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // Two lines, three on a small phone where the button beside it takes a third of the width,
            // so a long name ("Box L001 · Kettle, mugs, tea, cutlery") is read whole.
            Text(
                m.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = if (trailing != null && narrowScreen(360f)) 3 else 2, overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoomBadge(data.room(m.destRoomId))
                InsideChip(carried)
                if (showStage) IconFlag(if (m.onTheVan() || m.stage != Stage.LOADED) m.stage.icon() else Pic.WithMe, data.statusText(m))
                m.flags().forEach { (picture, word) -> IconFlag(picture, word) }
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
