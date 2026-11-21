package app.housemove.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.*
import app.housemove.ui.*

/** How far packing has got in one old-home room: its boxes, and its "fully packed" switch. */
internal data class RoomPacking(val room: Room, val sealed: Int, val total: Int) {
    val state: RoomState
        get() = when {
            room.packed -> RoomState.PACKED
            total == 0 -> RoomState.NO_BOXES
            sealed == 0 -> RoomState.NOT_STARTED
            else -> RoomState.PACKING
        }

    /** "6 boxes left", in the words of the callout. */
    fun leftText(): String {
        val left = total - sealed
        return when {
            room.packed -> "Fully packed"
            total == 0 -> "No boxes yet"
            left == 0 -> "All $total sealed"
            left == 1 -> "1 box left"
            else -> "$left boxes left"
        }
    }

    /** For a screen reader: the same facts as the tile, in a sentence. */
    fun spoken(): String = when {
        total == 0 && room.packed -> "${room.name}: marked packed, no boxes"
        total == 0 -> "${room.name}: no boxes yet"
        else -> "${room.name}: $sealed of $total boxes sealed, ${state.label}"
    }

    /** How many piles of boxes the drawing shows for this room. */
    fun stacks(): Int = when {
        total == 0 -> 0
        total <= 3 -> 1
        total <= 8 -> 2
        else -> 3
    }
}

/**
 * The old home's rooms in their own order, each with its packing state. Counted as on
 * every screen: a container inside another is sealed and carried with that one, so only
 * the containers that are not inside another count, each under the room it was packed in.
 */
internal fun AppData.roomPacking(): List<RoomPacking> {
    val boxes = topBoxes()
    return roomsOf(Home.ORIGIN).map { r ->
        val fromHere = boxes.filter { it.originRoomId == r.id }
        RoomPacking(r, fromHere.count { it.sealed }, fromHere.size)
    }
}

/**
 * The room to pack next: the one this phone is packing in if it is not finished, else
 * one with boxes still open, else the first that is not marked packed.
 */
internal fun List<RoomPacking>.nextToPack(currentRoomId: String?): RoomPacking? {
    val open = filter { !it.room.packed }
    return open.firstOrNull { it.room.id == currentRoomId }
        ?: open.firstOrNull { it.total > it.sealed }
        ?: open.firstOrNull()
}

/**
 * The rooms of the old home at a glance: the house with its roof off, every room coloured
 * by how far packing has got, then the numbers. Room management (adding, linking,
 * colours) stays on its own screen, behind the button at the top right.
 */
@Composable
fun OverviewScreen() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val packing = data.uses(Part.BOXES)
    val rooms = data.roomPacking()
    val next = if (packing) rooms.nextToPack(device.currentRoomId) else null
    // The room the marker and the callout are on: the one tapped, else the next to pack.
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = rooms.firstOrNull { it.room.id == pickedId } ?: next ?: rooms.firstOrNull()

    Screen(
        "Rooms", tab = "home", subtitle = data.move.originName,
        actions = { TopAction(Icons.Default.Tune, "Manage rooms") { nav.open("rooms") } },
    ) {
        if (rooms.isEmpty()) {
            ScrollColumn {
                IsoRooms(emptyList())
                EmptyState(
                    Pic.Room, "No rooms yet",
                    text = "Add the rooms of ${data.move.originName.lowercase()} to see packing room by room.",
                    action = "Add rooms", onAction = { nav.open("rooms") },
                )
            }
            return@Screen
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Box(Modifier.widthIn(max = 460.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 12.dp)) {
                IsoRooms(
                    rooms.map { IsoRoom(it.room.id, it.room.name, it.state, it.stacks()) },
                    selectedId = shown?.room?.id,
                    onRoom = { pickedId = it.id },
                )
                if (shown != null) {
                    GlassTag(
                        shown.room.name, shown.leftText(),
                        if (shown.room.packed) Icons.Default.Check else Icons.Outlined.Inventory2,
                        Modifier.align(Alignment.TopEnd).padding(top = 14.dp).widthIn(max = 210.dp),
                        kind = when (shown.state) {
                            RoomState.PACKED -> OrbKind.Primary
                            RoomState.PACKING -> OrbKind.Amber
                            else -> OrbKind.Selected
                        },
                    )
                }
            }

            // Every room as a tile: its name, boxes sealed of boxes begun, and a bar. Four fit across;
            // more, or four on a screen too narrow for "Main bedroom", scroll sideways instead of shrinking.
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                val tile = (maxWidth - 32.dp - 24.dp) / 4
                val scrolls = rooms.size > 4 || tile < 80.dp
                Row(
                    // All tiles as tall as the tallest: a long room name takes two lines, never "…".
                    (if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()).height(IntrinsicSize.Min).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rooms.forEach { r ->
                        RoomTile(
                            r, selected = r.room.id == shown?.room?.id,
                            modifier = (if (scrolls) Modifier.width(tile.coerceAtLeast(84.dp)) else Modifier.weight(1f)).fillMaxHeight(),
                        ) {
                            pickedId = r.room.id
                        }
                    }
                }
            }

            if (packing) {
                val sealed = data.progress().sealed
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberCard("Boxes sealed", Modifier.weight(1f).fillMaxHeight(), onClick = { nav.goTab("boxes") }) {
                        Dial(sealed, "boxes sealed", Modifier.align(Alignment.CenterHorizontally))
                        val open = sealed.total - sealed.done
                        GlassChip(
                            when {
                                sealed.total == 0 -> "No boxes yet"
                                open == 0 -> "All sealed"
                                else -> "$open still open"
                            },
                            Modifier.align(Alignment.CenterHorizontally),
                            tone = if (sealed.total > 0 && open == 0) GlassTone.Mint else if (open > 0) GlassTone.Amber else GlassTone.Plain,
                        )
                    }
                    val volume = data.volume()
                    val vehicle = data.vehicle()
                    NumberCard(
                        vehicle.replaceFirstChar { it.uppercase() } + " load", Modifier.weight(1f).fillMaxHeight(),
                        onClick = { nav.open(if (data.uses(Part.PLAN)) "plan" else "load") },
                    ) {
                        GlassChip(
                            when (volume.trips) {
                                0 -> "Nothing to load yet"
                                1 -> "Fits in 1 trip"
                                else -> "Needs ${volume.trips} trips"
                            },
                            tone = if (volume.trips > 1) GlassTone.Amber else if (volume.trips == 1) GlassTone.Mint else GlassTone.Plain,
                        )
                        Column {
                            Text("${oneDecimal(volume.totalM3)} m³", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "of a ${fmtNum(data.move.vanCapacityM3)} m³ $vehicle", style = MaterialTheme.typography.bodySmall,
                                color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        val load = data.loadByRoom()
                        LoadBars(rooms.map { load[it.room.id] ?: 0.0 })
                    }
                }

                // The way on from here.
                ActionPill(
                    Icons.Outlined.Inventory2, "Pack a box", if (next != null) "${next.room.name} is next" else "Every room is marked packed",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                ) {
                    if (next != null) store.setDevice { it.copy(currentRoomId = next.room.id) }
                    nav.open("pack")
                }
            }
        }
    }
}

