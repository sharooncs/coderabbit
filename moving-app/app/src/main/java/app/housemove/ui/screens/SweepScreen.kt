package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.Ratio
import app.housemove.data.SWEEP_HINTS
import app.housemove.data.Stage
import app.housemove.data.movables
import app.housemove.ui.EmptyHint
import app.housemove.ui.Hint
import app.housemove.ui.LocalStore
import app.housemove.ui.ProgressRow
import app.housemove.ui.Screen
import app.housemove.ui.ScrollColumn
import app.housemove.ui.Section
import app.housemove.ui.appData

/** Last walk round the old home: tick each room once it is empty and checked. */
@Composable
fun SweepScreen() {
    val store = LocalStore.current
    val data = appData()
    val rooms = data.roomsOf(Home.ORIGIN)
    val stillHere = data.movables().filter { it.stage == Stage.AT_ORIGIN && !it.travelsWithMe }
    val known = rooms.map { it.id }.toSet()
    val noRoom = stillHere.count { it.originRoomId == null || it.originRoomId !in known }
    val swept = rooms.count { it.swept }

    Screen("Final sweep") {
        ScrollColumn {
            ProgressRow("Rooms swept", Ratio(swept, rooms.size))
            if (rooms.isNotEmpty() && swept == rooms.size && stillHere.isEmpty()) {
                MdBanner(MdResult(MdTone.GOOD, "Sweep complete", "Read the meters, lock up and hand over the keys."))
            }
            Section("Rooms") {
                if (rooms.isEmpty()) EmptyHint("No old-home rooms yet. Add them under More → Rooms.")
                rooms.forEach { room ->
                    val left = stillHere.count { it.originRoomId == room.id }
                    MdSweepRow(
                        name = room.name,
                        checked = room.swept,
                        warning = when (left) {
                            0 -> null
                            1 -> "1 thing still here"
                            else -> "$left things still here"
                        },
                        onChange = { store.put(room.copy(swept = it)) },
                    )
                }
                if (noRoom > 0) {
                    Text(
                        if (noRoom == 1) "1 thing with no room is still at the old home." else "$noRoom things with no room are still at the old home.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Section("Places people forget") {
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
            Hint("Tick a room only when it is empty and you have looked in every cupboard.")
        }
    }
}

@Composable
private fun MdSweepRow(name: String, checked: Boolean, warning: String?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (warning != null) {
                Text(warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
