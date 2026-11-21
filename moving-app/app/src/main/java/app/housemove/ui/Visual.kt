package app.housemove.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.housemove.data.*

/*
 * The app's visual kit. Screens are built from these so they look and behave alike:
 *
 *   Fold          a section that stays closed until needed, showing a one-line summary
 *   IconChoice    pick one of a few things, as tiles with a picture
 *   ToggleTiles   switch several things on or off, as tiles with a picture
 *   RoomTiles     pick a room; new-home rooms show their colour
 *   Ring          progress as a circle
 *   BigAction     the one main button of a screen
 *   PillButton    any other button: mint for the thing to do, glass for the alternative
 *   EmptyState    what a screen shows before there is anything in it
 *   StepRow       one step of an ordered list of things to do
 *   Tile          a tappable square with an icon, a title and a short status (done, waiting or needing attention)
 *   Meter, Dial   progress as a glowing bar, and as a half-circle gauge
 *
 * Everything here is made of glass (see Glass.kt) and stands on the night ground.
 * Mint means selected or done, amber in progress, slate not started, red wrong; none
 * of them is the only sign of it: there is always a tick, a picture or a word as well.
 *
 * Rules of thumb: one main action per screen, visible without scrolling; pictures and
 * colour before words; at most one short hint per section; rarely used things go in a Fold.
 */

// ---- pictures for the app's own vocabulary ---------------------------------------

fun Disposition.icon(): ImageVector = when (this) {
    Disposition.UNDECIDED -> Icons.Default.HelpOutline
    Disposition.MOVE -> Icons.Default.LocalShipping
    Disposition.SELL -> Icons.Default.Sell
    Disposition.DONATE -> Icons.Default.VolunteerActivism
    Disposition.BIN -> Icons.Default.Delete
    Disposition.STORE -> Icons.Default.Warehouse
}

fun Priority.icon(): ImageVector = when (this) {
    Priority.FIRST_NIGHT -> Icons.Default.Bedtime
    Priority.DAY_1 -> Icons.Default.WbSunny
    Priority.WEEK_1 -> Icons.Default.DateRange
    Priority.WHENEVER -> Icons.Default.AllInclusive
}

fun Stage.icon(): ImageVector = when (this) {
    Stage.AT_ORIGIN -> Icons.Default.Home
    Stage.LOADED -> Icons.Default.LocalShipping
    Stage.DELIVERED -> Icons.Default.Place
    Stage.DONE -> Icons.Default.CheckCircle
}

/** Icons used for the same idea everywhere. */
object Pic {
    val Fragile = Icons.Default.WineBar
    val Heavy = Icons.Default.FitnessCenter
    val Upright = Icons.Default.ArrowUpward
    val TwoPeople = Icons.Default.Group
    val TakeApart = Icons.Default.Build
    val WithMe = Icons.Default.DirectionsCar
    val FirstNight = Icons.Default.Bedtime
    val Hazard = Icons.Default.Warning
    val Damage = Icons.Default.BrokenImage
    val Label = Icons.Default.QrCode2
    val Box = Icons.Default.Inventory2
    val Item = Icons.Default.Chair
    val Device = Icons.Default.Tv
    val Room = Icons.Default.MeetingRoom
    val Camera = Icons.Default.PhotoCamera
    val Size = Icons.Default.Straighten
    val Notes = Icons.Default.Notes
    val Where = Icons.Default.Place
    val More = Icons.Default.Tune
}

// ---- Fold --------------------------------------------------------------------------

/**
 * A section that is closed until tapped. When closed it shows [summary], so the
 * person can see what is set without opening it. Use for anything that is not
 * needed on most visits to the screen.
 */
@Composable
fun Fold(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    key: String = title,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(open) }
    GlassCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!expanded && !summary.isNullOrBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Close" else "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

// ---- tiles for choosing -------------------------------------------------------------

data class Choice<T>(val value: T, val label: String, val icon: ImageVector)

/**
 * One tile of a choice. Selected is mint-tinted glass with a tick in the corner, so it
 * does not depend on colour. [tap] is the selectable/toggleable modifier: it goes after
 * the glass so the ripple keeps to the rounded shape.
 */
@Composable
private fun PickTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    tap: Modifier = Modifier,
) {
    val fg = if (selected) Night.MintPale else Night.Text
    Box(
        modifier.glass(RoundedCornerShape(if (large) 22.dp else 16.dp), if (selected) GlassTone.Mint else GlassTone.Plain, shadow = 4.dp).then(tap),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(horizontal = if (large) 10.dp else 4.dp, vertical = if (large) 22.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 4.dp, Alignment.CenterVertically),
        ) {
            Icon(
                icon, contentDescription = null, tint = if (selected) Night.Mint else Night.TextDim,
                modifier = Modifier.size(if (large) 40.dp else 24.dp),
            )
            Text(
                label,
                style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle, contentDescription = null, tint = Night.Mint,
                modifier = Modifier.align(Alignment.TopEnd).padding(if (large) 8.dp else 4.dp).size(if (large) 18.dp else 13.dp),
            )
        }
    }
}

