package app.housemove.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.CheckCircle
import app.housemove.ui.icons.filled.RadioButtonUnchecked
import app.housemove.ui.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.Ratio
import app.housemove.data.SWEEP_HINTS
import app.housemove.data.Stage
import app.housemove.data.movables
import app.housemove.ui.EmptyState
import app.housemove.ui.Fold
import app.housemove.ui.LocalNav
import app.housemove.ui.LocalStore
import app.housemove.ui.Pic
import app.housemove.ui.Screen
import app.housemove.ui.ScrollColumn
import app.housemove.ui.TileGrid
import app.housemove.ui.appData
import app.housemove.ui.open
import app.housemove.data.*
import app.housemove.ui.*

/** Last walk round the old home: tick each room once it is empty and checked. */
@Composable
fun SweepScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val rooms = data.roomsOf(Home.ORIGIN)
    val stillHere = data.movables().filter { it.stage == Stage.AT_ORIGIN && !it.travelsWithMe }
    val known = rooms.map { it.id }.toSet()
    val noRoom = stillHere.count { it.originRoomId == null || it.originRoomId !in known }
    val swept = rooms.count { it.swept }

    Screen("Final sweep") {
        ScrollColumn {
            if (rooms.isEmpty()) {
                EmptyState(
                    Pic.Room, "No old-home rooms yet",
                    text = "Add the rooms to tick them off one by one.",
                    action = "Add rooms", onAction = { nav.open("rooms") },
                )
            } else {
                ProgressCard(Ratio(swept, rooms.size), "$swept of ${rooms.size} rooms checked")
                if (swept == rooms.size && stillHere.isEmpty()) {
                    MdBanner(MdResult(MdTone.GOOD, "Sweep complete", "Read the meters, lock up and hand over the keys."))
                }
                TileGrid(rooms) { room, m ->
                    val left = stillHere.count { it.originRoomId == room.id }
                    MdSweepTile(
                        name = room.name,
                        checked = room.swept,
                        warning = if (left == 0) null else countOf(left, "thing") + " still here",
                        onChange = { store.put(room.copy(swept = it)) },
                        modifier = m,
                    )
                }
            }
            if (noRoom > 0) {
                MdBanner(
                    MdResult(
                        MdTone.BAD,
                        if (noRoom == 1) "1 thing with no room is still here" else "$noRoom things with no room are still here",
                    )
                )
            }
            Fold("Places people forget", Icons.Default.Search, summary = "Cupboards, loft, shed, keys") {
                SWEEP_HINTS.forEach { hint ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(hint, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** A room as a large tile to tick, with what is still in it in the error colour. */
@Composable
private fun MdSweepTile(name: String, checked: Boolean, warning: String?, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.heightIn(min = 112.dp)
            .glass(shape, if (checked) GlassTone.Mint else GlassTone.Plain, shadow = 5.dp)
            .then(if (warning != null) Modifier.border(1.5.dp, scheme.error, shape) else Modifier)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (checked) scheme.tertiary else scheme.outline,
            modifier = Modifier.size(40.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = if (checked) scheme.onTertiaryContainer else scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (warning != null) Text(warning, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
    }
}
