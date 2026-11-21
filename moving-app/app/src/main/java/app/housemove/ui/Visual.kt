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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
 *   EmptyState    what a screen shows before there is anything in it
 *   StepRow       one step of an ordered list of things to do
 *   Tile          a tappable square with an icon, a title and a short status
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
    Card(modifier.fillMaxWidth()) {
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

@Composable
private fun PickTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) scheme.primary else scheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Pick exactly one. Tiles share the width, [columns] to a row. */
@Composable
fun <T> IconChoice(
    options: List<Choice<T>>,
    selected: T?,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = options.size.coerceIn(1, 4),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    PickTile(
                        c.icon, c.label, c.value == selected,
                        Modifier.weight(1f).selectable(selected = c.value == selected, role = Role.RadioButton) { onPick(c.value) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    PickTile(
                        t.icon, t.label, t.on,
                        Modifier.weight(1f).toggleable(value = t.on, role = Role.Switch) { t.set(it) },
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
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rooms.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { r ->
                    val selected = r.id == selectedId
                    val rc = roomColor(r.color)
                    val coloured = home == Home.DEST
                    val bg = when {
                        selected && coloured -> rc.color()
                        selected -> scheme.primaryContainer
                        else -> scheme.surface
                    }
                    val fg = when {
                        selected && coloured -> rc.onColor()
                        selected -> scheme.onPrimaryContainer
                        else -> scheme.onSurface
                    }
                    Row(
                        Modifier.weight(1f).heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) (if (coloured) rc.color() else scheme.primary) else scheme.outlineVariant,
                                RoundedCornerShape(12.dp),
                            )
                            .selectable(selected = selected, role = Role.RadioButton) {
                                onPick(if (selected && allowNone) null else r.id)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (coloured && !selected) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(rc.color()))
                        } else {
                            Icon(
                                if (selected) Icons.Default.Check else Pic.Room,
                                contentDescription = null, tint = fg, modifier = Modifier.size(18.dp),
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
    val track = MaterialTheme.colorScheme.surfaceVariant
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
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1)
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
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
    val m = modifier.fillMaxWidth().heightIn(min = 60.dp)
    if (primary) Button(onClick = onClick, modifier = m, enabled = enabled, content = content)
    else OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, content = content)
}

/** A tappable square: picture, title, and a short status line. */
@Composable
fun Tile(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    done: Boolean = false,
    alert: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .border(1.dp, if (alert) scheme.error else scheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.weight(1f))
            if (done) Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = scheme.tertiary, modifier = Modifier.size(20.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!status.isNullOrBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (alert) scheme.error else scheme.onSurfaceVariant,
                maxLines = 1,
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
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
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
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) scheme.primaryContainer else Color.Transparent)
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
    BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
