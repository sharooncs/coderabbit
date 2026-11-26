package app.housemove.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.CheckCircle
import app.housemove.ui.icons.filled.Sync
import app.housemove.ui.icons.filled.SyncDisabled
import app.housemove.ui.icons.filled.WifiFind
import app.housemove.ui.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.sync.AutoSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Syncing by itself on the same Wi-Fi ([AutoSync], design/auto-sync.md), while the app is on screen. Null where the app
 * is shown without it (most tests): the screens then show the setting, and the last sync as the move remembers it.
 */
val LocalAutoSync = staticCompositionLocalOf<AutoSync?> { null }

/**
 * Sync and backup's line about syncing by itself ("Up to date with Asha's phone · 2 min ago", "Looking for the family's
 * phones on this Wi-Fi"), one hint when there is something to say, and the switch "Sync by itself on Wi-Fi".
 */
@Composable
fun AutoSyncSection() {
    val store = LocalStore.current
    val engine = LocalAutoSync.current
    val settings by store.phone.settings.collectAsState()
    val device = deviceInfo()
    val flow = remember(engine) { engine?.status ?: MutableStateFlow(AutoSync.Status(AutoSync.State.LOOKING)) }
    val live by flow.collectAsState()
    // The last sync is the move's own (by code or by itself); the rest is what the engine is doing now.
    val status = (if (settings.autoSync) live else live.copy(state = AutoSync.State.OFF)).copy(lastWith = device.lastSyncWith, lastAt = device.lastSyncAt)
        .let { s -> if (s.state == AutoSync.State.UP_TO_DATE && s.lastAt == 0L) s.copy(state = AutoSync.State.LOOKING) else s }
    // "2 min ago" moves on with the clock.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    Section {
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val (icon, tint) = when (status.state) {
                AutoSync.State.UP_TO_DATE -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.tertiary
                AutoSync.State.SYNCING -> Icons.Default.Sync to MaterialTheme.colorScheme.primary
                AutoSync.State.OFF -> Icons.Default.SyncDisabled to MaterialTheme.colorScheme.onSurfaceVariant
                AutoSync.State.NO_WIFI, AutoSync.State.DENIED -> Icons.Default.WifiOff to MaterialTheme.colorScheme.onSurfaceVariant
                AutoSync.State.LOOKING -> Icons.Default.WifiFind to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(status.line(now), style = MaterialTheme.typography.titleSmall)
                status.hint(now)?.let { Hint(it) }
            }
        }
        SwitchRow(
            "Sync by itself on Wi-Fi", settings.autoSync,
            { on ->
                store.phone.updateSettings { it.copy(autoSync = on) }
                engine?.poke()
            },
            sub = "While HouseMove is open, the phones of this move on the same Wi-Fi sync with no code.",
        )
    }
}