private fun oneDecimal(v: Double): String = (Math.round(v * 10) / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }

/**
 * Rough volume of what leaves each old-home room, the same way the load estimate counts it:
 * what is inside a container takes no room of its own.
 */
private fun AppData.loadByRoom(): Map<String?, Double> {
    val out = HashMap<String?, Double>()
    topBoxes().forEach { out.merge(it.originRoomId, it.volumeM3(), Double::plus) }
    looseItems().forEach { out.merge(it.originRoomId, it.volumeM3() ?: DEFAULT_ITEM_M3, Double::plus) }
    return out
}

/** One room: its name, sealed of begun, a bar. Tapping it puts the marker on that room in the drawing. */
@Composable
private fun RoomTile(r: RoomPacking, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val allSealed = r.total > 0 && r.sealed == r.total
    Column(
        modifier
            .glass(RoundedCornerShape(18.dp), if (selected) GlassTone.Mint else GlassTone.Plain, shadow = 6.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics { contentDescription = r.spoken() }
            .heightIn(min = 64.dp)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(r.room.name, style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (r.total == 0) "–" else "${r.sealed}/${r.total}",
                fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            // Packed is said with a tick as well as with the colour of the bar.
            if (r.room.packed) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Night.MintLight, modifier = Modifier.size(14.dp))
        }
        Meter(
            if (r.room.packed) 1f else if (r.total == 0) 0f else r.sealed.toFloat() / r.total,
            Modifier.fillMaxWidth(),
            color = if (r.room.packed || allSealed) Night.MintLight else Night.AmberDeep,
        )
    }
}

/** A glass panel about one number, with a small arrow that says it opens the detail. */
@Composable
private fun NumberCard(title: String, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier, onClick = onClick, shape = RoundedCornerShape(26.dp), sheen = true) {
        Column(Modifier.fillMaxHeight().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.NorthEast, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(16.dp))
            }
            content()
        }
    }
}

/** Small bars, one per room, for how much each adds to the load. The biggest glows. Decoration: the number above says it. */
@Composable
private fun LoadBars(volumes: List<Double>, height: Dp = 34.dp) {
    val shown = volumes.take(8)
    val max = shown.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val top = shown.indexOfFirst { it == shown.maxOrNull() && it > 0 }
    Canvas(Modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
        val w = 11.dp.toPx()
        val gap = 6.dp.toPx()
        shown.forEachIndexed { i, v ->
            val h = (size.height * (0.18f + 0.82f * (v / max).toFloat())).coerceAtMost(size.height)
            val at = Offset(i * (w + gap), size.height - h)
            val r = CornerRadius(4.dp.toPx())
            if (i == top) {
                drawRoundRect(Night.Mint.copy(alpha = 0.30f), Offset(at.x - 3.dp.toPx(), at.y - 3.dp.toPx()), Size(w + 6.dp.toPx(), h + 3.dp.toPx()), CornerRadius(7.dp.toPx()))
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFD6FFF3), Color(0xFF3FC79F)), startY = at.y, endY = size.height), at, Size(w, h), r)
            } else {
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF7FE9C9), Color(0xFF23896E)), startY = at.y, endY = size.height), at, Size(w, h), r)
            }
        }
    }
}
