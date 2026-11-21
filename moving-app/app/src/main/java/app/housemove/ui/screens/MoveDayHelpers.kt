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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingBag
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
import androidx.compose.ui.platform.LocalConfiguration
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
import app.housemove.data.labelFromQr
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

// ---- what is inside what ----------------------------------------------------------------

/** A few words for a thing in the middle of a sentence: "L012", "suitcase B2", "Floor lamp". */
internal fun mdShort(m: Movable): String = when {
    !m.isBox || m.label.isNullOrBlank() -> m.title
    m.kind == null || m.kind == ContainerKind.CARTON -> m.label
    else -> m.kind.noun.lowercase() + " " + m.label
}

/**
 * How many things each container carries: the items packed in it, the containers inside it
 * and what those hold. Worked out once per screen, so a long list stays quick.
 */
internal fun mdCarried(data: AppData): Map<String, Int> {
    if (data.items.none { it.boxId != null && !it.deleted } && data.boxes.none { it.parentId != null && !it.deleted }) return emptyMap()
    val out = HashMap<String, Int>()
    val outer = HashMap<String, String>()
    val boxes = data.liveBoxes
    for (b in boxes) {
        val p = data.parentOf(b) ?: continue
        outer[b.id] = p.id
        out.merge(p.id, 1, Int::plus)
    }
    val ids = boxes.map { it.id }.toSet()
    for (i in data.liveItems) {
        val b = i.boxId?.takeIf { it in ids } ?: continue
        out.merge(b, 1, Int::plus)
        outer[b]?.let { out.merge(it, 1, Int::plus) }
    }
    return out
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
    val code = labelFromQr(text)
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

/** The heading above a group of rows, on the open ground: its name, how many, and an optional button on the right. */
@Composable
internal fun MdGroupHeader(title: String, count: Int, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 22.dp, end = 16.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, color = Night.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            Spacer(Modifier.width(8.dp))
            GlassChip(count.toString())
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
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
internal fun mdScannerHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.22f).dp.coerceIn(130.dp, 200.dp)

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
 * ([carried], from [mdCarried]) and a picture for each warning. Tapping it opens the
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
            // Two lines, so a long name is still told apart from its neighbours on a small phone.
            Text(m.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoomBadge(data.room(m.destRoomId))
                if (carried > 0) GlassChip(countOf(carried, "thing") + " inside")
                if (showStage) IconFlag(m.stage.icon(), stageText(m.stage, m.isBox, data.vehicle()))
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