/** Pick exactly one. Tiles share the width, [columns] to a row. [large] is for a screen's main choice. */
@Composable
fun <T> IconChoice(
    options: List<Choice<T>>,
    selected: T?,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = options.size.coerceIn(1, 4),
    large: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    PickTile(
                        c.icon, c.label, c.value == selected,
                        Modifier.weight(1f).fillMaxHeight(),
                        large = large,
                        tap = Modifier.selectable(selected = c.value == selected, role = Role.RadioButton) { onPick(c.value) },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

data class Toggle(val label: String, val icon: ImageVector, val on: Boolean, val set: (Boolean) -> Unit)

/** Several independent on/off tiles. */
@Composable
fun ToggleTiles(toggles: List<Toggle>, modifier: Modifier = Modifier, columns: Int = toggles.size.coerceIn(1, 4)) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        toggles.chunked(columns).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    PickTile(
                        t.icon, t.label, t.on,
                        Modifier.weight(1f).fillMaxHeight(),
                        tap = Modifier.toggleable(value = t.on, role = Role.Switch) { t.set(it) },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Pick a room. New-home rooms are tiles in their own colour, the same colour as
 * the tape, the label and the door sign, so the choice is recognised at a glance.
 */
@Composable
fun RoomTiles(
    home: Home,
    selectedId: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = true,
    columns: Int = 2,
) {
    val rooms = appData().roomsOf(home)
    if (rooms.isEmpty()) {
        Hint("No rooms yet. Add them under More → Rooms.", modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rooms.chunked(columns).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { r ->
                    val selected = r.id == selectedId
                    val rc = roomColor(r.color)
                    val coloured = home == Home.DEST
                    val shape = RoundedCornerShape(16.dp)
                    val fg = when {
                        selected && coloured -> rc.onColor()
                        selected -> Night.MintPale
                        else -> Night.Text
                    }
                    // A chosen new-home room is filled with its own colour, the one on its tape and door sign.
                    val look = if (selected && coloured) {
                        Modifier.clip(shape).background(rc.color()).border(2.dp, Color.White.copy(alpha = 0.75f), shape)
                    } else {
                        Modifier.glass(shape, if (selected) GlassTone.Mint else GlassTone.Plain, shadow = 4.dp)
                    }
                    Row(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp)
                            .then(look)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                onPick(if (selected && allowNone) null else r.id)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (coloured && !selected) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(rc.color()).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
                        } else {
                            Icon(
                                if (selected) Icons.Default.Check else Pic.Room,
                                contentDescription = null, tint = if (selected) fg else Night.TextDim, modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            r.name, color = fg, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- showing progress and state ------------------------------------------------------

/** Progress as a circle, with [content] in the middle (a number or an icon). */
@Composable
fun Ring(
    ratio: Ratio,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    stroke: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit = {
        Text("${ratio.done}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    },
) {
    val track = Night.Track
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            if (ratio.fraction > 0f) {
                drawArc(
                    color, -90f, 360f * ratio.fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** A flag with a picture: used where there is room for it. */
@Composable
fun IconFlag(icon: ImageVector, text: String, error: Boolean = false) {
    val (bg, fg) = flagColors(error)
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Progress as a thin bar that glows a little. [color] says how it is going: mint when
 * done or fine, amber while under way.
 */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = Night.MintLight, height: Dp = 5.dp) {
    Canvas(modifier.height(height)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Night.Track, cornerRadius = r)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            val w = (size.width * f).coerceAtLeast(size.height)
            drawRoundRect(color.copy(alpha = 0.28f), topLeft = Offset(-1.5f, -2.5f), size = Size(w + 3f, size.height + 5f), cornerRadius = CornerRadius(size.height))
            drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/**
 * Progress as a half-circle gauge with the count underneath its arch, for the one
 * number a panel is about. Says "[done] of [total] [what]" to a screen reader.
 */
@Composable
fun Dial(ratio: Ratio, what: String, modifier: Modifier = Modifier, width: Dp = 128.dp) {
    Box(
        modifier.size(width, width * 0.61f).semantics(mergeDescendants = true) { contentDescription = "${ratio.done} of ${ratio.total} $what" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 11.dp.toPx()
            val r = (size.width - w * 3f) / 2f
            val c = Offset(size.width / 2f, size.height - w * 0.9f)
            val box = Size(r * 2, r * 2)
            val at = Offset(c.x - r, c.y - r)
            drawArc(Color.Black.copy(alpha = 0.35f), 180f, 180f, false, at, box, style = Stroke(w + 2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Color.White.copy(alpha = 0.14f), 180f, 180f, false, at, box, style = Stroke(w, cap = StrokeCap.Round))
            val sweep = 180f * ratio.fraction.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(Night.Mint.copy(alpha = 0.22f), 180f, sweep, false, at, box, style = Stroke(w * 1.9f, cap = StrokeCap.Round))
                drawArc(
                    Brush.horizontalGradient(0f to Color(0xFF2FB893), 1f to Color(0xFFC8FFEE), startX = at.x, endX = at.x + box.width),
                    180f, sweep, false, at, box, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Text("${ratio.done}/${ratio.total}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---- actions and entry points --------------------------------------------------------

/** The one main button of a screen: tall, full width, with a picture. */
@Composable
fun BigAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val fg = if (primary) Night.OnMint else Night.Text
    val look = if (primary) Modifier.mintPill() else Modifier.glass(CircleShape, sheen = true)
    Row(
        modifier.fillMaxWidth().enabledLook(enabled).then(look)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center)
    }
}

/** The body of a primary button: a glossy mint pill with a soft mint glow beneath it. */
internal fun Modifier.mintPill(): Modifier = this
    .drawBehind {
        val r = CornerRadius(size.height / 2f)
        // The glow, in widening faint layers.
        for (i in 5 downTo 1) {
            val g = 3.dp.toPx() * i
            drawRoundRect(
                Color(0xFF28DCAA).copy(alpha = 0.055f), topLeft = Offset(-g * 0.6f, 4.dp.toPx() - g * 0.2f),
                size = Size(size.width + g * 1.2f, size.height + g * 0.9f), cornerRadius = CornerRadius(size.height / 2f + g),
            )
        }
        drawRoundRect(Brush.verticalGradient(0f to Color(0xFFA9F5DD), 0.42f to Night.Mint, 1f to Color(0xFF34BE98)), cornerRadius = r)
        drawRoundRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.85f), 0.5f to Color.White.copy(alpha = 0f)),
            cornerRadius = r, style = Stroke(1.dp.toPx()),
        )
    }
    .clip(CircleShape)

/**
 * A button as wide as its words (or as wide as its [modifier] says): the glossy mint pill
 * for the thing to do, glass for the alternative. Use it wherever a stock Material button
 * would otherwise go, so nothing on a screen is a flat slab. A screen's one main action
 * is a [BigAction].
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    tone: GlassTone = GlassTone.Plain,
) {
    val fg = if (primary) Night.OnMint else if (tone == GlassTone.Plain) Night.Text else tone.accent()
    val look = if (primary) Modifier.mintPill() else Modifier.glass(CircleShape, tone, shadow = 4.dp)
    Row(
        modifier.enabledLook(enabled).then(look)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center)
    }
}

/**
 * A tappable square: picture, title, and a short status line.
 * Three things can be said in its corner, and never by colour alone: [done] is a tick,
 * [waiting] an amber hourglass (it is with someone else: a suggestion waiting for an
 * Organiser), [alert] a red mark (it needs this person's attention now).
 */
@Composable
fun Tile(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    done: Boolean = false,
    alert: Boolean = false,
    waiting: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val amber = waiting && !alert
    Column(
        modifier
            .glass(shape, shadow = 5.dp)
            .then(
                when {
                    alert -> Modifier.border(1.5.dp, scheme.error.copy(alpha = 0.85f), shape)
                    amber -> Modifier.border(1.5.dp, Night.Amber.copy(alpha = 0.7f), shape)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.weight(1f))
            // A mark for "needs attention", an hourglass for waiting, a tick for done: never the colour alone.
            if (alert) Icon(Icons.Default.ErrorOutline, contentDescription = "Needs attention", tint = scheme.error, modifier = Modifier.size(20.dp))
            else if (amber) Icon(Icons.Default.HourglassTop, contentDescription = "Waiting", tint = Night.Amber, modifier = Modifier.size(20.dp))
            else if (done) Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = scheme.tertiary, modifier = Modifier.size(20.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!status.isNullOrBlank()) {
            // Two lines, so a status is whole on a narrow tile too.
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (alert) scheme.error else if (amber) Night.AmberPale else scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tiles laid out [columns] to a row, all the same width. */
@Composable
fun <T> TileGrid(items: List<T>, modifier: Modifier = Modifier, columns: Int = 2, tile: @Composable (T, Modifier) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** What a screen shows before there is anything in it: a picture, one line, one button. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(88.dp).drawBehind { drawOrb(OrbKind.Plain) }, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(2.dp))
            PillButton(action, onAction)
        }
    }
}

/**
 * One step of an ordered list of things to do (the Move day list, a checklist).
 * [ratio] draws a ring round the picture; a finished step shows a tick.
 */
@Composable
fun StepRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ratio: Ratio? = null,
    status: String? = null,
    done: Boolean = ratio != null && ratio.total > 0 && ratio.done >= ratio.total,
    current: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth()
            .then(if (current) Modifier.glass(RoundedCornerShape(22.dp), GlassTone.Mint, shadow = 4.dp) else Modifier.clip(RoundedCornerShape(22.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ring(ratio ?: Ratio(if (done) 1 else 0, 1), size = 48.dp, stroke = 5.dp, color = if (done) scheme.tertiary else scheme.primary) {
            Icon(
                if (done) Icons.Default.Check else icon, contentDescription = if (done) "Done" else null,
                tint = if (done) scheme.tertiary else scheme.primary, modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!status.isNullOrBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}

/** Outline used by tiles; exposed for screens that draw their own. */
@Composable
fun tileBorder(selected: Boolean = false): BorderStroke =
    BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Night.GlassRim)
