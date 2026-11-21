package app.housemove.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.AppData
import app.housemove.data.PhotoKind
import app.housemove.data.Stage
import app.housemove.data.fmtDay
import app.housemove.data.movables
import app.housemove.data.relDay
import app.housemove.ui.Hint
import app.housemove.ui.LocalNav
import app.housemove.ui.MenuRow
import app.housemove.ui.Screen
import app.housemove.ui.Section
import app.housemove.ui.TopActions
import app.housemove.ui.appData
import app.housemove.ui.open

private fun mdRecordBadge(data: AppData, home: app.housemove.data.Home): String {
    val owners = data.roomsOf(home).map { it.id }.toSet() + ("home:" + home.name)
    val photos = data.livePhotos.count { it.kind == PhotoKind.ROOM && it.ownerId in owners }
    return if (photos == 1) "1 photo" else "$photos photos"
}

/** Move-day hub: the steps of the day in order, each with a live count. */
@Composable
fun MoveDayScreen() {
    val data = appData()
    val nav = LocalNav.current
    val all = data.movables()
    val total = all.size
    val loaded = all.count { it.stage != Stage.AT_ORIGIN }
    val arrived = all.count { mdArrived(it.stage) }
    val onVan = all.count { it.stage == Stage.LOADED }
    val unpacked = all.count { it.stage == Stage.DONE }
    val originRooms = data.roomsOf(app.housemove.data.Home.ORIGIN)
    val swept = originRooms.count { it.swept }

    Screen("Move day", back = false, tab = "moveday", actions = { TopActions() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val day = data.move.moveDay
                Text(
                    if (day == null) "No move date set yet" else "Move day: ${fmtDay(day)} (${relDay(day)})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Hint("Work down this list on the day. Each step shows how far along you are.")
            }
            MenuRow(
                Icons.Default.PhotoCamera, "Old home record",
                "Photos of every room and the meter readings", mdRecordBadge(data, app.housemove.data.Home.ORIGIN),
            ) { nav.open("records/ORIGIN") }
            HorizontalDivider()
            MenuRow(
                Icons.Default.LocalShipping, "Load the van",
                "Scan or tick everything as it goes on", "$loaded / $total",
            ) { nav.open("load") }
            HorizontalDivider()
            MenuRow(
                Icons.Default.CleaningServices, "Final sweep",
                "Check every room before you lock up", "$swept / ${originRooms.size}",
            ) { nav.open("sweep") }
            HorizontalDivider()
            MenuRow(
                Icons.Default.Home, "New home record",
                "Photos and meters before anything is carried in", mdRecordBadge(data, app.housemove.data.Home.DEST),
            ) { nav.open("records/DEST") }
            HorizontalDivider()
            MenuRow(
                Icons.Default.MoveToInbox, "Unload",
                "Scan at the door to see which room it goes to", "$arrived / $total",
            ) { nav.open("unload") }
            HorizontalDivider()
            MenuRow(
                Icons.Default.TaskAlt, "Reconcile",
                "Make sure nothing is left on the van",
                if (onVan == 0 && arrived > 0) "All in" else "$onVan on van",
            ) { nav.open("reconcile") }
            HorizontalDivider()
            MenuRow(
                Icons.Default.Unarchive, "Unpack",
                "First-night boxes first, then room by room", "$unpacked / $total",
            ) { nav.open("unpack") }
            HorizontalDivider()

            Section("Before you start", Modifier.padding(16.dp)) {
                Text("• Sync all phones the evening before, so everyone has the same list.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "• At the new home, photograph every room and read the meters before anything is carried in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("• First-night boxes go on the van last, so they come off first.", style = MaterialTheme.typography.bodyMedium)
                Text("• Check Reconcile before the van goes back.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
